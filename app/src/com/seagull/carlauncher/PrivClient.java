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
