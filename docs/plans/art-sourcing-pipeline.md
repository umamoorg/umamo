# Art Sourcing Pipeline — Design Roadmap

Status: roadmap (2026-07-12).  This is a multi-session design backbone, not a single implementation plan.  Each numbered phase below is sized to become its own planning session.  It supersedes and expands the "Art-first pipeline" Claude Note in [TODO.md](../../TODO.md) (the 9-step list at `## Art-first pipeline`), which stays as the terse status tracker; this document is the reasoning and the decision record behind it.

## Purpose

Decide how Umamo sources, meshes, packs, stores, and refreshes artwork — end to end — so that the decisions here can drive three dependent designs that are all still open: the native UMA format, the artwork import / re-import flow, and UV / texture file handling.  The immediate concrete deliverable the caller named is a cross-platform raster image codec family (Phase A); the rest of the document places that piece in the pipeline it belongs to so a future session picks it up with the whole shape in view.

## The thesis

Umamo owns the whole pipeline from source artwork to packed texture atlas, and that is the
differentiator.

- Cubism started PSD-only and later bolted on flat-PNG import without breaking backwards compatibility, so its entire artwork model is still shaped like a PSD (`CLayeredImage.psdFile`, `psdBytes`, `psdFileLastModified` — see [CMO3.md §4](../formats/CMO3.md)).  We keep the interop but refuse to inherit the everything-is-a-PSD framing: sources are peer formats, none privileged.
- Blender does not own this pipeline: it expects the packed texture atlas to be authored outside Blender and reconciled with manual UV unwrapping.  We do the opposite.
- The 2D source art gives us, per layer, the opaque silhouette of that piece before packing — for free.  That single fact means Umamo can generate the initial mesh over each layer, pack those layers into an atlas itself, and derive the UVs from the packing, with no manual unwrap step.  The same alpha-shape analysis feeds both the mesh and the pack.

This is the capability Blender lacks and Cubism half-owns behind a legacy PSD model.  The roadmap is the plan to build it cleanly while staying a drop-in CMO3 interop partner.

## Invariants this roadmap must not break

These are locked in [CLAUDE.md](../../.claude/CLAUDE.md) and the TODO Art-first note.  Every phase is checked against them.

1. Mesh geometry and UVs are decoupled (Blender-style).  Editing geometry never re-derives UVs the way Cubism does at the default form.  Already true in code (`withMeshPositions` shares the uvs array by reference).
2. UVs bind to source-art pixel space via a stable layer identity; the atlas is a repackable indirection layer, never a source of truth.  Repacking moves where pixels sit in the page, never the vertex→art-pixel mapping.
3. The native format is source-agnostic.  PSD, CLIP, KRA are peer sources, each keeping its own stable layer identity (`lyid` / CLIP uuid / KRA uuid).  Do not shim CLIP or KRA down to a PSD shape on import — that throws away the stable IDs that make re-import reliable.
4. CMO3 is an interop boundary, not the native format — unwrapped on import, re-synthesized on export.  CMO3 round-trip fidelity is a hard gate (semantic validity, reopen in the official editor with no loss).
5. Binary formats implement the shared `FormatCodec<TModel>` contract dispatched through `FormatRegistry`; JSON sidecars stay `String`-shaped helpers, outside the byte contract.
6. Neutral source pixels are RGBA8888, straight (non-premultiplied) alpha, top row first.  Higher bit depths and HDR are down-converted (lossy by the model's contract).

## Where we are today (the starting line)

Verified against the tree on 2026-07-12.

Exists and works:

- The codec seam: `FormatCodec<TModel>` / `ReadOnlyCodec` / `ArtReader : ReadOnlyCodec<SourceArt>` in
	`:format` commonMain, with `FormatRegistry` (detect-by-magic, resolve-by-extension) in
	jvmAndroidMain.
- The neutral ingest model: `SourceArt` / `SourceGroup` / `SourceLayer` / `LayerRaster` / `LayerId` /
	`LayerBounds` / `LayerBlend` in `module/format/src/commonMain/.../art/SourceArt.kt`.
- Three layered readers producing full `SourceArt` including decoded straight-alpha RGBA pixels: PSD
	(pure-Kotlin TwelveMonkeys port, `java.nio` + `java.util.zip`), CLIP (CSFCHUNK container + embedded
	SQLite + zlib tiles), KRA (ZIP + `maindoc.xml` via JDOM + pure-Kotlin LZF tiles).  All three live
	in jvmAndroidMain, are registered, and are tested.
- CMO3 → `PuppetModel` import end to end, with atlas textures.  UVs are read verbatim from the CMO3
	art meshes; the atlas page(s) are extracted whole from the imported CMO3
	(`extractPuppetTextures` + `Cmo3Model.extractLayerPng` + `decodePngToRgba`) and uploaded to GL.
- CMO3 read + write and MOC3 read + write (byte-exact for an unedited MOC3); JSON family as text
	helpers on `Moc3`.
- The UV editor (2026-07): edits existing UVs over an existing atlas page in texel space (v-axis
	flipped, since stored v=0 is the top atlas row).

Absent — this is the work the roadmap is about:

- No pure-common raster image decoder of any kind.  Standalone image decode is one PNG-only `decodePngToRgba` expect/actual in `:render` routing to `javax.imageio.ImageIO` (JVM) and `android.graphics.BitmapFactory` (Android).  There is no image encoder anywhere except a debug `GlPuppetRenderer.dumpPng`.  BMP, WebP, JPEG, JPEG 2000, and TIFF appear nowhere in the codebase.
- No atlas packer.  No bin-packing / rect-placement / atlas-authoring code exists; the atlas is only ever consumed pre-packed from a CMO3.  `PuppetTextures` stores only a page index per drawable — the region rectangle is implicit in the mesh UVs, so there is no data model for a Umamo-built placement.
- No auto-mesh (mesh from a layer's opaque region).
- No `SourceArt → PuppetModel` bridge (no `fromPsd` / `fromLayered`).  Opening a PSD/CLIP/KRA is blocked in the UI (`Document.kt` returns `NotOpenable`; the picker only offers `.cmo3`).
- `:reimport` is a contracts-only skeleton: `Reconciler` / `LayerMatcher` / `SourceWatcher` are interfaces with no implementations, `SourceBinding` / `LayerKey` are never persisted or resolved, there are two disconnected identity models (the readers emit `LayerId(String)`, reimport declares a typed `LayerKey`) with no bridge, no callers, and no tests.  `Drawable` carries no source-layer field.
- No `PuppetModel → CMO3` writer.  Saving a CMO3 currently re-emits the original bytes.
- The UMA on-disk format is undesigned — the `### UMA (Native File Format)` heading in TODO.md is empty; the container is only "tentatively ZIP/TAR".

## The pipeline, as layers

```
    SOURCE FILES                        (A) Raster Codec         (B) Layered Readers
    ┌────────────────────────────┐      PNG BMP WebP JPEG        PSD  CLIP  KRA
    │ flat rasters + layered art │────► TIFF ───────┐            (already produce
    └────────────────────────────┘                  │            SourceArt today)
                                                    ▼
                                    ┌──────────────────────────────┐
                                    │  SourceArt  (Neutral Model)  │  Layers + Bounds + RGBA + Stable ID
                                    └───────────────┬──────────────┘
                                                    │
                          ┌─────────────────────────┼──────────────────────────┐
                          ▼                         ▼                          ▼
					(c) Alpha-shape Analysis   (d) Auto-mesh per layer   (e) Atlas packer
					Trimmed Bounds / Contour   Mesh Over Opaque Region   Layer Rasters → Page(s)
                          │                         │                    Placement Rects → UVs
                          └─────────────┬───────────┴──────────┬───────────────┘
                                        ▼                      ▼
                             ┌───────────────────┐   ┌───────────────────┐
                             │ SourceArt→Puppet  │   │ PuppetTextures    │  Page Index + REGION Rect
                             │ Bridge + Import   │──►│ (Packer Output)   │  (New: Placement Data Model)
                             └─────────┬─────────┘   └───────────────────┘
                                       │
                   ┌───────────────────┼─────────────────────────┐
                   ▼                   ▼                         ▼
    (f) reimport reconcile    (g) UMA native format    (h) CMO3 interop
    stable-id diff + repack   ZIP container, model     import: attach source bindings
    holding invariant #2      JSON + source + atlas    export: synthesize CLayeredImage /
                              + UI view state          CModelImage / atlas + the
                                                       default-form reconciliation
```

The lettered nodes are the phases below.  The dependency edges are the sequencing constraint: `(a)`
is a leaf with no dependencies and is the named near-term deliverable; the bridge `(e→"SourceArt→
Puppet")` is the first point where a non-CMO3 file becomes an editable rig, and it depends on `c`,
`d`, and `e`.

---

## Phase A — Raster image codec family

Goal: a platform-agnostic image codec layer for PNG, BMP, WebP, JPEG, and TIFF, so Umamo can (1) import flat rasters as source art, (2) decode the CMO3 atlas the same way on desktop and Android, and (3) encode the atlas / thumbnails it will generate in later phases.  This is the leaf of the graph — nothing depends on the rest of the pipeline — so it can start immediately and in parallel with everything.

Independent of the rest of the roadmap, but it unblocks: Phase E (flat-raster source import), Phase G (atlas + thumbnail encoding into UMA), and cleans up the `:render` PNG-decode seam that Phase H already relies on.

### The seam and where it lives

Model image I/O in `:format` (the module owns "art I/O" per the module graph), not in `:render`.
Define a raster codec that produces / consumes RGBA pixels, distinct from the existing layered
`ArtReader`:

- `ArtReader : ReadOnlyCodec<SourceArt>` (the existing name) is for layered art — it returns a layer tree.  Do not overload it.
- A new `RasterCodec : FormatCodec<RasterImage>` (name to ratify) is the flat image codec — bytes ↔ `RasterImage(width, height, rgba)` (RGBA8888, straight alpha, top row first — invariant #6).  Read and, where the format supports it, write.
- A flat raster imported as source art is a thin adapter: decode via `RasterCodec`, wrap the single decoded image in a one-layer `SourceArt` (root group wrapping one layer), exactly as Cubism collapses a non-PSD import.  That keeps flat rasters on the same `SourceArt` path as PSD/CLIP/KRA without a second import mechanism.
- Replace `:render`'s `decodePngToRgba` expect/actual with a call into the `RasterCodec` PNG path. Decision to resolve in this phase: `:render` commonMain does not currently depend on `:format` commonMain — either add that dependency (image I/O is legitimately shared) or keep a thin decode seam in `:render` that delegates.  Recommend adding the dependency; it removes an entire platform-split and the debug `dumpPng` can move onto the encoder too.

### Format matrix and priority

Recommended read/write coverage and ordering.  "Pure Kotlin" means a commonMain decoder with no host image library, matching how PSD/CLIP/KRA already work — the only way to guarantee byte-identical decode on desktop and Android.

| Format    | Read | Write          | Priority    | Approach                                                                                                                                                                               |
| --------- | ---- | -------------- | ----------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| PNG       | yes  | yes            | 1           | Pure Kotlin. DEFLATE via `java.util.zip` (or okio), filter types 0–4, Adam7 read. The atlas / thumbnail / CMO3-atlas workhorse; replaces `decodePngToRgba`.                            |
| BMP       | yes  | yes            | 2           | Pure Kotlin, trivial (uncompressed BGRA/RGB).  Cheap, and a good debug-dump + round-trip test vehicle.                                                                                 |
| JPEG      | yes  | no             | 2           | Baseline decode (Huffman + IDCT + YCbCr→RGB).  Port from TwelveMonkeys imageio-jpeg.  Lossy → never used for the atlas.                                                                |
| WebP      | yes  | no             | 3           | Lossless VP8L read + write first (a lossless-WebP atlas is smaller than PNG); lossy VP8 read deferred. Pure-Kotlin VP8L is substantial.                                                |
| TIFF      | yes  | no             | 3           | Port from TwelveMonkeys imageio-tiff; scope to uncompressed / LZW / DEFLATE / PackBits strips first.  Some scan / archival pipelines.                                                  |

The caller's guidance — "read or write will not necessarily be needed in all directions for each format, but it is better to build them now" — maps to: build the `RasterCodec` seam and the tractable formats (PNG, BMP) now, wire the heavy ones (JPEG, WebP, TIFF, JP2) behind the same seam and fill them in as capacity allows.  Register each in `FormatRegistry` as it lands.

### Session scope

The `RasterCodec` seam + `RasterImage` type + PNG (read/write) + BMP (read/write) + the flat-raster→ `SourceArt` adapter + retiring `decodePngToRgba` is a clean first session.  JPEG / WebP / TIFF are each a follow-up session (or a batch), gated on the cross-cutting pure-vs-platform decision below.

---

## Phase B — Alpha-shape analysis

Goal: per source layer, compute the opaque-region description that both the auto-mesh and the packer consume — tight (alpha-trimmed) bounds, a coverage/occupancy summary, and (stretch) a simplified alpha contour polygon.  This is a small shared foundation extracted so Phases C and D do not each re-derive it.

Depends on: the source readers (exist).  Feeds: Phase C (pack rects), Phase D (mesh silhouette).

Decisions: alpha threshold for "opaque"; whether the contour is a real boundary trace (marching-squares style) or just the trimmed rect for v1; how to treat fully-transparent and 1-pixel-sliver layers (exclude, matching the empty-UV mesh exclusion already in the UV editor).

Session scope: small; could be folded into the front of Phase C or D rather than run alone.

---

## Phase C — Atlas packer

Goal: take the per-layer opaque rasters and arrange them into one or more packed pages, producing a placement (page index, rect, rotation) per layer, and encode each page via Phase A.  This is the capability Blender lacks and today's Umamo lacks entirely.

Depends on: Phase A (encode/decode the page), Phase B (trimmed rects).  Introduces a placement data model — `PuppetTextures` today stores only a page index per drawable, with the region implicit in the UVs.  The packer must record the region rect explicitly (this is the authoritative source→atlas indirection, invariant #2), and Phase E derives UVs from it.

Key design decisions (each worth pinning in the Phase-C session):

- Packing algorithm.  Recommend MaxRects (Jylänki) or a skyline/shelf variant, with alpha-trimmed axis-aligned rects and optional 90° rotation.  Tighter polygon / convex-hull packing is a stretch.
	- Jukka Jylänki paper: https://raw.githubusercontent.com/rougier/freetype-gl/master/doc/RectangleBinPack.pdf (For document reference only.  AI: Don't download this, it's very long.)
- Padding / bleed.  Tiles need an edge gutter to stop neighbor bleed under bilinear filtering — the renderer already forgoes mipmaps for this reason, but bilinear still samples the gutter.  Pick a gutter width and an extrusion (edge-clamp vs transparent).
- Page size and count policy.  Cubism allows any number of pages, from 32² to 16384² in size.  The example corpus uses one 8192² page.  Our baseline is GL 3.3 / GLES 3.0; GLES 3.0 only guarantees `GL_MAX_TEXTURE_SIZE ≥ 2048`, so Android tablets may cap well below 8192.  The packer must take a configurable max page size and spill to multiple pages, and the renderer / `PuppetTextures` already generalize to N pages.
- Determinism.  Repacking must be stable enough that an unchanged model repacks identically (fidelity signal), and a re-import repacks with minimal churn (Phase F).

Session scope: a full session on its own.  The placement data-model change to `PuppetTextures`/`Drawable` is part of it.

---

## Phase D — Auto-mesh from art

Goal: generate an initial editable triangle mesh over each layer's opaque region — "mesh from art".  At birth, a drawable's positions and UVs are two views of the same layout; they only diverge once the mesh is edited (invariant #1).

Depends on: Phase B.  Reuses the topology infrastructure built in the tools work (`withMeshTopologyEdit`, which already rebuilds keyform delta arrays and remaps glue to a new vertex count — see [tools-and-shortcuts.md](tools-and-shortcuts.md) Phase 7).

Decisions: mesh strategy — bounding quad (Cubism's trivial default), alpha-contour + constrained Delaunay interior (Cubism's automatic generation), or an alpha-clipped grid; density controls; pen-first authoring affordances later.
Human note: I'm not sure where the AI inferred the bounding quad and Delaunay information.  It is not in any Cubism documentation, but there is a reference to useDelaunayTriangulation on GEditableMesh2 in the XML of the CMO3 file format.  Either way, lets do what is best for Umamo.

Session scope: a full session; the mesh-generation strategy alone is a meaningful design choice.

---

## Phase E — SourceArt → PuppetModel bridge and import wiring

Goal: the first point where a non-CMO3 file becomes an editable rig.  Turn a `SourceArt` (layered or flat) into a `PuppetModel`: auto-mesh each layer (D), pack the layers (C), derive UVs from the placement, place each drawable by its layer bounds, and attach a source binding (the stable `LayerId`) to each drawable.  Then unblock the UI import path (`Document.kt` and the file picker currently reject everything but CMO3).

Depends on: Phase C, Phase D.  Closes the "no `fromPsd`/`fromLayered`" gap and the "non-CMO3 import blocked" gap.

Decisions: how the source binding is stored on the model — `Drawable` needs a new source-layer field, and it must bridge the two identity models that exist today (reader `LayerId(String)` vs reimport typed `LayerKey`).  Recommend collapsing to one identity representation here so Phase F does not inherit the split.  Also: the display-name-from-layer-name affordance the mockup calls for (the parts tree speaks `Head` / `Ear R`, not `ArtMesh32`).

Session scope: a full session; the identity-model unification is the subtle part.

---

## Phase F — Re-import reconcile

Goal: implement the headline feature.  On a watched-file change, reconcile the re-read `SourceArt` against the model's source bindings: match by stable `LayerId`, classify (matched / moved / resized / added / removed / renamed), and for a matched layer update its atlas tile and UVs while preserving mesh / deformers / keyforms where topology still fits — never silently deleting rig work.  Matched changes may trigger an atlas repack, protected by invariant #2.

Depends on: Phase E (persisted source bindings + the packer).  Turns the `:reimport` skeleton into a working engine: implement `Reconciler`, `LayerMatcher` (fuzzy rename), and `SourceWatcher` (desktop `WatchService` / Android `FileObserver`), and add the reconcile review UI (diff / merge step).

Decisions: the reconcile-result model already declares matched / added / needs-review but folds removed and renamed into needs-review — decide whether moved/resized deserve first-class cases; the cross-format-change warning (PSD→KRA is destructive, per TODO § Reimport) and the eventual manual layer-remap dialog; how a repack minimizes UV churn for untouched layers.

Session scope: a large session, possibly split (reconcile engine vs watcher vs review UI).

---

## Phase G — UMA native format

Goal: the source-agnostic native container that actually preserves the decoupling CMO3 fights against — decoupled geometry + UVs + source art with stable layer identity + editor UI state.

Depends on: Phase E (needs the full in-memory model, including source bindings and the packed atlas, to serialize).  Runs largely parallel to Phase F.

Design decisions (this phase is mostly decisions — the on-disk format is entirely undesigned):

- Container: recommend ZIP over TAR.  ZIP gives random access and per-entry compression, is ubiquitous, and the codebase already reads ZIP cross-platform (KRA); TAR buys nothing here.
- Layout (proposal, to ratify): `manifest.json` (format version + Cubism-generation-equivalent), the model graph JSON (parameters / drawables / deformers / keyforms / parts / physics), the packed atlas page(s) encoded via Phase A, thumbnails, and the editor view state CMO3 cannot hold (outliner open/closed branches, panel state — TODO already earmarks UMA for this).
- What form the source art takes inside UMA (open question O8).  Recommend embedding the original source bytes verbatim (the `.psd` / `.clip` / `.kra`) to preserve stable IDs and enable re-import against the embedded copy, plus a cached normalized `SourceArt` snapshot for fast load.  Storing only a normalized snapshot would lose format-specific identity — do not do that (invariant #3).
- Codec: `object Uma : FormatCodec<…>` implementing the shared contract (invariant #5); JSON pieces stay text helpers, not part of the byte contract.

Session scope: a full session on the container + manifest + model serialization; the source-embedding policy is the load-bearing decision.

---

## Phase H — CMO3 interop hardening

Goal: keep CMO3 a smooth interop boundary once the native pipeline exists.

Import side (partly exists): unwrap CMO3's decomposed `CLayeredImage` tree + per-layer PNGs, and — new — attach source bindings from the CLayeredImage layers where identity is recoverable, so a CMO3-origin model can still re-import against its original source and can round-trip into UMA without losing the decoupling.  Ingest the existing atlas + UVs as-is on import (do not repack — preserve fidelity).

Export side (does not exist — save re-emits original bytes): synthesize the `CLayeredImage` / `CModelImage` / `CModelImageGroup` / `CTextureAtlas` / `ModelImageEntry` chain from our model + packed atlas (the reverse of `extractPuppetTextures` and the CMO3 image chain documented in [CMO3.md §4](../formats/CMO3.md)).

The hard decision (TODO step 8, "DECISION REQUIRED"): Cubism couples geometry and UVs at the default/neutral form — a Umamo model with decoupled geometry (geometry ≠ UVs) produces a Cubism default form that renders displaced and gets its UVs re-derived / corrupted on Cubism's next re-atlas.  Decide how Umamo's neutral geometry maps onto Cubism's default form.  Candidate strategies (to weigh in the Phase-H session, not resolved here):

- Bake the decoupled state into a Cubism-consistent default form on export (may weaken the decoupling on a subsequent CMO3 re-import).
- Constrain / warn on UV edits that would make a lossless CMO3 export impossible.
- Accept and document the displacement for models that will not round-trip through Cubism.

This gates the CMO3 round-trip fidelity contract and ties directly to the mesh/UV-decoupling memory's "CMO3 export hazard."

Depends on: shares export-synthesis machinery with Phase G; the import-side binding attach can proceed earlier, in parallel.

Session scope: two sessions realistically — the import-side binding attach, and the export writer + default-form decision.

---

## Cross-cutting decisions

These span phases and are worth deciding early because they constrain several downstream sessions.

### Pure-Kotlin vs platform-fallback per raster format

The tension: the PSD/CLIP/KRA readers are pure Kotlin, so Umamo's art stack decodes identically on desktop and Android; `decodePngToRgba` cheats via ImageIO/BitmapFactory, which differ (ImageIO cannot decode WebP on the JVM; BitmapFactory can; neither does JP2 or TIFF well).  Since every current target is JVM-based, "platform agnostic" today means desktop-JVM and Android decode identically without a host image library — not iOS.

Recommendation: pure Kotlin for the formats Umamo's own pipeline depends on end to end (PNG above all — the atlas and UMA storage must be byte-identical on both platforms), platform-fallback tolerable only for import-only source formats where a minor decode difference is acceptable (JPEG).  Keep the `RasterCodec` seam common so a pure-Kotlin implementation can replace a fallback per format without touching callers.

### Atlas packing algorithm, padding, and multi-page

Decided in Phase C, but it ripples into Phase F (repack churn), Phase G (page encoding), and the renderer (multi-page upload already generalizes).  MaxRects + alpha-trim + optional 90° rotation + edge gutter, configurable max page size with multi-page spill, is the recommended baseline.

### Premultiplied alpha and color space

Invariant #6 fixes straight-alpha RGBA8888 at the model boundary; the renderer premultiplies in-shader.  The packer and encoder must stay straight-alpha through UMA storage and only premultiply at upload.  Color-space handling (sRGB assumption, ICC profiles from TIFF/JPEG sources) is currently ignored — decide whether to keep ignoring it or to normalize to sRGB on ingest.

### One identity representation

The reader `LayerId(String)` vs reimport typed `LayerKey` split must collapse to one representation, ideally in Phase E before Phase F builds on it.  Persisted on `Drawable` as the source binding.

## Risks and open questions

- The CMO3 default-form reconciliation (Phase H) is the highest-risk correctness point for the interop promise; it gates the round-trip fidelity contract and has no obvious lossless answer.
- Android page-size caps (GLES `GL_MAX_TEXTURE_SIZE`) may force multi-page atlases where Cubism uses one 8192² page — the packer and any CMO3 export must not assume a single page.
- Pure-Kotlin JPEG / WebP decoders are large efforts; the pure-vs-fallback decision controls how much of that is on the critical path.
- Mask / channel round-trip: CLIP masks are baked into alpha and PSD/KRA masks are not separately modeled, so masks cannot round-trip after import today.  A `SourceArt` model extension, not yet decided.
- Repack determinism vs churn: re-import must repack with minimal UV disturbance for untouched layers while the invariant protects the vertex→pixel mapping — a real tension to design against in Phase F.

## Suggested first session

Phase A, scoped to the `RasterCodec` seam + PNG (read/write) + BMP (read/write) + the flat-raster→`SourceArt` adapter + retiring `decodePngToRgba`.  It has no upstream dependencies, is the caller's named near-term need, delivers a testable unit immediately (round-trip PNG/BMP; decode the corpus CMO3 atlas through the new path and assert pixel-identity with the old `decodePngToRgba`), and unblocks Phases E and G.  Phases B–D can be scoped in parallel since they do not depend on A.
