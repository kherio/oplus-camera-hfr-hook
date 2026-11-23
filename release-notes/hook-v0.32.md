# OplusCamera HFR hook v0.32

- **Zoom up to 5x with super-steady video** (also while recording). The app's zoom mapping (`hg.g.f`) returned 1.0 from 2x up in this mode, which restarted the preview at `zoomRatio = 1.0` and ignored later zoom changes. The hook returns the requested zoom instead, and the pinch limit is now 5.0.
- Slow motion hook unchanged.
- Adds a launcher icon (from v0.24.1).
- Removes all experimental and diagnostic hooks (operation mode overrides, tele threshold, zoom request logging).
