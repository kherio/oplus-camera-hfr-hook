Zoom up to 70x.

- `CameraHWConfiguration.config`: `MaxDigitalZoom`, `ZoomValueList`, video zoom range, `PreviewRange`,
  `scaleThresholdRatioRange` and `Range[2]` raised to 70 (generated with `scripts/apply_hw_zoom.sh ... 70`).
- `oplus_camera_config`: tele zoom range `2.0,60.0` -> `2.0,80.0`.
- `dumpsys media.camera` reports `availableMaxDigitalZoom = 70.0` and `zoomRatioRange = [1.0 70.0]`.
- Includes everything from v0.3 (`kernel_umount` fix). APK unchanged (`zoom50`).
