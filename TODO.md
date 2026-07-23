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
* DONE: Composite `multiplyColor` / `screenColor` are editable via a color picker (the swatch on the shared `HexColorField` now opens an RGB-slider popover, so the Settings color rows get it too), and `maskedBy` has an add/remove relation-list editor (`PartMaskEditor`) in the Isolated-part composite block. Both write through the existing `setPartComposite` (one undo step). Remaining: editing a `Drawable`'s own `maskedBy` (the read-only mask count in Relations) once a drawable mask-list edit op exists; per-key (keyform-grid) tint-color editing (this edits the static fallback only).
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
app/desktop/src/jvmMain/kotlin/org/umamo/editor/desktop/EditorApp.kt
The file picker just writes out the original CMO3 right now as a save test.  Nothing actually converts the PuppetModel into CMO3 format.
* Document State - One document per window instance.
	* Opening the application should start as a fresh new document.
		* Open/Import should ask to save before doing if the new document is dirty.
* Drag and drop file opening.

## Puppet Model, CMO3, MOC3
* Handle opacity inheritance from parts.  Double check deformers as well.  (The Model A "Effects" layer bug: runtime Part has no opacity field yet, so a part-level opacity cascade is not applied.)
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
* The backend seam is now `RenderDevice` (`:render/commonMain/.../device/`), NOT the old `Renderer` interface (deleted). `PuppetRenderer` (commonMain, zero GL calls) drives everything through it; `GlRenderDevice` is the desktop GL 3.3 impl. A backend is one `RenderDevice` impl.
* Android GLES renderer backend = a second `RenderDevice` impl (`GlesRenderDevice`), a near-transliteration of `GlRenderDevice` (same calls, GLES binding style). The one real divergence: GLES 3.0 has no texture buffer, so the glue store (`createDeformedPositionStore`) repacks as a 2D texture (TODO Claude Note § option (b)) — hidden behind `DeformedPositionStore`, so the renderer is untouched. See Claude Notes § Android GLES renderer backend.
* MacOS: a JVM threading/context fix (keeps the whole GL device); iPadOS: a Metal `RenderDevice` impl + MSL shaders. Split, not one line — see docs/plan/portability.md.
	* app/desktop/src/jvmMain/kotlin/org/umamo/editor/desktop/viewport/CglOffscreenGlContext.kt

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

## Android GLES renderer backend (deferred 2026-06-21)

**What.** (2026-07-16: superseded by the RenderDevice seam — see § Render.  The renderer is now the backend-neutral `PuppetRenderer` in commonMain; only `GlRenderDevice` is desktop GL.)  Original note: The shipped renderer was the **desktop GL 3.3 core** impl (LWJGL), in
`:render/jvmMain`. It implements the backend-agnostic `Renderer` interface (`:render/commonMain`). Android
needs a **second `Renderer` impl** in `:render/androidMain` using **GLES 3.0** (`android.opengl.GLES30`)
hosted in a `GLSurfaceView` (vs desktop's `AWTGLCanvas`). Everything backend-neutral — the eval,
`preparePose`, `PoseDeformInputs`, the `Renderer` interface — is already shared in `commonMain`, so this is
"write the GLES impl + wire it into the Android app", **not** a re-architecture.

**Shader portability (GLSL → GLSL ES 3.0).** `DEFORM_GLSL` / the vertex shaders need: `#version 300 es`;
explicit precision (`precision highp float;` + `precision highp int;`, `highp` samplers); `in/out` (already
300-style). `texelFetch` + RG32F sampling for the delta/control-point textures are fine in ES 3.0.

**The one real blocker — glue's texture buffer.** The glue pass uses a **`samplerBuffer` / texture buffer
object** for the random-access partner lookup. TBOs are **core only in GLES 3.2**, not 3.0/3.1. On GLES,
options: (a) `EXT_texture_buffer` if present; (b) repack the shared position buffer as a regular **2D
texture** and index it by `(globalIndex % width, globalIndex / width)` via `texelFetch` (works on ES 3.0);
(c) fall back to CPU glue on Android (the hybrid path). Option (b) is the cleanest portable route and would
also simplify the desktop path.

**Validation.** The GPU-vs-CPU transform-feedback test is desktop-only (GLFW), but the same
`Renderer`-output-vs-`applyCpuDeform` approach applies; an on-device or emulator render-diff is the Android
analogue.

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
4. UV editor. Pending — see § UV Editor / § UV Editing. This is the missing half of decoupling: geometry is
	editable today, UVs are read-only/inherited. Needed to author the mapping, including duplicated/flipped
	regions (eyes, etc.).
5. Mesh editing (rest geometry). Built: object + edit mode, UV-preserving, edits the neutral base that every
	keyform is a delta off. Remaining: topology edits (subdivide / merge / rip) must resize the UV array AND
	every keyform's delta array to the new vertex count — see § Render "remeshing" and § Shortcuts (M / V / J).
6. Rigging. Parameters, deformers, keyforms on top of the rest mesh — the actual deformation authoring.
	Largely built for CMO3-imported models.
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
