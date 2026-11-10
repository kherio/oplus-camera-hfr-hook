Camera HW config "50e" and a boot-time fix for the mount issue.

- `CameraHWConfiguration.config`: `MaxDigitalZoom = 50.0` (was 34.0, which capped the zoom at ~34x because the
  HAL clamps `zoomRatio` to it).
- `post-fs-data.sh` now runs `ksud feature set kernel_umount 0` on every boot. Without it, apps that get module
  mounts unmounted (Settings, SystemUI, and the camera after its UID changes) see a 0-byte APK: wrong name/icon,
  or the camera does not open.
- APK: `zoom50` (`f11f95462b59`).
