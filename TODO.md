# TODO

## Documentation
* Have a full model/ map made in docs/. (When everything is done.)

## Deferred
* GPU glue: multi-pair seam vertices (deferred 2026-06-21)
* Android GLES renderer backend (deferred 2026-06-21)

## VERY IMPORTANT
* Hire translators for localization.
* Final pass on keyboard shortcuts.
* Final pass on default settings.
* Final pass on theme colors.
	* This includes MeshEditColors.kt.(One full pass has already been done.)

## User Stories
* From CrystalorImLisa on Reddit: The ability to mirror deformers and drawables along with their key frames.
	* Umamo solution: Select a deformer and the drawable -> Duplicate -> Mirror X (On the duplicate) -> Do some minor UV clean up -> Done!
	* https://www.reddit.com/r/Live2D/comments/1uy0871/is_there_a_way_to_duplicate_a_warp_deformer/

## World Origin
I should fix the naming so that origin is X and Z in the code.  Z up, Y forward.

## Performance
Investigate if dragging an area gutter is performance bound or if something stupid is happening like thousands of updates per second.

## MOC3 Lowering
LimeBirb has some non-byte exactness to investigate.

## Properties Panel
A mega area panel of sorts with left side icon tab strip and each tab having collapsible sections.  This panel will have a lot of data and controls so it is important to make sure we get the data design right ahead of time.

* The document-level **runtime-compatibility target data model** behind Document › Runtime — the enabled export targets (Cubism, Ayagami, …) + each target's options, how it persists on the document, and how it drives CMO3/MOC3 export. Scaffolded as a placeholder section now; its data design is a separate pass (depends on cataloguing each target runtime's capabilities).
* Opacity is not properly wired up yet from the properties panel, potentially in the renderer, and also keyed opacity.
* UMA serialization of the latent composite (the format work this unblocks).
* Improvements
	* Follow-ups still open:
		* Parts and deformers still have no editable transform — needs the deformer → part → mesh cascade.

* Single/multiple relation pickers.
	* Improvements
		* Persist list height.(Stored in UMA format, maybe?)
		* Deformer pickAt().
		* Context Menus

## Artwork Import
* We need to properly handle different blending mode imports from artwork to setup the drawables automatically.

## Portability
* Can we move extractPuppetTextures (module/render/src/jvmAndroidMain/kotlin/org/umamo/render/Cmo3PuppetTextures.kt) into commonMain to sit next to the new Moc3PuppetTextures and inherit from a base?
	* Potential clean up of Cmo3Document as well.  If there is going to be a new Moc3Document, we should consider having them as separate files next to Document.kt.

## Read/Write Filing Handling
module/ui/src/jvmAndroidMain/kotlin/org/umamo/ui/app/EditorApp.kt (the shared shell — the old app/desktop path is gone)
The file picker just writes out the original CMO3 right now as a save test.  Nothing actually converts the PuppetModel into CMO3 format.  See docs/plan/art-sourcing-pipeline.md § Phase H for the export writer and the default-form decision it is gated on, and docs/plan/portability.md § Phase G for why that decision is cheaper to make BEFORE the writer is built than after.
* Document State - One document per window instance.
	* Opening the application should start as a fresh new document.
		* Open/Import should ask to save before doing if the new document is dirty.
* Drag and drop file opening.

## Puppet Model, CMO3, MOC3
* ~~Handle opacity inheritance from parts.~~ DONE: `foldNonIsolatedPartOpacity` (`:render/eval/DeformPrepare.kt`) folds every non-isolated ancestor part's opacity (keyed, or the static `PartComposite.opacity`) into each drawable per pose; isolated parts are skipped there because their opacity applies to the composited layer instead.  The Model A "Effects" layer renders correctly.
	* Deformers: CONFIRMED GAP, CORPUS-MEASURED (2026-07-24).  Both format layers already decode the channels — CMO3's `ACDeformerForm` carries `opacity` + `multiplyColor` + `screenColor` on every warp and rotation keyform (the `ACDrawableForm` set minus drawOrder), and MOC3's `WarpKeyform`/`RotationKeyform` carry the same three.  Only the RUNTIME ingest drops them: `Cmo3Import.warpForm` keeps just `positions`, `rotationForm` just the pivot, and `WarpForm`/`RotationForm` have nowhere to put the rest (`Moc3Import` likewise).  So every affected subtree renders permanently at full opacity and untinted, with no warning.
		* Scan: `module/format/src/jvmTest/.../DeformerChannelProbeTest.kt` (print-only, runs off `cmo3.probe` + `moc3.samples`, self-skips without a corpus).
		* CMO3, 26 samples — warp 4177 keyforms / 19 non-opaque / 0 multiply / 2 screen; rotation 1208 / 34 / 29 / 28.  Affected: haruto_pc_pro_t02, koharu_pc_pro_t02, modelA, modelC, modelE.
		* MOC3, 24 authored samples — warp 10098 keyforms / 174 non-opaque / 9 multiply / 20 screen; rotation 3678 / 54 / 25 / 24.  Affected: Azxiana, LimeBirb, modelA, modelC, modelE.
		* The DEFORMER NAMES are the real finding, and the minimum opacity is 0.000 everywhere: "Tears Rotation", "Hologram Display", "Monochrome Display", "Night Rotation", "Magic Circle A Display", "Sparkle R Display", "Heart Fail Display", "Arm R Display".  Deformer opacity is being used as a parameter-driven SUBTREE SHOW/HIDE SWITCH, and Umamo currently shows all of them all the time.  modelC additionally tints through deformers ("Flask Ink Color Heal", "Overall Color").  Azxiana and LimeBirb are our own models.
		* Why it was never caught: the differential oracle dumps drawable GEOMETRY (`D` lines: vertex count + position/UV hashes) and OFFSCREEN channels (`O` lines), but nothing compares `csmGetDrawableOpacities` / multiply / screen against our eval.  The composite work added `[offscreens]` to dump_model.c; the drawable channels never got the same treatment, so the whole opacity/color surface is un-gated.
		* Fix, in order: (1) add the three channels to `WarpForm`/`RotationForm`; (2) read them in both importers; (3) cascade down the deformer chain in `DeformPrepare`, beside `foldNonIsolatedPartOpacity` — opacity is presumably multiplicative but the color combination rule and the interaction with an enclosing isolated part must come from the oracle, NOT a guess; (4) extend dump_model.c with a drawable-channel section and add a `DrawableChannelOracleTest`, which both pins the cascade rule and closes the blind spot permanently.
		* Do this BEFORE the per-channel keyform split under § Keyform Insertion: that split needs deformers to have scalar and color channels anyway, so this is a prerequisite that pays into it rather than competing with it.
* MOC3
	* CDI3 - Export mesh display names as a separate array.

## Tools, Shortcuts, and Gizmos
* Improvements
	* Unconnected proportional editing should edit all meshes when multiple meshes are selected for edit mode.  I would like to merge the proportional button and falloff settings into one menu with the connected checkbox.
* New Icons (For myself to get/make.)
	* Replace magnet from the cursor/selection menu.
	* The Tabler icons on the toolbar are probably fine, but I will check what is available from the Blender icons.

## Overlays Toggle
* Overlay visibility toggles from viewport header.
	* General Information - The spot in the AreaHeader showing the selected item will be moved here.  It's too much in the AreaHeader.
	* Wireframe (Object Mode)
	* Grid - Ability to change scale and divisions.
	* 3D Cursor

## Object and Mesh Editing
* Improvements
	* Mirror along X/Z axis, mirror with 2D cursor as the axis.  Note: This is a small divergence to Blender's style.  In Blender there is an origin for each object that can be moved to different places.  Umamo still has the centroid origin calculated, but no way to move it or even if it was moved, a way to store it.

## UV Editor
* Bugs/Improvements
	* Rip and Vertex Slide are activating the 2D viewport mesh rip/slide.  Needs to be implemented for UV and then properly gated.
		* Do a study to determine if rip functionality is really needed.  It is definitely needed for 3D work, but for 2D work I think it is less useful.  Though I'm curious what people would create with the functionality being available.
	* Mirror UVs are shown in the command palette when editing a mesh in the 2D viewport.
* UV Snap Pie
	* (Deferred) Selected to Adjacent Unselected - Moves selection to adjacent unselected element.
		* Implementation difficulty: This moves the UV vertex that has been disconnected from its sibling, which is one vertex in the mesh, on top of each other.  We will have to either walk the UV/mesh to find the sibling or store it.  Selected to Adjacent Unselected is only needed if rip is supported in UVs.
* Relax/Pinch tools - deferred; needs brush machinery (radius cursor, per-stroke commits) that nothing else has yet.
* Multi-page sessions show only the active drawable's page; meshes on other pages are not drawn (no indicator yet).

## Shortcuts
https://hollisbrown.github.io/blendershortcuts/ - I should make a page like this demonstrating the shortcuts for Umamo.

# Parameters
* Improvements
	* The filter menu could have just been a copy past from the outliner, but instead it is a button.
* New Features
	* Blend Shapes and Keyforms - When blend shapes exist:
		* Modifier key + click to add or remove ticks from track
* UI Improvements (For me to test and visually check.)
	* Drop zone indicator needs a slight adjustment to not be hidden by the elevation.

**Finding (2026-07-24): the runtime data model ALREADY works this way — nothing has to change to allow it.**  `Parameter` carries only `id/name/min/max/default/kind`; it holds NO key positions.  Every keyable item owns its own `KeyformGrid`, whose `KeyformAxis(parameterId, keys: FloatArray)` stores that item's own key positions on that parameter.  Two drawables keyed on `ParamAngleX` at `[-30, 0, 30]` and `[-30, -10, 0, 30]` are already representable and already evaluate correctly (the multilinear blend reads each item's own axis).  Cubism enforces shared key positions in its EDITOR; the CMO3/MOC3 file formats store per-item grids, so Umamo inherited the freedom for free by importing them faithfully.  What that leaves is not a data-model decision but two pieces of work: the AUTHORING ops (bind an item to a parameter = add an axis; insert/move/delete a key = resize that axis and rebuild the cell product) and the EXPORT decomposition (union-resample at CMO3/MOC3 write time).  The design note below stands as written — read it as a plan for the UI and the exporter, not for the model.

I'm considering an Umamo design decision that would operate a bit differently than how Cubism does for displaying and editing parameters.  Right now Cubism has a parameter slider and any item can be keyed on to it, but all items have to share the same key positions.  In Umamo, I would prefer to be able to have one parameter, key any item on to with arbritrary key positions bounded by the minimum and maximum.  I would like your feedback, if you would do this, and any notes for improvements.
* Parameters area (Scrubbing, basic editing, which exists right now.)
* Parameter Action Editor/Dope Sheet/Whatever area it will be called.
	* When selecting a parameter, this area would populate tracks of each item keyed on to it.
		* Blender style to insert a new key frame: I to insert from selection.
* When exporting from the internal format to CMO3 and MOC3, it would decompose the tracks into a compatible format.  If all the items share the same key positions then they all get controlled by the same parameter.  If only a few do, then only those few share the same parameter while the rest get assigned to new parameters with numerically appended names.
```
**Decoupled per-item keyforms on a shared parameter**

- One parameter, each item keyed at its own arbitrary positions within [min, max] — drop Cubism's shared-key constraint. Falls out of the blend-shape model (parameter = blend axis; each item blends its own keyforms independently).
- UI: keep the Parameters area (scrub/edit). Add a per-parameter dope sheet — select a param → tracks for each keyed item; Blender-style `I` to insert key from selection.
- Data model: store per-item, per-axis key positions → per-item N-D keyform grid. A dope-sheet track is a 1D projection of that grid; editing a key has grid implications for multi-bound items.
- Export (CMO3/MOC3) default = **union-resample**: union the key positions across items, give every item a keyform at each union position (fill gaps with its own interpolated value). Exports as ONE parameter, lossless — linear blend means an inserted key lands on the existing segment. Preserves one-control semantics.
- Splitting divergent items onto new appended-name params (`Body_Y_2`…) = **opt-in fallback only** (keyform-count optimization, or a genuinely independent control). Not the default: splitting silently turns one control into several that a host must co-drive.
- Verify against a real file: keyform-axis interpolation is *linear*, not smoothed (bezier/smoothing should live on the deferred animation timeline, not the modeling blend). Resample losslessness depends on this.
- Bonus affordance: dope sheet flags the default-value column / items missing a neutral key → makes "reset to 0 doesn't return to rest" visible.
```

## Keyform Insertion (the authoring model)

DECIDED: Blender-style manual insertion.  Scrub the parameter(s) to the target position, manipulate the deformer, press `I` to insert the keyform from the current selection.  No auto-key by default.

Order matters and differs from the first sketch: it is scrub → manipulate → `I`, not manipulate → scrub → `I`.  Scrubbing re-evaluates the rig, so a manipulation made before the scrub has to either be discarded or carried, and both are surprising.  Blender discards; matching that is the least surprising answer for anyone who has used Blender, and the "carry" behavior has no coherent meaning for a warp lattice anyway.

Open decisions, in rough order of how much they shape the implementation:

* **The first `I` on an unbound item must SEED an axis, not create a single key.**  One key on an axis makes the item constant over the whole parameter — including at neutral — so a naive first insert would silently move the rest pose.  Cubism seeds min/default/max holding the current form.  Recommend the same: bind creates the axis with a small seeded key set all holding the CURRENT form, then writes the manipulated form into the cell at the scrub position.  Decide the seed set: {min, default, max}, or {default, scrubPosition}.
* **`I` on a multi-bound item inserts a grid SLICE, not a cell.**  An item on two axes has a 2D cell product; inserting a new key on axis X means adding a whole COLUMN (one new cell per existing Y key), and only the cell at the current (x, y) is known from the manipulation.  The rest of the column must be filled by interpolating the neighboring columns — union-resample applied locally.  This is the main implementation subtlety, and it is the same operation the CMO3/MOC3 exporter needs, so build it once as a shared grid op.
* **PER-CHANNEL TRACKS — a second, deeper deviation from Cubism, and the one that needs deciding FIRST.**  Cubism's keyform cell is all-or-nothing: `CArtMeshForm` carries positions + drawOrder + opacity + multiply/screen color together, and Umamo currently mirrors that (`MeshForm`, `PartForm`).  The goal is for each channel to be an INDEPENDENT track with its own axes and its own key positions — scrub a parameter, right-click Opacity in the properties panel, Insert Keyframe, and a new opacity track appears in the dope sheet bound to that parameter, with no geometry key created and no geometry axis touched.
	* This is a DATA-MODEL change, not just UI: `MeshForm` has to shed its scalars, and each owner carries several grids instead of one.  Suggested shape, grouped by value type because the generic needs it: `geometry: KeyformGrid<TGeometry>?` (mesh deltas / warp control points / rotation pivot), `scalars: Map<ScalarChannel, KeyformGrid<Float>>` (opacity, drawOrder), `colors: Map<ColorChannel, KeyformGrid<ColorRgb>>` (multiply, screen).  Adding a channel later is an enum constant, not a new field.
	* **Do it BEFORE the authoring ops, not after.**  Nothing in the tree authors a keyform yet, so this is the cheapest it will ever be.  Every insert/move/delete/bind op written against the bundled shape would have to be rewritten against the split one.
	* Blast radius is smaller than it looks: `KeyformGridEval` already samples per channel (`samplePartDrawOrder`, `samplePartOpacity`, the combined drawable sampler), so those functions take a channel grid instead of reaching into a shared cell.  The rest is both importers, `DeformPrepare`, and the oracle tests.
	* `I` stays unambiguous WITHOUT a Blender-style keying set, because the invocation site names the channel: viewport `I` keys geometry (the thing just manipulated), a properties-row context menu keys that row's channel, dope-sheet `I` on a track keys that track.  No menu to design.
	* **Export bake is LOSSLESS.**  Union-refining a multilinear grid is exact — a multilinear function restricted to a sub-cell is still multilinear, and multilinear interpolation of that sub-cell's corners reproduces the unique multilinear function through them.  So both refining an axis and adding a whole axis along which a channel is constant preserve every value exactly.  This rests entirely on the interpolation being multilinear with NO smoothing; verify that against a real file before relying on it (already flagged in the design note above).
	* **Export cost is a cell-count PRODUCT, but it is not a penalty versus Cubism.**  Geometry on 2 keys of AngleX plus opacity on 5 keys of ParamFade bakes to a 2x5 = 10-cell grid, every cell carrying a full copy of the position deltas.  That looks alarming until you notice a Cubism user authoring the same rig needs both axes on the item too and lands on the same 10 cells.  Umamo is not paying more; it just is not making the rigger type it.  The cost is the format's, and it appears only at export.
	* **Import needs a SPLIT, and optionally a compaction.**  CMO3/MOC3 arrive as one unified grid, so import must fan it out into per-channel tracks.  The naive split (every channel inherits the full grid) is correct but saves nothing.  Compaction — drop keys that are exactly interpolable from their neighbours, drop axes along which a channel is constant — turns an imported Cubism rig into a sparse Umamo rig automatically.  Use EXACT-only compaction (bit-equal, or a tight ULP bound), never an epsilon: an epsilon would silently alter someone's rig, which the fidelity contract does not allow.  Testable invariant worth pinning: import-compact then export-union reproduces the ORIGINAL grid.
* **Auto-key is a Cubism-migration friction point, not just a preference.**  Cubism auto-keys: any edit at a parameter value creates or updates a keyform with no explicit gesture.  A Cubism migrant will lose work silently under manual keying until the habit changes.  Recommend manual as the default with auto-key as a setting the Cubism keymap preset turns on, plus a visible "unkeyed edit pending" indicator in the viewport so a scrub never discards work invisibly.
* **The per-form write path differs by deformer type.**  `WarpForm` stores ABSOLUTE control points, `RotationForm` an ABSOLUTE pivot transform, `MeshForm` deltas off the mesh base.  Only the mesh case needs a base subtraction on write.
* **`Alt+I` to remove a key.**  Removing the last key on an axis = unbind, which is exactly `withAxisCollapsed` (already implemented in ParameterCrudEdits.kt for parameter deletion, currently `internal`).  Reuse it rather than writing a second collapse.

Two prerequisites this plan needs that do not exist yet:

* **There is no "selected/active parameter" concept.**  `ParametersViewState` tracks open range editors, expanded groups, and filters — nothing marks a parameter as the insertion target.  "Select the parameter(s)" needs building before `I` has anywhere to write.
* **Posed authoring is currently REFUSED, and the reason is real math, not a stray guard.**  `EditorSession.beginObjectOperator` refuses while any parameter is off its default, because the deformer-chain world→local inverse (`worldToLocalLinearized`) is exact only at neutral.  That constraint does not disappear when keyform authoring lands: dragging a TOP-LEVEL deformer's control points under a pose needs no chain inverse and can be unblocked cheaply, but dragging a NESTED deformer's, or a drawable's vertices, under a pose inherits the same inexactness the guard exists to prevent.  Decide what posed editing is permitted per target type — do not just remove the guard.

# Button UI
* Needs a click action, either a background color change or movement.

# Tooltips
* Consider swapping to BasicTooltipBox in the future to get rip of the desktop and Android split of TooltipArea.  BasicTooltipBox is more recent as of writing this, July 2026, is being actively iterated against.
* Anywhere that we are using semantics/contentDescription we need to have a Tooltip as well.

# DRY
* ClickGestures - singleOrDoubleClick - We might be able to reuse this in other areas that experience the same issue.(WorkspaceTabs, OutlinerSpace)

## Format

### UMA (Native File Format)
See the roadmap: docs/plan/art-sourcing-pipeline.md § Phase G — the source-agnostic container is designed there.

## Import
Initial import and setup of art into a puppet.  Realistically, editor controls need to exist first.  There are test CMO3 files to work with to get editor controls going.
MOC3 with sidecar processing - DONE (File > Import MOC3…, the file.importMoc3 command): Moc3Import joins the decoded moc with model3.json (required, plus every listed texture) and cdi3.json (optional, degrades to raw ids); missing sidecars fail with dedicated alerts (MissingManifest/MissingTexture).  Moc3Cmo3ParityTest pins the coordinate conversion against the CMO3 corpus twin.  Remaining: Android sibling discovery (SAF has no directory access - desktop-first for now), blend shapes import as no-op sliders until blend-shape eval exists.

## Reimport
* Detection of edited source art files when application reacquires focus.
* (Might not be appropriate for the reimport module, but has to be reusable across every platform.) Detection of the user trying to change the source art file format(PSD -> KRA) should warn that it is destructive since layer matching heuristics are not perfect and could result in orphaned layer data.  Later on having a dialog to manually remap these layers would be nice.  A dailog for manually remapping will be needed eventually for when layer matching heuristics file even when reimporting the same source art file format.

## Render
* GPU glue: multi-pair seam vertices — latent correctness gap; see Claude Notes § GPU glue: multi-pair seam vertices.

## Outliner
* Later: editing the `drawOrder` NUMBER and the group flag (a separate draw-order concern) in the Inspector.
* Context Menu — DONE (right-click on desktop): Toggle Visibility, Toggle Selectability, Rename, Delete.
	* TODO: touch long-press still starts a drag, so the context menu has no mobile trigger yet — the long-press-vs-drag arbitration needs resolving (or a dedicated touch affordance) before Android.
* Deferred
	* When the native UMA format exists we can track open/closed branches.  Cubism/CMO3 does not track this and it is all collapsed by default.

## UI
* The placeholder checkerboard(EmptyViewportBackdrop) could just be the renderer showing the viewport without a model loaded.  It's fine as a placeholder for now.
* Viewport view styles - Top right, in the header area.
* Viewport loading overlay and mouse busy pointer.
* AreaHeader/Viewport2DHeaderControls
	* Font size and icon sizes don't line up resulting in the font being 1px offset.(Lots of manual tweaking is required.)
	* When the width becomes too small the icons in the DropdownChip start shrinking, but the chip does not.  The icons should not shrink.
	* Search box shrinks, but eventually then squishing upwards causing clipping.

* Menu - New Items
	* File
		* Import/Export - "Import MOC3…" exists as a flat row; fold into an Import submenu when image import lands, and add Export.
	* Edit
		* Cut/Copy/Paste

## Theme Colors
* Ability to edit ALL the theme colors (the UmamoColors palette) for a custom look through preferences.  For example, in Blender I make my vertex colors as ff00ec(unselected), ff7a00(selected), and 7de400(active selection) since it is easier for me to see.
	* The color-blind-assist first pass — vertex/edge/face gizmo colors plus the selection highlight — already exists in Settings > Colors.

## Settings
* Settings Window - Curated settings.  Not everything from the settings.json can be exposed.  So each tab/section will be manually built.
* Keybinding - input.keybinding (Includes keyboard, mouse, and pen buttons.)
* Pen Binding (JPen, Wacom) - input.pen (Includes pen, pressure, and things related to the radial menu.)
* New Startup Settings Screen
	* Import from pervious version.
	* Select from binding defaults.
* The settings UI needs a design pass since it is basically just squares and whatever thrown together right now.
* Make history limit configurable.

## Keybindings
* Create default keybinding maps for Blender and Cubism styles.

## Random Bugs
* While resizing the application window pressing escape to cancel the resize snaps the window back, but the Compose area does not.

## Storage

## Future Feature Wishes
* Pose Reference - A poseable and adjustable 3D mannequin model for overlay reference.
* Really good edge detection for auto-mesh.
* Normal map, emission, metallic, and reflection shaders for texturing.
* Key/mouse/pen input overlay for recording/streaming.
* History playback for proof of work.  The history system is there, but that is a lot of track over a long session.  So capture a snapshot every time period or number of snapshots.

# Build and Distribute
* We need to setup packageDistributionForCurrentOS and associated Github Actions.

## Input

### Pen backend + radial menu (sketch, deferred)

Sketch:
- Settings: `input.pen.backend` ("auto"/"native"/"jpen"/"disabled"), later `input.pen.pressureCurve` etc.
- A `PenInput` seam (commonMain interface) producing pressure / tilt / barrel buttons, `expect`/`actual`: Android `actual` via `MotionEvent` (`getPressure`, `getAxisValue(AXIS_TILT/ORIENTATION)`) in the viewport pointer pipeline (Android pen is the easier, priority target per the thesis); desktop `actual` via JPen or Wintab / Windows Ink behind the seam (the risky part — no clean Compose-desktop pressure path). Barrel buttons bind to commands through the existing action registry.
- Radial menu: a commonMain Compose overlay whose entries come from the action registry, opened by a pen-bound action / long-press — the keyboardless-tablet entry point that carries tool switching and reaching Preferences (per CLAUDE.md).
- `PenSection`: backend dropdown now, pressure-curve editor later; replaces the stub.
- Why deferred: desktop pressure integration + the radial menu + a per-mode keymap context model is a feature of its own. Plan separately once Phase 3's keymap foundation lands (the radial menu and barrel-button binds reuse it).

## Command Palette
* Icons for commands - Long tail feature, would need to add a lot of icons.  We can reuse the existing icons for current commands such as editor/select modes.

## Pose Palette/Library
* Cubism 5.4 added a "Model state set" which is just a pose library.  The data is saved into the CMO3 file.  This should be easy to implement and store in the native UMA format.

# Claude Notes

## GPU glue: multi-pair seam vertices (deferred 2026-06-21)

**What.** The GPU glue weld (`PuppetRenderer` two-pass; `module/render/src/commonMain/.../puppet/`) stores **one
partner per vertex** in its per-vertex glue attribute (partner global index, glue index, weld weight; built
in `buildGlueAttributes`, consumed by `GLUE_VERTEX_SHADER`). If a single mesh vertex participates in **more
than one** glue pair — e.g. a corner vertex shared by two seams — only the last-written pair survives, so the
GPU applies **one** weld where the CPU `applyGluesResolved` applies **both, sequentially**. That would diverge
from the CPU/oracle at such shared verts.

**Why it's fine right now.** Erica's four glues have **disjoint** seam vertices (no vertex is in two pairs),
so the GPU render is pixel-perfect vs the CPU (maxDiff 3/255, 0 px >8). This is a **latent** gap that only a
model with shared seam verts would expose.

**Detection.** Add a glue-aware per-vertex check: run the two-pass GPU glue, transform-feedback-capture the
**post-weld** positions, and diff against the CPU `applyCpuDeform` (which includes glue) on a model whose
glue pairs share vertices. (The existing `GpuDeformValidationTest` only validates the pre-glue deform.)

**Fix options (when/if it bites).**
1. Per-vertex support for *N* partners: widen the glue attribute to a small fixed array (or an indexed side
	buffer) and loop the welds in the shader **in the CPU's pair order** so the sequential result matches.
2. Detect shared seam verts at import and fall those specific glue meshes back to CPU glue (the hybrid path),
	keeping the rest on the GPU.

## Android GLES renderer backend (deferred 2026-06-21; rewritten 2026-07-24)

**Where this stands.** The re-architecture worry in the original note is dead: `PuppetRenderer` is
backend-neutral commonMain and makes zero GL calls, all shader source is commonMain under `:render/glsl/`
emitted per dialect by `GlslDialect`, and `:render/androidMain` already carries a `GlesRenderDevice` stub.
So what is left is genuinely "fill in one `RenderDevice` implementation + bridge it into the Android app",
against an API that has already been proven by a shipping backend.  Two pieces:

1. **`GlesRenderDevice`** — a near-transliteration of `GlRenderDevice` (542 lines, jvmMain/LWJGL) in the
	GLES binding style (`android.opengl.GLES30`).  Same calls, different binding surface.
2. **The Compose-image bridge + a GLES `PuppetViewportService`**, the Android peer of `OffscreenPuppetService`.
	`MainActivity` passes `viewportServiceFactory = null` today, which is why viewport areas render placeholders.
	`SupersampledSurface` and the device's `resolve` + ticket-based async readback are already commonMain, so
	the offscreen/readback stack is reusable rather than rewritten.

**The one real divergence — glue's texture buffer.** The glue pass needs a random-access partner lookup.
Texture buffer objects are **core only in GLES 3.2**, not 3.0/3.1.  Options: (a) `EXT_texture_buffer` if
present; (b) repack the shared position buffer as a regular **2D texture** and index it by
`(globalIndex % width, globalIndex / width)` via `texelFetch` (works on ES 3.0); (c) fall back to CPU glue
on Android.  Option (b) is the cleanest portable route and would also simplify the desktop path.  This is now
hidden behind `DeformedPositionStore` / `createDeformedPositionStore`, so whichever option is taken, the
renderer is untouched.

**Shader portability (GLSL → GLSL ES 3.0).** Mostly handled by `GlslDialect` already: `#version 300 es`,
explicit precision (`precision highp float;` + `precision highp int;`, `highp` samplers), `in/out` (already
300-style).  `texelFetch` + RG32F sampling for the delta/control-point textures are fine in ES 3.0.

**Validation.** The GPU-vs-CPU transform-feedback tests (`GpuDeformValidationTest`, `GpuGlueValidationTest`)
are desktop-only (GLFW), but they diff `RenderDevice` output against `applyCpuDeform`, which is backend-neutral —
an on-device or emulator render-diff is the Android analogue and needs no new oracle.

## Art-first pipeline: path to a functional editor (mesh/UV decoupling)
Full design roadmap: docs/plan/art-sourcing-pipeline.md (supersedes and expands this note; the 9 steps below map onto its Phases A–H).  This note stays as the terse status tracker.

**Governing design decision.** Art-mesh geometry and texture UVs are independent concepts (Blender-style),
unlike Cubism which welds them at the default/neutral form. The mesh is the art's renderer — moving it moves
where the art draws — but it always keeps sampling the correct texels. Two invariants must hold at every step
below:
- Editing mesh geometry (move/scale/rotate vertices) never changes UVs. It only moves where the art draws;
  it does NOT re-derive which atlas texels are sampled the way Cubism does. Already true: `withMeshPositions`
  shares the uvs array by reference; `MeshTransforms` / `ObjectTransforms` touch positions only.
- UVs bind to source-art pixel space via a stable `LayerId`; the texture atlas is a repackable indirection
  layer, never a source of truth. Regenerating/repacking the atlas moves where pixels physically sit in the
  page — never the vertex→art-pixel mapping.

**End goal.** Import layered art, auto-mesh it, rig it, and refresh non-destructively against art changes,
with CMO3 read/write as the interop boundary and UMA as the native format that actually preserves the
decoupling. The front half (steps 1–4) currently only works via a pre-baked CMO3; the native art-first path
is still ahead.

1. Source-art ingest → neutral model. Built: PSD/KRA readers produce `SourceArt` (LayerId / LayerBounds /
	LayerBlend). Pending: a `SourceArt` → `PuppetModel` path (no fromPsd/fromLayered exists). Layer bounds
	place each drawable; layer pixels become the texture. See § Import.
2. Auto-mesh from art ("mesh from art"). Pending: generate an initial mesh over each layer's opaque region.
	At birth, positions and UVs are two views of the same art layout — they only diverge once geometry is
	edited. Foundation built: the per-layer opaque region (alpha-trimmed bounds + occupancy + a marching-squares
	contour) comes from `analyzeAlpha` in `:format` — Phase B, shared with step 3.
3. Atlas generation / packing. Pending: no packer exists yet (today the atlas is inherited from the imported
	CMO3 — `extractPuppetTextures`). Pack layer tiles into page(s), emit UVs pointing at the tiles; hold the
	vertex→art-pixel binding invariant across every repack. Foundation built: the trimmed pack rects come from
	`analyzeAlpha` (Phase B).
4. UV editor. BUILT (2026-07) — `UvEditorSpace` + `UvEdits`, editing existing UVs over an existing atlas
	page in texel space (v=0 is the atlas TOP row).  The half of decoupling that was missing is now
	authorable.  Remaining gaps are listed under § UV Editor, and none of them block the pipeline: what the
	editor still cannot do is author a mapping over an atlas Umamo BUILT, because there is no packer yet
	(step 3).
5. Mesh editing (rest geometry). Built: object + edit mode, UV-preserving, edits the neutral base that every
	keyform is a delta off. Remaining: topology edits (subdivide / merge / rip) must resize the UV array AND
	every keyform's delta array to the new vertex count — see § Render "remeshing" and § Shortcuts (M / V / J).
6. Rigging. Parameters, deformers, keyforms on top of the rest mesh — the actual deformation authoring.
	CORRECTED 2026-07-24: this was previously logged as "largely built for CMO3-imported models", which
	conflated evaluating an imported rig with authoring one.  What is built is the READ half — import,
	multilinear keyform blend, deformer cascade, GPU deform, oracle-gated against the official core — plus
	parameter CRUD (create/rename/delete/range/link/group/reorder) and structural moves (re-parent a
	deformer, re-home a drawable).  What does NOT exist anywhere in the tree: creating a deformer, binding an
	object to a parameter (adding a keyform axis), and inserting/moving/deleting a keyform.  There is no
	`param.addKeyform` command — CLAUDE.md names one as an example, not as shipped code — and `:edit` has no
	keyform-authoring op at all.  A model can be posed, inspected, and its mesh edited at rest; it cannot yet
	be RIGGED.  This is the largest single gap between Umamo and the thesis, and both candidate next steps
	(deformer gizmos, dope sheet) are attempts to close different halves of it.
7. Re-import (the headline feature). Scaffolded: Reconciler / SourceWatcher / SourceBinding. Identity-keyed
	(LayerId) non-destructive reconcile: a matched layer updates its atlas tile/UVs while mesh/deformers/
	keyforms are preserved; added/removed/renamed layers are flagged and reviewable, never silently deleted.
	May trigger an atlas repack (the invariant above protects it). See § Reimport.
8. CMO3 write-back — DECISION REQUIRED before the exporter is written. See § Read/Write Filing Handling: no
	`PuppetModel` → CMO3 writer exists (save just re-emits the original bytes). Hazard: a decoupled geometry
	edit produces a Cubism default form whose geometry no longer matches its UVs — Cubism renders it displaced
	and re-derives/corrupts the UVs on its next re-atlas. Decide how Umamo's neutral geometry maps onto
	Cubism's default form. This gates the CMO3 round-trip fidelity contract.
9. Native UMA format. See § Format / UMA. The source-agnostic container storing decoupled geometry + UVs +
	source art with stable layer identity — the format that preserves the decoupling CMO3 fights against.
