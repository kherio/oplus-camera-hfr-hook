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

        // v0.11: diagnostico de Noche (8 RAW que nunca llegan a APS). Registra por que
        // CaptureImageCategory.isValid() devuelve false. Clases del SDK sin ofuscar.
        try {
            final String AD = "com.oplus.ocs.camera.consumer.apsAdapter.adapter.";
            final Class<?> cat = XposedHelpers.findClass(AD + "CaptureImageCategory", lpparam.classLoader);
            final Class<?> par = XposedHelpers.findClass(AD + "ApsParameters", lpparam.classLoader);
            int n = XposedBridge.hookAllMethods(cat, "isValid", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object meta = XposedHelpers.getObjectField(param.thisObject, "mMetaItem");
                        java.util.List<?> imgs = (java.util.List<?>) XposedHelpers.getObjectField(param.thisObject, "mImageItemList");
                        StringBuilder sb = new StringBuilder("isValid=" + param.getResult());
                        sb.append(" images=").append(imgs == null ? -1 : imgs.size());
                        sb.append(" meta=").append(meta != null);
                        if (imgs != null && imgs.size() > 0) {
                            sb.append(" imgTs=").append(kv(imgs.get(0), par, "KEY_TIME_STAMP"));
                            sb.append(" needMeta=").append(kv(imgs.get(0), par, "KEY_NEED_META_DATA"));
                        }
                        if (meta != null) {
                            sb.append(" metaTs=").append(kv(meta, par, "KEY_TIME_STAMP"));
                            sb.append(" merge=").append(kv(meta, par, "KEY_MERGE_NUMBER"));
                            sb.append(" capStreams=").append(kv(meta, par, "KEY_CAPTURE_STREAM_NUMBER"));
                            sb.append(" useTuning=").append(kv(meta, par, "KEY_USE_TUNING_DATA"));
                        }
                        Log.e(LT, "NIGHTDIAG " + sb);
                    } catch (Throwable t) {
                        Log.e(LT, "NIGHTDIAG fallo: " + t);
                    }
                }
            }).size();
            Log.e(LT, "hook CaptureImageCategory.isValid en " + n + " metodo(s)");
            for (String mn : new String[] {"addMetadata", "addImage"}) {
                final String name = mn;
                XposedBridge.hookAllMethods(XposedHelpers.findClass(AD + "ApsCaptureAdapterImpl", lpparam.classLoader), mn,
                        new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            Log.e(LT, "NIGHTDIAG " + name + " ts=" + kv(param.args[0], par, "KEY_TIME_STAMP"));
                        } catch (Throwable t) {
                            Log.e(LT, "NIGHTDIAG " + name + " fallo: " + t);
                        }
                    }
                });
            }
        } catch (Throwable t) {
            Log.e(LT, "no engancho el SDK de captura: " + t);
        }

        // v0.12: de donde salen (o no) los metadatos de cada frame. Registra los callbacks de
        // captura del consumidor y onPictureMetaArrived.
        try {
            Class<?> ci = XposedHelpers.findClass("com.oplus.ocs.camera.consumer.ConsumerImpl", lpparam.classLoader);
            Class<?> cb = XposedHelpers.findClass("com.oplus.ocs.camera.consumer.ConsumerImpl$InnerCameraPictureCallbackAdapter", lpparam.classLoader);
            XC_MethodHook logCb = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        StringBuilder sb = new StringBuilder("NIGHTDIAG2 ").append(param.method.getName());
                        if (param.args != null) {
                            for (Object a : param.args) {
                                if (a instanceof android.hardware.camera2.CaptureRequest) {
                                    Object tag = ((android.hardware.camera2.CaptureRequest) a).getTag();
                                    if (tag != null) {
                                        sb.append(" reqNum=").append(XposedHelpers.getObjectField(tag, "mRequestNum"));
                                        sb.append(" useCapMeta=").append(XposedHelpers.getObjectField(tag, "mbUseCaptureMeta"));
                                        sb.append(" raw=").append(XposedHelpers.getObjectField(tag, "mRawValue"));
                                    } else {
                                        sb.append(" tag=null");
                                    }
                                } else if (a instanceof Boolean || a instanceof Long || a instanceof Integer) {
                                    sb.append(" arg=").append(a);
                                }
                            }
                        }
                        Log.e(LT, sb.toString());
                    } catch (Throwable t) {
                        Log.e(LT, "NIGHTDIAG2 fallo: " + t);
                    }
                }
            };
            int n = XposedBridge.hookAllMethods(ci, "onPictureMetaArrived", logCb).size();
            for (String mn : new String[] {"onCaptureCompleted", "onPictureCaptureProgressed", "onCaptureFailed", "onCaptureStarted"}) {
                n += XposedBridge.hookAllMethods(cb, mn, logCb).size();
            }
            Log.e(LT, "hook callbacks de captura en " + n + " metodo(s)");
        } catch (Throwable t) {
            Log.e(LT, "no engancho callbacks de captura: " + t);
        }

        // v0.14 (experimento): el resultado parcial del HAL no trae SENSOR_TIMESTAMP. Guardamos
        // frame->timestamp desde onCaptureStarted, y al llegar el resultado parcial de ese frame
        // lo reenviamos a ConsumerImpl.onPictureMetaArrived(..., false) inyectando el timestamp
        // (CaptureResult.get(SENSOR_TIMESTAMP) devuelve el guardado mientras dura la llamada).
        // Solo para peticiones multiframe (mRequestNum > 1).
        try {
            Class<?> cb2 = XposedHelpers.findClass("com.oplus.ocs.camera.consumer.ConsumerImpl$InnerCameraPictureCallbackAdapter", lpparam.classLoader);
            final Class<?> crCls = Class.forName("android.hardware.camera2.CaptureResult");
            final Object tsKey = crCls.getField("SENSOR_TIMESTAMP").get(null);
            final java.util.concurrent.ConcurrentHashMap<Long, Long> frameTs = new java.util.concurrent.ConcurrentHashMap<Long, Long>();
            final java.util.concurrent.ConcurrentHashMap<Long, Object[]> pending = new java.util.concurrent.ConcurrentHashMap<Long, Object[]>();
            final ThreadLocal<Long> forceTs = new ThreadLocal<Long>();
            final boolean[] keysLogged = new boolean[1];
            final java.util.Set<String> reqLogged = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<String, Boolean>());
            XposedBridge.hookAllMethods(crCls, "get", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Long f = forceTs.get();
                    if (f == null || param.args == null || param.args.length != 1 || param.getResult() != null) return;
                    if (param.args[0] == tsKey) {
                        param.setResult(f);
                        return;
                    }
                    // v0.18/v0.19: clave ausente en el resultado parcial -> valor del CaptureRequest;
                    // si tampoco esta, un valor por defecto para las claves que el APS necesita.
                    try {
                        String name = String.valueOf(param.args[0].getClass().getMethod("getName").invoke(param.args[0]));
                        Object req = param.thisObject.getClass().getMethod("getRequest").invoke(param.thisObject);
                        Object v = null;
                        if (req != null) {
                            java.util.Set<?> ks = (java.util.Set<?>) req.getClass().getMethod("getKeys").invoke(req);
                            if (reqLogged.add("#keys")) {
                                StringBuilder sb = new StringBuilder();
                                for (Object k : ks) sb.append(k.getClass().getMethod("getName").invoke(k)).append(' ');
                                Log.e(LT, "NIGHTDIAG5 claves del request: " + sb);
                            }
                            for (Object k : ks) {
                                if (name.equals(String.valueOf(k.getClass().getMethod("getName").invoke(k)))) {
                                    v = req.getClass().getMethod("get", k.getClass()).invoke(req, k);
                                    break;
                                }
                            }
                        }
                        String src = "request";
                        if (v == null) {
                            src = "defecto";
                            if (name.equals("android.jpeg.orientation")) v = Integer.valueOf(0);
                            else if (name.equals("android.flash.mode")) v = Integer.valueOf(0);
                            else if (name.equals("android.sensor.sensitivity")) v = Integer.valueOf(100);
                            else if (name.equals("android.lens.aperture")) v = Float.valueOf(1.8f);
                            else if (name.equals("android.scaler.cropRegion"))
                                v = Class.forName("android.graphics.Rect").getConstructor(int.class, int.class, int.class, int.class)
                                        .newInstance(0, 0, 4096, 3072);
                        }
                        if (v != null) {
                            param.setResult(v);
                            if (reqLogged.add(name)) Log.e(LT, "NIGHTDIAG5 clave " + name + "=" + v + " (" + src + ")");
                        }
                    } catch (Throwable t) {
                        if (reqLogged.add("#err")) Log.e(LT, "NIGHTDIAG5 fallo: " + t);
                    }
                }
            });
            XposedBridge.hookAllMethods(cb2, "onCaptureStarted", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (param.args != null && param.args.length >= 3
                                && param.args[1] instanceof Number && param.args[2] instanceof Number) {
                            frameTs.put(((Number) param.args[2]).longValue(), ((Number) param.args[1]).longValue());
                        }
                    } catch (Throwable t) {
                        Log.e(LT, "NIGHTDIAG3 started fallo: " + t);
                    }
                }

                // v0.17: el reenvio va DESPUES del onCaptureStarted original, porque ahi
                // ApsProcessor.onPictureMetaStart fija mCaptureMergeIdentity con el timestamp del
                // primer frame; si reenviamos antes, el frame 1 se cuenta bajo identidad 0.
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (param.args != null && param.args.length >= 3
                                && param.args[1] instanceof Number && param.args[2] instanceof Number) {
                            long ts = ((Number) param.args[1]).longValue();
                            long frame = ((Number) param.args[2]).longValue();
                            Object[] p = pending.remove(frame);
                            if (p != null) {
                                forward(p[0], p[1], p[2], ts, frame, forceTs);
                            }
                        }
                    } catch (Throwable t) {
                        Log.e(LT, "NIGHTDIAG3 started2 fallo: " + t);
                    }
                }
            });
            int n2 = XposedBridge.hookAllMethods(cb2, "onPictureCaptureProgressed", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (param.args == null || param.args.length < 2) {
                            return;
                        }
                        Object req = param.args[0];
                        Object tag = ((android.hardware.camera2.CaptureRequest) req).getTag();
                        if (tag == null) {
                            return;
                        }
                        int num = ((Integer) XposedHelpers.getObjectField(tag, "mRequestNum")).intValue();
                        if (num <= 1) {
                            return;
                        }
                        Object cr = XposedHelpers.callMethod(param.args[1], "getCaptureResult");
                        if (cr == null) {
                            Log.e(LT, "NIGHTDIAG3 cr=null");
                            return;
                        }
                        long frame = ((Number) XposedHelpers.callMethod(cr, "getFrameNumber")).longValue();
                        if (!keysLogged[0]) {
                            keysLogged[0] = true;
                            StringBuilder sb = new StringBuilder("NIGHTDIAG3 claves:");
                            for (Object k : (java.util.List<?>) XposedHelpers.callMethod(cr, "getKeys")) {
                                sb.append(' ').append(XposedHelpers.callMethod(k, "getName"));
                            }
                            Log.e(LT, sb.toString());
                        }
                        Object outer = XposedHelpers.getObjectField(param.thisObject, "this$0");
                        Long mapTs = frameTs.get(frame);
                        if (mapTs != null) {
                            forward(outer, req, cr, mapTs, frame, forceTs);
                        } else {
                            pending.put(frame, new Object[] {outer, req, cr});
                            Log.e(LT, "NIGHTDIAG3 parcial en espera frame=" + frame);
                        }
                    } catch (Throwable t) {
                        Log.e(LT, "NIGHTDIAG3 fallo: " + t);
                    }
                }
            }).size();
            Log.e(LT, "hook reenvio meta parcial (v0.19) en " + n2 + " metodo(s)");
        } catch (Throwable t) {
            Log.e(LT, "no engancho reenvio meta parcial: " + t);
        }
        hookMergeDiag(lpparam.classLoader);
    }

    // v0.16: diagnostico del recuento de frames (merge identity) en APS.
    private static void hookMergeDiag(ClassLoader cl) {
        try {
            final String AD = "com.oplus.ocs.camera.consumer.apsAdapter.adapter.ApsCaptureAdapterImpl";
            Class<?> cap = XposedHelpers.findClass(AD, cl);
            XposedBridge.hookAllMethods(cap, "countAddFrame", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Log.e(LT, "NIGHTDIAG4 countAddFrame id=" + param.args[0] + " size=" + param.args[1]
                                + " merge=" + param.args[2] + " fail=" + param.args[3]
                                + " mapAntes=" + XposedHelpers.getObjectField(param.thisObject, "mAddFrameFinishMap"));
                    } catch (Throwable t) {
                        Log.e(LT, "NIGHTDIAG4 count fallo: " + t);
                    }
                }
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Log.e(LT, "NIGHTDIAG4 countAddFrame -> " + param.getResult()
                                + " mapDespues=" + XposedHelpers.getObjectField(param.thisObject, "mAddFrameFinishMap")
                                + " captureFail=" + XposedHelpers.getObjectField(param.thisObject, "mbCaptureFail"));
                    } catch (Throwable t) {
                        Log.e(LT, "NIGHTDIAG4 count2 fallo: " + t);
                    }
                }
            });
            Class<?> ap = XposedHelpers.findClass("com.oplus.ocs.camera.consumer.ApsProcessor", cl);
            XC_MethodHook idLog = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        String st = Log.getStackTraceString(new Throwable());
                        String[] ls = st.split("\n");
                        StringBuilder sb = new StringBuilder();
                        for (int i = 1; i < ls.length && i < 5; i++) {
                            sb.append(ls[i].trim()).append(" | ");
                        }
                        StringBuilder a = new StringBuilder();
                        for (Object o : param.args) {
                            if (o instanceof Number) {
                                a.append(o).append(' ');
                            }
                        }
                        Log.e(LT, "NIGHTDIAG4 " + param.method.getName() + " args=" + a
                                + " identidadAntes=" + XposedHelpers.getObjectField(param.thisObject, "mCaptureMergeIdentity")
                                + " cbNum=" + XposedHelpers.getObjectField(param.thisObject, "mCaptureStartedCallbackNum")
                                + " via " + sb);
                    } catch (Throwable t) {
                        Log.e(LT, "NIGHTDIAG4 id fallo: " + t);
                    }
                }
            };
            int n = XposedBridge.hookAllMethods(ap, "onApsCaptureStarted", idLog).size();
            n += XposedBridge.hookAllMethods(ap, "onPictureMetaStart", idLog).size();
            Log.e(LT, "hook diagnostico merge identity en " + n + " metodo(s)");
        } catch (Throwable t) {
            Log.e(LT, "no engancho diagnostico merge: " + t);
        }
    }

    private static void forward(Object outer, Object req, Object cr, long ts, long frame, ThreadLocal<Long> forceTs) {
        forceTs.set(Long.valueOf(ts));
        try {
            XposedHelpers.callMethod(outer, "onPictureMetaArrived", req, cr, Boolean.FALSE);
            Log.e(LT, "NIGHTDIAG3 reenviado frame=" + frame + " ts=" + ts);
        } catch (Throwable t) {
            Log.e(LT, "NIGHTDIAG3 reenvio fallo frame=" + frame + ": " + t);
        } finally {
            forceTs.remove();
        }
    }

    private static Object kv(Object item, Class<?> par, String keyName) throws Throwable {
        java.lang.reflect.Field f = par.getField(keyName);
        Object key = f.get(null);
        for (java.lang.reflect.Method m : item.getClass().getMethods()) {
            if (m.getName().equals("get") && m.getParameterTypes().length == 1) {
                return m.invoke(item, key);
            }
        }
        return "?";
    }
}
