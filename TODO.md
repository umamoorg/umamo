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
* glTF export for easy import into game engines.  (Requested; far future.)
	* Shares buffer/accessor concepts with UMA's bulk-geometry encoding decision (docs/plan/uma-format.md U1), and would slot into the Document › Runtime export-targets model (§ Properties Panel).
* From CrystalorImLisa on Reddit: The ability to mirror deformers and drawables along with their key frames.
	* Umamo solution: Select a deformer and the drawable -> Duplicate -> Mirror X (On the duplicate) -> Do some minor UV clean up -> Done!
	* https://www.reddit.com/r/Live2D/comments/1uy0871/is_there_a_way_to_duplicate_a_warp_deformer/

## World Origin
I should fix the naming so that origin is X and Z in the code.  Z up, Y forward.

## Artwork Import
* We need to properly handle different blending mode imports from artwork to setup the drawables automatically.

## Read/Write Filing Handling
* Clean up the boolean logic mess in AppMenu->fileMenu().
	* module/ui/src/commonMain/kotlin/org/umamo/ui/menu/AppMenu.kt
* Document State - One document per window instance.
	* Opening the application should start as a fresh new document.
* Drag and drop file opening.

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

## Context Issues
* If I search in an area header filter and then for example, click in the keyform sheet to scrubb, the focus is never removed from the input.  This results in confusion as to why undo/redo and other commands suddenly don't work.

## Shortcuts
https://hollisbrown.github.io/blendershortcuts/ - I should make a page like this demonstrating the shortcuts for Umamo.

## Properties Panel
* The document-level **runtime-compatibility target data model** behind Document › Runtime — the enabled export targets (Cubism, Ayagami, …) + each target's options, how it persists on the document, and how it drives CMO3/MOC3 export. Scaffolded as a placeholder section now; its data design is a separate pass (depends on cataloguing each target runtime's capabilities).
* UMA serialization of the latent composite (the format work this unblocks).
* Improvements
	* Parts and deformers still have no editable transform — needs the deformer → part → mesh cascade.
	* Do another pass on the keyed parameter/property highlight colors.  Why does off key filled color appear as grey over green?
* Single/multiple relation pickers.
	* Improvements
		* Persist list height.(Stored in UMA format, maybe?)
		* Deformer pickAt().
		* Context Menus

## Parameters
* Improvements
	* Search in header.

## Workspace
* Menu: Add Previous/Next workspace to the main menu.

## Button UI
* Needs a click action, either a background color change or movement.

## Tooltips
* Consider swapping to BasicTooltipBox in the future to get rip of the desktop and Android split of TooltipArea.  BasicTooltipBox is more recent as of writing this, July 2026, is being actively iterated against.
* Anywhere that we are using semantics/contentDescription we need to have a Tooltip as well.

## DRY
* ClickGestures - singleOrDoubleClick - We might be able to reuse this in other areas that experience the same issue.(WorkspaceTabs, OutlinerSpace)

## Format

### File
* Automatic Backup

### UMA (Native File Format)
See the roadmap: docs/plan/art-sourcing-pipeline.md § Phase G — the source-agnostic container is designed there.
See format planning document: docs/plan/uma-format.md

Format Goals:
* Forwards compatible - Newer editors should be able to open older version files and upgrade as necessary.
* Best effort backwards compatible - Older editors should be able to open newer version files and not crash on unknown data.
* Extensible - Eventually physics and animation will make it into the format.  Both of those not part of the base puppet model, but features that interacts with it driving parameters and properties.
* Has to store atlas textures, individual layer textures.  They are all just textures.  Possibly having an "isAtlas" flag would only be data reference purposes and not treating the underlying image differently.
* Data structures should be marked with file version and/or sub-versions.
* Storage of editor state: Collapsed/expanded sections in different panels, tracking visibility, etc.

## Import
Initial import and setup of art into a puppet.  Realistically, editor controls need to exist first.  There are test CMO3 files to work with to get editor controls going.
* MOC3 sidecar discovery on Android.  MOC3 might be a desktop only feature.

## Reimport
* Detection of edited source art files when application reacquires focus.
* (Might not be appropriate for the reimport module, but has to be reusable across every platform.) Detection of the user trying to change the source art file format(PSD -> KRA) should warn that it is destructive since layer matching heuristics are not perfect and could result in orphaned layer data.  Later on having a dialog to manually remap these layers would be nice.  A dailog for manually remapping will be needed eventually for when layer matching heuristics file even when reimporting the same source art file format.

## Render
* GPU glue: multi-pair seam vertices — latent correctness gap; see Claude Notes § GPU glue: multi-pair seam vertices.

## Glue
* Glue intensity is keyable but has no Properties home (glue is not selectable) — see Claude Notes § Glue intensity has no editable home.

## Outliner
* After searching for something and clicking on it, when clearing the search it should resolve that tree branch to be open.  Having it return to be closed with the mystery item selected somewhere in the depths is a poor experience.
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
* Audit default keybinding maps for Blender and Cubism styles.

## Random Bugs
* While resizing the application window pressing escape to cancel the resize snaps the window back, but the Compose area does not.

## Storage

## Future Feature Wishes
* Pose Reference - A poseable and adjustable 3D mannequin model for overlay reference.
* Really good edge detection for auto-mesh.
* Normal map, emission, metallic, and reflection shaders for texturing.
* Key/mouse/pen input overlay for recording/streaming.
* History playback for proof of work.  The history system is there, but that is a lot of track over a long session.  So capture a snapshot every time period or number of snapshots.

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
* Improvements
	* Now that the hovered area is tracked everywhere we can filter by what commands are available per area.

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
5. Mesh editing (rest geometry). Built: object + edit mode, UV-preserving, edits the neutral base that every
	keyform is a delta off. Remaining: topology edits (subdivide / merge / rip) must resize the UV array AND
	every keyform's delta array to the new vertex count — see § Render "remeshing" and § Shortcuts (M / V / J).
6. Rigging. Parameters, deformers, keyforms on top of the rest mesh — the actual deformation authoring.
7. Re-import (the headline feature). Scaffolded: Reconciler / SourceWatcher / SourceBinding. Identity-keyed
	(LayerId) non-destructive reconcile: a matched layer updates its atlas tile/UVs while mesh/deformers/
	keyforms are preserved; added/removed/renamed layers are flagged and reviewable, never silently deleted.
	May trigger an atlas repack (the invariant above protects it). See § Reimport.
9. Native UMA format. See § Format / UMA. The source-agnostic container storing decoupled geometry + UVs +
	source art with stable layer identity — the format that preserves the decoupling CMO3 fights against.

## MOC3 export: section 160 keys the runtime's offscreen walk (found 2026-08-04)

**What.** Section 160 - not 155 (owner) and not 152 - is what drives offscreen evaluation in the official
core.  Proven by patching an exported file: blank 160 and every offscreen stays at opacity 0 / multiply
0,0,0; fill it for every part and all 24 evaluate.  152 is not that switch (blanking it changes values but
evaluates everything).

**Why that is a problem.** 152 and 160 are byte-identical in every corpus file EXCEPT modelA, where they
name different parts for 13 of 24 offscreens and 160 carries a 25th entry (`PartHairBackOut`) that owns no
offscreen at all.  152 is the exact inverse of the owner column; 160 is keyed by whichever part the
runtime's own walk reaches, and that key is not reconstructible from the corpus yet.  The export writes the
owner-consistent inverse into both, which is right for every model whose part numbering survives the export
unchanged - and on modelA leaves 12 of 24 offscreens unvisited, so the runtime never evaluates them.

**Where it is pinned.** `Moc3ExportOracleTest.offscreenChannelGap` (structure still compared, channels
exempt for modelA only) and `OffscreenSectionAliasProbeTest` (asserts the two columns DO diverge, so the
"alias" reading cannot be re-derived from the small samples a fourth time).

**Leads.** The 160-named parts look like sub-group parts of the render tree rather than offscreen owners,
and `pipeline.c`'s render-index rule gives a sub-group part owning an offscreen its own render slot - so
160 is plausibly "part → offscreen render slot" over the render walk.  Ordering the exported offscreens by
render traversal instead of owner index was tried and made it worse (26 → 31 divergences), so the fix is in
the MAP, not the order.  A v6 corpus model with many offscreens and an unedited part order would settle it.

## MOC3 export: the strip follows the SECTION table, not the target ladder (found 2026-08-04)

**What.** `RuntimeTarget.supports` gates EDITING, and it follows the official editor's target dialog -
which places the reversed mask at 4.0 and the parameter repeat at 5.3.  Both are carried by every moc
version back to v1 (a constant-flag bit and section 54).  A version downgrade that stripped on that
ladder made a v5 file with repeating parameters round-trip LOSSILY through a v5 export; LimeBirb caught
it on the first run.  `Moc3VersionDowngrade` therefore asks `Section.indexIn(version)` instead, and
`RuntimeTarget`'s own docblock already said this is how it should be ("the section-level nuances belong
to export lowering").

**Consequence to keep in mind.** The pre-export confirmation reads `unsupportedFeaturesInUse`, which
still uses the editor ladder, so it can name a feature the export does not actually strip.  That is the
conservative direction and it is pinned in `Moc3VersionDowngradeTest`; if the confirmation ever needs to
be exact, give it the same section-derived predicate rather than moving the strip onto the editor's.

**Also fixed here.** A color TRACK of pure identity cells no longer counts as "uses color".  A moc
import fans every channel out of one bundled grid, and compaction deliberately leaves the track alone
when its axis does not bracket the parameter's range (modelE's ArtMesh120) - so a v3 model with no
color data at all was reporting that the export stripped its multiply and screen color.

## MOC3 export writes a FAMILY, and the picker only picks one file (found 2026-08-04)

**What.** `file.exportMoc3` writes the moc through the picker's own handle and every other family
member (`model3.json`, `cdi3.json`, the atlas pages, and every retained sidecar) beside it with okio,
creating subdirectories the source layout used (`motion/`).  That mirrors the import, which finds the
same family by reading siblings of the picked moc - and inherits the same limit: an Android SAF
`content://` handle has no resolvable parent, so on Android the moc lands alone and the log says so.
A directory picker in `:storage`'s `FilePicker` would fix both ends at once.

**cdi3 carries art-mesh names now** (`Cdi3Json.drawables`, an Umamo extension the editor ignores).  It is
the only thing that can: a moc stores art-mesh IDs and no names at all, so without it every drawable
name a rigger typed dies on the first MOC3 round trip.

**Two things the export needs that the model does not hold.**  A CMO3-origin document has no atlas
page index on its drawables (its pixels are embedded per drawable), so the page binding is taken from
the decoded texture set at export time (`withTexturePagesFrom`).  And an UNKEYED drawable under a
deformer stores its rest mesh in canvas space, which needs `:render`'s warp inverse to write - injected
as the `CanvasToParentSpace` seam, since `:interop` is `:render`'s sibling, not its dependent.  The
warp inverse must be seeded at the LATTICE CENTRE, not at the canvas position: seeding it with the
canvas value put Newton hundreds of units outside the [0,1] lattice and it came back off by 1.8e7.

**One unexplained test flake, recorded rather than chased.**  `Cmo3TargetVersionWriteTest.
sameValueSetKeepsMainXmlByteIdentical` failed once (one byte, `' '` vs `'8'`, at offset 6780293 of
Erica's main.xml) during a six-module concurrent run, and has not reproduced in seven subsequent runs
(isolated, whole-:format twice, six-module twice, plus four repeats of the CMO3 classes).  If it
returns, suspect nondeterministic iteration order in the CMO3 writer's slot replay rather than
anything in the MOC3 work - the byte-identity claim is only as strong as that ordering.

## MOC3 export wrote every CMO3-origin model at pixel scale (found 2026-08-05)

**What.** A moc's `pixelsPerUnit` is a BAKE parameter, not a project property.  Every corpus `.cmo3`
stores `CModelInfo.pixelsPerUnit = 1` - a CMO3 works in canvas pixels - so the export was writing 1,
and the whole rig went out at pixel scale, hundreds of times too large for any runtime.  It loaded, it
self-round-tripped, and every gate stayed green: only comparing against the editor's own bake showed it.
`Moc3Export.mocPixelsPerUnitFor` now defaults to the canvas WIDTH (the export dialog's own default,
which 21 of 25 corpus bakes use exactly) and keeps a MOC3-origin model's own scale.  A rigger who chose
a different bake scale cannot have it recovered from the project - that wants an export option, like the
hidden-object toggle.

**How it showed up.** `Cmo3ToMoc3OracleTest` reported 0 of 1046 drawables within the oracle's geometry
tolerance.  After the fix: 935 (89%), spread evenly across models, which is the authoring skew between
a project and its bake.  The floor is pinned at 85%.

**Sketch parts are dropped now**, as the editor's bake drops them - a guide image is a tracing
reference, not runtime content, and the whole subtree goes.  That plus the hidden-object rule (we KEEP
hidden objects; the editor deletes them unless the export option is ticked) accounts for every
structural difference between our lowering and the editor's bake across 21 twins - the remaining count
is zero.

**modelE's twin pair is not the same revision.**  Its `.cmo3` and `.moc3` disagree about 44 of 178 art
meshes, the parameter list, and the deformer tree - the moc was baked from a different edit.  Both
cross-format gates detect that structurally and skip the pair rather than naming it, so a future corpus
change is handled the same way; the count of skipped pairs is printed and asserted to stay a minority.

**Docs.** `MOC3.md` gained §5.7 (all 167 slots, per-version, generated from `SectionLayout.kt` - the
enum is the authority and the table follows it), §8 Export, and §9 stating the three ways this document
relates to the third-party spec (where it corrects us, where we resolve its unknowns, where we are ahead
and must not be "corrected" toward it).  `Hierarchies.md` §3's four stale claims are fixed: deformers DO
carry ids (s11), section 15 IS the deformer→part link in every version, `inferDeformerParts` is a
fallback rather than the path, and only the GLUE→part link is genuinely absent.
