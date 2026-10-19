package com.seagull.carlauncher;

import java.util.Locale;

/**
 * root 守护进程（RootMain）与 Launcher（PrivClient）之间的行文本协议。
 *
 * 为什么是文本而不是 AIDL：构建链里没有 aidl 工具（app/build.sh 只有 7 步），
 * 而通道对面是另一个进程（app_process 起的 root 守护），必须跨进程。
 * 一请求一应答、参数天然无空格（包名/组件名/displayId），行协议够用且可肉眼排障。
 *
 * 报文格式（\n 结尾，UTF-8）：
 *   请求:  <seq> <OP> <arg>*\n
 *   应答:  <seq> OK <payload>\n    或    <seq> ERR <message>\n
 *
 * 这个类刻意不 import 任何 android.* —— 协议层要能在纯 JVM 上自检
 * （见 app/selfcheck/PrivCodecCheck.java）。
 */
public final class PrivCodec {

    public static final String OP_PING = "PING";
    public static final String OP_LAUNCH = "LAUNCH";
    public static final String OP_MOTION = "MOTION";
    public static final String OP_MOVE = "MOVE";
    /**
     * MOVE_ROOT：把目标包的任务搬进「锚点 Activity 所在的那个 root task」。
     * 与 MOVE（moveRootTaskToDisplay，靠 displayId 落点）不同：这条走
     * CarWithX TaskMover 的原逻辑（getTasks 找任务 → getAllRootTaskInfosOnDisplay
     * 找锚点 root task → moveTaskToRootTask），落点由 root task 决定，不依赖
     * ROM 是否保留 `am stack move-task` 子命令，也不需要解析 am 的文本输出。
     */
    public static final String OP_MOVE_ROOT = "MOVE_ROOT";
    public static final String OP_REMOVE = "REMOVE";
    /*
     * 批次 AK~AM 新增（OneStep4 思路落地，实现自写）：
     *   CREATE  — 守护进程（root 上下文）建 TRUSTED+PUBLIC 虚拟屏，任务可以原生
     *             落在这块屏（"生在画布"），不再受"私有屏不许放别人东西"限制；
     *   RESIZE  — 守护进程持有的 VirtualDisplay.resize（root 屏没有本地 vd 对象）；
     *   SURFACE — 画布 Surface 经 Parcel 序列化 + Base64 传给守护进程挂载；
     *   RELEASE — 拆 root 屏；
     *   ARM     — K3 启动拦截的"武装路由源"（displayId + 嵌入应用 + 触摸 TTL）；
     *   CTRL    — IActivityController 全局启动拦截开/关；
     *   WATCH   — 订阅事件推送（任务栈变化、拦截改道），守护进程在同一连接上
     *             持续推 EVT 行（连接本身就是订阅，一连接一订阅者）；
     *   STATE   — 自检页用：监听/拦截/武装/持有屏 的一行快照。
     */
    public static final String OP_CREATE = "CREATE";
    public static final String OP_RESIZE = "RESIZE";
    public static final String OP_SURFACE = "SURFACE";
    public static final String OP_RELEASE = "RELEASE";
    public static final String OP_ARM = "ARM";
    public static final String OP_CTRL = "CTRL";
    public static final String OP_WATCH = "WATCH";
    public static final String OP_STATE = "STATE";

    /** 守护进程主动推送的事件行前缀（无 seq，出现在订阅连接上）。 */
    public static final String EVT = "EVT";
    /** 事件类型：任务栈有任何变化（触发客户端走既有自愈搬回，不携带任务详情）。 */
    public static final String EVT_BUMP = "BUMP";
    /** 事件类型：拦截并改道了一次启动（pkg displayId）。 */
    public static final String EVT_ROUTED = "ROUTED";
    /** 事件类型：订阅连接上线/掉线（pkg 留空）。 */
    public static final String EVT_WATCH = "WATCH";

    /** 线协议里 motion 的 action 用 MotionEvent 原始 action（含 pointer index 高位）。 */
    public static final int ACTION_DOWN = 0;
    public static final int ACTION_UP = 1;
    public static final int ACTION_MOVE = 2;
    public static final int ACTION_CANCEL = 3;
    public static final int ACTION_POINTER_DOWN = 5;
    public static final int ACTION_POINTER_UP = 6;

    private PrivCodec() {}

    /* ---------------- 编码 ---------------- */

    public static String encodeRequest(long seq, String op, String... args) {
        StringBuilder sb = new StringBuilder(32);
        sb.append(seq).append(' ').append(op);
        for (String a : args) {
            if (a == null) a = "";
            // 参数一律不允许带空格/换行：调用方负责 pre-check，这里兜底拒绝
            if (a.indexOf(' ') >= 0 || a.indexOf('\n') >= 0) {
                throw new IllegalArgumentException("协议参数含空白: " + a);
            }
            sb.append(' ').append(a);
        }
        return sb.append('\n').toString();
    }

    /**
     * MOTION 请求：一次带上当前全部触点（多指手势的 POINTER_DOWN/UP 才能真），
     * 外加目标 displayId —— 注入哪块屏由客户端决定（守护进程可能同时服务多个镜像槽）。
     * action 用原始 action（MotionEvent.getAction()，pointer index 在高 8 位）。
     */
    public static String encodeMotionRequest(long seq, int displayId, int action,
                                              long downTime, long eventTime,
                                              float[] xs, float[] ys, int count) {
        if (xs == null || ys == null || count <= 0 || count > 16
                || xs.length < count || ys.length < count) {
            throw new IllegalArgumentException("motion 触点参数非法");
        }
        String[] args = new String[5 + count * 2];
        args[0] = Integer.toString(displayId);
        args[1] = Integer.toString(action);
        args[2] = Long.toString(downTime);
        args[3] = Long.toString(eventTime);
        args[4] = Integer.toString(count);
        for (int i = 0; i < count; i++) {
            args[5 + i * 2] = f2s(xs[i]);
            args[6 + i * 2] = f2s(ys[i]);
        }
        return encodeRequest(seq, OP_MOTION, args);
    }

    public static String encodeOk(long seq, String payload) {
        StringBuilder sb = new StringBuilder(16);
        sb.append(seq).append(" OK");
        String p = payload == null ? "" : payload.replace('\n', ' ').trim();
        if (!p.isEmpty()) sb.append(' ').append(p);
        return sb.append('\n').toString();
    }

    public static String encodeErr(long seq, String message) {
        String m = message == null ? "unknown" : message.replace('\n', ' ');
        return seq + " ERR " + m + "\n";
    }

    /* ---------------- 解码 ---------------- */

    /** 解码请求；格式不符返回 null（调用方丢弃该行）。 */
    public static Request decodeRequest(String line) {
        if (line == null) return null;
        String s = line.trim();
        if (s.isEmpty()) return null;
        int sp1 = s.indexOf(' ');
        if (sp1 < 0) return null;
        try {
            long seq = Long.parseLong(s.substring(0, sp1));
            String rest = s.substring(sp1 + 1).trim();
            if (rest.isEmpty()) return null;
            // -1 = 保留尾随空串：尾部空格不该吞掉最后一个参数（旧 split(" ")
            // 把 "1 ping " 切成 ["1","ping"]，尾巴少一段即与发送端约定不符）
            String[] parts = rest.split(" ", -1);
            if (parts.length == 0) return null;
            String op = parts[0];
            if (op.isEmpty()) return null;
            String[] args = new String[parts.length - 1];
            System.arraycopy(parts, 1, args, 0, args.length);
            return new Request(seq, op, args);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 解码应答；格式不符返回 null。 */
    public static Response decodeResponse(String line) {
        if (line == null) return null;
        String s = line.trim();
        if (s.isEmpty()) return null;
        int sp1 = s.indexOf(' ');
        int sp2 = s.indexOf(' ', sp1 + 1);
        if (sp1 < 0) return null;
        try {
            long seq = Long.parseLong(s.substring(0, sp1));
            String status = sp2 < 0 ? s.substring(sp1 + 1) : s.substring(sp1 + 1, sp2);
            String payload = sp2 < 0 ? "" : s.substring(sp2 + 1);
            boolean ok = "OK".equals(status);
            return new Response(seq, ok, payload);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /* ---------------- MOTION 参数解析（守护进程侧） ---------------- */

    /**
     * 解析 MOTION 的 args（不含 op 本身）：
     * displayId action downTime eventTime count [x y]*
     * 任何一项非法返回 null —— 宁可丢一个事件，也别让守护进程被一条脏报文打挂。
     */
    public static MotionSpec parseMotion(String[] a) {
        if (a == null || a.length < 6) return null;
        try {
            int displayId = Integer.parseInt(a[0]);
            int action = Integer.parseInt(a[1]);
            long down = Long.parseLong(a[2]);
            long ev = Long.parseLong(a[3]);
            int count = Integer.parseInt(a[4]);
            if (count <= 0 || count > 16) return null;
            if (a.length != 5 + count * 2) return null;   // 多给少给都拒绝，别悄悄取前几段
            float[] xs = new float[count];
            float[] ys = new float[count];
            for (int i = 0; i < count; i++) {
                xs[i] = Float.parseFloat(a[5 + i * 2]);
                ys[i] = Float.parseFloat(a[6 + i * 2]);
                if (Float.isNaN(xs[i]) || Float.isInfinite(xs[i])
                        || Float.isNaN(ys[i]) || Float.isInfinite(ys[i])) return null;
            }
            return new MotionSpec(displayId, action, down, ev, xs, ys, count);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /* ---------------- 小工具 ---------------- */

    /**
     * float → 线上字符串。整数去掉小数点省流量（触摸坐标大多是整数），
     * 小数保留 1 位（手指亚像素抖动对跟手感有影响，别四舍五入掉）。
     * 不用 Float.toString 是怕 locale 或者科学计数法乱入。
     */
    public static String f2s(float v) {
        if (v == Math.floor(v) && !Float.isInfinite(v)) return Long.toString((long) v);
        return String.format(Locale.US, "%.1f", v);
    }

    /** 把原始 action 翻成人话，只给排障日志用。 */
    public static String describeAction(int action) {
        int base = action & 0xff;
        int index = (action & 0xff00) >> 8;
        String name;
        switch (base) {
            case ACTION_DOWN: name = "DOWN"; break;
            case ACTION_UP: name = "UP"; break;
            case ACTION_MOVE: name = "MOVE"; break;
            case ACTION_CANCEL: name = "CANCEL"; break;
            case ACTION_POINTER_DOWN: name = "POINTER_DOWN"; break;
            case ACTION_POINTER_UP: name = "POINTER_UP"; break;
            default: name = "ACT_" + base; break;
        }
        return (base == ACTION_POINTER_DOWN || base == ACTION_POINTER_UP)
                ? name + "#" + index : name;
    }

    /* ---------------- 载体 ---------------- */

    public static final class Request {
        public final long seq;
        public final String op;
        public final String[] args;
        public Request(long seq, String op, String[] args) {
            this.seq = seq; this.op = op; this.args = args;
        }
    }

    public static final class Response {
        public final long seq;
        public final boolean ok;
        public final String payload;
        public Response(long seq, boolean ok, String payload) {
            this.seq = seq; this.ok = ok; this.payload = payload;
        }
    }

    public static final class MotionSpec {
        public final int displayId;
        public final int action;
        public final long downTime;
        public final long eventTime;
        public final float[] xs;
        public final float[] ys;
        public final int count;
        public MotionSpec(int displayId, int action, long downTime, long eventTime,
                          float[] xs, float[] ys, int count) {
            this.displayId = displayId; this.action = action;
            this.downTime = downTime; this.eventTime = eventTime;
            this.xs = xs; this.ys = ys; this.count = count;
        }
    }
}
