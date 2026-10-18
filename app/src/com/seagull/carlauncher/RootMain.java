package com.seagull.carlauncher;

import android.content.Intent;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.view.InputDevice;
import android.view.MotionEvent;

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
 * 生命周期：无客户端 60s 自退出，不宿留。
 */
public final class RootMain {

    private static final String TAG = "SeagullPrivd";
    private static final String SOCKET = PrivClient.SOCKET;
    private static final long IDLE_EXIT_MS = 60_000;

    private static volatile long lastActive = System.currentTimeMillis();
    private static volatile int clients;

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
        LocalServerSocket server;
        try {
            server = bind();
        } catch (Throwable t) {
            Log.e(TAG, "bind " + SOCKET + " 失败（大概率 SELinux 拦了 abstract socket）: " + t);
            return;
        }
        startWatchdog();
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
                clients++;
                final LocalSocket cc = c;
                Thread t = new Thread(() -> serve(cc), "seagull-privd-client");
                t.setDaemon(true);
                t.start();
            } catch (Throwable t) {
                Log.w(TAG, "accept 失败: " + t);
                if (c != null) { try { c.close(); } catch (Throwable ignore) {} }
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
            Object v = m.invoke(null, "com.seagull.carlauncher");
            if (v instanceof Integer) sSelfUid = (Integer) v;
        } catch (Throwable t) {
            Log.w(TAG, "selfUid 反射失败，回落 dumpsys: " + t);
        }
        if (sSelfUid > 0) return sSelfUid;
        try {
            java.lang.Process p = Runtime.getRuntime().exec(
                    new String[]{"dumpsys", "package", "com.seagull.carlauncher"});
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
        } catch (Throwable t) {
            Log.w(TAG, "selfUid dumpsys 也失败: " + t);
        }
        return sSelfUid;
    }

    /* ---------------- 客户端会话 ---------------- */

    private static void serve(LocalSocket c) {
        try (LocalSocket s = c;
             BufferedReader in = new BufferedReader(
                     new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter out = new BufferedWriter(
                     new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = readLineSafe(in)) != null) {
                lastActive = System.currentTimeMillis();
                PrivCodec.Request r = PrivCodec.decodeRequest(line);
                String resp;
                if (r == null) {
                    resp = PrivCodec.encodeErr(0, "bad request");
                } else {
                    String payload = handle(r);
                    resp = payload.startsWith("err:")
                            ? PrivCodec.encodeErr(r.seq, payload.substring(4))
                            : PrivCodec.encodeOk(r.seq, payload);
                }
                out.write(resp);
                out.flush();
            }
        } catch (IOException eof) {
            // 客户端断开：正常路径
        } catch (Throwable t) {
            Log.w(TAG, "client 会话异常: " + t);
        } finally {
            clients--;
            lastActive = System.currentTimeMillis();
        }
    }

    private static String handle(PrivCodec.Request r) {
        try {
            switch (r.op) {
                case PrivCodec.OP_PING:
                    return "pong";
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

    private static Bundle launchOptions(int displayId) throws Exception {
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
    private static Object startActivityAsUser(Intent intent, Bundle options,
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
            Log.w(TAG, "setDisplayId 反射失败（老 ROM 没有这个方法）: " + t);
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

    private static IBinder service(String name) throws Exception {
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
                if (clients <= 0 && idle > IDLE_EXIT_MS) {
                    Log.i(TAG, "无客户端 " + (idle / 1000) + "s，自退出");
                    System.exit(0);
                }
            }
        }, "seagull-privd-wd");
        wd.setDaemon(true);
        wd.start();
    }
}
