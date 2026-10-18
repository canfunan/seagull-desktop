package com.seagull.carlauncher;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * `am stack list` 输出解析（批次 K，目标自愈 ensureOnDisplay 的纯逻辑部分）。
 *
 * 移植自用户仓库 carlink-desktop-private 的 svc/StackListParser.kt（其上核验
 * 参考版 TaskMover.smali）：车联助手在 app_process 里用反射搬任务，我们在 root
 * shell 下用 `am stack list` 找目标任务是否还在虚拟屏 —— singleTask 应用
 * （网易云这类）在虚拟屏内部跳转会把任务拉回主屏，自愈必须靠这个判据。
 *
 * 零 android.* import，可纯 JVM 单测（app/selfcheck/StackListCheck.java）——
 * 这是"目标任务是否还在虚拟屏"的唯一判据，解析错一格，自愈就会误重拉。
 *
 * 输出结构（AOSP 各版本大体一致）：
 *   displayId=0
 *     stackId=1 ...
 *       taskId=25 ...
 *         *TaskRecord{...} ... topActivity=ComponentInfo{com.netease.cloudmusic/...}
 *   displayId=2
 *     stackId=7 ...
 *       taskId=31 ...
 */
public final class StackScan {

    public static final class TaskRef {
        public final int stackId;
        public final int taskId;
        public TaskRef(int stackId, int taskId) { this.stackId = stackId; this.taskId = taskId; }
        @Override public String toString() { return "stack=" + stackId + "/task=" + taskId; }
    }

    private StackScan() {}

    /** 按 displayId 分段；displayId 行独立成行（stack 行里也含 displayId= 字样，勿误分）。 */
    public static Map<Integer, String> segments(String out) {
        Map<Integer, String> map = new LinkedHashMap<>();
        if (out == null) return map;
        int cur = -1;
        StringBuilder sb = new StringBuilder();
        for (String line : out.split("\n")) {
            String t = line.trim();
            // 段头容错：有些 ROM 的 displayId= 行带尾随内容（"displayId=0 stacks=2"、
            // "displayId=0 (default)"），整段 isDigits 会一个段都切不出来 →
            // 自愈静默失效。改成前缀 + 取前导整数。
            if (t.startsWith("displayId=")) {
                int id = leadingInt(t, 10);
                if (id >= 0) {
                    if (cur >= 0) map.put(cur, sb.toString());
                    cur = id;
                    sb.setLength(0);
                    continue;
                }
            }
            if (cur >= 0) {
                sb.append(line).append('\n');
            }
        }
        if (cur >= 0) map.put(cur, sb.toString());
        return map;
    }

    /** 从 off 起取前导十进制整数，没有则 -1。 */
    private static int leadingInt(String s, int off) {
        int i = off, end = i;
        while (end < s.length() && Character.isDigit(s.charAt(end))) end++;
        if (end == i) return -1;
        try { return Integer.parseInt(s.substring(i, end)); } catch (Throwable t) { return -1; }
    }

    /** 目标包是否在指定屏上（任务描述/topActivity 任意位置含包名）。 */
    public static boolean isRunningOnDisplay(String out, String pkg, int displayId) {
        String seg = segments(out).get(displayId);
        return segHasPkg(seg, pkg);
    }

    /**
     * 指定屏上包含目标包的最近一个任务（搬回的源）。
     * 任务块 = taskId= 行（含）到下一个 taskId= 行（不含）；包名出现在块内即命中。
     */
    public static TaskRef findTaskOnDisplay(String out, String pkg, int displayId) {
        String seg = segments(out).get(displayId);
        if (seg == null || pkg == null || pkg.isEmpty()) return null;
        List<String> lines = new ArrayList<>();
        for (String l : seg.split("\n")) lines.add(l);
        int[] stackOf = new int[lines.size()];
        int lastStack = -1;
        for (int i = 0; i < lines.size(); i++) {
            lastStack = pickStack(lines.get(i), lastStack);
            stackOf[i] = lastStack;
        }
        // 收集任务块起点
        List<int[]> starts = new ArrayList<>();   // {taskId, stackId, lineIdx}
        for (int i = 0; i < lines.size(); i++) {
            int tid = pickInt(lines.get(i), "taskId=");
            if (tid >= 0) starts.add(new int[] {tid, stackOf[i], i});
        }
        TaskRef hit = null;
        for (int s = 0; s < starts.size(); s++) {
            int from = starts.get(s)[2];
            int to = (s + 1 < starts.size()) ? starts.get(s + 1)[2] : lines.size();
            for (int i = from; i < to; i++) {
                if (lineHasPkg(lines.get(i), pkg)) {
                    hit = new TaskRef(starts.get(s)[1], starts.get(s)[0]);
                    break;
                }
            }
        }
        return hit;
    }

    /** 指定屏上的第一个任务（move-task 的目的 rootTask；找不到锚点时的兜底）。 */
    public static TaskRef firstTaskOnDisplay(String out, int displayId) {
        String seg = segments(out).get(displayId);
        if (seg == null) return null;
        int lastStack = -1;
        for (String line : seg.split("\n")) {
            lastStack = pickStack(line, lastStack);
            int tid = pickInt(line, "taskId=");
            if (tid >= 0) return new TaskRef(lastStack, tid);
        }
        return null;
    }

    /**
     * 指定屏上的第一个 **stack**（root task）id —— 哪怕这块屏上一个任务都没有。
     * 批次 AA 新增：move-task 的落点需要的是 root task，而空虚拟屏通常仍有一个
     * 默认 root task（只是里面没任务）—— 锚点 Activity 已经自己退掉时靠它兜底，
     * 否则自愈会因为"没有锚点栈"直接放弃搬运。
     * 返回 -1 = 该屏在输出里连一条栈信息都没有。
     */
    public static int firstStackOnDisplay(String out, int displayId) {
        String seg = segments(out).get(displayId);
        if (seg == null) return -1;
        for (String line : seg.split("\n")) {
            int v = pickStackAny(line);
            if (v >= 0) return v;
        }
        return -1;
    }

    /**
     * 目标屏上「锚点所在栈」的 id —— 复刻 CarWithX 的 awk
     * （`/^Stack id=/ {s=$0}  /PipAnchorActivity/ {print s; exit}`）。
     *
     * 这是路①「锚点 + move-task」的落点：move-task 的目的参数要的是 **root task id**，
     * 而 am stack list 只能按"某个包的任务"去找 —— 用自家空 Activity 当锚，
     * 就能稳定解析出目标屏上的那个 root task id（绕开 setLaunchDisplayId 的权限限制）。
     *
     * anchorClass 传锚点 Activity 的类全名（出现在该栈的 topActivity/baseActivity 行里即命中）。
     * 找不到返回 -1（锚点可能已自己退掉，调用方用 {@link #firstStackOnDisplay} 兜底）。
     */
    public static int anchorStackOnDisplay(String out, int displayId, String anchorClass) {
        String seg = segments(out).get(displayId);
        if (seg == null || anchorClass == null || anchorClass.isEmpty()) return -1;
        int last = -1;
        for (String line : seg.split("\n")) {
            int v = pickStackAny(line);
            if (v >= 0) last = v;
            if (line.indexOf(anchorClass) >= 0) return last;
        }
        return -1;
    }

    /**
     * 任意屏上包含目标包的任务；**优先主屏（display 0）**。
     * 批次 AA 新增：搬运的源任务可能在任何屏上（多数在主屏），
     * 逐个屏去问太啰嗦，这里一次给答案。
     */
    public static TaskRef findTaskAnywhere(String out, String pkg) {
        TaskRef onMain = findTaskOnDisplay(out, pkg, 0);
        if (onMain != null) return onMain;
        for (Integer d : segments(out).keySet()) {
            if (d == null || d == 0) continue;
            TaskRef t = findTaskOnDisplay(out, pkg, d);
            if (t != null) return t;
        }
        return null;
    }

    /** 目标包出现在哪些 display 上（搬运前判"别的虚拟屏是不是也有同名实例"用）。 */
    public static List<Integer> displaysWithPkg(String out, String pkg) {
        List<Integer> hits = new ArrayList<>();
        for (Map.Entry<Integer, String> e : segments(out).entrySet()) {
            if (e.getKey() == null) continue;
            if (segHasPkg(e.getValue(), pkg)) hits.add(e.getKey());
        }
        return hits;
    }

    /* ---------------- 内部小工具 ---------------- */

    private static int pickStack(String line, int last) {
        int v = pickInt(line, "stackId=");
        return v >= 0 ? v : last;
    }

    /**
     * 行内取「栈 id」，兼容 am stack list 各代口径：
     *   stackId=7（A10/A11 的 am stack list） / Stack id=7（更早） / RootTask id=7（A12+ 部分 ROM）。
     * 三个都没有返回 -1。
     */
    private static int pickStackAny(String line) {
        int v = pickInt(line, "stackId=");
        if (v >= 0) return v;
        v = pickInt(line, "RootTask id=");
        if (v >= 0) return v;
        return pickInt(line, "Stack id=");
    }

    /** 行内找 key=数字，取第一个；找不到 -1。 */
    private static int pickInt(String line, String key) {
        int p = line.indexOf(key);
        if (p < 0) return -1;
        p += key.length();
        int q = p;
        while (q < line.length() && line.charAt(q) >= '0' && line.charAt(q) <= '9') q++;
        if (q == p) return -1;
        try {
            return Integer.parseInt(line.substring(p, q));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * 段内是否含目标包的任务（行级边界匹配）。
     * 【坑】不能用整段 contains(pkg)：com.foo 会命中 com.foobar 的段，
     * 于是"目标已在虚拟屏"被误判、该搬回的任务不搬（批次 P 修）。
     */
    private static boolean segHasPkg(String seg, String pkg) {
        if (seg == null || pkg == null || pkg.isEmpty()) return false;
        for (String line : seg.split("\n")) {
            if (lineHasPkg(line, pkg)) return true;
        }
        return false;
    }

    /** 行内含包名边界：包名后必须是字符串结尾或非包名字符。 */
    static boolean lineHasPkg(String line, String pkg) {
        if (line == null || pkg == null || pkg.isEmpty()) return false;
        int from = 0;
        while (true) {
            int i = line.indexOf(pkg, from);
            if (i < 0) return false;
            boolean leftOk = i == 0 || !isPkgChar(line.charAt(i - 1));
            int after = i + pkg.length();
            boolean rightOk = after >= line.length() || !isPkgChar(line.charAt(after));
            if (leftOk && rightOk) return true;
            from = i + 1;
        }
    }

    private static boolean isPkgChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9') || c == '_' || c == '.';
    }
}
