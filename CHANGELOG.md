# Changelog

All notable changes to Umamo will be documented in this file.

Umamo is early alpha.

(Unreleased changes)

## 0.2.1-dev - 2026-08-11

### Added
* Diagnostic CLI: A new `:cli` module(`./gradlew :cli:run`) exposes headless `dump`, `convert`, `diff`, and `extract` for inspecting CMO3 and MOC3 files.
* MOC3 export: A new Export Options dialog for choosing MOC3 export granularity.
* History: The number of history steps is now configurable.
* Area Header Controls: Controls in area headers now overflow into a "...>" pop up instead of disappearing.

### Changed
* CMO3 import and export are now at beta level status.  Numerous bugs have been corrected especially on the export side.
* MOC3 import to CMO3 export conversion is now alpha level status.  Texture atlases are now automatically cut up into source artwork layers and the produced CMO3 now displays properly in the Cubism editor.  While a cut up texture atlas won't be original layered artwork quality this makes it easier to manually reconcile the source artwork layers and also get a functional CMO3.
* Deformers imported from a MOC, which never have a display name in the MOC3, are now labeled with the drawable they deform when that's unambiguous(e.g. "Warp40 (ArtMesh5)") instead of just the raw identifier.
* Export notices, the report shown after an import or export finishes, are now localized instead of always appearing in English.
* The pointer is now properly tracked across all hovered areas and the old "active area ID" was removed.  This fixes a lot of hotkey fighting between areas.
* Outliner: After searching then selecting an item in a collapsed branch, the branch is expanded with `revealTarget()` when clearing the search term.
* Viewport/UV HUD Overlay: Now shows the part, drawable, and deformer name in the upper left.

### Fixed
* Switch a part composite from isolated to anything else stops rendering composite effects.  Originally for Umamo it was intended that opacity could be applied to have a part and have it cascade down to drawables to make it quick to change the opacity of all drawables in a part.  However, reconciling Cubism behavior with Umamo desires would create too many problems.
* MOC3 export: Identifiers were being deduplicated before being truncated.  This means the truncated identifier could then immediately become a duplicate again.  Now they are truncated, deduplicated, and properly written into the MOC3 and sidecars.
* MOC3 export: Blend shape deltas under a deformer chain were computed in the wrong point space, looking up the entity's own space instead of its parent's, producing incorrect deltas for any blend shape nested under a deformer.
* MOC3 export no longer silently overwrites existing sidecar files(textures, model3.json, cdi3.json, and the rest) already present at the destination; it now warns and lists every file that would be overwritten before you confirm.
* Windows OS: Workaround for AWT not sending a resize event when cancelling a window resize on Windows causing the Compose around to be stuck at the wrong size until resizing without cancelling.
* Accessibility: Various semantics/contentDescription spots were fixed and visual tooltips added.

## 0.2.0-dev - 2026-08-03

### Added

* CMO3 export: Alpha level support of writing Cubism 5.4 compliant CMO3 files.
* MOC3 to CMO3 conversion: Development level support of writing Cubism 5.4 complient CMO3 files converted from MOC3.  Source artwork reconciliation is not implemented yet so converted files will not display textures in the official Cubism editor.
* New :interop module that serves as the import and exporting interoperability border between the :format and :runtime modules.
* Keyform Sheet: New editor area for editing key forms and blend shapes for all keyable properties.
* Document > Runtime Target selection: Can now select the runtime target for the puppet including Ayagami and Cubism 3.0 through 5.4.  Selecting a target will automatically hide features in the UI that are not available for that runtime.
* Right-click context menu(Cut/Copy/Paste/Select All) on text input fields throughout the UI.  This replaces the built-in Compose menu that was blocking some context menus.
* Viewport rendering settings: Super sampling options to toggle off for performance improvements.

### Changed

* Properties Panel: Expanded and updated for the Keyform Sheet.  Missing properties implemented and the ability to hover over a property then press a shortcut to key it was added.

### Fixed

* Dragging a panel splitter gutter no longer lags out and fails to work on slow systems.  This was due to an issue with how the movement input was accumulated.
* Dragging a panel splitter gutter only writes up the layout settings after a debounce period.

## 0.1.0-dev - 2026-07-27

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
* CMO3 artwork that wasn't packed into a texture atlas rendered enlarged with its outer margin clipped because its UVs weren't remapped through the image's logical frame transform.
* The legacy "Add"/"Multiply" blend modes rendered with incorrect, non-premultiplied math whenever Alpha Blend was set to anything other than "Over".
* The "Out" alpha blend mode cut a hole in the wrong layer(source instead of destination) inverting the intended silhouette effect.
* Extended blend modes(Multiply, Screen, Overlay, etc.) mis-blended under Conjoint/Disjoint alpha blending because the blend mix weight ignored the selected alpha mode; unpremultiplied colors are now also clamped to avoid out of range artifacts.
* MOC3-imported drawables' multiply/screen tint keyforms were silently dropped when rest mesh geometry was rebased to canvas space.

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