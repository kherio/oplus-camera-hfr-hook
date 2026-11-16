# OplusCamera HFR hook v0.23

- **Experimental: electronic stabilization (EIS) for normal video.** The app requests operation mode `0x830B` for normal rear video, which has no entry in the HAL `EISTypeMatching` table, so only OIS is active. The hook now switches it to `0x800B` (720p/1080p/4K 30 fps, EIS3).
- Slow motion and super-steady zoom (up to 1.99x) are unchanged.
- Check with `adb shell "sleep 8; dumpsys media.camera"` while recording: `Operation mode` should be `32779`.
