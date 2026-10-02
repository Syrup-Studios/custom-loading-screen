## 0.1.0

- Add a configurable NeoForge 1.21.1 early loading screen.
- Use build version properties for NeoForge and Minecraft metadata ranges; remove obsolete FML metadata.
- Limit cached decoded textures to 128 MiB of RGBA pixels and use the scene fallback for rejected or failed texture loads.
- Fix sprite frame wrapping, animated progress-bar width, and measured text centering; reduce animation parent lookup work; bound scene reads and reject fractional or out-of-range integer fields.
- Fix stage reporting when the standard provider is selected.
