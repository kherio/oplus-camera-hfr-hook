# OplusCamera HFR hook v0.25 (test build)

- **Experimental:** raises the super-steady (mode 0x8028) pinch-zoom cap from 1.99x to 5.0x to find out whether the app/HAL honours zoom above 2x in this mode.
- Above 2x the request previously carried `zoomRatio=1.0`; this build lets us dump a request at ~4x.
