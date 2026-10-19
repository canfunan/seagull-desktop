package com.seagull.carlauncher;

import android.content.Context;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.util.Log;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

/**
 * root 守护进程（{@link RootMain}）的客户端。
 *
 * 存在意义：L3 的三个特权动作（启动应用进虚拟屏 / 真多指触摸注入 / 任务搬屏）
 * 以前靠 `su -c am start`、`su -c input ...` 这类 shell 命令，一次一次 fork root shell：
 * 慢（每次手势上百毫秒）、单指（`input` 表达不了 POINTER_DOWN/UP）、输出还得猜。
 * 守护进程跑在 root uid 的 app_process 里，进程内反射直接调隐藏 API
 * （Extendroid 同款机制），一个 LocalSocket 长连接搞定全部。
 *
 * 降级链是硬约束：守护进程起不来（多半是 SELinux 拦了 socket）时，
 * 本类只负责「如实报告失败」，命令照旧走 RootOps 里的 shell 兜底，
 * 绝不让调用方因为这里失败而崩。
 */
public final class PrivClient {

    private static final String TAG = "SeagullPriv";
    /** abstract namespace 的服务名，两边写死一致。 */
    public static final String SOCKET = "seagull.privd";
    private static final String DAEMON_MAIN = "com.seagull.carlauncher.RootMain";
    private static final int SPAWN_WAIT_MS = 3500;

    private static Context appCtx;
    /** volatile 是给 available() 免锁读用的：UI 线程不能卡在 ensure() 的类锁后面。 */
    private static volatile LocalSocket sock;
    private static BufferedReader in;
    private static BufferedWriter out;
    /** 本进程会话内禁用：拉起失败过一次（大概率 SELinux）就不再重试，免得每次手势都去 fork su。 */
    private static boolean disabled;
    private static long seq;

    private PrivClient() {}

    public static synchronized void init(Context c) {
        if (appCtx == null && c != null) appCtx = c.getApplicationContext();
    }

    /** 已连上（不代表 ping 通，心跳类操作用 ensure()）。免锁：UI 线程每次手势都会问。 */
    public static boolean available() {
        LocalSocket s = sock;
        return s != null && s.isConnected();
    }

    /** 排障/设置页用的一行状态。 */
    public static synchronized String status() {
        if (available()) return "守护进程在线";
        if (disabled) return "守护进程不可用（SELinux 拦截或拉起失败），已回退 shell 命令";
        return "守护进程未连接";
    }

    /** 重新试一次守护进程（设置页「重试」按钮用）。 */
    public static synchronized void reset() {
        closeQuietly();
        disabled = false;
    }

    public static synchronized void close() {
        closeQuietly();
    }

    private static void closeQuietly() {
        try { if (sock != null) sock.close(); } catch (Throwable ignore) {}
        sock = null;
        in = null;
        out = null;
    }

    /* ---------------- 连接管理 ---------------- */

    /**
     * 确保守护进程在线。两种拉起方式（setsid / 裸）各给一个连接窗口，
     * 都失败即把本会话标记为禁用。
     * 返回 false 时调用方应走 shell 兜底，不需要再问第二次。
     */
    public static synchronized boolean ensure() {
        if (disabled) return false;
        if (sock != null && ping()) return true;
        if (appCtx == null || !Caps.hasRoot()) {
            // 没有上下文（拉不起守护进程）或没 root：直接判不可用，不进重试循环
            disabled = true;
            return false;
        }
        String apk = appCtx.getPackageCodePath();
        String inner = "CLASSPATH=" + apk
                + " exec app_process --nice-name=seagull-privd /system-bin " + DAEMON_MAIN;
        // 先 setsid 版：守护进程脱离桌面进程组，桌面被杀重建时不必陪葬
        if (spawnOnce("setsid sh -c '" + inner + "'") && waitConnect(3)) {
            Log.i(TAG, "守护进程已连接（setsid 版）" + SOCKET);
            return true;
        }
        // setsid 版没连上：ROM 可能没 setsid，换裸版再来一次（多一个 su 的代价，值得）
        if (spawnOnce(inner) && waitConnect(4)) {
            Log.i(TAG, "守护进程已连接（裸版）" + SOCKET);
            return true;
        }
        disabled = true;
        closeQuietly();
        Log.w(TAG, "守护进程不可用，后续命令走 su -c 兜底");
        return false;
    }

    /** 拉起后轮询连接；rounds 为轮次（每轮 500ms）。 */
    private static boolean waitConnect(int rounds) {
        for (int i = 0; i < rounds; i++) {
            try {
                Thread.sleep(500);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return false;
            }
            closeQuietly();   // 上一轮连了一半的 socket 先收掉，别漏 fd
            if (connectOnce() && ping()) return true;
        }
        return false;
    }

    private static boolean connectOnce() {
        try {
            LocalSocket s = new LocalSocket();
            s.connect(new LocalSocketAddress(SOCKET, LocalSocketAddress.Namespace.ABSTRACT));
            s.setSoTimeout(2000);
            sock = s;
            in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            out = new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8));
            return true;
        } catch (Throwable t) {
            closeQuietly();
            return false;
        }
    }

    /**
     * 以 root 拉起守护进程。不 waitFor —— app_process 是常驻进程，等它退出就死锁；
     * su 是否真的起活了由 waitConnect 的连接结果判定。
     */
    private static boolean spawnOnce(String cmd) {
        try {
            Process p = new ProcessBuilder("su", "-c", cmd)
                    .redirectErrorStream(true).start();
            Thread drain = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        Log.i(TAG, "daemon: " + line);
                    }
                } catch (Throwable ignore) {}
            }, "seagull-privd-io");
            drain.setDaemon(true);
            drain.start();
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "拉起守护进程失败: " + t);
            return false;
        }
    }

    /* ---------------- 操作 ---------------- */

    public static synchronized boolean ping() {
        if (sock == null) return false;
        PrivCodec.Response r = roundTrip(
                PrivCodec.encodeRequest(nextSeq(), PrivCodec.OP_PING));
        return r != null && r.ok;
    }

    /** 把 comp（pkg/.Activity 形式）启动到指定虚拟屏；flags 为 Intent  flags（含 NEW_TASK）。 */
    public static synchronized boolean launch(int displayId, String comp, int flags) {
        if (!ensure()) return false;
        PrivCodec.Response r = roundTrip(PrivCodec.encodeRequest(nextSeq(),
                PrivCodec.OP_LAUNCH,
                Integer.toString(displayId), comp, Integer.toString(flags)));
        if (r == null || !r.ok) {
            Log.w(TAG, "LAUNCH 失败: " + (r == null ? "无应答" : r.payload));
            return false;
        }
        Log.i(TAG, "LAUNCH " + comp + " → display " + displayId + " : " + r.payload);
        return true;
    }

    /**
     * 注入一串触点（一次一个事件）。xs/ys 是目标屏坐标（客户端已换算缩放）。
     * action 用原始 action（MotionEvent.getAction()，POINTER_事件的 index 在高位）。
     */
    public static synchronized boolean motion(int displayId, int action,
                                              long downTime, long eventTime,
                                              float[] xs, float[] ys, int count) {
        if (!ensureForTouch()) return false;
        PrivCodec.Response r = roundTrip(PrivCodec.encodeMotionRequest(
                nextSeq(), displayId, action, downTime, eventTime, xs, ys, count));
        return r != null && r.ok;
    }

    public static synchronized boolean move(int taskId, int displayId) {
        if (!ensure()) return false;
        PrivCodec.Response r = roundTrip(PrivCodec.encodeRequest(nextSeq(),
                PrivCodec.OP_MOVE, Integer.toString(taskId), Integer.toString(displayId)));
        if (r == null || !r.ok) {
            Log.w(TAG, "MOVE 失败: " + (r == null ? "无应答" : r.payload));
            return false;
        }
        Log.i(TAG, "MOVE task " + taskId + " → display " + displayId + " : " + r.payload);
        return true;
    }

    /**
     * 把 pkg 的任务搬进「锚点包 anchorPkg 在 displayId 屏上的那个 root task」。
     * 复刻 CarWithX TaskMover 的 app_process 逻辑（见 RootMain.doMoveRoot）：
     * 不解析 am 文本、不依赖 `am stack move-task` 子命令，是路① 的守护进程主路。
     * 只在后台线程调用（ensure() 会 fork su）。
     */
    public static synchronized boolean moveToRoot(String pkg, int displayId, String anchorPkg) {
        if (!ensure()) return false;
        PrivCodec.Response r = roundTrip(PrivCodec.encodeRequest(nextSeq(),
                PrivCodec.OP_MOVE_ROOT, pkg, Integer.toString(displayId), anchorPkg));
        if (r == null || !r.ok) {
            Log.w(TAG, "MOVE_ROOT 失败: " + (r == null ? "无应答" : r.payload));
            return false;
        }
        Log.i(TAG, "MOVE_ROOT " + pkg + " → display " + displayId + " : " + r.payload);
        return true;
    }

    public static synchronized boolean remove(int taskId) {
        if (!ensure()) return false;
        PrivCodec.Response r = roundTrip(PrivCodec.encodeRequest(nextSeq(),
                PrivCodec.OP_REMOVE, Integer.toString(taskId)));
        return r != null && r.ok;
    }

    /* ---------------- 批次 AK~AM：建屏 / 拦截 / 监听 / 订阅 ---------------- */

    /** CREATE 应答载体：displayId + 是否真拿到 TRUSTED 位。 */
    public static final class Created {
        public final int displayId;
        public final boolean trusted;
        Created(int id, boolean trusted) { this.displayId = id; this.trusted = trusted; }
    }

    /**
     * K2：root 建 TRUSTED+PUBLIC 屏（surface 后挂）。只可在后台线程调用（会 fork su）。
     * 失败返回 null —— 调用方自动降回本地建屏三路（行为与 AJ 一致）。
     */
    public static synchronized Created create(int w, int h, int dpi) {
        if (!ensure()) return null;
        PrivCodec.Response r = roundTrip(PrivCodec.encodeRequest(nextSeq(),
                PrivCodec.OP_CREATE, Integer.toString(w), Integer.toString(h),
                Integer.toString(dpi)));
        if (r == null || !r.ok) {
            Log.w(TAG, "CREATE 失败: " + (r == null ? "无应答" : r.payload));
            return null;
        }
        // 应答格式：created <id> trusted=1|0
        try {
            String[] p = r.payload.trim().split(" ");
            int id = Integer.parseInt(p[1]);
            boolean trusted = p.length > 2 && p[2].endsWith("1");
            Log.i(TAG, "CREATE root 屏 id=" + id + " trusted=" + trusted);
            return new Created(id, trusted);
        } catch (Throwable t) {
            Log.w(TAG, "CREATE 应答解析失败: " + r.payload);
            return null;
        }
    }

    /** K2：root 屏改尺寸。后台线程调用。 */
    public static synchronized boolean resize(int displayId, int w, int h, int dpi) {
        if (!available() && !ensure()) return false;
        PrivCodec.Response r = roundTrip(PrivCodec.encodeRequest(nextSeq(),
                PrivCodec.OP_RESIZE, Integer.toString(displayId), Integer.toString(w),
                Integer.toString(h), Integer.toString(dpi)));
        return r != null && r.ok;
    }

    /**
     * K2：把画布 Surface 挂到 root 屏上。Surface 经 Parcel.marshal + Base64 过线协议
     *（Base64 NO_WRAP 无空白字符，行协议天然装得下）。s 为 null/无效 = 摘 surface
     *（对应本地路径的 setSurface(null)，画布离场但屏与任务都留着）。
     */
    public static synchronized boolean surface(int displayId, android.view.Surface s) {
        if (!available() && !ensure()) return false;
        String blob;
        if (s != null && s.isValid()) {
            android.os.Parcel p = android.os.Parcel.obtain();
            try {
                s.writeToParcel(p, 0);
                blob = android.util.Base64.encodeToString(p.marshall(),
                        android.util.Base64.NO_WRAP);
            } catch (Throwable t) {
                Log.w(TAG, "surface 序列化失败: " + t);
                return false;
            } finally {
                p.recycle();
            }
        } else {
            blob = "-";
        }
        PrivCodec.Response r = roundTrip(PrivCodec.encodeRequest(nextSeq(),
                PrivCodec.OP_SURFACE, Integer.toString(displayId), blob));
        return r != null && r.ok;
    }

    /** K2：拆 root 屏。幂等（屏已在守护进程退出时全拆 = 成功）。 */
    public static synchronized boolean release(int displayId) {
        if (!available() && !ensure()) return false;
        PrivCodec.Response r = roundTrip(PrivCodec.encodeRequest(nextSeq(),
                PrivCodec.OP_RELEASE, Integer.toString(displayId)));
        return r != null && r.ok;
    }

    /**
     * K3：武装/解除武装路由源（displayId + 嵌入应用）。触摸 TTL 由守护进程在
     * MOTION 里自刷，桌面只管部署时登记与清场。
     */
    public static synchronized boolean arm(int displayId, String pkg) {
        if (!available() && !ensure()) return false;
        PrivCodec.Response r = roundTrip(PrivCodec.encodeRequest(nextSeq(),
                PrivCodec.OP_ARM, Integer.toString(displayId),
                pkg == null || pkg.isEmpty() ? "-" : pkg));
        return r != null && r.ok;
    }

    /** K3：全局启动拦截开/关。守护进程死了拦截自动解除（system_server 侧 binder 清理）。 */
    public static synchronized boolean ctrl(boolean on) {
        if (!available() && !ensure()) return false;
        PrivCodec.Response r = roundTrip(PrivCodec.encodeRequest(nextSeq(),
                PrivCodec.OP_CTRL, on ? "on" : "off"));
        if (r == null || !r.ok) {
            Log.w(TAG, "CTRL(" + on + ") 失败: " + (r == null ? "无应答" : r.payload));
            return false;
        }
        Log.i(TAG, "启动拦截 " + (on ? "已开启" : "已关闭"));
        return true;
    }

    /** 自检页：守护进程一行快照（watch/held/subs/ctrl）。后台线程调用。 */
    public static synchronized String state() {
        if (!ensure()) return "守护进程离线";
        PrivCodec.Response r = roundTrip(PrivCodec.encodeRequest(nextSeq(), PrivCodec.OP_STATE));
        return r != null && r.ok ? r.payload : "STATE 失败: " + (r == null ? "无应答" : r.payload);
    }

    /* ---------------- 事件订阅（独立连接） ----------------
     * 主连接是请求-应答；订阅连接上守护进程持续推 EVT 行（BUMP/ROUTED），
     * 一连接一订阅者。守护进程 EOF（桌面死亡/主动断开）即自动摘除订阅。
     */

    /** 事件行回调（watch 线程上调，消费方自行 post 到主线程）。 */
    public interface EventListener { void onEventLine(String line); }

    private static volatile EventListener evtListener;
    private static LocalSocket watchSock;
    private static Thread watchThread;

    /**
     * 订阅事件推送（幂等：换 listener 只换回调，连接复用）。
     * 会 fork su —— 后台线程调用。返回 false = 守护进程不可用。
     */
    public static synchronized boolean subscribe(EventListener l) {
        if (l == null) return false;
        evtListener = l;
        if (watchThread != null && watchThread.isAlive()) return true;
        if (!ensure()) { evtListener = null; return false; }
        Thread t = new Thread(PrivClient::runWatch, "seagull-watch");
        t.setDaemon(true);
        watchThread = t;
        t.start();
        return true;
    }

    /** 退订并断开订阅连接（幂等）。 */
    public static synchronized void unsubscribe() {
        evtListener = null;
        try { if (watchSock != null) watchSock.close(); } catch (Throwable ignore) {}
        watchSock = null;
        watchThread = null;   // 线程随 readLine EOF 自然退出
    }

    public static boolean subscribed() {
        Thread t = watchThread;
        return t != null && t.isAlive();
    }

    /**
     * 订阅会话主体：独立 socket，发 WATCH on 后只读行。
     * 收到的行两类：① "EVT ..." → 派发给 listener；② 应答行（WATCH on/off 的 OK）
     * → 忽略。socket 死了线程自然退出；下次 subscribe 再起新的。
     */
    private static void runWatch() {
        LocalSocket s = null;
        BufferedReader rin = null;
        BufferedWriter wout = null;
        try {
            s = new LocalSocket();
            s.connect(new LocalSocketAddress(SOCKET, LocalSocketAddress.Namespace.ABSTRACT));
            s.setSoTimeout(0);
            watchSock = s;
            rin = new BufferedReader(
                    new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            wout = new BufferedWriter(
                    new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8));
            wout.write(PrivCodec.encodeRequest(1, PrivCodec.OP_WATCH, "on"));
            wout.flush();
            Log.i(TAG, "事件订阅连接已上线");
            String line;
            while ((line = rin.readLine()) != null) {
                if (line.startsWith(PrivCodec.EVT + " ")) {
                    EventListener l = evtListener;
                    if (l != null) {
                        try { l.onEventLine(line); } catch (Throwable t) {
                            Log.w(TAG, "事件回调异常: " + t);
                        }
                    }
                }
                // 其余（WATCH on 的 OK 应答等）丢弃
            }
            Log.w(TAG, "事件订阅连接断开（EOF）");
        } catch (Throwable t) {
            Log.w(TAG, "事件订阅异常退出: " + t);
        } finally {
            try { if (s != null) s.close(); } catch (Throwable ignore) {}
            if (watchSock == s) watchSock = null;
            if (evtListener != null) evtListener = null;
        }
    }

    /* ---------------- 收发 ---------------- */

    private static long nextSeq() { return ++seq; }

    /**
     * motion（触摸手势链）专用：手势事件从主线程 OnTouchListener 直达这里。
     * ensure() 失败时会在调用线程上 sleep 500ms×N + fork su（最多 ~3.5s）——
     * 守护进程掉线后的第一次触摸在主线程跑这条链就是 ANR（输入超时 5s）。
     * 主线程只接受"已连接"状态：掉线立即返回 false，TouchForward 本手势
     * 余下事件退回命令通道（跑在 seagull-touch 线程）。launch/move 等只在
     * 后台线程走，仍用完整 ensure()。
     */
    private static boolean ensureForTouch() {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            return available();
        }
        return ensure();
    }

    /** 一次请求-应答。任何 IO 异常都意味着连接已死：关掉，让上层走兜底。 */
    private static PrivCodec.Response roundTrip(String req) {
        if (sock == null || out == null || in == null) return null;
        try {
            out.write(req);
            out.flush();
            String line = in.readLine();
            if (line == null) {
                closeQuietly();
                return null;
            }
            return PrivCodec.decodeResponse(line);
        } catch (Throwable t) {
            Log.w(TAG, "roundTrip 失败: " + t);
            closeQuietly();
            return null;
        }
    }
}
