Super-steady video with zoom up to 2x.

- APK: `zoom50` + `Lm5/e;->c()Z` forced to true (`func_sat_tele_suit_with_super_eis`), sha256 prefix `94c96fec8e48`.
- Needs the LSPosed module `oplus-camera-hfr-hook` v0.7 (enables the pinch gesture in this mode and caps the zoom
  bar at 2x). Without the hook the pinch does not start.
- Zoom up to 70x in normal modes, as in v0.4.
- Limitation: super-steady video does not go beyond 2x. The mode (`0x8028`, 1080p 60 fps) is single-camera and
  the request keeps `zoomRatio = 1.0`; adding the tele camera to the mode's mask did not help.
