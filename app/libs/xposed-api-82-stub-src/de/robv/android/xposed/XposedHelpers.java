package de.robv.android.xposed;

/**
 * 签名桩：反射 hook 工具。
 * 仅用于编译期签名匹配，运行时由 LSPosed 提供真实实现。
 */
public final class XposedHelpers {

    private XposedHelpers() { }

    public static XC_MethodHook.Unhook findAndHookMethod(Class<?> clazz, String methodName,
            Object... parameterTypesAndCallback) {
        return null;
    }

    public static XC_MethodHook.Unhook findAndHookMethod(String className, ClassLoader classLoader,
            String methodName, Object... parameterTypesAndCallback) {
        return null;
    }

    public static Object getObjectField(Object obj, String fieldName) {
        return null;
    }

    public static Object callMethod(Object obj, String methodName, Object... args) {
        return null;
    }

    public static Object callStaticMethod(Class<?> clazz, String methodName, Object... args) {
        return null;
    }

    public static void setObjectField(Object obj, String fieldName, Object value) { }
}
