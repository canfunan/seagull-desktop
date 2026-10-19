package com.seagull.carlauncher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.MotionEvent;
import android.view.Surface;

/**
 * 一个「应用真进小窗」的镜像槽 —— L3 核心。
 *
 * 移植自用户仓库 caogenfunan123/carlink-desktop 的 mirror/MirrorSlot.kt
 * （m13 版起含 TRUSTED 屏），机制核验自 carplay-reverse-engineering 报告
 * §6/§11、《画中画触摸行为分析报告》与 OneStep4 对标结论。
 *
 * 与 L1（WindowCard）的本质区别：
 *   L1 = MediaProjection 镜像主屏，只读；
 *   L3 = createVirtualDisplay 建独立屏 → root 把目标应用搬进去
 *        → 画面渲染进我们的 SurfaceView → 触摸回注进去。
 *        目标应用真的在这块屏上跑，不是镜像。
 *
 * 建屏三条路（批次 R 起，CarWithX 私有屏直建优先）：
 *   · flags=0 私有屏直建（批次 R，实验台真机验证）：DisplayManager 公开 8 参重载，
 *     零授权零弹窗，无投影会话依赖 —— 共用会话/录屏撤销那类故障从根上消失；
 *   · TRUSTED 直建：root 授角色后用 DisplayManager 公开 6 参 createVirtualDisplay
 *     + flag 候选降级 + TRUSTED 位校验 —— Android 14 上任务不被拉回主屏的关键；
 *   · MediaProjection 兜底：普通应用合法拿虚拟屏的正道，无 TRUSTED，
 *     Android 14 上 singleTask 目标可能被拉回主屏（有 ensureOnDisplay 自愈兜底）。
 * 部署即落锚点（PipAnchorActivity，批次 R）：VD 常驻自家 root task，重钉有落点、
 * 空屏不被系统清理；应用缺席（画中画里按了返回）由 MirrorHost.relaunchIfAbsent 节流重拉。
 */
public final class MirrorSlot implements CanvasSource {

    private static final String TAG = "SeagullMirror";

    private final Context ctx;
    private final String name;

    private MediaProjection projection;
    private VirtualDisplay vd;
    private volatile int displayId = -1;
    private volatile boolean ready;
    /** 最近一次建屏/改尺寸后的 VD 像素尺寸：触摸前用它校验「画布 ⇄ 虚拟屏」是否还对齐。 */
    private volatile int vdW, vdH;
    /** 批次 K：true = 本次 VD 是 TRUSTED 受信屏（任务才不会被系统拉回主屏）。 */
    private volatile boolean trusted;
    /** 批次 R：建屏方式（日志/诊断用）。"直建(flags=0)" / "TRUSTED" / "投影"。 */
    private volatile String mode = "";
    /** 批次 K：true = TRUSTED 直建失败且缺录屏 token，需要先走录屏授权再兜底。 */
    private volatile boolean needsProjection;
    /** 批次 AL：true = 本次屏是守护进程（root）建的 —— resize/挂面/拆全走 PrivClient。 */
    private volatile boolean rootBased;
    private String lastError = "";
    private String lastPkg = "";
    private String lastSig = "";

    /**
     * 批次 CF：true = 本槽应用不能安全吃 resize（resizeableActivity=false 且未声明
     * SCREEN_SIZE/ORIENTATION 类 configChanges —— 网易云 3.1.0 反编译坐实：一 resize
     * 就 Activity 重建、MainActivity 清空 fragment 状态回首页）。
     * frozen 槽的 VD 建屏后尺寸终身不变，所有 resize 路径（deploy 重挂 / syncSize /
     * attachSurface / 尺寸抖动）一律拦截；分屏预览改走快照读面，SurfaceView 只在
     * 单应用 1:1 时挂输出。
     */
    private volatile boolean resizeFrozen;
    /** 最近一次 SurfaceView 侧的有效 surface：快照切回实显时重挂它（frozen 槽输出切换全靠它）。 */
    private volatile Surface liveSurface;
    /** 快照读面（frozen 槽专属）：分屏预览时 VD 输出改挂这里，PipBoard 定时拉帧刷 ImageView。 */
    private ImageReader snapReader;
    /** true = VD 输出正挂在快照读面上（分屏预览态）。 */
    private volatile boolean snapMode;

    /**
     * root 屏维护队列（批次 AL）：root 屏没有本地 VirtualDisplay 对象，
     * resize/挂面/拆屏都是一次守护进程往返。所有异步维护排进这条单线程队列：
     * ① 绝不在主线程做往返（ensure() 可能 fork su，秒级阻塞 = ANR）；
     * ② 串行 —— 并发 resize 一块屏是「点击错位」的另一个来源。
     */
    private static final java.util.concurrent.ExecutorService ROOT_EXEC =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "seagull-rootvd");
                t.setDaemon(true);
                return t;
            });

    private void rootExecAsync(Runnable r) {
        try { ROOT_EXEC.execute(r); } catch (Throwable ignore) {}
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    /** 触摸转发交给 TouchForward（跟手/防误滑/长按/排障都在里面）。
     * 审计 L13：volatile —— touchFor() 的 DCL 读路径在触摸主线程，
     * 无 volatile 时另一线程的写入可能对本线程不可见（DCL 经典坑）。 */
    private volatile TouchForward touch;

    /**
     * 懒初始化必须双检加锁：部署后台线程（deploy）与触摸主线程（onTouch）两条
     * 路径都会首触，无锁时两线程各 new 一个 TouchForward → 各起一个
     * HandlerThread，被覆盖的那个永远 shutdown 不到（线程泄漏）。
     */
    private TouchForward touchFor() {
        if (touch == null) {
            synchronized (this) {
                if (touch == null) {
                    LauncherModel m = new LauncherModel(ctx, false);
                    // 批次 CD：touchFollow 弃用 —— 命令通道跟手（一串 su 分段 swipe）被坐实
                    // 为「只能点不能滑」的根因，命令通道一律抬起后一条完整 swipe；
                    // 守护进程中继天生真跟手，不受此参数影响。
                    touch = new TouchForward(m.touchThreshold, m.longPressMs);
                }
            }
        }
        return touch;
    }

    public MirrorSlot(Context ctx, String name) {
        // 进程级常驻（MirrorHost 持有）：绝不能攥着 Activity 不放，存 app context
        this.ctx = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        this.name = name;
        // 给 PrivClient 一个上下文：守护进程靠 base.apk 路径拉起，早给早生效
        PrivClient.init(this.ctx);
    }

    /** 批次 N：触摸坐标映射。VD 模式恒等（1:1，scale 恒 1）；推流模式由外部换实现。 */
    private volatile TouchTransformer transformer = TouchTransformer.identity();

    /** 换坐标映射（DiPlay 推流模式预留）。切回 VD 传 identity()。 */
    public void setTransformer(TouchTransformer t) {
        transformer = (t != null) ? t : TouchTransformer.identity();
        Log.i(TAG, "[" + name + "] 触摸映射 → " + transformer.describe());
    }

    public int displayId() { return displayId; }
    public boolean ready() { return ready; }
    /** 批次 AI：体检/铺满巡检要用 VD 逻辑尺寸。 */
    public int vdW() { return vdW; }
    public int vdH() { return vdH; }
    public int vdDpi() { return vdDpi; }

    /** 批次 CF：本槽是否冻结尺寸（不可缩放应用）。 */
    public boolean resizeFrozen() { return resizeFrozen; }
    public void setResizeFrozen(boolean frozen) { resizeFrozen = frozen; }

    /**
     * 快照读面（可能 null：还没建立）。PipBoard 在快照态部署/重挂时喂给 deploy
     * 的必须是它 —— 喂 SurfaceView 的面会把输出从快照切回实显，预览就黑了。
     */
    public Surface snapshotSurface() {
        ImageReader r = snapReader;
        return r != null ? r.getSurface() : null;
    }

    public boolean trusted() { return trusted; }
    public boolean needsProjection() { return needsProjection; }
    /** 批次 R：true = 这块屏由 MediaProjection 建出，投影一停屏就死。 */
    public boolean projectionBased() { return "投影".equals(mode); }
    public String lastError() { return lastError; }

    /* ---------------- 全局活跃屏注册表（供抽屉"投到车机屏"用） ---------------- */

    private static final java.util.LinkedHashSet<Integer> ACTIVE = new java.util.LinkedHashSet<>();

    static {
        // 审计 M8：守护进程自退出后被 su 重启（gen 变化）时，它 HELD 表里的屏
        // 全没了 —— 客户端这边记的 displayId 全是幽灵，触摸路由和「投到车机屏」
        // 都会拿着死 id 空转。这里清对账表；各 slot 自身的 displayId 等下次
        // deploy() 重建时自然换成新值。
        PrivClient.onRespawn = () -> {
            int n;
            synchronized (MirrorSlot.class) { n = ACTIVE.size(); ACTIVE.clear(); }
            if (n > 0) Log.w(TAG, "守护进程重启，已清 " + n + " 个失效 displayId 记账");
        };
    }

    private static synchronized void register(int d) { if (d > 0) ACTIVE.add(d); }
    private static synchronized void unregister(int d) { ACTIVE.remove(d); }
    public static synchronized boolean hasMirrorDisplay() { return !ACTIVE.isEmpty(); }
    public static synchronized int newestDisplay() {
        Integer last = null;
        for (Integer i : ACTIVE) last = i;
        return last == null ? -1 : last;
    }
    public static synchronized int activeCount() { return ACTIVE.size(); }

    /** 批次 AI：活跃虚拟屏快照（设置页「画中画体检」遍历用）。 */
    public static synchronized int[] activeDisplays() {
        int[] a = new int[ACTIVE.size()];
        int i = 0;
        for (Integer d : ACTIVE) a[i++] = d;
        java.util.Arrays.sort(a);
        return a;
    }

    /* ---------------- 部署 ---------------- */

    /**
     * 部署（幂等）。sig = pkg + surface + 尺寸，没变直接返回 true。
     * 【必须在后台线程调用】：内部会跑 su，阻塞数秒。
     *
     * 建屏策略（批次 K，对标 carlink-desktop m13 / OneStep4）：
     *   ① TRUSTED 直建：root 授 COMPANION_DEVICE_APP_STREAMING 角色 → 本进程
     *      DisplayManager.createVirtualDisplay(name,w,h,dpi,surface,flags)，5 组
     *      flag 候选逐个降级 + Display flags 校验 TRUSTED 位（1<<7）；
     *   ② TRUSTED 不可用（角色授予失败 / ROM 拒绝）→ MediaProjection 兜底，
     *      flags = PUBLIC|OWN_CONTENT_ONLY|PRESENTATION（参考实现实测组合）。
     * 为什么 TRUSTED 是关键：Android 14 只允许 home-affinity/TASK_ON_HOME 任务
     * （singleTask 应用）落在受信屏；非受信屏上即使注入了 launchDisplayId，
     * 任务也会被系统拉回默认屏 —— 用户实测"进去桌面还不是画中画界面"的根因。
     *
     * 尺寸口径：VD 严格按 SurfaceView 的实际 w/h/dpi 建（1:1）——
     * 触摸坐标零换算、应用窗口按画布尺寸布局（"画中画跟随画布大小"）。
     * 批次 J 的 PUBLIC|AUTO_MIRROR 已废弃：任务不在屏上时 AUTO_MIRROR 会把
     * 手机桌面镜像进画中画，正是"画中画里看到的是桌面而不是应用"的来源。
     */
    public boolean deploy(MediaProjection mp, String pkg, Surface surface, int w, int h, int dpi) {
        // 审计 H6：deploy/detach/teardown 来自不同线程（PipBoard 后台部署线程、
        // UI 线程、surface 回调），裸并发会交叉改写 vd/displayId/rootBased 等
        // 生命周期字段（A 槽 deploy 到一半 B 线程 teardown → 半成品状态）。
        // LIFE_LOCK 把生命周期操作整体串行化：teardown 要等 deploy 落地（最多
        // 一次 su 往返），换来的是状态永远一致。
        synchronized (LIFE_LOCK) {
            return deployLocked(mp, pkg, surface, w, h, dpi);
        }
    }

    private final Object LIFE_LOCK = new Object();

    private boolean deployLocked(MediaProjection mp, String pkg, Surface surface, int w, int h, int dpi) {
        // 【批次 AE·黑屏根因修复】surface 为 null（画布 GONE / 布局还没跑）时：
        //   · VD 还活着且包没换 → 什么都不做直接返回。VD 一拆，屏上的任务跟着被
        //     销毁 —— 这正是「进音乐杀高德 / 进高德杀音乐 / 返回桌面画布黑屏」的
        //     根因：隐藏槽的 deploy 拿到 null surface 走到 teardownVd，把在跑的
        //     另一个应用连屏带任务拆了，再冷启动到一块没有输出的新屏。
        //   · 连 VD 都没有 → 也不盲建。盲建的屏没有输出面，纯黑；等 surfaceChanged
        //     → attachTo 带真 surface 来部署（PipBoard.deploySlot 已改成等画布）。
        // teardown 只属于两种情形：用户换了应用（pkg 变）/ 显式清空。
        if (surface == null || !surface.isValid()) {
            if ((vd != null || rootBased) && displayId > 0 && pkg != null && pkg.equals(lastPkg)) {
                return true;
            }
            lastError = "画布未就绪，等待 Surface 再部署";
            return false;
        }
        String sig = pkg + "|" + System.identityHashCode(surface) + "|" + w + "x" + h;
        if (sig.equals(lastSig) && ready) return true;
        lastError = "";

        if (pkg == null || pkg.trim().isEmpty()) { lastError = "未选择目标应用"; return false; }
        if (w <= 0 || h <= 0) { lastError = "锚点尺寸未就绪"; return false; }

        // 已有 root 屏、只是 surface/尺寸变了 → 守护进程 resize + 挂面，不重建
        if (rootBased && pkg.equals(lastPkg) && displayId > 0) {
            // 批次 CF：frozen 槽绝不 resize —— 只换输出面（快照读面 ↔ SurfaceView）
            if (resizeFrozen) {
                final int id = displayId;
                final Surface fs = surface;
                liveSurface = fs;
                ready = true;
                lastSig = sig;
                rootExecAsync(() -> PrivClient.surface(id, fs));
                Log.i(TAG, "[" + name + "] frozen root 屏仅换面（尺寸锁定 "
                        + vdW + "x" + vdH + "）");
                return true;
            }
            final int id = displayId, fw = w, fh = h, fdpi = dpi;
            final Surface fs = surface;
            ready = true;
            lastSig = sig;
            rootExecAsync(() -> {
                // 批次 CC：簿记只在 resize 确认后更新 —— 在途/失败期间触摸按旧尺寸比例
                // 换算，与屏上被缩放的内容严格对齐（车联助手不变量，机制见 syncSize 注释）。
                boolean ok = PrivClient.resize(id, fw, fh, fdpi);
                PrivClient.surface(id, fs);
                if (ok) {
                    setSizeBookkeeping(fw, fh, fdpi);
                    Log.i(TAG, "[" + name + "] root 屏 resized/reattached " + fw + "x" + fh
                            + " on displayId=" + id);
                } else {
                    Log.w(TAG, "[" + name + "] root resize 失败（挂面已完成），触摸换算保持 "
                            + vdW + "x" + vdH);
                }
            });
            return true;
        }

        // 已有 VD、只是 surface/尺寸变了 → resize + setSurface，不重建（避免目标重启）
        VirtualDisplay existing = vd;
        if (existing != null && pkg.equals(lastPkg) && displayId > 0) {
            // 批次 CF：frozen 槽绝不 resize —— 只换输出面
            if (resizeFrozen) {
                liveSurface = surface;
                existing.setSurface(surface);
                lastSig = sig;
                // ready 必须跟着恢复：①提示层不能永久压在真画面上 ②触摸回注判 ready
                ready = true;
                Log.i(TAG, "[" + name + "] frozen VD 仅换面（尺寸锁定 "
                        + vdW + "x" + vdH + "）");
                return true;
            }
            try {
                existing.resize(w, h, dpi);
                existing.setSurface(surface);
                vdW = w;
                vdH = h;
                vdDpi = dpi;
                lastSig = sig;
                // 【关键】resize 成功即画面已恢复，ready 必须跟着恢复，
                // 否则：①提示层永久压在真画面上 ②触摸回注判 ready 永远 false → 点击失效
                ready = true;
                Log.i(TAG, "[" + name + "] resized/reattached " + w + "x" + h
                        + " on displayId=" + displayId);
                return true;
            } catch (Throwable t) {
                lastError = "resize 失败: " + t;
                return false;
            }
        }

        teardownVd();
        lastPkg = pkg;
        setProjection(mp);
        needsProjection = false;
        trusted = false;
        mode = "";

        /*
         * 批次 AL（OneStep 思路落地，实现自写）—— root TRUSTED 屏优先路：
         * 屏由守护进程（uid 0）建，TRUSTED+PUBLIC 直接拿到，任务可以"生在画布"
         * （launchOptions.setLaunchDisplayId 一次成功，不再有搬回拉锯），
         * Android 14 拉回主屏与私有屏"不许放别人东西"两类问题从根上消失。
         * 空气开关 persist.seagull.rootbridge 关闭/建屏失败 → 自动落回旧三路，
         * 行为与 AJ 完全一致（通用要求：失败降级）。
         */
        if (Caps.rootBridgeOn() && PrivClient.ensure()) {
            PrivClient.Created rc = PrivClient.create(w, h, dpi);
            if (rc != null) {
                rootBased = true;
                trusted = rc.trusted;
                mode = "root屏";
                setProjection(null);   // root 屏与投影无关（按引用计数妥善处置旧会话）
                displayId = rc.displayId;
                vdW = w;
                vdH = h;
                vdDpi = dpi;
                register(displayId);
                PrivClient.surface(displayId, surface);
                Log.i(TAG, "[" + name + "] root TRUSTED 屏建成 id=" + displayId
                        + " trusted=" + trusted + "，走生在画布部署");
                if (finishDeploy(pkg, sig, surface)) return true;
                rootBased = false;
                return false;
            }
            Log.w(TAG, "[" + name + "] root 建屏失败，回落本地建屏三路");
        }

        /*
         * 建屏三路（批次 R 重排，CarWithX 私有屏直建提为首选）：
         *   ① flags=0 私有屏直建（批次 R，实验台 seagull-pip-lab 在用户真机验证过）：
         *      DisplayManager 公开 API，零授权零弹窗，无投影会话依赖 —— 批次 Q 那类
         *      "两槽共用会话"的整类 bug 从根上消失。私有屏只有本进程看得见，
         *      SurfaceView 恰好是自己的，画中画场景刚好。
         *   ② TRUSTED 直建（批次 K）：root 授角色后建受信屏，收留 singleTask 任务最稳；
         *   ③ MediaProjection 兜底：需要录屏授权 token。
         * ①失败才试②、②失败才试③；每一路的失败原因都留在日志里。
         */
        VirtualDisplay made = createPlainVd(w, h, dpi, surface);
        if (made != null) {
            vd = made;
            mode = "直建(flags=0)";
            setProjection(null);   // 私有屏与投影无关
            Log.i(TAG, "[" + name + "] 私有屏直建成功 flags=0 " + w + "x" + h);
        } else {
            made = createTrustedVd(w, h, dpi, surface);
            if (made != null) {
                vd = made;
                trusted = true;
                mode = "TRUSTED";
                setProjection(null);   // TRUSTED 屏不靠 MediaProjection
                Log.i(TAG, "[" + name + "] TRUSTED 虚拟屏建成 " + w + "x" + h);
            } else {
                // 兜底：MediaProjection 路径（需要录屏授权 token）
                if (mp == null) {
                    needsProjection = true;
                    lastError = "私有屏与 TRUSTED 直建均失败，且无录屏授权（兜底路径缺 token）";
                    return false;
                }
                Log.w(TAG, "[" + name + "] 直建两路都失败，回落 MediaProjection 路径");
                try {
                    vd = mp.createVirtualDisplay(
                            "seagull-" + name, w, h, dpi,
                            TrustedFlags.projectionFallbackFlags(),
                            surface, null, mainHandler);
                    mode = "投影";
                } catch (Throwable t) {
                    lastError = "createVirtualDisplay 失败: " + t;
                    Log.e(TAG, lastError, t);
                    return false;
                }
                if (vd == null) { lastError = "createVirtualDisplay 返回 null"; return false; }
            }
        }

        displayId = vd.getDisplay() != null ? vd.getDisplay().getDisplayId() : -1;
        if (displayId < 0) { lastError = "拿不到虚拟屏 id"; teardownVd(); return false; }
        vdW = w;
        vdH = h;
        vdDpi = dpi;

        // 建屏成功即注册（触摸/抽屉据此找得到这块屏），不必等 Surface
        register(displayId);
        return finishDeploy(pkg, sig, surface);
    }

    /**
     * 建屏成功后的公共部署段（root 屏与本地屏同款，批次 AL 抽取）：
     *   ① relaxCompat（搬任务路也要写，进程虽旧但之后任何冷启动都吃得到）；
     *   ② 先搬已有任务（保运行状态，不杀导航/播放），搬不动才冷启动；
     *   ③ 部署后自愈一次 + 两拍布局抖动；
     *   ④ K3：部署即武装路由源（displayId ↔ 嵌入应用；触摸 TTL 由守护进程自刷）。
     * 失败拆屏返回 false。
     */
    private boolean finishDeploy(String pkg, String sig, Surface surface) {
        // 批次 CD：首写兼容位会顺带 force-stop 一次（老进程吃不到 FORCE_RESIZE_APP，
        // letterbox 黑边修不掉 → 边角点不到）。之后有标记，绝不再杀。
        RootOps.relaxCompat(ctx, pkg);

        boolean moved;
        try {
            moved = RootOps.moveExistingTask(ctx, pkg, displayId);
        } catch (Throwable t) {
            Log.w(TAG, "[" + name + "] 路①(锚点+搬任务)异常，回落冷启动: " + t);
            moved = false;
        }
        Log.i(TAG, "[" + name + "] 路①(锚点+搬任务)=" + moved);

        if (!moved) {
            boolean ok = RootOps.launchOnDisplay(ctx, pkg, displayId);
            if (!ok) {
                lastError = "部署失败：搬已有任务与冷启动都没成功（display " + displayId + "）";
                teardownVd();
                return false;
            }
        }

        // 部署即自愈一次（批次 L）：singleTask 目标落地瞬间就可能被系统拉回默认屏
        try {
            String heal = RootOps.ensureOnDisplay(ctx, pkg, displayId);
            Log.i(TAG, "[" + name + "] 部署后自愈: " + heal);
        } catch (Throwable t) {
            Log.w(TAG, "[" + name + "] 部署后自愈异常: " + t);
        }

        // K3 R2：部署成功即武装（拦截改道需要知道"哪块屏跑着谁"）。空气开关
        // 关闭时不武装 —— 行为与 AJ 一致。arm/ctrl 都是一次守护进程往返，排队走。
        if (Caps.rootBridgeOn()) {
            final int fd = displayId;
            final String fpkg = pkg;
            rootExecAsync(() -> {
                PrivClient.arm(fd, fpkg);
                PrivClient.ctrl(true);
            });
        }

        lastSig = sig;
        ready = surface != null && surface.isValid();
        // 【批次 AH】搬已有任务进新屏后，应用窗口常停在旧尺寸 → 两拍"布局抖动"
        // 强制重排铺满（不杀进程、不丢导航/播放状态）。
        // 【批次 CF】frozen 槽只拍密度（修竖屏烙印），尺寸拍/尺寸抖动全免 ——
        // 网易云对尺寸变化零容忍（resizeableActivity=false，一 resize 就重建回首页）。
        if (resizeFrozen) {
            scheduleNudgeDensity(2000);
        } else {
            scheduleNudge(2000);
            scheduleNudge(4500);
        }
        Log.i(TAG, "[" + name + "] " + pkg + " 已部署到 displayId=" + displayId + " " + vdW + "x" + vdH
                + " trusted=" + trusted + " root=" + rootBased + " ready=" + ready);
        return true;
    }

    /**
     * CarWithX 私有屏直建（批次 R 首选路）：DisplayManager 公开 8 参重载，
     * flags=0 = PRIVATE —— 不需要角色、不需要录屏授权，本进程对这块屏有完全控制权。
     * 实验（seagull-pip-lab）在用户真机 Redmi K60 / HyperOS Android 14 上验证：
     * 这条路能建屏、`am start --display` 能进、应用画面能渲染进 SurfaceView。
     * 失败（ROM 拒绝/异常）返回 null，调用方走 TRUSTED 路。
     */
    private VirtualDisplay createPlainVd(int w, int h, int dpi, Surface surface) {
        android.hardware.display.DisplayManager dm =
                (android.hardware.display.DisplayManager)
                        ctx.getSystemService(Context.DISPLAY_SERVICE);
        if (dm == null) return null;
        try {
            return dm.createVirtualDisplay("seagull-" + name, w, h, dpi, surface, 0, null, null);
        } catch (Throwable t) {
            Log.w(TAG, "[" + name + "] 私有屏直建被拒: " + t);
            return null;
        }
    }

    /**
     * TRUSTED 虚拟屏直建（API 33+；角色授予失败或 ROM 拒绝时返回 null，
     * 调用方回落 MediaProjection）。flag 候选逐个降级，建后校验 Display flags
     * 是否真带 TRUSTED 位（1<<7）—— 不校验等于没建，有些 ROM 会静默吞掉 flag。
     */
    private VirtualDisplay createTrustedVd(int w, int h, int dpi, Surface surface) {
        if (android.os.Build.VERSION.SDK_INT < 33) return null;
        if (!RootOps.grantTrustedDisplayRole(ctx)) {
            Log.w(TAG, "[" + name + "] TRUSTED 角色授予失败，跳过直建");
            return null;
        }
        android.hardware.display.DisplayManager dm =
                (android.hardware.display.DisplayManager)
                        ctx.getSystemService(Context.DISPLAY_SERVICE);
        if (dm == null) return null;
        int sdk = android.os.Build.VERSION.SDK_INT;
        for (int raw : TrustedFlags.candidateFlags()) {
            int flags = TrustedFlags.normalize(raw, sdk);
            VirtualDisplay candidate;
            try {
                // 6 参公开重载（API 33 起在 SDK 里）；老设备 NoSuchMethodError 由 catch 兜
                candidate = dm.createVirtualDisplay("seagull-" + name, w, h, dpi, surface, flags);
            } catch (Throwable t) {
                Log.w(TAG, "[" + name + "] flags=0x" + Integer.toHexString(flags)
                        + " 建屏被拒: " + t);
                continue;
            }
            if (candidate == null) continue;
            int df = -1;
            try {
                df = candidate.getDisplay() != null ? candidate.getDisplay().getFlags() : -1;
            } catch (Throwable ignore) {}
            if (TrustedFlags.hasTrusted(df)) {
                Log.i(TAG, "[" + name + "] TRUSTED 屏建成 flags=0x" + Integer.toHexString(flags)
                        + " displayFlags=0x" + Integer.toHexString(df));
                return candidate;
            }
            Log.w(TAG, "[" + name + "] flags=0x" + Integer.toHexString(flags) + " 未获受信位"
                    + " (df=0x" + Integer.toHexString(df) + ")，降级下一候选");
            try { candidate.release(); } catch (Throwable ignore) {}
        }
        return null;
    }

    /**
     * Surface 后到（SurfaceView 回调晚于建屏）：把已有 VD 的输出挂到这块 Surface 上。
     * 产品主路径用它 —— 先建屏 + 搬应用（不依赖 Surface 就绪），Surface 一到就出画面。
     */
    public boolean attachSurface(Surface surface, int w, int h, int dpi) {
        if (vd == null || displayId <= 0) return false;
        if (surface == null || !surface.isValid()) return false;
        // 批次 CF：frozen 槽只挂面，尺寸/dpi 一律不动（SurfaceView 单应用 1:1 时
        // 带来的 w/h 恰好等于建屏尺寸；分屏时输出在快照读面上，这路不该被走到）
        if (resizeFrozen) {
            try {
                liveSurface = surface;
                vd.setSurface(surface);
                ready = true;
                lastSig = lastPkg + "|" + System.identityHashCode(surface) + "|" + w + "x" + h;
                Log.i(TAG, "[" + name + "] frozen surface 已挂载（尺寸锁定 "
                        + vdW + "x" + vdH + "）displayId=" + displayId);
                return true;
            } catch (Throwable t) {
                lastError = "frozen attachSurface 失败: " + t;
                Log.w(TAG, lastError, t);
                return false;
            }
        }
        try {
            vd.resize(w, h, dpi);
            vdW = w;
            vdH = h;
            vdDpi = dpi;
            vd.setSurface(surface);
            ready = true;
            lastSig = lastPkg + "|" + System.identityHashCode(surface) + "|" + w + "x" + h;
            Log.i(TAG, "[" + name + "] surface 已挂载 " + w + "x" + h + " displayId=" + displayId);
            return true;
        } catch (Throwable t) {
            lastError = "attachSurface 失败: " + t;
            Log.w(TAG, lastError, t);
            return false;
        }
    }

    /* ---------------- 触摸回注 ---------------- */

    /**
     * 手势开始前校准尺寸（批次 AD·修「点击错位」；批次 CC 按车联助手不变量重写）。
     *
     * 画布被改成半屏 / 铺满之后，若 VD 没有跟着 resize 到同一像素尺寸，应用仍按旧尺寸
     * 渲染、被合成器缩放到画布上；而触摸是按新尺寸原样注入的 —— 于是越往右下越偏。
     * 这里在每根手势的 DOWN 上比一次，只在尺寸真的漂了才 resize（同尺寸直接返回，零开销）。
     *
     * 批次 CC（反编译 com.leting 新版坐实，旧实现恰好反着做）：
     *   ① **尺寸簿记只在 resize 确认后更新**。车联助手从不"先记账再生效"：它的 VD 在
     *      surfaceChanged 里同步 resize，坐标口径永远等于屏上真实内容，触摸 1:1 零簿记。
     *      旧实现"本地簿记先行"，root 屏 resize 却是异步的 —— 簿记说新尺寸、屏还是旧尺寸
     *      的窗口期里（某些 ROM 静默拒绝 resize 时甚至是永久），fx/fy 恒 1，点击整体偏移，
     *      这正是「触摸不准」的主根因。现在确认成功才记账；在途/失败期间触摸按旧尺寸
     *      比例换算，与屏上被缩放的内容严格对齐，点哪儿是哪儿。
     *   ② **密度恒定**（对齐车联助手：dpi 恒为建屏值，从不重算）—— DOWN 校准只对齐
     *      像素尺寸，不触发应用 density 重配/重排。dpi 只在 deploy/attachSurface 定。
     */
    public void syncSize(int w, int h) {
        // 批次 CF：frozen 槽 DOWN 校准直接短路 —— VD 尺寸终身锁定，触摸按
        // vdW/vdH 与画布的固定比例换算（单应用 1:1，分屏不透传触摸）。
        if (resizeFrozen) return;
        if (displayId <= 0 || w <= 0 || h <= 0) return;
        if (vdW == w && vdH == h) return;
        if (rootBased) {
            final int d = displayId, fdpi = vdDpi;
            rootExecAsync(() -> {
                // 在途/失败不改簿记：触摸换算继续按旧尺寸走，与屏上内容保持比例一致。
                // 失败日志是「root resize 被 ROM 拒绝」的唯一可见信号，别吞。
                if (PrivClient.resize(d, w, h, fdpi)) {
                    setSizeBookkeeping(w, h, fdpi);
                    Log.i(TAG, "[" + name + "] root 尺寸校准确认 → " + w + "x" + h);
                } else {
                    Log.w(TAG, "[" + name + "] root resize 失败，触摸换算保持 "
                            + vdW + "x" + vdH);
                }
            });
            return;
        }
        if (vd == null) return;
        try {
            vd.resize(w, h, vdDpi);
            setSizeBookkeeping(w, h, vdDpi);
            Log.i(TAG, "[" + name + "] VD 尺寸校准 → " + w + "x" + h + "@" + vdDpi + "dpi");
        } catch (Throwable t) {
            Log.w(TAG, "[" + name + "] VD 尺寸校准失败: " + t);
        }
    }

    /* ---------------- 批次 AH：布局抖动（搬入后强制重排铺满） ---------------- */

    /** deploy / attachSurface 时记录的 dpi，抖动回弹与后续校准时保持不变。 */
    private volatile int vdDpi;

    /** 尺寸簿记锁：vdW/vdH/vdDpi 三元组原子更新（触摸线程读、异步确认线程写）。 */
    private static final Object SIZE_LOCK = new Object();

    /**
     * 尺寸簿记统一入口（批次 CC）：**只在 resize 确认成功后调用** ——
     * 车联助手不变量：坐标口径永远跟屏上真实尺寸走，绝不先行。
     */
    private void setSizeBookkeeping(int w, int h, int dpi) {
        synchronized (SIZE_LOCK) {
            vdW = w;
            vdH = h;
            vdDpi = dpi;
        }
    }

    /**
     * VD 抖动：强制画布里的应用重排/重建。批次 AJ 升级为两拍：
     *
     * 第 1 拍（尺寸 ±1px）：可缩放应用直接按新尺寸重排，铺满旧布局停摆类问题。
     *
     * 第 2 拍（密度 ±8 再回）：修网易云「不铺满」的根 —— 反编译坐实（批次 AJ）：
     * 网易云 iot 版 base/b.attachBaseContext 用 **Application 的 defaultDisplay
     * （永远是 display 0，手机主屏=竖屏）** 的宽高比自判朝向，ratio≤1.3 就
     * applyOverrideConfiguration 烙死 PORTRAIT，且 onConfigurationChanged 永不重算。
     * 模块 D 段（XposedEntry.hookPortraitConfigOverride）能把这个烙印改写成横屏，
     * 但只对「attachBaseContext 时」生效 —— 搬任务路部署的是在手机上早已初始化的
     * 旧 activity，hook 没有机会跑。而它的 configChanges 不含 DENSITY：**密度一变，
     * activity 必重建**（重跑 attachBaseContext → D 段生效 → 横屏渲染），进程不死、
     * 音乐不断。密度 ±8 肉眼无感，两拍在 1s 内完成。
     */
    public void nudgeLayout() {
        // root 屏：两拍抖动整段排队走守护进程 resize（专用线程里 sleep，不占别人的路）
        if (rootBased) { nudgeRoot(); return; }
        final int d = displayId;
        final int w = vdW, h = vdH, dpi = vdDpi;
        if (vd == null || d <= 0 || w <= 0 || h <= 0 || dpi <= 0) return;
        android.os.Handler hd = new android.os.Handler(android.os.Looper.getMainLooper());
        try {
            vd.resize(w, Math.max(64, h - 1), dpi);
            hd.postDelayed(() -> {
                if (vd == null || displayId != d) return;
                try { vd.resize(w, h, dpi); } catch (Throwable ignore) {}
            }, 400);
            Log.i(TAG, "[" + name + "] 布局抖动(尺寸) " + w + "x" + Math.max(64, h - 1)
                    + " → " + w + "x" + h);
        } catch (Throwable t) {
            Log.w(TAG, "[" + name + "] 布局抖动(尺寸)失败: " + t);
        }
        try {
            hd.postDelayed(() -> {
                if (vd == null || displayId != d) return;
                try { vd.resize(w, h, dpi + 8); } catch (Throwable ignore) {}
            }, 1200);
            hd.postDelayed(() -> {
                if (vd == null || displayId != d) return;
                try { vd.resize(w, h, dpi); } catch (Throwable ignore) {}
            }, 2200);
            Log.i(TAG, "[" + name + "] 密度抖动 dpi " + dpi + "→" + (dpi + 8) + "→" + dpi
                    + "（触发 activity 重建，让 D 段重新判定朝向）");
        } catch (Throwable t) {
            Log.w(TAG, "[" + name + "] 密度抖动失败: " + t);
        }
    }

    /** 部署成功后的两拍抖动（2s/4.5s）：给应用留出搬入/挂面的完成时间。 */
    private void scheduleNudge(long delayMs) {
        new android.os.Handler(android.os.Looper.getMainLooper())
                .postDelayed(this::nudgeLayout, delayMs);
    }

    /** root 屏版布局抖动：尺寸 ±1px 与密度 ±8 两拍，全部走守护进程 resize（串行队列）。 */
    private void nudgeRoot() {
        final int d = displayId, w = vdW, h = vdH, dpi = vdDpi;
        if (d <= 0 || w <= 0 || h <= 0 || dpi <= 0) return;
        rootExecAsync(() -> {
            try {
                PrivClient.resize(d, w, Math.max(64, h - 1), dpi);
                Thread.sleep(400);
                PrivClient.resize(d, w, h, dpi);
                Thread.sleep(800);
                PrivClient.resize(d, w, h, dpi + 8);
                Thread.sleep(1000);
                PrivClient.resize(d, w, h, dpi);
                Log.i(TAG, "[" + name + "] root 屏布局抖动完成 " + w + "x" + h);
            } catch (Throwable t) {
                Log.w(TAG, "[" + name + "] root 屏布局抖动失败: " + t);
            }
        });
    }

    /* ---------------- 批次 CF：frozen 槽的密度拍与快照预览 ---------------- */

    /**
     * frozen 槽专用：只跑密度拍（±8 再回，尺寸不动）。
     *
     * 密度拍的存在意义是修网易云「竖屏烙印」（见 nudgeLayout 注释）：D 段 hook 只在
     * attachBaseContext 时生效，密度变化触发 activity 重建才让横屏判定跑上。frozen 槽
     * 只在**首次部署/换应用**（finishDeploy）后拍一次 —— 那时用户还没进歌词页，重建
     * 无感；「桌面往返」路径绝不调用本方法，重建链路被彻底切断。
     */
    private void scheduleNudgeDensity(long delayMs) {
        new Handler(Looper.getMainLooper()).postDelayed(this::nudgeDensityOnly, delayMs);
    }

    private void nudgeDensityOnly() {
        final int d = displayId, w = vdW, h = vdH, dpi = vdDpi;
        if (d <= 0 || w <= 0 || h <= 0 || dpi <= 0) return;
        if (rootBased) {
            rootExecAsync(() -> {
                try {
                    PrivClient.resize(d, w, h, dpi + 8);
                    Thread.sleep(1000);
                    PrivClient.resize(d, w, h, dpi);
                    Log.i(TAG, "[" + name + "] frozen 密度拍完成（尺寸锁定 " + w + "x" + h + "）");
                } catch (Throwable t) {
                    Log.w(TAG, "[" + name + "] frozen 密度拍失败: " + t);
                }
            });
            return;
        }
        if (vd == null) return;
        Handler hd = new Handler(Looper.getMainLooper());
        try {
            hd.postDelayed(() -> {
                if (vd == null || displayId != d) return;
                try { vd.resize(w, h, dpi + 8); } catch (Throwable ignore) {}
            }, 0);
            hd.postDelayed(() -> {
                if (vd == null || displayId != d) return;
                try { vd.resize(w, h, dpi); } catch (Throwable ignore) {}
            }, 1000);
            Log.i(TAG, "[" + name + "] frozen 密度拍 dpi " + dpi + "→" + (dpi + 8) + "→" + dpi);
        } catch (Throwable t) {
            Log.w(TAG, "[" + name + "] frozen 密度拍失败: " + t);
        }
    }

    /**
     * frozen 槽的输出面切换（PipBoard 在单应用 ↔ 分屏切换后调）。
     *
     * true = 分屏预览：VD 输出挂快照读面（ImageReader），PipBoard 定时
     * {@link #acquireSnapshot()} 拉帧刷 ImageView。应用侧零感知 —— 它以为
     * 还在一块终身不变的屏上跑，Activity 不重建、歌词页/播放状态原地保留。
     * false = 单应用：输出挂回 SurfaceView（liveSurface），1:1 实显 + 触摸透传。
     */
    public void setSnapshotMode(boolean on) {
        if (!resizeFrozen || displayId <= 0) return;
        if (on) {
            ImageReader r = snapReader;
            if (r == null) {
                try {
                    r = ImageReader.newInstance(vdW, vdH, PixelFormat.RGBA_8888, 2);
                } catch (Throwable t) {
                    Log.w(TAG, "[" + name + "] 快照读面建立失败: " + t);
                    return;
                }
                snapReader = r;
            }
            snapMode = true;
            Surface snap = r.getSurface();
            if (rootBased) {
                final int d = displayId;
                rootExecAsync(() -> PrivClient.surface(d, snap));
            } else {
                try { vd.setSurface(snap); } catch (Throwable t) {
                    Log.w(TAG, "[" + name + "] 快照面挂载失败: " + t);
                }
            }
            Log.i(TAG, "[" + name + "] 输出 → 快照读面 " + vdW + "x" + vdH);
        } else {
            snapMode = false;
            Surface live = liveSurface;
            if (rootBased) {
                final int d = displayId;
                rootExecAsync(() -> PrivClient.surface(d, live));
            } else {
                try { vd.setSurface(live); } catch (Throwable ignore) {}
            }
            Log.i(TAG, "[" + name + "] 输出 → 实显 surface");
        }
    }

    /**
     * 拉最新一帧快照（PipBoard 主线程定时调）。无新帧返回 null（ImageView 保持旧图）。
     * rowStride 与 w*4 不一致（行对齐 padding）时按整行建图再裁剪，一次 copy 无逐像素循环。
     *
     * 审计 M13：快照泵 600ms 一拍，960×540 ARGB 一帧约 2MB —— 旧实现每拍 new
     * Bitmap，~3.3MB/s 的分配压力全喂给 GC（车机低端内存上就是周期性卡顿）。
     * 改双缓冲轮替复用：UI 显示 0 号缓冲时下一帧写 1 号，再下一帧才覆写 0 号
     * （此时 ImageView 已切到 1 号）。PipBoard 侧只 setImageBitmap 不 recycle，
     * 与该模式兼容。
     */
    private Bitmap snapBuf0, snapBuf1;
    private int snapBufIdx;

    private static Bitmap snapEnsure(Bitmap old, int rw, int h) {
        if (old != null && !old.isRecycled()
                && old.getWidth() == rw && old.getHeight() == h) return old;
        try { return Bitmap.createBitmap(rw, h, Bitmap.Config.ARGB_8888); }
        catch (Throwable t) { return null; }
    }

    public Bitmap acquireSnapshot() {
        ImageReader r = snapReader;
        if (r == null || !snapMode) return null;
        Image img = null;
        try {
            img = r.acquireLatestImage();
            if (img == null) return null;
            Image.Plane p = img.getPlanes()[0];
            java.nio.ByteBuffer buf = p.getBuffer();
            buf.rewind();
            int rowStride = p.getRowStride();
            int w = img.getWidth(), h = img.getHeight();
            int rw = rowStride / 4;
            if (rw <= 0 || h <= 0 || w <= 0) return null;
            Bitmap b;
            if ((snapBufIdx++ & 1) == 0) {
                b = snapBuf0 = snapEnsure(snapBuf0, rw, h);
            } else {
                b = snapBuf1 = snapEnsure(snapBuf1, rw, h);
            }
            if (b == null) return null;
            b.copyPixelsFromBuffer(buf);
            if (rw != w) {
                try { return Bitmap.createBitmap(b, 0, 0, w, h); }
                catch (Throwable t) { return b; }
            }
            return b;
        } catch (Throwable t) {
            Log.w(TAG, "[" + name + "] 快照拉帧失败: " + t);
            return null;
        } finally {
            if (img != null) {
                try { img.close(); } catch (Throwable ignore) {}
            }
        }
    }

    /** 拆屏/换应用时释放快照读面（teardownVd 调）。 */
    private void releaseSnapshot() {
        ImageReader r = snapReader;
        snapReader = null;
        liveSurface = null;
        // 审计 M13：缓冲位图只断引用（UI 可能还持有其中一块在显示），不 recycle
        snapBuf0 = null;
        snapBuf1 = null;
        if (r != null) {
            try { r.close(); } catch (Throwable ignore) {}
        }
    }

    /** 上一次喂进来的画布尺寸：CANCEL 补发（cancelStroke）时用同一坐标系。 */
    private volatile int svW, svH;

    /**
     * SurfaceView 触摸转发：点/滑/长按都交给 TouchForward（12.1）。
     *
     * 批次 AE：坐标不再假设 1:1 —— 按传入的画布实际尺寸逐轴换算到 VD 空间。
     * 画布被拉伸/暂未 resize 对齐时点哪儿都是哪儿（见 TouchForward 头注释）。
     */
    public void onTouch(MotionEvent e, int canvasW, int canvasH) {
        if (displayId <= 0) return;
        if (canvasW > 0) svW = canvasW;
        if (canvasH > 0) svH = canvasH;
        float sx = (vdW > 0 && svW > 0) ? vdW / (float) svW : transformer.scaleX();
        float sy = (vdH > 0 && svH > 0) ? vdH / (float) svH : transformer.scaleY();
        touchFor().setDisplay(displayId, sx, sy);
        touchFor().setVdBounds(vdW, vdH);
        touchFor().feed(e);
    }

    /* ---------------- 生命周期 ---------------- */

    /** surface 被销毁：只断输出，保留 VD 与目标任务栈，下次 surface 回来再挂。 */
    public void detachSurface() {
        synchronized (LIFE_LOCK) {
            if (rootBased && displayId > 0) {
                final int d = displayId;
                rootExecAsync(() -> PrivClient.surface(d, null));
                ready = false;
                return;
            }
            try { if (vd != null) vd.setSurface(null); } catch (Throwable ignore) {}
            ready = false;
        }
    }

    public void teardownVd() {
        synchronized (LIFE_LOCK) { teardownVdLocked(); }
    }

    private void teardownVdLocked() {
        final int d = displayId;
        final boolean wasRoot = rootBased;
        ready = false;
        rootBased = false;
        snapMode = false;
        releaseSnapshot();
        unregister(d);
        if (wasRoot && d > 0) {
            // root 屏：拆屏是守护进程往返，排队走（幂等；守护进程已死也安全）。
            // 不在这里无条件 disarm —— 武装是全局单源，拆 A 槽会把 B 槽的武装一起解掉；
            // 过期武装由 5s 触摸 TTL 自然失效（K3 设计），下次部署 finishDeploy 会重武装。
            final int fd = d;
            rootExecAsync(() -> PrivClient.release(fd));
        } else {
            try { if (vd != null) vd.release(); } catch (Throwable ignore) {}
        }
        vd = null;
        // 批次 Q 本意「每槽一份独立投影会话」，但 PipBoard 拿不到第二份授权
        // 时仍会让两槽共用同一个 MediaProjection（PipBoard.java 降级分支）。
        // 审计 M9：共用实例被一槽 stop 会连带杀掉另一槽的流（画面全黑）。
        // 引用计数判定：独占才 stop，共享的 stop 责任归 PipBoard.stopProjections()。
        if (projection != null) {
            MediaProjection p = projection;
            projection = null;
            if (projRelease(p)) {
                try { p.stop(); } catch (Throwable ignore) {}
            } else {
                Log.i(TAG, "[" + name + "] 共享投影会话，跳过 stop（由 stopProjections 统一收）");
            }
        }
        displayId = -1;
        lastSig = "";
        lastPkg = "";
    }

    /** 审计 M9：共享投影会话引用计数（IdentityHashMap，同一授权降级复用时 >1）。 */
    private static final java.util.IdentityHashMap<MediaProjection, int[]> PROJ_REFS =
            new java.util.IdentityHashMap<>();

    /** 记一次槽对本投影的持有；本槽独占返回 true。 */
    private static boolean projAcquire(MediaProjection mp) {
        if (mp == null) return true;
        synchronized (PROJ_REFS) {
            int[] c = PROJ_REFS.get(mp);
            if (c == null) { PROJ_REFS.put(mp, new int[]{1}); return true; }
            c[0]++;
            return false;
        }
    }

    /** 释放一次槽的持有；引用归零（或未记账）返回 true = 允许 stop。 */
    private static boolean projRelease(MediaProjection mp) {
        if (mp == null) return true;
        synchronized (PROJ_REFS) {
            int[] c = PROJ_REFS.get(mp);
            if (c == null) return true;
            if (--c[0] <= 0) { PROJ_REFS.remove(mp); return true; }
            return false;
        }
    }

    /** 槽内统一换投影：旧引用按引用计数处置，新引用记账。 */
    private void setProjection(MediaProjection mp) {
        MediaProjection old = projection;
        projection = mp;
        if (mp != old) {
            projAcquire(mp);
            if (projRelease(old)) {
                try { if (old != null) old.stop(); } catch (Throwable ignore) {}
            }
        }
    }

    public void teardown() {
        synchronized (LIFE_LOCK) {
            teardownVdLocked();
        }
        if (touch != null) {
            touch.shutdown();
            touch = null;
        }
    }

    /** 诊断行：给 UI 显示"虚拟屏到底建没建、走的是哪条建屏路"。 */
    public String describe() {
        if (displayId <= 0) {
            return "未部署" + (lastError.isEmpty() ? "" : "（" + lastError + "）");
        }
        return (ready ? "已就绪" : "已建屏未挂载")
                + " · " + (mode.isEmpty() ? "?" : mode)
                + " · displayId=" + displayId
                + (lastPkg.isEmpty() ? "" : " · " + lastPkg);
    }
}
