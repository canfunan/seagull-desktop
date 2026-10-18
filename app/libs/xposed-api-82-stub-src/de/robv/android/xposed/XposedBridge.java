package de.robv.android.xposed;

/**
 * 签名桩：日志与全局入口。
 */
public final class XposedBridge {

    private XposedBridge() { }

    public static void log(String text) { }

    public static void log(Throwable t) { }

    /**
     * 批次 AG 增补：hook 任意方法（Xposed 原始 API，所有框架版本都有）。
     * C0 总闸要按名字枚举 Instrumentation.execStartActivity 的全部重载逐条挂载，
     * findAndHookMethod（按参数类型精确匹配）盖不住跨 ROM 的签名差异。
     */
    public static XC_MethodHook.Unhook hookMethod(java.lang.reflect.Member hookMethod,
                                                  XC_MethodHook callback) {
        return null;
    }
}
