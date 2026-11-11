package de.robv.android.xposed;
import java.util.Set;
public final class XposedBridge {
    public static void log(String text) { }
    public static Object hookMethod(java.lang.reflect.Member m, XC_MethodHook cb) { return null; }
    public static Set<Object> hookAllMethods(Class<?> hookClass, String methodName, XC_MethodHook callback) { return null; }
}
