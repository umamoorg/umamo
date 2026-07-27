# Changelog

All notable changes to Umamo will be documented in this file.

Umamo is early alpha.  Entries are grouped under the version they ship in; sections predating the first tagged release are still grouped by date range.

## [Unreleased]

## [0.1.0-dev] - 2026-07-27

### Added

* Properties panel: A new editable, tabbed panel(Document/Object/Data) replacing the old read-only Inspector, with a header search that filters down to matching rows and switches tabs automatically.  This brings in being able to edit many more puppet properties such as blend modes, alpha modes, compositing, and more.
* Logs panel: A new space showing the diagnostic output, color-coded by severity, with Copy to Clipboard and Export to File actions.
* Tagged releases: pushing a `vX.Y.Z` tag builds, tests, and publishes desktop artifacts for `linux-x64`, `linux-arm64`, `windows-x64`, `macos-arm64`, and `macos-x64` which are self contained application images with a bundled JRE plus a runnable uber jar per target.  Unsigned, no installer, no auto-update yet.  See [RELEASING.md](RELEASING.md).

### Changed

* Composite(part/group) rendering is faster: An isolated part whose blend is pose-identity Normal/Over over an all-Normal/Over subtree now draws inline instead of through its own offscreen layer, and each composite layer's clear/snapshot/draw is scissored to its subtree's posed bounds instead of the full viewport.
* Undo history entries for Grab/Scale/Rotate gestures are now labeled per domain and per operation(e.g. "Scale Vertices", "Rotate UVs", "Scale Objects") instead of collapsing to a generic "Move..." label regardless of what actually happened.
* The legacy "Add (Before 5.3)" / "Multiply (Before 5.3)" blend modes are relabeled "(Legacy)" and sorted to the bottom of the blend-mode picker; their properties no longer show the Alpha Blend field, which never applied to them.  They might be renamed again in the future.

### Fixed

* Deformer opacity and multiply/screen color keyforms are now applied and cascade down the deformer hierarchy, so keying a deformer's opacity to show/hide a subtree, or its tint color to color a group animates correctly instead of being ignored.
* CMO3 artwork that wasn't packed into a texture atlas rendered enlarged with its outer margin clipped because its UVs weren't remapped through the image's logical-frame transform.
* The legacy "Add"/"Multiply" blend modes rendered with incorrect, non-premultiplied math whenever Alpha Blend was set to anything other than "Over".
* The "Out" alpha blend mode cut a hole in the wrong layer(source instead of destination) inverting the intended silhouette effect.
* Extended blend modes(Multiply, Screen, Overlay, etc.) mis-blended under Conjoint/Disjoint alpha blending because the blend mix weight ignored the selected alpha mode; unpremultiplied colors are now also clamped to avoid out of range artifacts.
* MOC3-imported drawables' multiply/screen tint keyforms were silently dropped when rest-mesh geometry was rebased to canvas space.

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