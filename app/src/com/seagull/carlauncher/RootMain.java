package com.seagull.carlauncher;

import android.content.Intent;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Base64;
import android.util.Log;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.Surface;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * root 守护进程 —— 由 PrivClient 通过 `su -c app_process` 拉起。
 *
 * 为什么需要它：把应用启动进虚拟屏（setLaunchDisplayId）、真多指触摸注入
 * （IInputManager.injectInputEvent + MotionEvent.setDisplayId）、任务搬屏
 * （moveRootTaskToDisplay）这三件事全是 @hide，且权限校验要求调用方是
 * system / shell / root uid。普通应用进程三者都不是；这个进程以 uid 0 运行，
 * 所有 binder 权限检查直接放行（与 root 下执行 am/input 等效，但没有进程启动开销），
 * 隐藏 API 在 app_process 里也没有 enforcement。
 *
 * 协议：见 PrivCodec。一请求一应答，坏报文丢事件不杀进程。
 * WATCH 连接例外：发送 WATCH on 后连接转为事件订阅，守护进程在同一连接上持续推
 * EVT 行（任务栈变化 BUMP / 拦截改道 ROUTED），直到断开或 WATCH off。
 * 生命周期：无客户端 60s 自退出，不宿留（订阅连接算活跃客户端）。
 *
 * 批次 AK~AM（OneStep4 思路落地，实现自写）新增三块能力，全部在本进程（uid 0）：
 *   · root 上下文建 TRUSTED+PUBLIC 虚拟屏（CREATE/RESIZE/SURFACE/RELEASE），
 *     任务可以原生落进这块屏 —— "生在画布"，私有屏"别的应用不许放东西"的
 *     限制从根上消失，PUBLIC 还让输入法能弹进画布；
 *   · ITaskStackListener 实时任务监听（TaskWatcher）—— 事件触发自愈，替代轮询；
 *   · IActivityController 全局启动拦截（LaunchController）—— 画布内点出来的
 *     跨应用启动改道回画布，不依赖 LSPosed 勾作用域。
 * 关键事件同步落盘 /data/local/tmp/seagull-root.log（RootLog，治"抓不到日志"）。
 */
public final class RootMain {

    private static final String TAG = "SeagullPrivd";
    private static final String SOCKET = PrivClient.SOCKET;
    private static final long IDLE_EXIT_MS = 60_000;
    /** 审计 M8：守护进程实例代际 —— 每次进程启动不同，随 STATE 下发给客户端对账 */
    private static final long GEN = System.currentTimeMillis();

    private static volatile long lastActive = System.currentTimeMillis();
    /** 审计 M7：在途客户端数，accept 线程 ++ / serve 线程 --，原子化 */
    private static final java.util.concurrent.atomic.AtomicInteger clients =
            new java.util.concurrent.atomic.AtomicInteger();

    /** 守护进程持有（root 建）的虚拟屏：displayId → VirtualDisplay。 */
    private static final java.util.Map<Integer, android.hardware.display.VirtualDisplay> HELD =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** 事件订阅连接（WATCH on 的会话）。写入方：pushEvent（多线程）。 */
    private static final java.util.List<Subscriber> SUBSCRIBERS =
            java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    /**
     * 审计 H2：订阅写手线程化。旧实现 pushEvent 在调用方线程（TaskWatcher /
     * LaunchController）里直接 write——某一订阅者 socket 缓冲写满（对端假死、
     * WiFi 半开）就整条事件链路卡死，连带应用启动都跟着挂。现在每个订阅连接
     * 一条写手线程 + 有界队列（64 条）：pushEvent 只做 offer（满即丢，事件是
     * 通知语义，丢旧的可接受），写阻塞/失败只影响自己那条连接。
     */
    private static final class Subscriber {
        final BufferedWriter w;
        final java.util.concurrent.ArrayBlockingQueue<String> q =
                new java.util.concurrent.ArrayBlockingQueue<>(64);
        final Thread writer;
        volatile boolean dead;
        int dropped;

        Subscriber(BufferedWriter w) {
            this.w = w;
            writer = new Thread(() -> {
                while (!dead) {
                    try {
                        String line = q.poll(5, java.util.concurrent.TimeUnit.SECONDS);
                        if (line == null) continue;
                        synchronized (w) {
                            w.write(line);
                            w.write('\n');
                            w.flush();
                        }
                    } catch (InterruptedException ie) {
                        return;
                    } catch (Throwable t) {
                        dead = true;
                        return;
                    }
                }
            }, "seagull-evt-writer");
            writer.setDaemon(true);
            writer.start();
        }
    }
    /** ActivityThread.getSystemContext() 缓存：root 上下文（建屏/装控制器都要 Context）。 */
    private static volatile android.content.Context sysCtx;

    /** 单行长度上限：私有 socket 的协议是一行一请求，超长行直接拒绝，别全接进内存。 */
    private static final int MAX_LINE = 64 * 1024;

    private static String readLineSafe(BufferedReader in) throws IOException {
        StringBuilder sb = new StringBuilder(256);
        int ch;
        while ((ch = in.read()) >= 0) {
            if (ch == '\n') return sb.toString();
            if (ch == '\r') continue;
            if (sb.length() >= MAX_LINE) {
                // 把这一行剩的读干再拒，避免残留半行走私进下一条请求
                while ((ch = in.read()) >= 0 && ch != '\n') { /* drain */ }
                return null;
            }
            sb.append((char) ch);
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private RootMain() {}

    public static void main(String[] args) {
        Log.i(TAG, "daemon start pid=" + android.os.Process.myPid()
                + " uid=" + android.os.Process.myUid());
        RootLog.i(TAG, "daemon start pid=" + android.os.Process.myPid()
                + " uid=" + android.os.Process.myUid());
        LocalServerSocket server;
        try {
            server = bind();
        } catch (Throwable t) {
            Log.e(TAG, "bind " + SOCKET + " 失败（大概率 SELinux 拦了 abstract socket）: " + t);
            return;
        }
        startWatchdog();
        // 审计 L8：selfUid 在守护进程刚起来时算好（反射或 dumpsys 一次），
        // 别等第一个客户端来了才在 accept 线程上 fork dumpsys；
        // dumpsys 子进程用完即销毁、stderr 重定向避免挂在无读者的管道上
        try { selfUid(); } catch (Throwable ignore) {}
        Log.i(TAG, "listening on abstract socket " + SOCKET);
        while (true) {
            LocalSocket c = null;
            try {
                c = server.accept();
                // peer uid 校验：抽象 socket 上任何同命名空间进程都可能连进来，
                // 只放行 shell / system / root。否则等于给任意 app 开了一个
                // 能拉起虚拟屏、转发触摸的特权通道。
                int uid = peerUid(c);
                // 白名单：root / system / 本应用自身 uid。SHELL_UID(2000) 已撤：
                // adb shell 连抽象 socket 就能指挥守护进程（拉起虚拟屏、转发
                // 触摸），KernelSU 的同意框就白给了。旧白名单还不含本家
                // uid(10xxx)：普通安装下 launcher 自己的连接全被拒，PRIV_*
                // 全程静默降级成 shell 慢通道（批次 T 复盘坐实）。
                if (uid != android.os.Process.ROOT_UID
                        && uid != android.os.Process.SYSTEM_UID
                        && uid != selfUid()) {
                    Log.w(TAG, "拒绝非特权 peer uid=" + uid);
                    try { c.close(); } catch (Throwable ignore) {}
                    continue;
                }
                lastActive = System.currentTimeMillis();
                clients.incrementAndGet();
                final LocalSocket cc = c;
                Thread t = new Thread(() -> serve(cc), "seagull-privd-client");
                t.setDaemon(true);
                t.start();
            } catch (Throwable t) {
                Log.w(TAG, "accept 失败: " + t);
                if (c != null) { try { c.close(); } catch (Throwable ignore) {} }
                // 审计 L8：accept 持续异常（fd 打光/SELinux 抽风）时退避，
                // 不然死循环刷 logcat 把整台机的日志盘刷爆
                try { Thread.sleep(200); } catch (InterruptedException ie) { return; }
            }
        }
    }

    /** 取对端 uid；LocalSocket.getPeerCredentials() API 29+ 才有，拿不到当 -1（拒绝）。 */
    private static int peerUid(LocalSocket c) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                return c.getPeerCredentials().getUid();
            }
            return -1;
        } catch (Throwable t) {
            Log.w(TAG, "peerUid 失败: " + t);
            return -1;
        }
    }

    private static volatile int sSelfUid = -1;

    /**
     * 本应用自己的 uid。守护进程由本应用派 su 拉起，服务对象就是它：
     * 普通安装 = 10xxx，装进 /system/priv-app = 1000，两条路都要放行。
     * 取法两条：反射 Process.getUidForName（@hide，root 下可用），
     * 失败回落 dumpsys package 的 userId=/appId= 行。
     */
    private static int selfUid() {
        if (sSelfUid > 0) return sSelfUid;
        try {
            java.lang.reflect.Method m = android.os.Process.class
                    .getMethod("getUidForName", String.class);
            Object v = m.invoke(null, Constants.SELF_PACKAGE);
            if (v instanceof Integer) sSelfUid = (Integer) v;
        } catch (Throwable t) {
            Log.w(TAG, "selfUid 反射失败，回落 dumpsys: " + t);
        }
        if (sSelfUid > 0) return sSelfUid;
        try {
            java.lang.Process p = Runtime.getRuntime().exec(
                    new String[]{"dumpsys", "package", Constants.SELF_PACKAGE});
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()));
            String line;
            while ((line = r.readLine()) != null) {
                int i = line.indexOf("userId=");
                if (i < 0) i = line.indexOf("appId=");
                if (i < 0) continue;
                int j = line.indexOf('=', i) + 1, k = j;
                while (k < line.length() && Character.isDigit(line.charAt(k))) k++;
                if (k > j) { sSelfUid = Integer.parseInt(line.substring(j, k)); break; }
            }
            r.close();
            p.destroy();   // 审计 L8：读完即杀，别留僵尸
        } catch (Throwable t) {
            Log.w(TAG, "selfUid dumpsys 也失败: " + t);
        }
        return sSelfUid;
    }

    /* ---------------- 客户端会话 ---------------- */

    private static void serve(LocalSocket c) {
        boolean subscribed = false;
        Subscriber sub = null;
        BufferedWriter out = null;
        try (LocalSocket s = c;
             BufferedReader in = new BufferedReader(
                     new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8))) {
            out = new BufferedWriter(
                    new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8));
            // 审计 M7：30s 无任何字节（含 PING）即断 —— 对端进程被杀/断电残留
            // 的死连接最多占位半分钟，不再永久挂着顶掉空闲退出
            s.setSoTimeout(30_000);
            String line;
            while ((line = readLineSafe(in)) != null) {
                lastActive = System.currentTimeMillis();
                PrivCodec.Request r = PrivCodec.decodeRequest(line);
                if (r == null) {
                    out.write(PrivCodec.encodeErr(0, "bad request"));
                    out.flush();
                    continue;
                }
                // WATCH：连接转为事件订阅（此后守护进程会在同一连接上推 EVT 行）。
                // 订阅连接上其余 op 照常受理（无害：客户端不会在订阅连接上发请求），
                // 客户端主动断开 = 退订（finally 摘订阅）。
                if (PrivCodec.OP_WATCH.equals(r.op)) {
                    boolean on = r.args.length > 0 && "on".equals(r.args[0]);
                    if (on && !subscribed) {
                        sub = new Subscriber(out);
                        SUBSCRIBERS.add(sub);
                        subscribed = true;
                        // 有订阅者才挂监听：事件没人听就不白注册
                        TaskWatcher.start();
                        RootLog.i(TAG, "订阅连接上线（共 " + SUBSCRIBERS.size() + "）");
                    } else if (!on && subscribed) {
                        if (sub != null) { sub.dead = true; sub.writer.interrupt(); }
                        SUBSCRIBERS.remove(sub);
                        sub = null;
                        subscribed = false;
                        if (SUBSCRIBERS.isEmpty()) TaskWatcher.stop();
                    }
                    out.write(PrivCodec.encodeOk(r.seq, subscribed ? "subscribed" : "unsubscribed"));
                    out.flush();
                    continue;
                }
                String payload = handle(r);
                String resp = payload.startsWith("err:")
                        ? PrivCodec.encodeErr(r.seq, payload.substring(4))
                        : PrivCodec.encodeOk(r.seq, payload);
                out.write(resp);
                out.flush();
            }
        } catch (java.net.SocketTimeoutException stale) {
            // 审计 M7：读超时 = 对端假死，按断开处理（不再当异常刷日志）
        } catch (IOException eof) {
            // 客户端断开：正常路径
        } catch (Throwable t) {
            Log.w(TAG, "client 会话异常: " + t);
        } finally {
            clients.decrementAndGet();
            // 订阅写手必须摘干净：残留的话 pushEvent 每轮都对死连接抛异常再摘一次
            if (sub != null) { sub.dead = true; sub.writer.interrupt(); }
            if (out != null) SUBSCRIBERS.remove(sub);
            if (subscribed && SUBSCRIBERS.isEmpty()) TaskWatcher.stop();
            lastActive = System.currentTimeMillis();
        }
    }

    /**
     * 事件推送（多线程调用：TaskWatcher / LaunchController）。
     * 审计 H2：快照后逐个 offer，不在锁内做任何 IO；死/满队列的订阅者
     * 顺手摘掉，写阻塞只影响它自己的写手线程。
     */
    public static void pushEvent(String line) {
        Object[] snap;
        synchronized (SUBSCRIBERS) {
            snap = SUBSCRIBERS.toArray();
        }
        for (Object o : snap) {
            Subscriber s = (Subscriber) o;
            if (s.dead) { SUBSCRIBERS.remove(s); continue; }
            if (!s.q.offer(line)) {
                // 队列满 = 对端消费不动：丢这条事件（通知语义可容忍），
                // 连续满三次视作假死摘除，避免长期空转
                if (++s.dropped >= 3) { s.dead = true; SUBSCRIBERS.remove(s); }
            } else {
                s.dropped = 0;
            }
        }
    }

    private static String handle(PrivCodec.Request r) {
        try {
            switch (r.op) {
                case PrivCodec.OP_PING:
                    // 审计 M8：pong 捎带代际，客户端发现 gen 变了就知道 held 屏全失效
                    return "pong;gen=" + Long.toHexString(GEN);
                case PrivCodec.OP_LAUNCH:
                    return doLaunch(r.args);
                case PrivCodec.OP_MOTION:
                    return doMotion(r.args);
                case PrivCodec.OP_MOVE:
                    return doMove(r.args);
                case PrivCodec.OP_MOVE_ROOT:
                    return doMoveRoot(r.args);
                case PrivCodec.OP_REMOVE:
                    return doRemove(r.args);
                /* 批次 AK~AM（OneStep 思路，实现自写） */
                case PrivCodec.OP_CREATE:
                    return doCreate(r.args);
                case PrivCodec.OP_RESIZE:
                    return doResize(r.args);
                case PrivCodec.OP_SURFACE:
                    return doSurface(r.args);
                case PrivCodec.OP_RELEASE:
                    return doRelease(r.args);
                case PrivCodec.OP_ARM:
                    return doArm(r.args);
                case PrivCodec.OP_CTRL:
                    return doCtrl(r.args);
                case PrivCodec.OP_STATE:
                    return doState();
                default:
                    return "err:unknown op " + r.op;
            }
        } catch (Throwable t) {
            Throwable cause = t.getCause() != null ? t.getCause() : t;
            Log.w(TAG, r.op + " 异常: " + cause);
            return "err:" + cause;
        }
    }

    /* ---------------- LAUNCH：进程内 startActivityAsUser ---------------- */

    private static String doLaunch(String[] a) throws Exception {
        if (a.length < 3) return "err:args";
        int displayId = Integer.parseInt(a[0]);
        String comp = a[1];
        int flags = Integer.parseInt(a[2]);
        android.content.ComponentName cn =
                android.content.ComponentName.unflattenFromString(comp);
        if (cn == null) return "err:bad comp " + comp;

        Intent i = new Intent(Intent.ACTION_MAIN);
        i.addCategory(Intent.CATEGORY_LAUNCHER);
        i.setComponent(cn);
        i.setFlags(flags);

        Bundle options = launchOptions(displayId);
        Object res = startActivityAsUser(i, options, flags, 0);
        return "started " + comp + " display=" + displayId + " result=" + res;
    }

    static Bundle launchOptions(int displayId) throws Exception {
        Class<?> ao = Class.forName("android.app.ActivityOptions");
        Object opts = ao.getMethod("makeBasic").invoke(null);
        ao.getMethod("setLaunchDisplayId", int.class).invoke(opts, displayId);
        return (Bundle) ao.getMethod("toBundle").invoke(opts);
    }

    /**
     * 优先用 am 同款 11 参重载（caller, callingPackage, ...）；
     * callingPackage 固定 com.android.shell —— 与 shell 命令行为对齐，
     * 各 ROM 对 shell 包的放开程度最高。
     */
    static Object startActivityAsUser(Intent intent, Bundle options,
                                               int flags, int userId) throws Exception {
        IBinder b = service("activity");
        Class<?> iam = Class.forName("android.app.IActivityManager");
        Object svc = iam.getMethod("asInterface", IBinder.class).invoke(null, b);
        Class<?> threadCls = Class.forName("android.app.IApplicationThread");
        Class<?> profilerCls = null;
        try {
            profilerCls = Class.forName("android.app.ProfilerInfo");
        } catch (Throwable ignore) {}

        Method best = null;
        for (Method m : iam.getMethods()) {
            if (!"startActivityAsUser".equals(m.getName())) continue;
            Class<?>[] p = m.getParameterTypes();
            if (p.length < 6) continue;
            if (p[p.length - 1] != int.class) continue;      // 末位 userId
            if (p[p.length - 2] != Bundle.class) continue;   // 次末位 options
            boolean hasIntent = false;
            for (Class<?> c : p) if (c == Intent.class) hasIntent = true;
            if (!hasIntent) continue;
            if (profilerCls != null) {
                boolean hasProfiler = false;
                for (Class<?> c : p) if (c == profilerCls) hasProfiler = true;
                if (!hasProfiler) continue;                  // am 用的重载必带 ProfilerInfo
            }
            if (best == null || p.length > best.getParameterTypes().length) best = m;
        }
        if (best == null) throw new NoSuchMethodException("startActivityAsUser 重载未找到");
        return best.invoke(svc, buildArgs(best, intent, options, flags, userId,
                threadCls, profilerCls));
    }

    /** 按参数类型逐个填值：ICaller 一律 null（uid 0 本身就是通行证）。 */
    private static Object[] buildArgs(Method m, Intent intent, Bundle options,
                                      int flags, int userId,
                                      Class<?> threadCls, Class<?> profilerCls) {
        Class<?>[] p = m.getParameterTypes();
        Object[] argv = new Object[p.length];
        int plainInts = 0;
        for (int i = 0; i < p.length; i++) {
            Class<?> c = p[i];
            if (c == threadCls) argv[i] = null;
            else if (c == Intent.class) argv[i] = intent;
            else if (c == Bundle.class) argv[i] = options;
            else if (c == String.class) argv[i] = i == 1 ? "com.android.shell" : null;
            else if (c == IBinder.class) argv[i] = null;
            else if (c == int.class) {
                if (i == p.length - 1) argv[i] = userId;          // userId 在末位
                else if (plainInts == 0) { argv[i] = 0; plainInts++; }  // 第一个是 requestCode
                else argv[i] = flags;                              // 第二个是 flags
            }
            else if (profilerCls != null && c == profilerCls) argv[i] = null;
            else throw new IllegalArgumentException("未知参数类型 " + c);
        }
        return argv;
    }

    /* ---------------- MOTION：真多指注入 ---------------- */

    private static String doMotion(String[] args) throws Exception {
        PrivCodec.MotionSpec ms = PrivCodec.parseMotion(args);
        if (ms == null) return "err:bad motion args";
        // K3：注入触摸 = 画布上的用户活动证据，刷新武装 TTL（拦截只信 5s 内摸过的屏）
        LaunchController.touchAt(ms.displayId);
        IBinder b = service("input");
        Class<?> iim = Class.forName("android.hardware.input.IInputManager");
        Object svc = iim.getMethod("asInterface", IBinder.class).invoke(null, b);
        MotionEvent ev = buildMotion(ms);
        if (ev == null) return "err:buildMotion failed";
        Object res = iim.getMethod("injectInputEvent", android.view.InputEvent.class, int.class)
                .invoke(svc, ev, 0);
        return Boolean.TRUE.equals(res) ? "injected" : "rejected";
    }

    private static MotionEvent buildMotion(PrivCodec.MotionSpec ms) throws Exception {
        MotionEvent.PointerProperties[] pp = new MotionEvent.PointerProperties[ms.count];
        MotionEvent.PointerCoords[] pc = new MotionEvent.PointerCoords[ms.count];
        for (int i = 0; i < ms.count; i++) {
            pp[i] = new MotionEvent.PointerProperties();
            pp[i].id = i;            // pointer id 与 index 一致：单流转发下最稳（input 工具同款）
            pp[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
            pc[i] = new MotionEvent.PointerCoords();
            pc[i].x = ms.xs[i];
            pc[i].y = ms.ys[i];
            pc[i].pressure = 1f;
            pc[i].size = 1f;
        }
        MotionEvent ev = MotionEvent.obtain(ms.downTime, ms.eventTime, ms.action, ms.count,
                pp, pc, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
        try {
            MotionEvent.class.getMethod("setDisplayId", int.class).invoke(ev, ms.displayId);
        } catch (Throwable t) {
            // 【批次 AI·fail-closed】setDisplayId 是 @hide 方法，部分 ROM/进程组合下
            // 反射会失败。此前只打警告继续注入 —— 事件没带 displayId 就落到主屏
            // (display 0)，表现正是「画布里两个应用触摸位置全错」且无任何可见痕迹。
            // 现在直接拒绝注入：调用方（TouchForward.replay）收到 err 会把本次手势
            // 余下事件退回 `input -d <id>` 命令通道，那条路的 displayId 是命令行参数，
            // 没有这个问题。
            Log.w(TAG, "setDisplayId 反射失败，拒绝注入（防误注主屏）: " + t);
            try { ev.recycle(); } catch (Throwable ignore) {}
            return null;
        }
        return ev;
    }

    /* ---------------- MOVE / REMOVE：任务搬屏 ---------------- */

    private static String doMove(String[] args) throws Exception {
        if (args.length < 2) return "err:args";
        int taskId = Integer.parseInt(args[0]);
        int displayId = Integer.parseInt(args[1]);
        IBinder b = service("activity_task");
        Class<?> iatm = Class.forName("android.app.IActivityTaskManager");
        Object svc = iatm.getMethod("asInterface", IBinder.class).invoke(null, b);
        Object res = iatm.getMethod("moveRootTaskToDisplay", int.class, int.class)
                .invoke(svc, taskId, displayId);
        return Boolean.TRUE.equals(res)
                ? "moved task " + taskId + " → display " + displayId
                : "moveRootTaskToDisplay 返回 " + res;
    }

    /**
     * MOVE_ROOT：复刻 CarWithX `com.leting.pip.TaskMover.main()` 的 app_process 逻辑
     * （对应复刻蓝图 §2.2「锚点 + move-task」）。三段：
     *   ① getTasks 找 pkg 的任务 → taskId（**要求它在主屏或目标屏**，
     *      别的虚拟屏上的同名任务属于对槽，绝不抢）
     *   ② getAllRootTaskInfosOnDisplay(displayId) 找 **锚点包** 所在的 root task → rootTaskId，
     *      锚点行匹配不到就用该屏第一个 root task 兜底
     *   ③ moveTaskToRootTask(taskId, rootTaskId, true)
     *
     * 为什么比 shell `am stack move-task` 稳：不解析 am 的文本输出、不依赖 ROM
     * 是否保留该子命令、也没有一次 fork 一个 su 的开销。@hide 接口方法在
     * app_process（uid 0）里没有 hidden-api enforcement，AIDL 代理方法都是 public。
     *
     * 参数顺序：pkg displayId anchorPkg。
     */
    private static String doMoveRoot(String[] a) throws Exception {
        if (a.length < 3) return "err:args";
        String pkg = a[0];
        int displayId = Integer.parseInt(a[1]);
        String anchorPkg = a[2];

        IBinder b = service("activity_task");
        Class<?> iatm = Class.forName("android.app.IActivityTaskManager");
        Object svc = iatm.getMethod("asInterface", IBinder.class).invoke(null, b);

        // ① 源任务：优先主屏(0)或目标屏上的实例；先记账，避免把对槽的任务抢走
        int taskId = -1, taskDisplay = -1;
        int fallbackId = -1, fallbackDisplay = -1;
        for (Object t : getTasks(iatm, svc)) {
            if (!(componentOf(t, "topActivity").contains(pkg)
                    || componentOf(t, "baseActivity").contains(pkg))) continue;
            int id = intField(t, "taskId");
            int d = optionalIntField(t, "displayId", -1);
            if (d == 0 || d == displayId) { taskId = id; taskDisplay = d; break; }
            if (fallbackId < 0) { fallbackId = id; fallbackDisplay = d; }
        }
        if (taskId < 0) { taskId = fallbackId; taskDisplay = fallbackDisplay; }
        if (taskId < 0) return "err:target task not found";
        if (taskDisplay == displayId) return "already on display " + displayId + " task=" + taskId;
        // 源任务在别的虚拟屏（对槽的实例）→ 明确拒绝，别把另一块画布上的应用搬走
        if (taskDisplay > 0) return "err:target task on other display " + taskDisplay + " , skip";

        // ② 落点 root task：锚点包所在栈；没有就用该屏第一个 root task 兜底
        int rootId = -1;
        @SuppressWarnings("unchecked")
        List<Object> roots = (List<Object>) iatm
                .getMethod("getAllRootTaskInfosOnDisplay", int.class).invoke(svc, displayId);
        if (roots != null) {
            for (Object rt : roots) {
                if (componentOf(rt, "topActivity").contains(anchorPkg)
                        || componentOf(rt, "baseActivity").contains(anchorPkg)) {
                    rootId = intField(rt, "taskId");
                    break;
                }
            }
            if (rootId < 0 && !roots.isEmpty()) rootId = intField(roots.get(0), "taskId");
        }
        if (rootId < 0) return "err:no root task on display " + displayId;

        // ③ 搬（返回 void，不抛异常即成功）
        iatm.getMethod("moveTaskToRootTask", int.class, int.class, boolean.class)
                .invoke(svc, taskId, rootId, true);
        return "moved task=" + taskId + " root=" + rootId + " display=" + displayId;
    }

    /**
     * getTasks：优先 am 同款 4 参重载（A12+），老 SDK 回落 1 参版。
     * displayId 传 -1 = 不按屏过滤（CarWithX 原值），我们在结果里再按 displayId 挑。
     */
    private static List<?> getTasks(Class<?> iatm, Object svc) throws Exception {
        try {
            Object r = iatm.getMethod("getTasks",
                            int.class, boolean.class, boolean.class, int.class)
                    .invoke(svc, 200, false, false, -1);
            return r instanceof List ? (List<?>) r : java.util.Collections.emptyList();
        } catch (NoSuchMethodException old) {
            Object r = iatm.getMethod("getTasks", int.class).invoke(svc, 200);
            return r instanceof List ? (List<?>) r : java.util.Collections.emptyList();
        }
    }

    /** 公开字段取值（RunningTaskInfo / RootTaskInfo 的字段都是 public）。读不到返回 ""。 */
    private static String componentOf(Object o, String field) {
        try {
            Object v = o.getClass().getField(field).get(o);
            return v == null ? "" : v.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    private static int intField(Object o, String field) throws Exception {
        return ((Number) o.getClass().getField(field).get(o)).intValue();
    }

    /** 同 intField，但字段缺失/取不到时回落 def（老 ROM 上 RunningTaskInfo 可能没有 displayId）。 */
    private static int optionalIntField(Object o, String field, int def) {
        try {
            Object v = o.getClass().getField(field).get(o);
            return v instanceof Number ? ((Number) v).intValue() : def;
        } catch (Throwable t) {
            return def;
        }
    }

    private static String doRemove(String[] args) throws Exception {
        if (args.length < 1) return "err:args";
        int taskId = Integer.parseInt(args[0]);
        IBinder b = service("activity_task");
        Class<?> iatm = Class.forName("android.app.IActivityTaskManager");
        Object svc = iatm.getMethod("asInterface", IBinder.class).invoke(null, b);
        Object res = iatm.getMethod("removeTask", int.class).invoke(svc, taskId);
        return Boolean.TRUE.equals(res) ? "removed " + taskId : "removeTask 返回 " + res;
    }

    /* ---------------- CREATE / RESIZE / SURFACE / RELEASE：root 虚拟屏（批次 AL） ----------------
     *
     * OneStep 思路、实现自写：屏由本进程（uid 0）建 —— TRUSTED 位在本进程直接拿得到
     * （不需要应用进程授角色再借 DisplayManager），任务可以原生落进这块屏（"生在画布"），
     * PRIVATE 屏"别的应用不许放东西"与 Android 14 拉回主屏两类问题从根上消失；
     * PUBLIC 还让输入法能弹进画布。
     *
     * 与 MirrorSlot 本地建屏（私有屏/角色 TRUSTED/投影）的关系：本组事务是优先路，
     * 失败（SELinux/ROM 拒绝）由客户端自动降回旧三路，行为与 AJ 一致。
     *
     * 生命周期：HELD 表持有 VirtualDisplay；守护进程退出 = 屏全拆（保活由桌面负责）。
     * Surface 不在 CREATE 时传 —— 走 SURFACE（Parcel 序列化 + Base64，行协议传 blob）。
     */

    private static String doCreate(String[] a) throws Exception {
        if (a.length < 3) return "err:args";
        int w = Integer.parseInt(a[0]);
        int h = Integer.parseInt(a[1]);
        int dpi = Integer.parseInt(a[2]);
        if (w <= 0 || h <= 0 || dpi <= 0) return "err:bad size " + w + "x" + h + "@" + dpi;
        // 审计 L8：上限钳制，防异常/恶意请求把 root 进程内存打爆
        if (w > 4096 || h > 4096 || dpi > 1000) return "err:size 超限 " + w + "x" + h + "@" + dpi;
        android.content.Context ctx = sysCtx();
        android.hardware.display.DisplayManager dm =
                (android.hardware.display.DisplayManager)
                        ctx.getSystemService(android.content.Context.DISPLAY_SERVICE);
        if (dm == null) return "err:DisplayManager 不可达";

        // flag 候选复用 TrustedFlags（与本地 TRUSTED 路同一套降级序列），root 下命中率高：
        // 首选组通常直接通过（uid 0 过 VIRTUAL_DISPLAY_FLAG_TRUSTED 权限检查）
        Throwable last = null;
        for (int raw : TrustedFlags.candidateFlags()) {
            int flags = TrustedFlags.normalize(raw, android.os.Build.VERSION.SDK_INT);
            android.hardware.display.VirtualDisplay vd;
            try {
                vd = dm.createVirtualDisplay("seagull-root", w, h, dpi, null, flags, null, null);
            } catch (Throwable t) {
                last = t;
                RootLog.w(TAG, "root 建屏 flags=0x" + Integer.toHexString(flags) + " 被拒", t);
                continue;
            }
            if (vd == null) continue;
            int df = -1;
            try {
                df = vd.getDisplay() != null ? vd.getDisplay().getFlags() : -1;
            } catch (Throwable ignore) {}
            boolean trusted = TrustedFlags.hasTrusted(df);
            int id = vd.getDisplay() != null ? vd.getDisplay().getDisplayId() : -1;
            if (id <= 0) {
                try { vd.release(); } catch (Throwable ignore) {}
                continue;
            }
            HELD.put(id, vd);
            RootLog.i(TAG, "root 屏建成 id=" + id + " " + w + "x" + h + "@" + dpi
                    + " trusted=" + trusted + " flags=" + TrustedFlags.describe(flags));
            return "created " + id + " trusted=" + (trusted ? 1 : 0);
        }
        return "err:全部 flag 候选被拒" + (last != null ? "（末次: " + last + "）" : "");
    }

    private static String doResize(String[] a) throws Exception {
        if (a.length < 4) return "err:args";
        int id = Integer.parseInt(a[0]);
        int w = Integer.parseInt(a[1]);
        int h = Integer.parseInt(a[2]);
        int dpi = Integer.parseInt(a[3]);
        android.hardware.display.VirtualDisplay vd = HELD.get(id);
        if (vd == null) return "err:no display " + id;
        vd.resize(w, h, dpi);
        return "resized " + id + " " + w + "x" + h + "@" + dpi;
    }

    private static String doSurface(String[] a) throws Exception {
        if (a.length < 2) return "err:args";
        int id = Integer.parseInt(a[0]);
        android.hardware.display.VirtualDisplay vd = HELD.get(id);
        if (vd == null) return "err:no display " + id;
        String blob = a[1];
        if (blob.isEmpty() || "-".equals(blob)) {
            vd.setSurface(null);
            RootLog.i(TAG, "root 屏 " + id + " surface 摘除（画布暂时离场）");
            return "surface cleared " + id;
        }
        byte[] raw;
        try {
            raw = Base64.decode(blob, Base64.NO_WRAP);
        } catch (IllegalArgumentException bad) {
            return "err:bad base64";
        }
        // 审计 M15：Surface Parcel 实际只有几百字节，64KB 行协议已天然封顶；
        // 这里再给显式上限，防止畸形大 blob 在 root 进程里白耗内存
        if (raw.length > 64 * 1024) return "err:surface blob 过大 " + raw.length;
        android.os.Parcel p = null;
        try {
            p = android.os.Parcel.obtain();
            p.unmarshall(raw, 0, raw.length);
            p.setDataPosition(0);
            Surface sf = Surface.CREATOR.createFromParcel(p);
            if (sf == null || !sf.isValid()) return "err:surface 无效";
            vd.setSurface(sf);
            RootLog.i(TAG, "root 屏 " + id + " surface 已挂载");
            return "surface set " + id;
        } finally {
            if (p != null) p.recycle();
        }
    }

    private static String doRelease(String[] a) throws Exception {
        if (a.length < 1) return "err:args";
        int id = Integer.parseInt(a[0]);
        android.hardware.display.VirtualDisplay vd = HELD.remove(id);
        if (vd == null) return "no display " + id + "（已不在持有表，幂等成功）";
        try { vd.release(); } catch (Throwable t) {
            RootLog.w(TAG, "root 屏 " + id + " release 异常", t);
        }
        RootLog.i(TAG, "root 屏 " + id + " 已拆除，剩 " + HELD.size());
        return "released " + id;
    }

    /* ---------------- ARM / CTRL：全局启动拦截（批次 AM） ---------------- */

    private static String doArm(String[] a) {
        if (a.length < 2) return "err:args";
        int id;
        try {
            id = Integer.parseInt(a[0]);
        } catch (NumberFormatException e) {
            return "err:bad displayId " + a[0];
        }
        String pkg = a[1];
        if (id <= 0 || "-".equals(pkg)) {
            LaunchController.arm(-1, "");
            return "disarmed";
        }
        LaunchController.arm(id, pkg);
        return "armed " + pkg + "@" + id;
    }

    private static String doCtrl(String[] a) {
        boolean on = a.length > 0 && "on".equals(a[0]);
        boolean ok = LaunchController.install(on);
        if (!ok) return "err:controller " + (on ? "install" : "remove") + " 失败（见日志）";
        return "ctrl " + (on ? "on" : "off");
    }

    private static String doState() {
        // 审计 M8：gen 随 STATE 下发 —— 客户端用它做代际握手，
        // 守护进程自退出后被 su 重启（gen 变化）时，客户端据此知道
        // HELD 里的虚拟屏全没了，避免拿着陈旧 displayId 继续画
        return "watch=" + (TaskWatcher.isRegistered() ? 1 : 0)
                + " held=" + HELD.keySet()
                + " subs=" + SUBSCRIBERS.size()
                + " gen=" + Long.toHexString(GEN)
                + " " + LaunchController.stateLine();
    }

    /* ---------------- root 系统上下文（建屏要用） ---------------- */

    /**
     * ActivityThread.getSystemContext()：app_process 进程里拿"系统级"Context 的唯一正路。
     * DisplayManager 注册在这个 Context 上（application context 不行 —— 没有 app 装配流程）。
     * 失败抛给调用方转 err（客户端自动降级旧链路）。
     */
    private static synchronized android.content.Context sysCtx() throws Exception {
        if (sysCtx != null) return sysCtx;
        Class<?> at = Class.forName("android.app.ActivityThread");
        Object thread = at.getMethod("systemMain").invoke(null);
        sysCtx = (android.content.Context) thread.getClass()
                .getMethod("getSystemContext").invoke(thread);
        if (sysCtx == null) throw new IllegalStateException("getSystemContext 返回 null");
        RootLog.i(TAG, "systemContext 就绪 pkg=" + sysCtx.getPackageName());
        return sysCtx;
    }

    /* ---------------- 基础设施 ---------------- */

    /**
     * 绑定 abstract namespace 服务端。
     * LocalServerSocket(LocalSocketAddress) 是 @hide（公开构造只有 String/FileDescriptor，
     * 而 String 版落在 RESERVED 文件命名空间 /dev/socket 下），所以反射拿。
     * 反射不到就退 RESERVED —— 那时客户端也得跟着改，先在日志里喊一声。
     */
    private static LocalServerSocket bind() throws Exception {
        try {
            LocalSocketAddress addr = new LocalSocketAddress(
                    SOCKET, LocalSocketAddress.Namespace.ABSTRACT);
            java.lang.reflect.Constructor<LocalServerSocket> c =
                    LocalServerSocket.class.getConstructor(LocalSocketAddress.class);
            return c.newInstance(addr);
        } catch (Throwable t) {
            Log.w(TAG, "abstract namespace 构造反射失败，退 RESERVED: " + t);
            return new LocalServerSocket(SOCKET);
        }
    }

    static IBinder service(String name) throws Exception {
        Class<?> sm = Class.forName("android.os.ServiceManager");
        IBinder b = (IBinder) sm.getMethod("getService", String.class).invoke(null, name);
        if (b == null) throw new IllegalStateException("ServiceManager.getService(" + name + ") 为 null");
        return b;
    }

    private static void startWatchdog() {
        Thread wd = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(10_000);
                } catch (InterruptedException e) {
                    return;
                }
                long idle = System.currentTimeMillis() - lastActive;
                if (clients.get() <= 0 && idle > IDLE_EXIT_MS) {
                    Log.i(TAG, "无客户端 " + (idle / 1000) + "s，自退出");
                    System.exit(0);
                }
            }
        }, "seagull-privd-wd");
        wd.setDaemon(true);
        wd.start();
    }
}
