# OplusCamera HFR hook v0.26 (diagnostic build)

- Logs every change of `CONTROL_ZOOM_RATIO` set on a capture request and, when it drops from above 1.9x to 1.0, the call stack that did it. Used to find who clamps super-steady zoom above 2x.
