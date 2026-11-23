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

### 2. Zoom up to 5x with super-steady video
With super-steady video the stock app refuses the pinch gesture and, even when it is forced, drops the zoom back
to 1x when it reaches 2x. The module:
- makes `jg.b2.h()` return true while a pinch is being handled (`jg.b2$a.a` / `jg.b2$a.c`),
- keeps the pinch limit (`jg.b2` fields `k` and `y`) at 5.0 while the super-EIS flag is set (the flag is read from
  `v6.f1.p(w6.c)`), and puts the original values back when you leave the mode, and
- fixes the zoom mapping `hg.g.f(float)`, which returned 1.0 from 2x up in this mode; that made the app restart the
  preview with `zoomRatio = 1.0` and ignore every later zoom change. With the fix the zoom ratio keeps reaching
  the camera HAL up to 5x, also while recording.

It needs the patched APK from oplus-camera-port (`force_true.py ... 'Lm5/e;->c()Z'`). Between 0.6x and 1x there is
no change because this mode only uses the main camera.

Class names are obfuscated and only valid for the camera app of OxygenOS 16.0.5.1301 (CPH2449).
It does not touch the ROM or any other app.

## Night mode (not supported)
Manual Night mode (8 RAW frames merged by the stock APS) cannot be made to work with hooks on this ROM, so the
module does not try to. What was found, in case somebody wants to take it further:

1. The vendor HAL never delivers the final `onCaptureCompleted` for Night frames, only a partial result with 15
   `android.control.*` keys and no `SENSOR_TIMESTAMP`, so the CameraUnit SDK never gets per-frame metadata and
   the images are dropped before reaching the APS. This can be worked around by re-feeding the partial result to
   `ConsumerImpl.onPictureMetaArrived` after `onCaptureStarted`, with the timestamp injected (all 8 frames then
   share the merge identity and `countAddFrame` completes).
2. The APS then starts processing, but reads the native metadata buffer of that partial result:
   `ANDROID_SENSOR_SENSITIVITY`, `ANDROID_FLASH_MODE`, crop region, device orientation, colour temperature and the
   HAL-generated tables (`SUPER_NIGHT_GAMMA`, `NATURE_TONE_LTM_GAMMA`, face ROI) are all null, and `apsRoutine`
   crashes with SIGSEGV/SIGABRT. Those tables are produced by the Oplus HAL, so they cannot be faked.
3. Redirecting the `night` mode to the plain photo mode in the mode factory (`zg.h3.a`) gives a blurry preview
   and a frozen app, because the app keeps using the `night` name for its own configuration.
4. Any other mode that takes a multi-frame capture (e.g. Portrait in low light, `aps_algo_portrait_supernight`)
   hits the same APS crash. There is also an intermittent `SIGABRT` in `vendor.qti.camera.provider` unrelated to
   the hook.

v0.22 removes all of the experimental Night hooks and diagnostics.

## Logs
Tag `HfrHook` (shown in LSPosed and logcat). The module logs only failures.
```
adb logcat -d | findstr HfrHook
```

## Build
GitHub Actions (`.github/workflows/build.yml`) builds the APK as an artifact. Locally: `gradle :app:assembleRelease`.

## Usage
1. Install the APK.
2. In LSPosed enable the module with scope `com.oplus.camera`.
3. Force stop the camera and open it again.
