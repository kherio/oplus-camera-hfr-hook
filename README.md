# oplus-camera-hfr-hook

Minimal LSPosed (Xposed) module for the OnePlus stock camera (`com.oplus.camera`) ported to an AOSP ROM
(crDroid / AlphaDroid, OnePlus 11). Companion of [oplus-camera-port](https://github.com/kherio/oplus-camera-port).

## What it fixes
### 1. Slow motion (240 fps)
Slow motion opens a *constrained high speed* session with a YUV surface of format 35. AOSP rejects it before it
reaches the HAL:

```
IllegalArgumentException: Surface format(35) is not for preview or hardware video encoding!
  at android.hardware.camera2.utils.SurfaceUtils.checkHighSpeedSurfaceFormat(SurfaceUtils.java:258)
```
OxygenOS' framework is modified to allow it; AOSP's is not. The module turns
`SurfaceUtils.checkHighSpeedSurfaceFormat` and `checkConstrainedHighSpeedSurfaces` into no-ops, only inside the
`com.oplus.camera` process.

### 2. Pinch-to-zoom with super-steady video (up to 2x)
With super-steady video the app refuses to start the pinch gesture. The module:
- makes `jg.b2.h()` return true while a pinch is being handled (`jg.b2$a.a` / `jg.b2$a.c`), and
- makes `v6.f1.p(w6.c)` report a maximum zoom of 2.0 when the super-EIS flag is set (zoom bar limit).

It needs the patched APK from oplus-camera-port (`force_true.py ... 'Lm5/e;->c()Z'`). Above 2x the camera
pipeline does not zoom in this mode.

Class names are obfuscated and only valid for the camera app of OxygenOS 16.0.5.1301 (CPH2449).
It does not touch the ROM or any other app.

## Logs
Tag `HfrHook` (shown in LSPosed and logcat). The zoom hooks are throttled, but they log at error level.
```
adb logcat -d | findstr HfrHook
```

## Build
GitHub Actions (`.github/workflows/build.yml`) builds the APK as an artifact. Locally: `gradle :app:assembleRelease`.

## Usage
1. Install the APK.
2. In LSPosed enable the module with scope `com.oplus.camera`.
3. Force stop the camera and open it again.
