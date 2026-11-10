package io.github.kherio.hfrhook;

import android.util.Log;
import android.view.MotionEvent;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * Hooks para la camara de OnePlus (com.oplus.camera 16.0.5.1301) portada a AOSP:
 *  1) Camara lenta: anula checkHighSpeedSurfaceFormat y checkConstrainedHighSpeedSurfaces (SurfaceUtils).
 *  2) Diagnostico del pellizco de zoom con el ultraestabilizador (nombres ofuscados de esa version):
 *     ig.c0.n2(MotionEvent) y jg.b2$a.c(jg.j). Escribe en logcat con la etiqueta HfrHook.
 */
public class HookEntry implements IXposedHookLoadPackage {
    private static final String TARGET = "com.oplus.camera";
    private static final String TAG = "[HfrHook] ";
    private static final String LT = "HfrHook";
    private static final String[] METHODS = {
            "checkHighSpeedSurfaceFormat",
            "checkConstrainedHighSpeedSurfaces"
    };
    private static long lastN2;
    private static long lastScale;

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) {
        if (!TARGET.equals(lpparam.packageName)) {
            return;
        }
        try {
            Class<?> cls = XposedHelpers.findClass("android.hardware.camera2.utils.SurfaceUtils", lpparam.classLoader);
            for (String m : METHODS) {
                int n = XposedBridge.hookAllMethods(cls, m, XC_MethodReplacement.DO_NOTHING).size();
                XposedBridge.log(TAG + m + " anulado en " + n + " metodo(s)");
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + "fallo al enganchar: " + t);
        }
        hookPinch(lpparam);
    }

    private static String safe(Object o, String method) {
        try {
            return String.valueOf(XposedHelpers.callMethod(o, method));
        } catch (Throwable t) {
            return "ERR(" + t.getClass().getSimpleName() + ")";
        }
    }

    private static String field(Object o, String name) {
        try {
            return String.valueOf(XposedHelpers.getObjectField(o, name));
        } catch (Throwable t) {
            return "ERR";
        }
    }

    private void hookPinch(LoadPackageParam lpparam) {
        try {
            Class<?> c0 = XposedHelpers.findClass("ig.c0", lpparam.classLoader);
            int n = XposedBridge.hookAllMethods(c0, "n2", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        MotionEvent ev = (MotionEvent) param.args[0];
                        long now = System.currentTimeMillis();
                        if (ev.getPointerCount() < 2 || now - lastN2 < 400) {
                            return;
                        }
                        lastN2 = now;
                        Object b2 = XposedHelpers.getObjectField(param.thisObject, "O");
                        Object det = b2 == null ? null : XposedHelpers.getObjectField(b2, "Y");
                        String c9 = "n/a";
                        if (b2 != null) {
                            Object ui = XposedHelpers.getObjectField(b2, "o0");
                            Object h9 = XposedHelpers.callMethod(ui, "h9");
                            c9 = safe(h9, "c9");
                        }
                        Log.e(LT, "n2 ptr=" + ev.getPointerCount() + " act=" + ev.getActionMasked()
                                + " b2=" + (b2 != null) + " detector=" + (det != null) + " h9.c9=" + c9
                                + " A2=" + safe(param.thisObject, "A2")
                                + (b2 != null ? " b2.c0=" + safe(b2, "c0") + " b2.K=" + field(b2, "K")
                                + " min(j)=" + field(b2, "j") + " max(k)=" + field(b2, "k")
                                + " E=" + field(b2, "E") : ""));
                    } catch (Throwable t) {
                        Log.e(LT, "n2 diag fallo: " + t);
                    }
                }
            }).size();
            Log.e(LT, "hook ig.c0.n2 en " + n + " metodo(s)");
        } catch (Throwable t) {
            Log.e(LT, "no engancho ig.c0.n2: " + t);
        }
        try {
            Class<?> a = XposedHelpers.findClass("jg.b2$a", lpparam.classLoader);
            int n = XposedBridge.hookAllMethods(a, "c", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        long now = System.currentTimeMillis();
                        if (now - lastScale < 300) {
                            return;
                        }
                        lastScale = now;
                        Object b2 = XposedHelpers.getObjectField(param.thisObject, "c");
                        Object ui = XposedHelpers.getObjectField(b2, "o0");
                        Log.e(LT, "scale c(): K=" + field(b2, "K") + " ui.I=" + safe(ui, "I")
                                + " a=" + field(param.thisObject, "a") + " b=" + field(param.thisObject, "b")
                                + " min(j)=" + field(b2, "j") + " max(k)=" + field(b2, "k")
                                + " x=" + field(b2, "x") + " y=" + field(b2, "y"));
                    } catch (Throwable t) {
                        Log.e(LT, "scale diag fallo: " + t);
                    }
                }
            }).size();
            Log.e(LT, "hook jg.b2$a.c en " + n + " metodo(s)");
        } catch (Throwable t) {
            Log.e(LT, "no engancho jg.b2$a.c: " + t);
        }
    }
}
