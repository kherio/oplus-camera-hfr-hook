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
    private static long lastBegin;
    private static long lastP;
    private static final float EIS_MAX_ZOOM = 1.99f;
    // true mientras el modo actual es ultraestabilizador (lo marca f1.p); el pellizco usa b2.k / b2.y como tope
    private static volatile boolean EIS_ACTIVE;
    private static float savedK = -1f;
    private static float savedY = -1f;

    private static float readF(Object o, String name) throws Exception {
        java.lang.reflect.Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return ((Number) f.get(o)).floatValue();
    }

    private static void writeF(Object o, String name, float v) throws Exception {
        java.lang.reflect.Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        if (f.getType() == double.class) {
            f.setDouble(o, v);
        } else {
            f.setFloat(o, v);
        }
    }

    /** Con ultraestabilizador baja el tope del pellizco a EIS_MAX_ZOOM; fuera de ese modo lo devuelve. */
    private static void capPinch(Object b2) {
        try {
            float k = readF(b2, "k");
            float y = readF(b2, "y");
            if (EIS_ACTIVE) {
                if (k > EIS_MAX_ZOOM) {
                    savedK = k;
                    writeF(b2, "k", EIS_MAX_ZOOM);
                }
                if (y > EIS_MAX_ZOOM) {
                    savedY = y;
                    writeF(b2, "y", EIS_MAX_ZOOM);
                }
            } else {
                if (savedK > 0 && k == EIS_MAX_ZOOM) {
                    writeF(b2, "k", savedK);
                }
                if (savedY > 0 && y == EIS_MAX_ZOOM) {
                    writeF(b2, "y", savedY);
                }
                savedK = -1f;
                savedY = -1f;
            }
        } catch (Throwable t) {
            Log.e(LT, "capPinch fallo: " + t);
        }
    }
    private static final ThreadLocal<Boolean> IN_PINCH = new ThreadLocal<Boolean>();

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
                protected void afterHookedMethod(MethodHookParam param) {
                    IN_PINCH.set(Boolean.FALSE);
                }

                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    IN_PINCH.set(Boolean.TRUE);
                    try {
                        capPinch(XposedHelpers.getObjectField(param.thisObject, "c"));
                    } catch (Throwable t) {
                        Log.e(LT, "cap fallo: " + t);
                    }
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

        try {
            Class<?> a = XposedHelpers.findClass("jg.b2$a", lpparam.classLoader);
            int n = XposedBridge.hookAllMethods(a, "a", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    IN_PINCH.set(Boolean.TRUE);
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    IN_PINCH.set(Boolean.FALSE);
                    try {
                        if (param.args == null || param.args.length != 1) {
                            return;
                        }
                        long now = System.currentTimeMillis();
                        if (now - lastBegin < 500) {
                            return;
                        }
                        lastBegin = now;
                        Object b2 = XposedHelpers.getObjectField(param.thisObject, "c");
                        Object c0 = XposedHelpers.getObjectField(b2, "q0");
                        Object cfg = XposedHelpers.getObjectField(b2, "b0");
                        Object view = XposedHelpers.getObjectField(b2, "M");
                        Log.e(LT, "onScaleBegin result=" + param.getResult()
                                + " b2.h=" + safe(b2, "h") + " b2.c0=" + safe(b2, "c0")
                                + " c0.U2=" + safe(c0, "U2") + " c0.C2=" + safe(c0, "C2")
                                + " view.enabled=" + safe(view, "isEnabled")
                                + " cfg.W=" + (cfg == null ? "null" : String.valueOf(XposedHelpers.getBooleanField(cfg, "W"))));
                    } catch (Throwable t) {
                        Log.e(LT, "begin diag fallo: " + t);
                    }
                }
            }).size();
            Log.e(LT, "hook jg.b2$a.a en " + n + " metodo(s)");
        } catch (Throwable t) {
            Log.e(LT, "no engancho jg.b2$a.a: " + t);
        }

        try {
            Class<?> b2 = XposedHelpers.findClass("jg.b2", lpparam.classLoader);
            int n = XposedBridge.hookAllMethods(b2, "h", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.args != null && param.args.length == 0 && Boolean.TRUE.equals(IN_PINCH.get())
                            && Boolean.FALSE.equals(param.getResult())) {
                        param.setResult(Boolean.TRUE);
                    }
                }
            }).size();
            Log.e(LT, "hook jg.b2.h en " + n + " metodo(s) (fuerza true durante el pellizco)");
        } catch (Throwable t) {
            Log.e(LT, "no engancho jg.b2.h: " + t);
        }

        try {
            Class<?> f1 = XposedHelpers.findClass("v6.f1", lpparam.classLoader);
            int n = XposedBridge.hookAllMethods(f1, "p", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (param.args == null || param.args.length != 1 || param.args[0] == null) {
                            return;
                        }
                        Object r = param.getResult();
                        if (!(r instanceof Float)) {
                            return;
                        }
                        float max = (Float) r;
                        boolean eis = XposedHelpers.getBooleanField(param.args[0], "Y");
                        EIS_ACTIVE = eis;
                        long now = System.currentTimeMillis();
                        if (now - lastP > 1000) {
                            lastP = now;
                            Log.e(LT, "f1.p max=" + max + " Y(eis)=" + eis);
                        }
                        if (eis && max < EIS_MAX_ZOOM) {
                            param.setResult(Float.valueOf(EIS_MAX_ZOOM));
                        }
                    } catch (Throwable t) {
                        Log.e(LT, "f1.p diag fallo: " + t);
                    }
                }
            }).size();
            Log.e(LT, "hook v6.f1.p en " + n + " metodo(s) (zoom maximo con ultraestabilizador = " + EIS_MAX_ZOOM + ")");
        } catch (Throwable t) {
            Log.e(LT, "no engancho v6.f1.p: " + t);
        }

        // v0.10: el macro automatico se queda sin guardar la foto (la rafaga RAW de la
        // ultra gran angular no llega nunca a APS). La app lee estas banderas con
        // CameraConfig.<metodo>(String clave[, boolean]) y asume "true" si faltan, asi que
        // las forzamos a false.
        try {
            Class<?> cc = XposedHelpers.findClass("com.oplus.camera.configure.CameraConfig", lpparam.classLoader);
            java.util.HashSet<String> names = new java.util.HashSet<String>();
            for (java.lang.reflect.Method m : cc.getDeclaredMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (m.getReturnType() == boolean.class && p.length >= 1 && p[0] == String.class
                        && java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
                    names.add(m.getName());
                }
            }
            XC_MethodHook macroOff = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (param.args == null || param.args.length < 1) {
                            return;
                        }
                        Object k = param.args[0];
                        if (k instanceof String && ((String) k).startsWith("com.oplus.feature.macro.mode.support")) {
                            param.setResult(Boolean.FALSE);
                        }
                    } catch (Throwable t) {
                        Log.e(LT, "macro off fallo: " + t);
                    }
                }
            };
            int n = 0;
            for (String name : names) {
                n += XposedBridge.hookAllMethods(cc, name, macroOff).size();
            }
            Log.e(LT, "hook CameraConfig (macro off) en " + n + " metodo(s)");
        } catch (Throwable t) {
            Log.e(LT, "no engancho CameraConfig: " + t);
        }
    }
}
