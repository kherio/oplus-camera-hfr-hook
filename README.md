# oplus-camera-hfr-hook

Módulo LSPosed (Xposed) mínimo para la cámara de OnePlus (`com.oplus.camera`) portada a una ROM AOSP
(crDroid / AlphaDroid, OnePlus 11).

## Problema
La cámara lenta abre una sesión *constrained high speed* (240 fps) con una superficie YUV de formato 35.
AOSP la rechaza antes de llegar al HAL:

```
IllegalArgumentException: Surface format(35) is not for preview or hardware video encoding!
  at android.hardware.camera2.utils.SurfaceUtils.checkHighSpeedSurfaceFormat(SurfaceUtils.java:258)
```

El framework de OxygenOS está modificado para permitirlo; el de AOSP no.

## Qué hace
Anula `SurfaceUtils.checkHighSpeedSurfaceFormat` solo dentro del proceso `com.oplus.camera`.
No toca la ROM ni ninguna otra app.

## Estado
Funciona en un OnePlus 11 (crDroid/AlphaDroid, Android 17) con la cámara portada: la cámara lenta a 240 fps
muestra la vista previa y graba. Hay que anular dos comprobaciones de AOSP, `checkHighSpeedSurfaceFormat` y
`checkConstrainedHighSpeedSurfaces`. El log del módulo aparece con la etiqueta `[HfrHook]`.

## Compilar
GitHub Actions (`.github/workflows/build.yml`) genera el APK como artefacto. En local: `gradle :app:assembleRelease`.

## Uso
1. Instalar el APK.
2. En LSPosed activar el módulo con ámbito `com.oplus.camera`.
3. Forzar el cierre de la cámara y abrir el modo Cámara lenta.
