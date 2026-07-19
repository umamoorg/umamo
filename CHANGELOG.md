# Changelog

All notable changes to Umamo will be documented in this file.

Umamo is early alpha and has no versioned releases yet, so entries are grouped by date range rather than version number.

## 2026-07-14 – 2026-07-20

### Added

* Source-art format support: readers for **BMP, JPEG, PNG, TIFF, and WebP**, alongside the existing PSD/CLIP/KRA support, broadening what art files Umamo can ingest.
* Alpha Shape Analysis: Pass that traces and simplifies the silhouette of a layer's alpha channel which is early groundwork toward automatic mesh generation from source art.
* MOC3 Import: Umamo can now load existing `.moc3` runtime files directly (not just `.cmo3` editor projects), converting them into an editable puppet.
* Blend Shape Support: Corrective blend shapes implemented end-to-end: decode/encode in the MOC3 format, CPU/GPU evaluation, and rendering, matching Cubism behavior.
* Offscreen/Composite Rendering: Parts can now render to an offscreen buffer and be composited with correct opacity and blend modes across nested groups, matching Cubism's part-level offscreen drawing feature in the viewport.
* Active/Selected object is now highlighted in a distinct color in the outliner and viewport.
* Snap radial(pie) menu for the UV Editor.

### Changed

* Rebuilt the renderer around a backend-neutral `RenderDevice` abstraction, splitting the previously monolithic GL renderer into focused pieces (shaders, frame encoding, GPU resource handles). This lays groundwork for future Android (GLES) and macOS/iOS (Metal) rendering backends.
* Outliner row striping now fills top-to-bottom consistently.
* CI now runs GL rendering tests in a headless environment.

### Fixed

* MOC3 import: incorrect rotation-deformer scaling.
* Several CMO3 read/write round-trip issues.
* MacOS crash/blank-viewport bug caused by an incorrect GLFW library binding (community contribution from Giodotblue).