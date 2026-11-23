package io.github.kherio.hfrhook;

import android.util.Log;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * Hooks for the stock OnePlus camera (com.oplus.camera 16.0.5.1301) ported to AOSP:
 *  1) Slow motion: skips checkHighSpeedSurfaceFormat / checkConstrainedHighSpeedSurfaces (SurfaceUtils).
 *  2) Super-steady video zoom up to 5x (obfuscated names of that exact version): lifts the pinch cap
 *     and the zoom mapping (hg.g.f) that fell back to 1.0 above 2x.
 */
public class HookEntry implements IXposedHookLoadPackage {
    private static final String TARGET = "com.oplus.camera";
    private static final String TAG = "[HfrHook] ";
    private static final String LT = "HfrHook";
    private static final String[] METHODS = {
            "checkHighSpeedSurfaceFormat",
            "checkConstrainedHighSpeedSurfaces"
    };
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
                XposedBridge.log(TAG + m + " skipped in " + n + " method(s)");
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + "hook failed: " + t);
        }
        hookPinch(lpparam);
        hookZoomMap(lpparam);
    }

    private void hookPinch(LoadPackageParam lpparam) {
        try {
            Class<?> a = XposedHelpers.findClass("jg.b2$a", lpparam.classLoader);
            XposedBridge.hookAllMethods(a, "c", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    IN_PINCH.set(Boolean.TRUE);
                    try {
                        capPinch(XposedHelpers.getObjectField(param.thisObject, "c"));
                    } catch (Throwable t) {
                        Log.e(LT, "cap failed: " + t);
                    }
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    IN_PINCH.set(Boolean.FALSE);
                }
            });
            XposedBridge.hookAllMethods(a, "a", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    IN_PINCH.set(Boolean.TRUE);
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    IN_PINCH.set(Boolean.FALSE);
                }
            });
        } catch (Throwable t) {
            Log.e(LT, "jg.b2$a hook failed: " + t);
        }
        try {
            Class<?> b2 = XposedHelpers.findClass("jg.b2", lpparam.classLoader);
            XposedBridge.hookAllMethods(b2, "h", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.args != null && param.args.length == 0 && Boolean.TRUE.equals(IN_PINCH.get())
                            && Boolean.FALSE.equals(param.getResult())) {
                        param.setResult(Boolean.TRUE);
                    }
                }
            });
        } catch (Throwable t) {
            Log.e(LT, "jg.b2.h hook failed: " + t);
        }
        try {
            Class<?> f1 = XposedHelpers.findClass("v6.f1", lpparam.classLoader);
            XposedBridge.hookAllMethods(f1, "p", new XC_MethodHook() {
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
                        boolean eis = XposedHelpers.getBooleanField(param.args[0], "Y");
                        EIS_ACTIVE = eis;
                        if (eis && ((Float) r) < EIS_MAX_ZOOM) {
                            param.setResult(Float.valueOf(EIS_MAX_ZOOM));
                        }
                    } catch (Throwable t) {
                        Log.e(LT, "f1.p hook failed: " + t);
                    }
                }
            });
        } catch (Throwable t) {
            Log.e(LT, "v6.f1.p hook failed: " + t);
        }
    }

    /**
     * hg.g.f(float) maps the displayed zoom to the real zoom. With super-steady video it returned 1.0 from 2x up,
     * which made the app restart the preview with zoomRatio 1.0. Return the requested zoom instead.
     */
    private void hookZoomMap(LoadPackageParam lpparam) {
        try {
            Class<?> g = XposedHelpers.findClass("hg.g", lpparam.classLoader);
            XposedBridge.hookAllMethods(g, "f", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (!EIS_ACTIVE || param.args == null || param.args.length != 1
                                || !(param.args[0] instanceof Float) || !(param.getResult() instanceof Float)) {
                            return;
                        }
                        float in = (Float) param.args[0];
                        if (in >= 2.0f && ((Float) param.getResult()) <= 1.0f) {
                            param.setResult(Float.valueOf(in));
                        }
                    } catch (Throwable t) {
                        Log.e(LT, "hg.g.f hook failed: " + t);
                    }
                }
            });
        } catch (Throwable t) {
            Log.e(LT, "hg.g.f hook failed: " + t);
        }
    }
}
