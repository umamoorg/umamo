# TODO

## Documentation
* Have a full model/ map made in docs/. (When everything is done.)

## Deferred
* GPU glue: multi-pair seam vertices (deferred 2026-06-21)
* Android GLES renderer backend (deferred 2026-06-21)

## WORK STEPS - 2026-09-14
~~1.) Leftovers - Art Sourcing Pipeline Verification~~
~~2.) Operation Strip - Make sure all application operations are using it.~~
~~3.) Phase C - Art Sourcing Pipeline - Cmo3AtlasUndedup Refit~~
~~4.) Phase H - Art Sourcing Pipeline - CMO3 Interop Hardening~~
5.) Phase D - Art Sourcing Pipeline - Automatic Mesh from Art - This should become it's own planning document.
6.) (Everything required for glue, deformers, and so on.)
7.) UMA Format (Phase G - Art Sourcing Pipeline) - Mostly done, a few final pieces to go through.

## VERY IMPORTANT
* Hire translators for localization.
* Final pass on keyboard shortcuts.
* Final pass on default settings.
* Final pass on default workspace layouts.
* Final pass on theme colors.

## User Stories
* glTF export for easy import into game engines.  (Requested; far future.)
	* Shares buffer/accessor concepts with UMA's bulk-geometry encoding decision (docs/plan/uma-format.md U1), and would slot into the Document › Runtime export-targets model (§ Properties Panel).
* From CrystalorImLisa on Reddit: The ability to mirror deformers and drawables along with their key frames.
	* Umamo solution: Select a deformer and the drawable -> Duplicate -> Mirror X (On the duplicate) -> Do some minor UV clean up -> Done!
	* https://www.reddit.com/r/Live2D/comments/1uy0871/is_there_a_way_to_duplicate_a_warp_deformer/

## Artwork Import
* We need to properly handle different blending mode imports from artwork to setup the drawables automatically.
* Automatic visual matching of imported artwork layers to layers cut from a texture atlas after a MOC3 to CMO3 conversion.

## Puppet Model, CMO3, MOC3
* Parameter Repeat
* Glue
* CMO3
	* ACParameterControllableSource.isVisible was wired up so now the outliner needs this toggle as well.  Deformer editing is not implemented yet so this is minor.
	* Refactor interop/cmo3/ in the same way that interop/moc3/ was done.
* MOC3
	* (Check the bullshit the AI did without my permission.)  CDI3 - Export mesh display names as a separate array.
	* Reconcile isVisible/isEnabled from MOC3 for deformers.  Maybe for CMO3 too.

## Tools, Shortcuts, and Gizmos
* Improvements
	* Unconnected proportional editing should edit all meshes when multiple meshes are selected for edit mode.  I would like to merge the proportional button and falloff settings into one menu with the connected checkbox.
	* ALT+H (Unhide All) and SHIFT+H (Hide everything that is not selected.)
* New Icons (For myself to get/make.)
	* Replace magnet from the cursor/selection menu.
	* The Tabler icons on the toolbar are probably fine, but I will check what is available from the Blender icons.

## Overlays Toggle
* Overlay visibility toggles from viewport header.
	* General Information - The spot in the AreaHeader showing the selected item will be moved here.  It's too much in the AreaHeader.
	* Wireframe (Object Mode)
	* Grid - Ability to change scale and divisions.
	* 2D Cursor

## Object and Mesh Editing
* Improvements
	* Mirror along X/Z axis, mirror with 2D cursor as the axis.  Note: This is a small divergence to Blender's style.  In Blender there is an origin for each object that can be moved to different places.  Umamo still has the centroid origin calculated, but no way to move it or even if it was moved, a way to store it.
	* Extrude(E) - Extrude an edge creates triangle cut quad automatically.

## Texture Authoring/UV Editor
* Follow Selection Header Control - Split it into options and images.
	* New custom image selection control.  This will also be an entry point for adding artwork.
	* Support renaming images.
* Improvements
	* Long running atlas packing should have a progress visible in the status bar.  We can also reuse this for other operations such as file open/import/export.
* Bugs
	* When relinking EricaTamamo.psd in EricaTamamo.cmo3 it results in some layers getting fringe artifacts like what was experienced in the past.

## UV Editor
* Bugs/Improvements
	* Rip and Vertex Slide could be implemented for UV.
		* Do a study to determine if rip functionality is really needed.  It is definitely needed for 3D work, but for 2D work I think it is less useful.  Though I'm curious what people would create with the functionality being available.
	* Mirror UVs are shown in the command palette when editing a mesh in the 2D viewport.
		* This is actually kind of useful, but technically breaks the border of the command palette only showing what is available per area.
	* Pixels outside of the canvas still need to render.
* UV Snap Pie
	* (Deferred) Selected to Adjacent Unselected - Moves selection to adjacent unselected element.
		* Implementation difficulty: This moves the UV vertex that has been disconnected from its sibling, which is one vertex in the mesh, on top of each other.  We will have to either walk the UV/mesh to find the sibling or store it.  Selected to Adjacent Unselected is only needed if rip is supported in UVs.
* Relax/Pinch tools - deferred; needs brush machinery (radius cursor, per-stroke commits) that nothing else has yet.

# Mesh and UV Editor Gizmos
* Improvements
	* Hold CTRL to enable snapping for rotation and grid placement.

## Shortcuts
https://hollisbrown.github.io/blendershortcuts/ - I should make a page like this demonstrating the shortcuts for Umamo.

## Properties Panel
* The document-level **runtime-compatibility target data model** behind Document › Runtime — the enabled export targets (Cubism, Ayagami, …) + each target's options, how it persists on the document, and how it drives CMO3/MOC3 export. Scaffolded as a placeholder section now; its data design is a separate pass (depends on cataloguing each target runtime's capabilities).
* UMA serialization of the latent composite (the format work this unblocks).
* Improvements
	* Parts and deformers still have no editable transform — needs the deformer → part → mesh cascade.
	* Do another pass on the keyed parameter/property highlight colors.  Why does off key filled color appear as grey over green?
	* Transform Position and Size should update the render while scrubbing.
	* Aspect locked properties(Size) should update the other control while one is being scrubbed.
* Single/multiple relation pickers.
	* Improvements
		* Persist list height.(Stored in UMA format, maybe?)
		* Deformer pickAt().
		* Context Menus

## Parameters
* Parameter templates:
	* Need a way to apply these without having to do a fresh import.

## Menus
* Clicking again should close instead of reopen the menu.

## Tooltips
* Consider swapping to BasicTooltipBox in the future to get rip of the desktop and Android split of TooltipArea.  BasicTooltipBox is more recent as of writing this, July 2026, is being actively iterated against.

## Workspace Tabs
* Improvements
	* Visual feedback of the tabs moving out of the way when reordering them.

## Keymap
* Keymappings will need to become area contextual.  For example: P is pin in the UV editor area.  Once the Separate operation(Into multiple drawables) exist, if we want to match Blender's P shortcut for that then it has to become contextual per area.

## Format

### File
* Automatic Backup

### UMA (Native File Format)
* Improvements
	* Preview thumbnail should take a snapshot of the 2D space and not from the part thumbnailer.  The part thumbnail does not respect visbility and so on.
	* Format icon helper - Display thumbnail as the icon with the Umamo logo overlaid.  Requires an installer.

See the roadmap: docs/plan/art-sourcing-pipeline.md § Phase G — the source-agnostic container is designed there.
See format planning document: docs/plan/uma-format.md

## Import
* MOC3 sidecar discovery on Android.  MOC3 might be a desktop only feature.

## Render
* GPU glue: multi-pair seam vertices — latent correctness gap; see Claude Notes § GPU glue: multi-pair seam vertices.

## Glue
* Glue intensity is keyable but has no Properties home(glue is not selectable).  See Claude Notes § Glue intensity has no editable home.

## UI
* Viewport view styles - Top right, in the header area.
* Viewport loading overlay and mouse busy pointer.
* AreaHeader/Viewport2DHeaderControls
	* Font size and icon sizes don't line up resulting in the font being 1px offset.(Lots of manual tweaking is required.)

* Menu - New Items
	* Edit
		* Cut/Copy/Paste

## Theme Colors
* Ability to edit ALL the theme colors (the UmamoColors palette) for a custom look through preferences.  For example, in Blender I make my vertex colors as ff00ec(unselected), ff7a00(selected), and 7de400(active selection) since it is easier for me to see.
	* The color-blind-assist first pass — vertex/edge/face gizmo colors plus the selection highlight — already exists in Settings > Colors.

## Refactor
* module/render/src/commonMain/kotlin/org/umamo/render/puppet/PuppetRenderer.kt

## DRY/Standardization
* Fields like PropertyFieldRow need to take a key use use that to get the correct resource key automatically instead of passing it.
	* For Example: properties_field_base_angle - "base_angle" -> Expanded out to `properties_field_base_angle` and `properties_field_base_angle_description`.
	* There are plenty of places in the code base that are passing the values around like this at the moment.  Difficulty: .* import level maybe?  I need to read up on the Kotlin compiler optimization to determine if this will be an issue.
	* operatorParameterDescriptionRes also is the start of something of what I am thinking, but hardcoded.

## Settings
* Keybinding - input.keybinding (Includes keyboard, mouse, and pen buttons.)
* Pen Binding (JPen, Wacom) - input.pen (Includes pen, pressure, and things related to the radial menu.)
* Quick Setup
	* Versioned Settings
	* Import from Previous
* The settings UI needs a design pass since it is basically just squares and whatever thrown together right now.
* New Settings
	* Setting to make Left Click, instead of ALT+Left Click, the default to activate the popup overlap picker.

## Keybindings
* Audit default keybinding maps for Blender and Cubism styles.

## Future Feature Wishes
* Pose Reference - A poseable and adjustable 3D mannequin model for overlay reference.
* Really good edge detection for auto-mesh.
* Normal map, emission, metallic, and reflection shaders for texturing.
* Key/mouse/pen input overlay for recording/streaming.
* History playback for proof of work.  The history system is there, but that is a lot of track over a long session.  So capture a snapshot every time period or number of snapshots.
* A proper bone skeleton system with bendy bones.

## Build and Distribute
* Eventually get installers, signing, and automatic updates setup.

## MacOS
* Zoom with the touchpad on my 2014 Macbook Pro is glitchy.  It will jump around and even go the wrong direction.
* Need to add back a light native menu so it does not say "MainKt" all the time.

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

## Status Bar
* The first iteration to improve the status bar hints was a good success.  Eventually:
	* Hint icons (Mouse button indicator, etc.)
	* Better contextual hints: Swap out anything that is irrelevant when selecting for example and just show selection relevant shortcuts.

## Pose Palette/Library
* Cubism 5.4 added a "Model state set" which is just a pose library.  The data is saved into the CMO3 file.  This should be easy to implement and store in the native UMA format.

## Color Space and Management
Right now the goal is to support sRGB from ingest to output with full correctness.  Eventually we want to be able to add linear color space, HDR, and so on.  While vtubers typically are 100% sRGB(art, edit, broadcast) some game developers may opt for different color spaces and require that flexibility.  I'm tentatively planning this work for Q1 2027.


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

## Deformer keeps no rest geometry once its last axis is collapsed (found 2026-09-04)

**What.** `withAxisCollapsed` returns null when the last axis goes, and `withParameterDeleted` stores that
null as a warp's or rotation's `geometryGrid` - so the deformer's lattice / pivot, which lived only in that
grid, is gone from the model.  The CMO3 export then has nothing to write for it and removes the source's
`keyformGridSource`; the official editor refuses a source without a default keyform (`setKeyformGridSource`
rejects null, `getDefaultKeyForm` throws "no KeyForms" - the same refusal that crashed it on the first
artwork-origin export, fixed for drawables/parts/glue by writing one default cell in
`Cmo3KeyformLowering.buildBundle`).

**Why it's fine right now.** Nothing in the editor deletes a parameter that keys a deformer's geometry
except the parameter-delete flow, and the structure round-trip gate only reaches the case by choosing a
victim parameter that keys deformer geometry.

**Fix sketch.** Keep the rest cell: a last-axis collapse should yield an axis-less one-cell grid holding the
form at the kept key (the deformer's rest lattice / pivot), not null - for drawables too, since the export
then writes the same shape the corpus does.  Once that holds, `writeGridWeb`'s empty-bundle branch becomes
unreachable and can go.

## Glue intensity has no editable home (deferred 2026-07-29)

**What.** `Glue.intensity` is a real keyable channel — `FormChannel.GLUE_INTENSITY`, with a static on
`Glue` and a track in `channelGrids` — and the keyform sheet already renders a row per glue and can move,
insert and delete its keys. What it has nowhere is a **Properties** home: there is no `SelectionTarget.Glue`,
no outliner entry, and no property section, so intensity cannot be typed, scrubbed, or keyed with `I` the
way every other channel now can. `:edit` also has no `withGlueIntensity` / `setGlueIntensity`; the only
writes are whole-object reconstructs inside the keyform and topology ops.

**Why it's fine right now.** The channel is reachable where it matters most — the sheet — and intensity is
the least-touched of the keyable channels (a weld is usually 1.0 and left alone). Nothing is silently
broken: an unexposed channel simply cannot be edited, rather than being editable and wrong.

**What giving it a home costs.** Four things, none of them local:
1. A new `SelectionTarget.Glue`, which touches selection, the outliner, the relation-pick system, and every
	exhaustive `when` over selection targets.
2. A stable identity story. A glue has **no id** — `KeyformOwner.Glue` addresses it by the `(meshA, meshB)`
	pair precisely because a list ordinal is not stable across edits (`KeyableTarget.kt`).
3. `Glue` is a plain `class`, not a `data class`, so it has no `copy()`; every rewrite reconstructs the full
	constructor. A `withGlueIntensity` must do the same.
4. An outliner presence raises a design question that has never been answered: where does a glue *sit* in a
	tree organised by parts and deformers, when it belongs to neither and welds two meshes that may be far
	apart in it?

**Fix sketch (when/if it bites).** The cheap half is `withGlueIntensity` / `setGlueIntensity` plus a
`GlueChange`, following the standard three-file property-op pattern — that alone would let the sheet's
context menu and `I` write the static. The expensive half is selectability, and is worth deferring until
there is a second reason to want it.