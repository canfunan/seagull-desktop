package com.seagull.carlauncher;

/**
 * 跨文件共享的进程级常量（审计 L15）。
 *
 * 此前 HOST_PACKAGE 以字符串字面量散在 XposedEntry/RootMain/PrivClient 三处、
 * LAUNCH_FLAGS 数值散在 RootOps/SelfTestMirror 两处 —— 改包名或改 flags 口径
 * 时漏一处就是静默错位。收编到这里，单一事实来源。
 */
public final class Constants {

    /** 本应用包名（Xposed 过滤自伤 / 守护进程 selfUid 反射与 dumpsys 都用它）。 */
    public static final String SELF_PACKAGE = "com.seagull.carlauncher";
    /** 批次 NB：vivo 三地图归一化导航信息广播（XposedEntry hookVivoNavInfo 发）。 */
    public static final String ACTION_NAV_INFO = "com.seagull.carlauncher.NAV_INFO";
    /** 批次 NB：导航结束清除（UCarMapService.removeNavInfo 时发）。 */
    public static final String ACTION_NAV_CLEAR = "com.seagull.carlauncher.NAV_CLEAR";

    /**
     * 虚拟屏任务的启动 flags（批次 K 口径）：
     *   NEW_TASK(0x10000000) | MULTIPLE_TASK(0x08000000)
     *   | EXCLUDE_FROM_RECENTS(0x00800000) —— 镜像实例不进最近任务
     * 组合 = 0x18800000。字符串给 am 命令行，int 给守护进程 launchOptions。
     */
    public static final String LAUNCH_FLAGS = "0x18800000";
    public static final int LAUNCH_FLAGS_INT = 0x18800000;

    private Constants() {}
}
