# Art Sourcing Pipeline — Design Roadmap

Status: roadmap (2026-07-12; Phase A shipped and back-annotated 2026-07-15, Phase B shipped and back-annotated 2026-07-17). This is a multi-session design backbone, not a single implementation plan. Each numbered phase below is sized to become its own planning session. Phases A and B are now records of what was built rather than proposals — where the implementation diverged from the plan, the divergence and its reason are kept, because several of those reasons constrain the phases still ahead. It supersedes and expands the "Art-first pipeline" Claude Note in [TODO.md](../../TODO.md) (the 9-step list at `## Art-first pipeline`), which stays as the terse status tracker; this document is the reasoning and the decision record behind it.

## Purpose

Decide how Umamo sources, meshes, packs, stores, and refreshes artwork — end to end — so that the decisions here can drive three dependent designs that are all still open: the native UMA format, the artwork import / re-import flow, and UV / texture file handling. The immediate concrete deliverable the caller named is a cross-platform raster image codec family (Phase A); the rest of the document places that piece in the pipeline it belongs to so a future session picks it up with the whole shape in view.

## The thesis

Umamo owns the whole pipeline from source artwork to packed texture atlas, and that is the
differentiator.

- Cubism started PSD-only and later bolted on flat-PNG import without breaking backwards compatibility, so its entire artwork model is still shaped like a PSD (`CLayeredImage.psdFile`, `psdBytes`, `psdFileLastModified` — see [CMO3.md §4](../formats/CMO3.md)). We keep the interop but refuse to inherit the everything-is-a-PSD framing: sources are peer formats, none privileged.
- Blender does not own this pipeline: it expects the packed texture atlas to be authored outside Blender and reconciled with manual UV unwrapping. We do the opposite.
- The 2D source art gives us, per layer, the opaque silhouette of that piece before packing — for free. That single fact means Umamo can generate the initial mesh over each layer, pack those layers into an atlas itself, and derive the UVs from the packing, with no manual unwrap step. The same alpha-shape analysis feeds both the mesh and the pack.

This is the capability Blender lacks and Cubism half-owns behind a legacy PSD model. The roadmap is the plan to build it cleanly while staying a drop-in CMO3 interop partner.

## Invariants this roadmap must not break

These are locked in [CLAUDE.md](../../.claude/CLAUDE.md) and the TODO Art-first note. Every phase is checked against them.

1. Mesh geometry and UVs are decoupled (Blender-style). Editing geometry never re-derives UVs the way Cubism does at the default form. Already true in code (`withMeshPositions` shares the uvs array by reference).
2. UVs bind to source-art pixel space via a stable layer identity; the atlas is a repackable indirection layer, never a source of truth. Repacking moves where pixels sit in the page, never the vertex→art-pixel mapping.
3. The native format is source-agnostic. PSD, CLIP, KRA are peer sources, each keeping its own stable layer identity (`lyid` / CLIP uuid / KRA uuid). Do not shim CLIP or KRA down to a PSD shape on import — that throws away the stable IDs that make re-import reliable.
4. CMO3 is an interop boundary, not the native format — unwrapped on import, re-synthesized on export. CMO3 round-trip fidelity is a hard gate (semantic validity, reopen in the official editor with no loss).
5. Binary formats implement the shared `FormatCodec<TModel>` contract dispatched through `FormatRegistry`; JSON sidecars stay `String`-shaped helpers, outside the byte contract.
6. Neutral source pixels are RGBA8888, straight (non-premultiplied) alpha, top row first. Higher bit depths and HDR are down-converted (lossy by the model's contract).

## Where we are today (the starting line)

Verified against the tree on 2026-07-12; the raster-codec entries re-verified 2026-07-15 after Phase A landed, and the alpha-shape-analysis entry added 2026-07-17 after Phase B.

Exists and works:

- The codec seam: `FormatCodec<TModel>` / `ReadOnlyCodec` / `ArtReader : ReadOnlyCodec<SourceArt>` in
  `:format` commonMain, with `FormatRegistry` (detect-by-magic, resolve-by-extension) in
  jvmAndroidMain.
- The raster codec family (Phase A, 2026-07-14): `RasterCodec` / `ReadOnlyRasterCodec` / `RasterImage`
  plus pure-Kotlin PNG (read/write), BMP (read/write), JPEG (read), WebP VP8L (read), and TIFF (read),
  all in `:format` commonMain and all registered. See Phase A below for what shipped versus what was
  planned.
- The neutral ingest model: `SourceArt` / `SourceGroup` / `SourceLayer` / `LayerRaster` / `LayerId` /
  `LayerBounds` / `LayerBlend` in `module/format/src/commonMain/.../art/SourceArt.kt`.
- Three layered readers producing full `SourceArt` including decoded straight-alpha RGBA pixels: PSD
  (pure-Kotlin TwelveMonkeys port), CLIP (CSFCHUNK container + embedded SQLite + zlib tiles), KRA
  (ZIP + `maindoc.xml` via JDOM + pure-Kotlin LZF tiles). All three are registered and tested.
  Phase A moved PSD and CLIP to commonMain onto the shared `binary/` substrate; KRA stays in
  jvmAndroidMain as the one genuine `java.util.zip` (`ZipInputStream`) user.
- CMO3 → `PuppetModel` import end to end, with atlas textures. UVs are read verbatim from the CMO3
  art meshes; the atlas page(s) are extracted whole from the imported CMO3
  (`extractPuppetTextures` + `Cmo3Model.extractLayerPng`, decoding through `PngCodec.read` since
  Phase A) and uploaded to GL.
- CMO3 read + write and MOC3 read + write (byte-exact for an unedited MOC3); JSON family as text
  helpers on `Moc3`.
- The UV editor (2026-07): edits existing UVs over an existing atlas page in texel space (v-axis
  flipped, since stored v=0 is the top atlas row).
- Alpha-shape analysis (Phase B, 2026-07-17): `analyzeAlpha` in `:format` commonMain (`org.umamo.format.art`)
  gives per-layer alpha-trimmed bounds, an opaque-pixel count/coverage, and a marching-squares alpha contour
  with Douglas-Peucker simplification, over any `LayerRaster` / `RasterImage`. The `(c)` node of the pipeline
  graph. Caller-less until Phases C/D consume it (packer rects, mesh silhouette). See Phase B below.

Absent — this is the work the roadmap is about:

- ~~No pure-common raster image decoder of any kind.~~ Closed by Phase A — see below. What remains of this gap: no encoder for anything but PNG and BMP, no JPEG 2000 at all, and `GlPuppetRenderer.dumpPng` is still on `javax.imageio` (the last `ImageIO` reference in production code, desktop-only debug).
- No atlas packer. No bin-packing / rect-placement / atlas-authoring code exists; the atlas is only ever consumed pre-packed from a CMO3. `PuppetTextures` stores only a page index per drawable — the region rectangle is implicit in the mesh UVs, so there is no data model for a Umamo-built placement.
- No auto-mesh (mesh from a layer's opaque region).
- No `SourceArt → PuppetModel` bridge (no `fromPsd` / `fromLayered`). Opening a PSD/CLIP/KRA is blocked in the UI (`Document.kt` returns `NotOpenable`; the picker only offers `.cmo3`).
- `:reimport` is a contracts-only skeleton: `Reconciler` / `LayerMatcher` / `SourceWatcher` are interfaces with no implementations, `SourceBinding` / `LayerKey` are never persisted or resolved, there are two disconnected identity models (the readers emit `LayerId(String)`, reimport declares a typed `LayerKey`) with no bridge, no callers, and no tests. `Drawable` carries no source-layer field.
- No `PuppetModel → CMO3` writer. Saving a CMO3 currently re-emits the original bytes.
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

The lettered nodes are the phases below. The dependency edges are the sequencing constraint: `(a)`
is a leaf with no dependencies and is the named near-term deliverable; the bridge `(e→"SourceArt→
Puppet")` is the first point where a non-CMO3 file becomes an editable rig, and it depends on `c`,
`d`, and `e`.

---

## Phase A — Raster image codec family — SHIPPED (2026-07-14)

Goal: a platform-agnostic image codec layer for PNG, BMP, WebP, JPEG, and TIFF, so Umamo can (1) import flat rasters as source art, (2) decode the CMO3 atlas the same way on desktop and Android, and (3) encode the atlas / thumbnails it will generate in later phases. This is the leaf of the graph — nothing depends on the rest of the pipeline — so it could start immediately and in parallel with everything.

Delivered in `b66bfa4` ("Raster artwork CODEC work.") and the follow-ups on the `newrasterformats` branch. It unblocks: Phase E (flat-raster source import), Phase G (atlas + thumbnail encoding into UMA), and it cleaned up the `:render` PNG-decode seam that Phase H relies on.

The phase landed considerably wider than it was scoped: all five formats decode, rather than PNG and BMP now with the rest deferred. Two of the plan's central predictions were resolved differently — the DEFLATE dependency and the `:render` → `:format` edge — and both are load-bearing for later phases, so they are recorded rather than quietly overwritten.

### The seam and where it lives

As planned, image I/O is modeled in `:format` (the module owns "art I/O" per the module graph), not in `:render`, and the raster codec is distinct from the layered `ArtReader`. The name `RasterCodec` was ratified. What differs is that the seam is two interfaces, not one, because read-only-ness is stated in the type rather than per-codec:

```kotlin
public interface RasterCodec : FormatCodec<RasterImage>
public interface ReadOnlyRasterCodec : RasterCodec, ReadOnlyCodec<RasterImage>
```

PNG and BMP implement `RasterCodec`; JPEG, WebP, and TIFF implement `ReadOnlyRasterCodec` and inherit a refusing `write` from `ReadOnlyCodec`. The matching `FileKind` declares `writable = false`, so a caller can tell before the call.

`RasterImage(width, height, rgba)` is as designed — RGBA8888, straight alpha, top row first (invariant #6), 16-bit channels down-converted to 8. It is deliberately a plain `class`, not a `data class`: a generated structural `equals` would deep-compare the pixel array. It is field-compatible with `art.LayerRaster`, which has the identical shape for the identical reason. There is no metadata slot — see the color-space note under Cross-cutting decisions.

The flat-raster → `SourceArt` adapter shipped as a top-level function, not an `ArtReader` implementation:

```kotlin
public fun rasterToSourceArt(image: RasterImage, name: String): SourceArt
```

It could not be an `ArtReader`: each codec object already registers as `FormatCodec<RasterImage>`, and one object cannot also be `ReadOnlyCodec<SourceArt>`. So it is not registered, and the documented call path is `FormatRegistry.detect(bytes) as? RasterCodec` → `read(bytes)` → `rasterToSourceArt(...)`. It produces one root-level full-canvas layer and passes the pixel buffer through by reference (the caller must not mutate afterwards). It has no callers outside its own test — Phase E is what consumes it.

`decodePngToRgba` is retired: the `expect` and both actuals (`ImageIO` / `BitmapFactory`) were deleted outright, and the CMO3 atlas path now decodes through `PngCodec.read` at `Cmo3PuppetTextures.decodeAtlasPng`.

Two divergences from the plan's proposals here:

- The `:render` commonMain → `:format` dependency was not added, and did not need to be. The plan's premise was wrong: `:runtime` already declares `api(project(":format"))`, so `:format` commonMain was on `:render` commonMain's compile classpath transitively all along. The decode call sites live in `jvmAndroidMain` regardless, because they need `Cmo3Model`. The platform split was removed either way.
- The debug `dumpPng` did NOT move onto the new encoder. `GlPuppetRenderer.dumpPng` still builds a `BufferedImage` and calls `ImageIO.write`, and is now the only `javax.imageio` reference left in production code. It is `jvmMain`, so it never compiles for Android, which is presumably why it was not urgent. `PngCodec.write` encodes exactly the 8-bit RGBA form it needs; this is a loose end, not a blocked one.

### Format matrix — as shipped

"Pure Kotlin" means a commonMain decoder with no host image library, matching how PSD/CLIP/KRA already work. Every raster codec met that bar, including the ones the plan was willing to concede to a platform fallback.

| Format | Read | Write | Codec object | As shipped                                                                                                                                                                                                                                                                                                                                               |
| ------ | ---- | ----- | ------------ | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| PNG    | yes  | yes   | `PngCodec`   | Decode is complete: all five color types, bit depths 1/2/4/8/16, `tRNS`, Adam7 interlace, all five filter types, multi-`IDAT`, per-chunk CRC-32 verification (mismatch is a hard error). Encode is deliberately narrow: 8-bit RGBA (type 6), non-interlaced, filter 0 on every scanline. Round-trip is pixel-exact, not byte-exact.                      |
| BMP    | yes  | yes   | `BmpCodec`   | Narrower than "trivial" implies: decode is 16/24/32-bit `BI_RGB` / `BI_BITFIELDS` / `BI_ALPHABITFIELDS` only — palette (≤8-bit) and RLE are explicitly rejected. Handles negative-height top-down. Encode is 32-bit `BITMAPV4HEADER` with explicit RGBA masks.                                                                                           |
| JPEG   | yes  | no    | `JpegReader` | In-house, not a TwelveMonkeys port. Baseline AND progressive (SOF0/SOF1/SOF2) — progressive was promoted into scope because Twitter, Pixiv, and Save-for-Web emit it. Plus restart intervals and arbitrary sampling factors. Rejects lossless/arithmetic/differential/12-bit/CMYK/YCCK.                                                                  |
| WebP   | yes  | no    | `WebPReader` | VP8L lossless read only, bare or inside VP8X. The planned VP8L writer did NOT land, so the lossless-WebP-atlas idea is still unbuilt; lossy VP8 throws. Ported from TwelveMonkeys imageio-webp.                                                                                                                                                          |
| TIFF   | yes  | no    | `TiffReader` | Much wider than the planned "uncompressed / LZW / DEFLATE / PackBits strips first": also CCITT MH-RLE / T.4 / T.6, JPEG-in-TIFF (with `JPEGTables` splicing per TIFF TN2, delegating to our own `decodeJpeg`), strips and tiles, chunky and planar, the horizontal predictor, and ExtraSamples alpha un-premultiplication. Classic TIFF, first IFD only. |

All five are registered in `FormatRegistry.codecs`, ordered after the longer-magic formats so BMP's 2-byte `"BM"` cannot shadow them. `FileKind` grew `Png` / `Bmp` / `Jpeg` / `WebP` / `Tiff`; the reserved `Uma` entry Phase G will claim already existed.

Two extension gotchas worth knowing before Phase E wires the picker: `FileKind.Jpeg.extension` is `"jpg"` and `FileKind.Tiff.extension` is `"tiff"`, and `FormatRegistry.detect`'s fallback compares the extension exactly — so a `.jpeg` or `.tif` file would not resolve by name. Magic detection covers this in practice; only the fallback path bites.

### What Phase A established that the plan did not anticipate

These emerged during implementation and bind the phases that follow:

- The shared binary substrate. `binary/Deflate.kt` (okio-backed zlib and raw-DEFLATE, in and out), `binary/Crc32.kt` (hand-written pure-Kotlin CRC-32), and `binary/ByteReader.kt` (the common-code replacement for `java.nio.ByteBuffer`). Anything later needing compression or byte-level reads uses these rather than reaching for `java.*`.
- No shared bit reader, on purpose. The three bit orders genuinely differ, so `WebpLsbBitReader` (LSB-first), `JpegBitReader` (MSB-first with `0xFF00` de-stuffing), and CCITT's own tree walker stay independent. `ByteReader` is shared; bit readers are not.
- Whole-array, never streaming. `FormatCodec`'s `read(ByteArray)` / `write(TModel): ByteArray` forces it, and both TwelveMonkeys ports were restructured away from `ImageInputStream` to match. Phase C's atlas pages and Phase G's UMA entries inherit this: a page is encoded as one `ByteArray`.
- Decompression-bomb bounding as an invariant. Every inflate takes a `maximumSize` derived from container metadata; `PngCodec` reconstructs the exact expected IDAT size from IHDR alone (summed across Adam7 passes) so a hostile stream stops there, and rejects `width * height * 4 > Int.MAX_VALUE` before allocating. Phase G's UMA reader should hold the same line.
- Best-effort corruption tolerance, uniform across codecs: a truncated stream yields what was recovered plus zero fill rather than throwing (PNG short scanlines, TIFF short strips, CCITT `CcittEndOfInput`, WebP zero bits past EOF). PNG chunk CRC mismatch is the deliberate exception and is fatal.
- Provenance is load-bearing. WebP, TIFF, and CCITT are attributed ports of TwelveMonkeys (BSD-3-Clause, Harald Kuhr) with per-file headers pointing at [CREDITS.md](../../CREDITS.md); PNG, BMP, and JPEG are in-house. This matters to the clean-room story and any future port must carry the same attribution.
- ImageIO survives as a test oracle. `PngDecodeParityTest` / `PngImageIoParityTest` in `jvmTest` assert our decode against `javax.imageio` independently — purged from main, kept as a reference implementation in tests. Corpus-gated tests self-skip when `test/corpus/` is absent; `bmp.sample` was dropped as unread.

### Outstanding from Phase A

Small, and none of it blocks Phase B–E:

- ~~The VP8L writer, if a lossless-WebP atlas is still wanted at Phase C.~~ - User decision: No VP8L writer.
- Lossy VP8 read, JPEG 2000, BMP palette/RLE read — all unbuilt, none currently needed.
- Stale KDoc in `:format` that predates the move to commonMain and misdescribes it: `FormatRegistry.kt` still says the raster codecs "live in jvmAndroidMain" and calls JPEG/WebP/TIFF "read placeholders" (they are complete); `TiffReader.kt` says "uses only `java.util.zip`"; `PngCodec.kt` / `PngChunks.kt` point at a zlib bridge in `org.umamo.format.raster` that actually lives in `org.umamo.format.binary`; `PsdReader.kt` claims `java.nio` + `java.util.zip`. Worth a cleanup pass.

---

## Phase B — Alpha-shape analysis — SHIPPED (2026-07-17)

Goal: per source layer, compute the opaque-region description that both the auto-mesh and the packer consume — tight (alpha-trimmed) bounds, a coverage/occupancy summary, and a simplified alpha contour polygon. A small shared foundation extracted so Phases C and D do not each re-derive it.

Delivered as three pure-Kotlin commonMain files in `org.umamo.format.art`, beside the neutral model they read: `AlphaAnalysis.kt` (public API + the single-pass scan), `AlphaContourTrace.kt` (the boundary tracer), and `ContourSimplify.kt` (the simplifier). No `java.*`; passes the iosArm64 purity gate. It unblocks Phase C (trimmed pack rects) and Phase D (mesh silhouette); nothing consumes it yet — the entry points are caller-less until those phases, exactly as `rasterToSourceArt` was after Phase A. The phase landed at its full stretch scope: the contour is a real marching-squares boundary trace with Douglas-Peucker simplification, not the trimmed-rect fallback the plan was willing to ship for v1.

### The API

One entry point, pure over `(width, height, rgba)` so both `LayerRaster` and the flat `RasterImage` feed it, plus thin extensions:

```kotlin
public fun analyzeAlpha(
	width: Int, height: Int, rgba: ByteArray,
	alphaThreshold: Int = DEFAULT_ALPHA_THRESHOLD,      // 1 — any nonzero alpha
	contourEpsilon: Float = DEFAULT_CONTOUR_EPSILON,    // 1.0 px
): AlphaAnalysis?                                       // null iff nothing is opaque

public fun LayerRaster.analyzeAlpha(...): AlphaAnalysis?
public fun SourceLayer.analyzeAlpha(...): AlphaAnalysis?   // stored pixels only — ignores opacity/visible/blend
public fun RasterImage.analyzeAlpha(...): AlphaAnalysis?   // in the raster package, to keep the raster → art dep one-way
public fun AlphaAnalysis.opaqueBoundsOnCanvas(layerBounds: LayerBounds): LayerBounds
```

`AlphaAnalysis(opaqueBounds, opaquePixelCount, contours)` carries a `boundsCoverage` accessor; `AlphaContour(points: IntArray, isHole: Boolean)`. Both are plain classes (identity equality), like `LayerRaster`/`RasterImage`, because they hold arrays.

### Decisions the plan left open, as resolved

- Opaque threshold is a parameter defaulting to `>= 1` (any nonzero alpha). Lossless for the packer — no antialiased edge pixel is trimmed; Phase D passes a higher threshold for a tighter silhouette.
- Null if nothing meets the threshold (covers the 0×0 raster and the CLIP/KRA 1×1 transparent placeholders). Sliver exclusion was pushed OUT of the analysis onto the consumers — a 1-px hairline layer with real pixels gets its true bounds, so C/D decide whether to drop it. This deliberately diverges from the plan's "(exclude)" parenthetical: dropping a real art layer at the analysis layer would be silent data loss.
- Contour is a marching-squares trace on the pixel-corner lattice (a point is a corner BETWEEN pixels, so polygons are watertight and pixel-exact) plus iterative closed-ring Douglas-Peucker. Winding keeps opaque pixels on the right (y-down) → outer rings positive shoelace, holes negative; `isHole` is derived from the exact ring and is authoritative (the winding redundancy holds only for `contourEpsilon = 0f`). Saddles (diagonal-only contact) take the tight right turn (4-connected foreground), so a checkerboard separates into unit squares. Holes are traced and flagged, not discarded.
- Output is deterministic (row-major north-edge discovery), which Phase C repack stability relies on.

### Notes for the phases that consume it

- `opaqueBounds` is RASTER-LOCAL and reuses `LayerBounds` (no new rect type); `opaqueBoundsOnCanvas(layer.bounds)` lifts it to canvas space. `LayerBounds` is now used in two frames and the compiler cannot catch a canvas/raster-local mixup — a distinct raster-local rect type is worth reconsidering when Phase C introduces the atlas-page placement rect.
- The mask is a bit-packed `LongArray` (an 8192² layer is 67M pixels — 8.4 MB packed vs 67 MB boolean, with a second same-geometry visited bitset alive during tracing).
- Known simplifier limitation, accepted for v1: on adversarial shapes Douglas-Peucker can self-intersect the simplified ring; pass `contourEpsilon = 0f` for the exact ring. Phase C reads only the rect, so it is unaffected.

### Tested

commonTest unit suites (synthetic pixels, so they also compile and run for iosArm64): `AlphaAnalysisTest` (bounds / count / threshold / validation), `AlphaContourTest` (geometry, saddles, holes), `ContourSimplifyTest` (Douglas-Peucker). Corpus-gated `AlphaAnalysisCorpusTest` (jvmTest) runs every raster layer of every PSD/CLIP/KRA sample — 438 layers across 13 samples on the local corpus — checking the analysis against independent rescans; the load-bearing invariant is that the signed contour areas sum to exactly the opaque pixel count.

---

## Phase C — Atlas packer

Goal: take the per-layer opaque rasters and arrange them into one or more packed pages, producing a placement (page index, rect, rotation) per layer, and encode each page via Phase A. This is the capability Blender lacks and today's Umamo lacks entirely.

Depends on: Phase A (encode/decode the page), Phase B (trimmed rects). Introduces a placement data model — `PuppetTextures` today stores only a page index per drawable, with the region implicit in the UVs. The packer must record the region rect explicitly (this is the authoritative source→atlas indirection, invariant #2), and Phase E derives UVs from it.

Key design decisions (each worth pinning in the Phase-C session):

- Packing algorithm. Recommend MaxRects (Jylänki) or a skyline/shelf variant, with alpha-trimmed axis-aligned rects and optional 90° rotation. Tighter polygon / convex-hull packing is a stretch.
  - Jukka Jylänki paper: https://raw.githubusercontent.com/rougier/freetype-gl/master/doc/RectangleBinPack.pdf (For document reference only. AI: Don't download this, it's very long.)
- Padding / bleed. Tiles need an edge gutter to stop neighbor bleed under bilinear filtering — the renderer already forgoes mipmaps for this reason, but bilinear still samples the gutter. Pick a gutter width and an extrusion (edge-clamp vs transparent).
- Page size and count policy. Cubism allows any number of pages, from 32² to 16384² in size. The example corpus uses one 8192² page. Our baseline is GL 3.3 / GLES 3.0; GLES 3.0 only guarantees `GL_MAX_TEXTURE_SIZE ≥ 2048`, so Android tablets may cap well below 8192. The packer must take a configurable max page size and spill to multiple pages, and the renderer / `PuppetTextures` already generalize to N pages.
- Determinism. Repacking must be stable enough that an unchanged model repacks identically (fidelity signal), and a re-import repacks with minimal churn (Phase F).

Session scope: a full session on its own. The placement data-model change to `PuppetTextures`/`Drawable` is part of it.

---

## Phase D — Auto-mesh from art

Goal: generate an initial editable triangle mesh over each layer's opaque region — "mesh from art". At birth, a drawable's positions and UVs are two views of the same layout; they only diverge once the mesh is edited (invariant #1).

Depends on: Phase B. Reuses the topology infrastructure built in the tools work (`withMeshTopologyEdit`, which already rebuilds keyform delta arrays and remaps glue to a new vertex count — see [tools-and-shortcuts.md](tools-and-shortcuts.md) Phase 7).

Decisions: mesh strategy — bounding quad (Cubism's trivial default), alpha-contour + constrained Delaunay interior (Cubism's automatic generation), or an alpha-clipped grid; density controls; pen-first authoring affordances later.
Human note: I'm not sure where the AI inferred the bounding quad and Delaunay information. It is not in any Cubism documentation, but there is a reference to useDelaunayTriangulation on GEditableMesh2 in the XML of the CMO3 file format. Either way, lets do what is best for Umamo.

Session scope: a full session; the mesh-generation strategy alone is a meaningful design choice.

---

## Phase E — SourceArt → PuppetModel bridge and import wiring

Goal: the first point where a non-CMO3 file becomes an editable rig. Turn a `SourceArt` (layered or flat) into a `PuppetModel`: auto-mesh each layer (D), pack the layers (C), derive UVs from the placement, place each drawable by its layer bounds, and attach a source binding (the stable `LayerId`) to each drawable. Then unblock the UI import path (`Document.kt` and the file picker currently reject everything but CMO3).

Depends on: Phase C, Phase D. Closes the "no `fromPsd`/`fromLayered`" gap and the "non-CMO3 import blocked" gap.

Phase A left two things wired for this phase and nothing else consuming them: `rasterToSourceArt(image, name)` is the flat-raster entry point (currently caller-less), and the picker will need `FileKind` extensions for the five raster kinds — note `Jpeg.extension` is `"jpg"` and `Tiff.extension` is `"tiff"`, so accepting `.jpeg` / `.tif` by name needs the registry's extension fallback widened or the picker filter written independently of it.

Decisions: how the source binding is stored on the model — `Drawable` needs a new source-layer field, and it must bridge the two identity models that exist today (reader `LayerId(String)` vs reimport typed `LayerKey`). Recommend collapsing to one identity representation here so Phase F does not inherit the split. Also: the display-name-from-layer-name affordance the mockup calls for (the parts tree speaks `Head` / `Ear R`, not `ArtMesh32`).

Session scope: a full session; the identity-model unification is the subtle part.

---

## Phase F — Re-import reconcile

Goal: implement the headline feature. On a watched-file change, reconcile the re-read `SourceArt` against the model's source bindings: match by stable `LayerId`, classify (matched / moved / resized / added / removed / renamed), and for a matched layer update its atlas tile and UVs while preserving mesh / deformers / keyforms where topology still fits — never silently deleting rig work. Matched changes may trigger an atlas repack, protected by invariant #2.

Depends on: Phase E (persisted source bindings + the packer). Turns the `:reimport` skeleton into a working engine: implement `Reconciler`, `LayerMatcher` (fuzzy rename), and `SourceWatcher` (desktop `WatchService` / Android `FileObserver`), and add the reconcile review UI (diff / merge step).

Decisions: the reconcile-result model already declares matched / added / needs-review but folds removed and renamed into needs-review — decide whether moved/resized deserve first-class cases; the cross-format-change warning (PSD→KRA is destructive, per TODO § Reimport) and the eventual manual layer-remap dialog; how a repack minimizes UV churn for untouched layers.

Session scope: a large session, possibly split (reconcile engine vs watcher vs review UI).

---

## Phase G — UMA native format

Goal: the source-agnostic native container that actually preserves the decoupling CMO3 fights against — decoupled geometry + UVs + source art with stable layer identity + editor UI state.

Depends on: Phase E (needs the full in-memory model, including source bindings and the packed atlas, to serialize). Runs largely parallel to Phase F.

Design decisions (this phase is mostly decisions — the on-disk format is entirely undesigned):

- Container: recommend ZIP over TAR. ZIP gives random access and per-entry compression, is ubiquitous, and the codebase already reads ZIP cross-platform (KRA); TAR buys nothing here.
- Layout (proposal, to ratify): `manifest.json` (format version + Cubism-generation-equivalent), the model graph JSON (parameters / drawables / deformers / keyforms / parts / physics), the packed atlas page(s) encoded via Phase A, thumbnails, and the editor view state CMO3 cannot hold (outliner open/closed branches, panel state — TODO already earmarks UMA for this).
- What form the source art takes inside UMA (open question O8). Recommend embedding the original source bytes verbatim (the `.psd` / `.clip` / `.kra`) to preserve stable IDs and enable re-import against the embedded copy, plus a cached normalized `SourceArt` snapshot for fast load. Storing only a normalized snapshot would lose format-specific identity — do not do that (invariant #3).
- Codec: `object Uma : FormatCodec<…>` implementing the shared contract (invariant #5); JSON pieces stay text helpers, not part of the byte contract.

Session scope: a full session on the container + manifest + model serialization; the source-embedding policy is the load-bearing decision.

---

## Phase H — CMO3 interop hardening

Goal: keep CMO3 a smooth interop boundary once the native pipeline exists.

Import side (partly exists): unwrap CMO3's decomposed `CLayeredImage` tree + per-layer PNGs, and — new — attach source bindings from the CLayeredImage layers where identity is recoverable, so a CMO3-origin model can still re-import against its original source and can round-trip into UMA without losing the decoupling. Ingest the existing atlas + UVs as-is on import (do not repack — preserve fidelity).

Export side (does not exist — save re-emits original bytes): synthesize the `CLayeredImage` / `CModelImage` / `CModelImageGroup` / `CTextureAtlas` / `ModelImageEntry` chain from our model + packed atlas (the reverse of `extractPuppetTextures` and the CMO3 image chain documented in [CMO3.md §4](../formats/CMO3.md)).

The hard decision (TODO step 8, "DECISION REQUIRED"): Cubism couples geometry and UVs at the default/neutral form — a Umamo model with decoupled geometry (geometry ≠ UVs) produces a Cubism default form that renders displaced and gets its UVs re-derived / corrupted on Cubism's next re-atlas. Decide how Umamo's neutral geometry maps onto Cubism's default form. Candidate strategies (to weigh in the Phase-H session, not resolved here):

- Bake the decoupled state into a Cubism-consistent default form on export (may weaken the decoupling on a subsequent CMO3 re-import).
- Constrain / warn on UV edits that would make a lossless CMO3 export impossible.
- Accept and document the displacement for models that will not round-trip through Cubism.

This gates the CMO3 round-trip fidelity contract and ties directly to the mesh/UV-decoupling memory's "CMO3 export hazard."

Depends on: shares export-synthesis machinery with Phase G; the import-side binding attach can proceed earlier, in parallel.

Session scope: two sessions realistically — the import-side binding attach, and the export writer + default-form decision.

---

## Cross-cutting decisions

These span phases and are worth deciding early because they constrain several downstream sessions.

### Pure-Kotlin vs platform-fallback per raster format — RESOLVED in Phase A: pure Kotlin, no fallbacks

The original tension: the PSD/CLIP/KRA readers are pure Kotlin, so Umamo's art stack decodes identically on desktop and Android; `decodePngToRgba` cheated via ImageIO/BitmapFactory, which differ. The plan recommended pure Kotlin for formats the pipeline depends on end to end, with a platform fallback tolerable for import-only formats like JPEG.

What was actually decided is stricter, and the reasoning changed underneath it. Every raster codec is pure-Kotlin commonMain, including JPEG — no format took the fallback. The plan's framing that "platform agnostic today means desktop-JVM and Android, not iOS" is dead: `:format` now declares an `iosArm64()` target, so commonMain purity is a compiler guarantee rather than a convention — `java.*` in commonMain is an unresolved reference, not a latent surprise. The target is a real iPadOS device target and is deliberately not a stand-in (a proxy would prove "non-JVM" without proving Apple), and `check` depends on `compileKotlinIosArm64` and `compileTestKotlinIosArm64` explicitly, because a device target has no runnable test task to drag its compile in.

This is why DEFLATE is okio, not `java.util.zip` as the Phase A table proposed: okio's `zlibMain` reaches every target including iosArm64. The build's own comment records that `KraReader` sat in commonMain importing `java.util.zip` and CI caught it — the gate is not theoretical. `KraReader` is now the one genuine `java.util.zip` user and lives in jvmAndroidMain accordingly.

Consequence for later phases: any new common code — the packer (C), the auto-mesh (D), the UMA codec (G) — must stay off `java.*` or it will fail the iOS compile. Reach for `binary/Deflate.kt`, `binary/Crc32.kt`, and `binary/ByteReader.kt`. The `RasterCodec` seam is common, so a future fallback would still be swappable per format without touching callers — but nothing needs one today.

### Atlas packing algorithm, padding, and multi-page

Decided in Phase C, but it ripples into Phase F (repack churn), Phase G (page encoding), and the renderer (multi-page upload already generalizes). MaxRects + alpha-trim + optional 90° rotation + edge gutter, configurable max page size with multi-page spill, is the recommended baseline.

### Premultiplied alpha and color space

Invariant #6 fixes straight-alpha RGBA8888 at the model boundary; the renderer premultiplies in-shader. The packer and encoder must stay straight-alpha through UMA storage and only premultiply at upload. Phase A holds this end: every decoder emits straight alpha, and TIFF's ExtraSamples associated-alpha un-premultiplies on the way in.

Color space is still ignored, and Phase A hardened that by omission rather than by decision. `RasterImage` has three fields and no metadata slot, so there is nowhere to put a profile even if a decoder read one: no `iCCP` / `gAMA` / `sRGB` chunk parsing, no EXIF, no orientation, no DPI. The only traces are write-side hardcodes in `BmpCodec.write` (~72 DPI, CSType `'sRGB'`). So the open question is unchanged but now has a cost attached: normalizing to sRGB on ingest means adding a metadata channel to `RasterImage` (or a parallel return), which is a signature change across all five codecs. Decide before Phase G commits UMA's pixel storage, not after.

### One identity representation

The reader `LayerId(String)` vs reimport typed `LayerKey` split must collapse to one representation, ideally in Phase E before Phase F builds on it. Persisted on `Drawable` as the source binding.

## Risks and open questions

- The CMO3 default-form reconciliation (Phase H) is the highest-risk correctness point for the interop promise; it gates the round-trip fidelity contract and has no obvious lossless answer.
- Android page-size caps (GLES `GL_MAX_TEXTURE_SIZE`) may force multi-page atlases where Cubism uses one 8192² page — the packer and any CMO3 export must not assume a single page.
- ~~Pure-Kotlin JPEG / WebP decoders are large efforts; the pure-vs-fallback decision controls how much of that is on the critical path.~~ Retired: both shipped pure-Kotlin in Phase A, and neither is on any critical path — the pipeline's mandatory format is PNG. The residual risk moved rather than vanished: they are now large bodies of hand-ported decode logic under our maintenance, exercised only by corpus-gated tests that self-skip on a fresh clone or CI.
- Mask / channel round-trip: CLIP masks are baked into alpha and PSD/KRA masks are not separately modeled, so masks cannot round-trip after import today. A `SourceArt` model extension, not yet decided.
- Repack determinism vs churn: re-import must repack with minimal UV disturbance for untouched layers while the invariant protects the vertex→pixel mapping — a real tension to design against in Phase F.

## Session order

Phase A was the first session and is done (2026-07-14) — it shipped its planned scope (the `RasterCodec` seam, PNG and BMP read/write, the flat-raster→`SourceArt` adapter, retiring `decodePngToRgba`) plus the JPEG, WebP, and TIFF decoders that were meant to be follow-ups.

Phase B was the second session and is done (2026-07-17) — `analyzeAlpha` (trimmed bounds + occupancy + marching-squares contour with Douglas-Peucker) in `:format` commonMain, at full stretch scope. It was run as its own small session rather than folded into Phase C's front.

Next: Phase C (atlas packer), the first phase that consumes Phase B. Phase D (auto-mesh) can run in parallel — it also consumes Phase B. Phase E is the gate — it depends on C and D and is where a non-CMO3 file first becomes an editable rig, so nothing downstream of it (F, G) starts earlier.

Carry into Phase C from A: pages encode via `PngCodec.write` (8-bit RGBA, filter 0 — fine for an atlas), page bytes are whole-`ByteArray` not streamed, and packer code lives in commonMain under the iosArm64 purity gate. Carry into C and D from B: `analyzeAlpha` is caller-less and awaiting them; its `opaqueBounds` is raster-local (`opaqueBoundsOnCanvas` lifts to canvas space), sliver exclusion is the consumer's call, and the reused `LayerBounds` now spans two coordinate frames — reconsider a distinct raster-local rect type when C introduces the atlas-page placement rect.
