# Changelog

All notable changes to Umamo will be documented in this file.

Umamo is early alpha.

(Unreleased changes)

### Added
* Format: New native UMA file format!
* Format: A UMA document remembers each panel's open branches, filters, tab, and the UV Editor's texture choice, per area.
* Format: A UMA document reopens to the pose, selection, mode, cursors, pivot and proportional editing settings, and each viewport's pan and zoom it was saved with.
* Format: The `.uma` file type is now declared for desktop packages and Android along with freedesktop files in the Linux build to register it per user.
* UI: The unsaved-changes prompts when replacing a document or quitting now offer Save, Don’t Save, and Cancel.
* UI: Added save confirmation for dirty files when closing the application.
* UI: Opens to a new document by default with New and Open file menu operations available.
* UI: Can now drag and drop files on the window.  Model files are opened and artworkd is added to the open document.
* UI: When a text entry box is active the mouse cursor now displays the text entry cursor everywhere to indicate that text entry is currently active.
* UI: Previous Workspace and Next Workspace added to Workspace menu.
* UI: Add hover tooltips to the Properties area and Operation Strip.
* Parameters: The parameters are now searchable.
* Source Artwork: The operation strip now offers to adjust the canvas placement of imported artwork to an existing document.  The alignment and offset can be changed.
* Source Artwork: Hovering a layer, art, or drawable row in the Sources panel previews its art.
* Source Artwork: A new Import preference shows the Sources panel's layer positions measured from the world axes instead of the art file's top-left corner.

### Changed
* UI: The CTRL+O keybinding was changed to file open instead of import CMO3.
* UI: Add Artwork and Import Artwork have been standardized to just import and add artwork to the open document.
* UI: Confirmation dialogs now take arguments for button names and actions along with a new third button.
* UI: Clicking away from all text input fields should now commit the change.
* UI: Clicking away from all text input fields should now end text entry input preventing other inputs from being eaten.
* UI: The command palette now lists only the commands that apply to the editor area under the pointer.  For example, Mirror UVs no longer shows while editing a mesh in the 2D viewport.
* UI: The status bar's shortcut suggestions now follow the editor area under the pointer.  This is the first iteration of this feature and will be improved in the future.
* UI: The popup overlap picker now uses the drawable name with the part name in parenthesis.
* UI: Buttons now use the accent color when pressed.
* UI: Positions in the Properties panel are now measured from the world axes, and the Origin fields place those axes from the canvas's bottom-left corner.
* UI: Before the 2D cursor is placed, the 2D Cursor pivot now turns about the world origin, the same point the cursor snaps already used, instead of the selection's median.
* UV Editor: Clicking inside a UV island in Object mode now selects it even where its texture is transparent.  Where islands overlap, the one with visible art under the cursor still wins.
* UV Editor: Switching to another page or layer for the first time refits the camera then remembers the camera position from there on.
* UV Editor: Fit View now also frames meshes that sit past the edge of the atlas page.

### Fixed
* UI: Modal key ladder issue with escape closing the preferences window first instead of the confirmation dialog.
* UI: Dirty documents were obliterated when opening a new document since the dirty check was broken.
* UI: Panel state(open branches, filters, the UV Editor's texture choice) no longer resets when switching workspace tabs.
* UI: Pressing Escape while rebinding a key would close the preferences instead of just cancelling the rebind.
* UI: The keybindings editor's clear button no longer sits underneath the scrollbar.
* UI: Double-clicking a workspace tab after reordering the tabs now renames the tab that was clicked instead of the tab that used to be in that spot.
* Source Artwork: Removed the singleton that could be accidentally be shared across documents.
* Format: An artwork file whose magic bytes are missing now routes to its reader by extension.
* Format: Performance optimizations for the PNG CODEC resulting in up to 50% less memory usage and up to 50% faster loads.
* Viewport: Camera fitting now falls back to the canvas bounds when there are no drawables.
* Viewport: Changing an area between the UV Editor and the 2D Viewport no longer leaves the previous editor's contents and camera behind.
* Outliner: A CTRL+Left Click or Shift+Left Click followed quickly by a plain click on the same row no longer opens rename.


## 0.3.0-dev - 2026-09-15

### Added
* Internationalization: Initial 한국어/Korean translation - Thank you to Nyaro for the translation.
* Export: Rework Cmo3AtlasUndedup to use the shared packer when exporting a fresh CMO3.
* Export: Generate layered art preview thumbnails for CMO3 export.
* Export: A document imported from source artwork(PSD, CLIP, or KRA) now writes its real layers and folders into the CMO3 instead of a synthesized stand-in, so the exported file reopens with every drawable's layer binding intact.
* Texture Authoring: The UV Editor now has object mode.  Click an UV island to select the drawable it belongs to, box select multiple, and Alt+Left=Click through overlapping islands, mirroring how object selection already works in the 2D viewport.
* Texture Authoring: A Texture Page selector in the UV Editor header lets you pin the view to a specific atlas page(with its dimensions and mesh count shown) instead of always following the current selection, plus new Next/Previous/Follow Selection commands for stepping through pages from anywhere.
* Texture Authoring: The UV Editor can now show and edit a drawable's mapping directly over its original source layer artwork instead of only the packed atlas page, with a searchable "Find by Artwork" picker to jump straight to a layer's drawable.  The cursor, pivot mode, snaps, and proportional editing all carry over to this view exactly as they work on the atlas page.
* Texture Authoring: A new "Show source artwork" document property switches the puppet between rendering from its packed texture atlas and rendering straight from each drawable's individual source layer, for checking the atlas mapping by eye before it's finalized.
* Texture Atlas: A new Repack Atlas command repacks every used piece of source artwork onto fresh atlas pages with Umamo's own packer.  Art that can't be packed, too large for a page, empty, or unreadable, cancels the whole repack with a report naming each piece.
* Texture Atlas: Exporting a CMO3 that has been converted from a MOC3 and then had its atlass repacked should now write a fully consistent CMO3.  Repacked layers should now properly target the renumbered pages.
* Texture Authoring: Turning on "Show source artwork" for a document whose artwork can't actually be displayed(nothing recoverable, or none of it decodes) now shows a notice instead of silently showing from the atlas instead.
* UI: A new Operation Strip modal will appear after the last operation in the focused 2D or UV viewport to make adjustments.
* Texture Atlas: Artwork placed on an atlas page can now be Grabbed, Rotated, and Scaled directly in the UV Editor's object mode, exactly like moving objects in the viewport.  Moving a piece of art carries every drawable mapped to it along with it and the page recomposes around the new arrangement.  Overlapping or off-page placements are flagged with an exact warning based on the art's actual painted pixels and mesh coverage, not just a bounding box.
* Texture Atlas: Placed artwork can now be pinned(P / Alt+P in the UV Editor's object mode, or the Data > Texture panel's checkbox) to protect it from being moved.  Pinned art is left alone by a hand Grab/Rotate/Scale(which refuses with a clear message if every selected tile is pinned) and by Repack Atlas, whose settings strip has a Keep Pinned Tiles option to override that for a single run.  Pinned-tile and warning highlight colors are now configurable in Settings > Colors.
* Texture Atlas: Repack Atlas's options(Max Page Size, rotation, gutter, extrusion, Alpha Threshold, and the square/power-of-two/shrink-to-fit flags) can now be adjusted after it runs from a small settings strip in the corner of the viewport or UV editor(or F9), which re-runs the repack instantly from where it started instead of asking for them in a dialog up front.  Add Artwork, Reload, Match Automatically, and moving/rotating/scaling placed artwork get the same adjustable strip for their own settings(Alpha Threshold, Birth Mesh Margin, Match Threshold, and the move/rotate/scale amounts).
* Parameters: New default seed parameters are applied when import artwork for the first time.
* Source Artwork: A new Sources space lists every artwork file behind a document, each file's layers with a bound/bound-by-name/unbound status shown as a traffic-light colored icon, the tiles and drawables under each bound layer, and an Unbound Art group for anything left over, with a filter for Unbound, Missing, or Needs Review.
* Source Artwork: A new File > Import > Artwork… command opens a PSD, CLIP, or Krita(.kra)file, or a flat PNG/BMP/JPEG/WebP/TIFF, straight in as an editable rig.
* Source Artwork: An automatic birth mesh is created for every new drawable on a fresh import.
* Source Artwork: The Sources space's header Reload button re-reads every artwork file still present on disk and folds in whatever changed: a matched layer's tile gets the new pixels, an untouched area is simply re-cut from the new art, and a hand-edited mesh keeps its vertices and keeps sampling the same spot on the canvas.  A layer that's gone missing or been renamed is never silently dropped from the rig, it's left alone and flagged for review instead.  A CMO3-imported document can still relink and reload from its own original layers even when the source file isn't at its recorded path, or fails to read, by falling back to the layer images embedded in the CMO3 itself.
* Source Artwork: Artwork files can now be watched for changes for automatic reload or notify to reload(See: Setings -> Import).
* Source Artwork: A new Match Automatically command scores every layer that went missing from a reload against the rest of the file(by name, folder, position, size, and pixel content)and proposes a match right on its Sources row.

### Changed
* Texture Atlas: Where each piece of source artwork sits on the atlas is now part of the document rather than something re-derived every time it's opened, so it can be edited, undone, and written back out.
* MOC3 to CMO3 conversion: The stand-in source document rebuilt from a MOC3's atlas pages now also opens and renders correctly in the official Cubism Editor, including switching between its layered-art and texture-atlas display modes.  The export notice wording was updated to describe what's actually still lost this way(the layers are baked-atlas slices, not the original editable artwork) instead of claiming the file wouldn't render at all.
* Source Artwork: The Sources space's relink and file-action menus, and the UV Editor's "Find by Artwork" layer picker, were rebuilt on the app's standard menu component, gaining grouped section headings and a searchable filter for long lists.

### Fixed
* UV Editor: Committing an edit could report vertices nobody moved as changed, because the display round trip wasn't bit-exact; this triggered spurious weld-divergence warnings on mappings that were never touched.
* MOC3 to CMO3 conversion: Reimported drawables were bound to a duplicate of the atlas page resource instead of the shared one the editor itself writes, which made every one of them look never packed and draw the wrong crop in the source layer view.
* Opening a document while another was already open could, for one frame, pair the new document with the previous document's editing session, so an export, the File menu, or the atlas page resolver could briefly act on stale data.
* Texture Atlas: Repack Atlas could apply a stale pack if a mesh was edited while the repack was still computing; it now cancels instead.
* Keyboard shortcuts could stop responding after using a dropdown chip, checkbox, number field, or section header that then disappeared from the screen while it still held focus; these now properly release focus back to the application.
* CMO3 Export: An artwork-imported document's parameters weren't placed in the export's parameter group hierarchy, and any part, drawable, or deformer with no authored keyforms at all exported with a missing keyform grid, both of which the official Cubism Editor refuses to open.  Both are now written the way the editor expects.
* CMO3 Export: A CMO3-origin document's reloaded or newly added artwork kept the old pixels showing in the editor's layered-art view even after the texture atlas had already picked up the new art; it's now written into the document's own retained layer tree too, so both display modes match.


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