# OplusCamera HFR hook v0.24

- **Experimental: normal-video EIS, second attempt.** v0.23 changed the value returned by `BaseMode.k8()`, but the session still opened with operation mode `0x830B`. v0.24 also hooks the sink that sets the session operation mode (`hh.k.K(int)`) and replaces `0x830B` with `0x800B` (720p/1080p/4K 30 fps, EIS3).
- Adds log lines (`hh.k.K(<value>)`) to see which operation mode the app requests.
