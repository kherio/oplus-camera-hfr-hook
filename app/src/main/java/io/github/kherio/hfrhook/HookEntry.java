package io.github.kherio.hfrhook;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * La app de cámara de OnePlus abre la sesión de cámara lenta (modo "constrained high speed", 240 fps)
 * con una superficie YUV (formato 35). AOSP la rechaza en SurfaceUtils:
 *   - checkHighSpeedSurfaceFormat: "Surface format(35) is not for preview or hardware video encoding!"
 *   - checkConstrainedHighSpeedSurfaces (linea 310): "This output surface is neither preview nor hardware video encoding surface"
 * Aqui se anulan ambas comprobaciones, solo dentro del proceso com.oplus.camera.
 */
public class HookEntry implements IXposedHookLoadPackage {
    private static final String TARGET = "com.oplus.camera";
    private static final String TAG = "[HfrHook] ";
    private static final String[] METHODS = {
            "checkHighSpeedSurfaceFormat",
            "checkConstrainedHighSpeedSurfaces"
    };

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
    }
}
