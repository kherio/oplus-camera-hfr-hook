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
    private static final float EIS_MAX_ZOOM = 5.0f;
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
        hookVideoOpMode(lpparam);
        hookOpModeSink(lpparam);
        hookZoomRequest(lpparam);
        hookStartPreview(lpparam);
        hookTeleThreshold(lpparam);
        hookZoomPresenter(lpparam);
    }

    private static final int OPMODE_VIDEO_NO_EIS = 33547; // 0x830B: sin entrada en EISTypeMatching
    private static final int OPMODE_VIDEO_EIS = 32779;    // 0x800B: 720P/1080P/4K 30fps EIS3
    private static long lastOp;

    /** Video normal: pide el modo de operacion 0x800B (con EIS3 en el HAL) en lugar de 0x830B. */
    private void hookVideoOpMode(LoadPackageParam lpparam) {
        String[] classes = {"com.oplus.camera.module.a", "mh.c"};
        for (String cn : classes) {
            try {
                Class<?> c = XposedHelpers.findClass(cn, lpparam.classLoader);
                int n = XposedBridge.hookAllMethods(c, "k8", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Object r = param.getResult();
                        if (r instanceof Integer && ((Integer) r) == OPMODE_VIDEO_NO_EIS) {
                            param.setResult(Integer.valueOf(OPMODE_VIDEO_EIS));
                            long now = System.currentTimeMillis();
                            if (now - lastOp > 1000) {
                                lastOp = now;
                                Log.e(LT, "k8: operation mode 0x830B -> 0x800B (EIS)");
                            }
                        }
                    }
                }).size();
                Log.e(LT, "hook " + cn + ".k8 en " + n + " metodo(s)");
            } catch (Throwable t) {
                Log.e(LT, "no engancho " + cn + ".k8: " + t);
            }
        }
    }

    /** Sumidero: hh.k.K(int) fija el modo de operacion de la sesion; cambia 0x830B por 0x800B. */
    private void hookOpModeSink(LoadPackageParam lpparam) {
        try {
            Class<?> c = XposedHelpers.findClass("hh.k", lpparam.classLoader);
            int n = XposedBridge.hookAllMethods(c, "K", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args != null && param.args.length == 1 && param.args[0] instanceof Integer) {
                        int v = (Integer) param.args[0];
                        Log.e(LT, "hh.k.K(" + v + ")");
                        if (v == OPMODE_VIDEO_NO_EIS) {
                            param.args[0] = Integer.valueOf(OPMODE_VIDEO_EIS);
                            Log.e(LT, "hh.k.K: 0x830B -> 0x800B (EIS)");
                        }
                    }
                }
            }).size();
            Log.e(LT, "hook hh.k.K en " + n + " metodo(s)");
        } catch (Throwable t) {
            Log.e(LT, "no engancho hh.k.K: " + t);
        }
    }

    private static volatile float lastZoomReq = -1f;

    /** Diagnostico: registra los cambios de CONTROL_ZOOM_RATIO y, si baja de >1.9 a 1.0, la pila de llamadas. */
    private void hookZoomRequest(LoadPackageParam lpparam) {
        try {
            Class<?> b = XposedHelpers.findClass("android.hardware.camera2.CaptureRequest$Builder", lpparam.classLoader);
            int n = XposedBridge.hookAllMethods(b, "set", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (param.args == null || param.args.length != 2 || param.args[0] == null) {
                            return;
                        }
                        Object key = param.args[0];
                        if (!"android.control.zoomRatio".equals(String.valueOf(XposedHelpers.callMethod(key, "getName")))) {
                            return;
                        }
                        float v = ((Number) param.args[1]).floatValue();
                        float prev = lastZoomReq;
                        if (Math.abs(v - prev) < 0.05f) {
                            return;
                        }
                        lastZoomReq = v;
                        Log.e(LT, "zoomRatio request " + prev + " -> " + v);
                        if (v <= 1.0f && prev > 1.9f) {
                            StackTraceElement[] st = new Throwable().getStackTrace();
                            StringBuilder sb = new StringBuilder("zoomRatio cae a 1.0 desde:");
                            for (int i = 1; i < st.length && i < 22; i++) {
                                sb.append("\n    ").append(st[i]);
                            }
                            Log.e(LT, sb.toString());
                        }
                    } catch (Throwable t) {
                        Log.e(LT, "zoom diag fallo: " + t);
                    }
                }
            }).size();
            Log.e(LT, "hook CaptureRequest.Builder.set en " + n + " metodo(s)");
        } catch (Throwable t) {
            Log.e(LT, "no engancho Builder.set: " + t);
        }
    }

    private static volatile float lastParamZoom = -1f;

    /** Diagnostico: en Camera2StateMachineImpl.startPreview lee el zoom de Parameter y, si cae a <=1.0 desde >1.9, imprime quien lo pidio. */
    private void hookStartPreview(LoadPackageParam lpparam) {
        try {
            final Class<?> keys = XposedHelpers.findClass("com.oplus.ocs.camera.metadata.UPreviewKeys", lpparam.classLoader);
            java.lang.reflect.Field kf = keys.getDeclaredField("KEY_ZOOM_RATIO");
            kf.setAccessible(true);
            final Object zoomKey = kf.get(null);
            Class<?> sm = XposedHelpers.findClass("com.oplus.ocs.camera.producer.device.Camera2StateMachineImpl", lpparam.classLoader);
            int n = XposedBridge.hookAllMethods(sm, "startPreview", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (param.args == null || param.args.length != 2 || param.args[1] == null) {
                            return;
                        }
                        Object p = param.args[1];
                        boolean has = Boolean.TRUE.equals(XposedHelpers.callMethod(p, "containCustomKey", zoomKey));
                        if (!has) {
                            return;
                        }
                        float v = ((Number) XposedHelpers.callMethod(p, "get", zoomKey)).floatValue();
                        float prev = lastParamZoom;
                        lastParamZoom = v;
                        if (v <= 1.0f && prev > 1.9f) {
                            StackTraceElement[] st = new Throwable().getStackTrace();
                            StringBuilder sb = new StringBuilder("startPreview con zoom " + prev + " -> " + v + " pedido desde:");
                            for (int i = 1; i < st.length && i < 30; i++) {
                                sb.append("\n    ").append(st[i]);
                            }
                            Log.e(LT, sb.toString());
                        }
                    } catch (Throwable t) {
                        Log.e(LT, "startPreview diag fallo: " + t);
                    }
                }
            }).size();
            Log.e(LT, "hook Camera2StateMachineImpl.startPreview en " + n + " metodo(s)");
        } catch (Throwable t) {
            Log.e(LT, "no engancho startPreview: " + t);
        }
    }

    /**
     * ig.c0.L3 cambia a la lente tele (pref_none_sat_ultra_tele_key) cuando el zoom llega a
     * "com.oplus.ultra.tele.zoom.value" (2.0 por defecto), lo que reinicia la vista previa con zoom 1.0.
     * Con el ultraestabilizador activo se sube ese umbral para seguir con el recorte.
     */
    private void hookTeleThreshold(LoadPackageParam lpparam) {
        try {
            Class<?> cc = XposedHelpers.findClass("com.oplus.camera.configure.CameraConfig", lpparam.classLoader);
            int n = XposedBridge.hookAllMethods(cc, "g", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (param.args != null && param.args.length == 2 && param.args[0] instanceof String
                                && ((String) param.args[0]).contains("tele")) {
                            Log.e(LT, "CameraConfig.g(" + param.args[0] + ") = " + param.getResult() + " eisActive=" + EIS_ACTIVE);
                        }
                        if (!EIS_ACTIVE || param.args == null || param.args.length != 2
                                || !"com.oplus.ultra.tele.zoom.value".equals(param.args[0])) {
                            return;
                        }
                        Object r = param.getResult();
                        if (r instanceof Float && ((Float) r) < 100f) {
                            param.setResult(Float.valueOf(100f));
                            Log.e(LT, "CameraConfig.g(ultra.tele.zoom.value): " + r + " -> 100.0");
                        }
                    } catch (Throwable t) {
                        Log.e(LT, "tele threshold fallo: " + t);
                    }
                }
            }).size();
            Log.e(LT, "hook CameraConfig.g en " + n + " metodo(s)");
        } catch (Throwable t) {
            Log.e(LT, "no engancho CameraConfig.g: " + t);
        }
    }

    /** Diagnostico: ig.c0.L3(float,boolean) (ZoomPresenter) con zoom >= 1.9. */
    private void hookZoomPresenter(LoadPackageParam lpparam) {
        try {
            Class<?> c0 = XposedHelpers.findClass("ig.c0", lpparam.classLoader);
            int n = XposedBridge.hookAllMethods(c0, "L3", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (param.args == null || param.args.length != 2 || !(param.args[0] instanceof Float)) {
                            return;
                        }
                        float z = (Float) param.args[0];
                        if (z < 1.9f) {
                            return;
                        }
                        Object cfg = null;
                        Object a = XposedHelpers.getObjectField(param.thisObject, "a");
                        if (a != null) {
                            cfg = XposedHelpers.callMethod(a, "h6");
                        }
                        Log.e(LT, "ig.c0.L3 zoom=" + z + " arg2=" + param.args[1] + " eis=" + EIS_ACTIVE
                                + " h6.o=" + (cfg == null ? "null" : field(cfg, "o"))
                                + " h6.m=" + (cfg == null ? "null" : field(cfg, "m"))
                                + " h6.n=" + (cfg == null ? "null" : field(cfg, "n"))
                                + " h6.F0=" + (cfg == null ? "null" : field(cfg, "F0")));
                    } catch (Throwable t) {
                        Log.e(LT, "L3 diag fallo: " + t);
                    }
                }
            }).size();
            Log.e(LT, "hook ig.c0.L3 en " + n + " metodo(s)");
        } catch (Throwable t) {
            Log.e(LT, "no engancho ig.c0.L3: " + t);
        }
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
    }
}
