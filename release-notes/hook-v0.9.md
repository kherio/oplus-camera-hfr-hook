- Super-steady video: the pinch limit is capped at 1.99x instead of 2.0x. The app switches lens at exactly 2.0x
  (its `DUAL` zoom point); this mode only uses the main camera, so reaching 2.0x made the image jump back to 1x.
- v0.8 change kept: while the super-EIS flag is set, `jg.b2.k` / `jg.b2.y` (the pinch limit, 5.0 by default) are
  lowered, and restored when leaving the mode.
- Pinch gating fix (`jg.b2.h()`) and slow-motion surface checks unchanged.
- Tested with the OnePlus camera of OxygenOS 16.0.5.1301 (CPH2449) on a OnePlus 11.
