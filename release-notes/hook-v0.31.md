# OplusCamera HFR hook v0.31 (test build)

- Logs the zoom mapping function `hg.g.f` for zooms above 1.9x and, with super-steady video active, returns the requested zoom instead of a value <= 1.0 (which made the app fall back to `zoomRatio = 1.0` at 2x).
