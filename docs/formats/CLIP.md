# CLIP (Clip Studio Paint) File Format

Status: layer tree + raster pixels + layer masks + clipping flag + full blend-mode enum + folder groups (blend / opacity / pass-through) + layer kinds + visibility + RGBA/greyscale/monochrome color modes implemented (read-only).

This documents what the `org.umamo.format.clip` reader in this prototype parses verified against several scenario test files.

Citations in code use `// CLIP: <table>.<column>` for database fields and `// CLIP: @ +0xNN` for
container byte offsets, matching the project's format-citation discipline.

## 1. Container — the CSFCHUNK wrapper

A `.clip` is a flat sequence of tagged chunks. Every integer is **big-endian**.  Byte verified against the samples:

```
@0x00  "CSFCHUNK"          8 bytes   container magic
@0x08  fileSize    u64     8 bytes   whole-file length            (sample: 0x01202AA2)
@0x10  headOffset  u64     8 bytes   offset of the first chunk    (always 24)
@0x18  ... chunk sequence, each:  [8-byte ASCII id][u64 size][`size` bytes payload]
```

Chunks observed, in order:

| Id         | Count | Payload                                                |
| ---------- | ----- | ------------------------------------------------------ |
| `CHNKHead` | 1     | 40-byte header, contents opaque — skipped              |
| `CHNKExta` | 57    | External **tiled raster** block data (see §4, decoded) |
| `CHNKSQLi` | 1     | A raw `SQLite format 3` database ← the reader's target |
| `CHNKFoot` | 1     | Size 0 — terminator                                    |

`ClipContainer.extractSqliteDatabase()` walks from `headOffset`, advancing `offset = payloadStart + size` per chunk, and returns the `CHNKSQLi` payload (the embedded DB). It stops at `CHNKFoot`.

Note: the chunk size is a full big-endian u64. A naive reader can mistake the high (zero) word for a "4-byte reserved + 4-byte size" split — it is not; it is one u64.

## 2. Embedded SQLite database

The `CHNKSQLi` payload is a complete SQLite 3 database. Tables present in the sample:

```
AnimationCutBank  Canvas          CanvasItem                   CanvasItemBank  CanvasPreview
ElemScheme        ExternalChunk   ExternalTableAndColumnName   Layer
LayerThumbnail    Mipmap          MipmapInfo                   Offscreen       ParamScheme
Project           RemovedExternal
```

The reader reads two tables (subset of columns; all are `DEFAULT NULL` in the real schema):

### `Canvas` (one row)

| Column             | Type    | Use                                              |
| ------------------ | ------- | ------------------------------------------------ |
| `CanvasWidth`      | REAL    | document width px (sample 3720) — rounded        |
| `CanvasHeight`     | REAL    | document height px (sample 5262) — rounded       |
| `CanvasRootFolder` | INTEGER | `MainId` of the synthetic root folder (sample 2) |

### `Layer` (one row per layer/folder)

| Column                       | Type    | Use                                                          |
| ---------------------------- | ------- | ------------------------------------------------------------ |
| `MainId`                     | INTEGER | Stable internal id; the tree node key                        |
| `LayerUuid`                  | TEXT    | Rename/reorder-stable uuid — preferred `LayerId`             |
| `LayerName`                  | TEXT    | Display name                                                 |
| `LayerFolder`                | INTEGER | Bit `0x10` set -> folder/group                               |
| `LayerType`                  | INTEGER | Raster/adjustment/text/… taxonomy (not yet mapped — §5)      |
| `LayerOpacity`               | INTEGER | **0..256** fixed-point (256 = opaque); `opacity/256`         |
| `LayerComposite`             | INTEGER | Blend-mode code -> LayerBlend (full enum — §3)               |
| `LayerClip`                  | INTEGER | Non-zero -> clip to the layer below -> `SourceLayer.clipped` |
| `LayerOffsetX/Y`             | INTEGER | Layer move offset (NOT the tile-grid origin — see §4 anchor) |
| `LayerFirstChildIndex`       | INTEGER | First child `MainId`, 0 -> leaf                              |
| `LayerNextIndex`             | INTEGER | Next sibling `MainId`, 0 -> end of chain                     |
| `LayerRenderMipmap`          | INTEGER | -> color raster pipeline (see §4)                            |
| `LayerRenderOffscrOffsetX/Y` | INTEGER | Grid anchor = `LayerOffset + this` (= 0 in corpus)           |
| `LayerLayerMaskMipmap`       | INTEGER | Non-zero -> mask raster pipeline (see §4 masks)              |

### Layer tree reconstruction

The tree is a `MainId` linked structure, not nesting rows. Start at `Canvas.CanvasRootFolder` (a synthetic root that is never emitted), descend via `LayerFirstChildIndex`, and iterate siblings via `LayerNextIndex`.  Depth-first flatten gives document order.

The walk visits the **bottom-most layer first** (the root's first child in the sample is `Paper` Clip Studio's default background), so the produced list is **bottom-to-top**, the painter's algorithm order the compositor expects, identical to the KRA reader's `layers` contract `SourceLayer.order` is assigned top-most-first (0 = topmost).

Sample result: canvas 3720×5262, **18 leaf layers** (5 folders + 1 root are traversed but not emitted).

### Folders (groups)

Folder rows are not emitted as layers, but the flattened leaf list would otherwise discard the folder's own attributes, so each folder is also captured as a `SourceGroup` (keyed by its slash- joined path, matching the enclosed layers' `groupPath`).  A folder carries:

- **blend** — a folder's blend mode lives in the same `LayerComposite` field as a layer (verified on `ClippingMaskLayers.clip`: "Folder, Multiply" = code 2). Folder blend is NOT equivalent to setting each child's blend, so it must be carried separately.
- **pass-through** — `LayerComposite == 30` is Clip Studio's "Through" mode (the default new-folder mode): the folder does not isolate, its children composite straight onto what is below.  One sample's folders are Through; `ClippingMaskLayers.clip`'s two folders are explicit Normal and Multiply (isolated).  `SourceGroup.passThrough` flags this; when set, `blend` does not apply.
- **opacity** (`LayerOpacity / 256`) and **clipped** (`LayerClip`; a folder can itself clip).

Clipping (`LayerClip`) is per-layer and surfaced as `SourceLayer.clipped`; "double clipping" is just two stacked clip layers over one base (verified: Ellipse + Triangle both clip over Rectangle inside a folder). Resolving which base a clip layer binds to is left to the compositor / re-import consumer.

## 3. Blend modes (`Layer.LayerComposite`)

The full enum was reverse-engineered from `LayerBlendModes.clip` (one layer per mode, each layer named after its Clip Studio Paint UI label).  Codes are contiguous 0..26, then 36 for Divide:

| Code | CSP Label    | Code | CSP Label     | Code | CSP Label  |
| ---- | ------------ | ---- | ------------- | ---- | ---------- |
| 0    | Normal       | 10   | Glow Dodge    | 20   | Hard Mix   |
| 1    | Darken       | 11   | Add           | 21   | Difference |
| 2    | Multiply     | 12   | Add (Glow)    | 22   | Exclusion  |
| 3    | Color Burn   | 13   | Lighter Color | 23   | Hue        |
| 4    | Linear Burn  | 14   | Overlay       | 24   | Saturation |
| 5    | Subtract     | 15   | Soft Light    | 25   | Color      |
| 6    | Darker Color | 16   | Hard Light    | 26   | Brightness |
| 7    | Lighten      | 17   | Vivid Light   | 36   | Divide     |
| 8    | Screen       | 18   | Linear Light  |      |            |
| 9    | Color Dodge  | 19   | Pin Light     |      |            |

`ClipBlend` maps each code to a [LayerBlend] enum value of the same meaning (`Brightness` -> the PSD-standard `Luminosity`). Notes:

- The `27..35` gap is unused for leaf layers.
- Code `30` appears only on folders.  It is the folder "pass-through" composite, not a leaf blend mode.  Folders are not emitted as layers, so it is mapped to Normal.
- `Glow Dodge` (10) and `Add (Glow)` (12) are CSP's clamped Color-Dodge / Add variants, kept as distinct enum values rather than folded into Color Dodge / Add.

## 4. Raster pixels — IMPLEMENTED

A layer's pixels are reached through the mipmap chain and decoded from the external chunk:

```
Layer.LayerRenderMipmap -> Mipmap.BaseMipmapInfo -> MipmapInfo.Offscreen -> Offscreen row
  Offscreen.Attribute  = the tile index (grid + per-tile sizes)
  Offscreen.BlockData  = "extrnlid"+GUID
    -> ExternalChunk(ExternalID = same GUID) -> Offset = absolute file offset of the CHNKExta chunk
    -> walk the chunk's block data -> per-tile compressed bytes
```

### Offscreen.Attribute (tile index)

A serialized property block of big-endian u32s and UTF-16BE names. The reader needs the grid shape: the `"Parameter"` entry is followed by four u32s — padded width, padded height, **columns, rows** (width/height are rounded up to a multiple of 256). A `"BlockSize"` entry holds the per-tile byte sizes; `"InitColor"` the fill color. Grid `columns × rows` equals the tile count exactly.

### Block data -> tiles

The block data is a run of tagged records (UTF-16BE tags, big-endian u32 fields): `BlockDataBeginChunk` (a 4-byte size, then strlen+tag, then block index, 12 unknown bytes, and a "has data" flag; if set, a 4-byte tile length and the tile body), then `BlockDataEndChunk`, with a trailing `BlockStatus` and `BlockCheckSum`.  Tiles flagged empty are fully transparent.  Tile `index` maps to grid position `(index % columns, index / columns)`.

### Per-Tile Pixel Format

Each tile body is `[4-byte little-endian length][zlib stream]`, the length is the deflate-stream size and the stream carries the standard `78 01` zlib header.  Every tile is an **alpha plane FIRST, then a color plane**; the inflated byte count identifies the canvas color mode (`Canvas.CanvasDefaultChannelOrder` / `…ColorTypeIndex`):

| Mode       | Channel Order    | Inflated Bytes       | Layout                                                   |
| ---------- | ---------------- | -------------------- | -------------------------------------------------------- |
| RGBA       | 33               | `256·256·5 = 327680` | `[alpha 8-bit][B,G,R,(unused) 8-bit]`                    |
| Greyscale  | 17 (ColorType 1) | `256·256·2 = 131072` | `[alpha 8-bit][gray 8-bit]`                              |
| Monochrome | 17 (ColorType 2) | `256·256/4 = 16384`  | `[alpha 1-bit][value 1-bit]`, MSB-first; value 0 = black |

The output pixel is straight-alpha `R,G,B,A`: RGBA -> (color.R, color.G, color.B, alpha); greyscale -> (gray, gray, gray, alpha); monochrome -> (255·valueBit ×3, 255·alphaBit). The RGBA color plane's own 4th byte is unused (alpha lives only in the alpha plane). Verified by decoding every corpus tile and by rendering greyscale/monochrome strokes (Greyscale.clip / Monochrome.clip). All samples are 8-bit (`CanvasChannelBytes = 1`); CSP is effectively 8-bit-only in practice, so 16-bit is not pursued.

The reader (`ClipRaster.kt`) inflates each non-empty tile, dispatches on the inflated size to expand the color mode to RGBA8888, and assembles the tiles cropped to the bounding box of non-empty tiles (memory-bounding large, mostly-empty layers). An unrecognized tile size is skipped, not guessed.

### Tile-Grid Anchor (Canvas Placement)

`LayerOffsetX/Y` is NOT the grid origin. The grid anchor (canvas position of tile column/row 0) is `LayerOffset + LayerRenderOffscrOffset`, and across the corpus `LayerRenderOffscrOffset = -LayerOffset` exactly, so the offscreen is canvas-anchored at `(0, 0)` (the offscreen is the canvas padded up to a multiple of 256). `bounds` is therefore `(anchorX + minTileCol*256, anchorY + minTileRow*256, croppedWidth, croppedHeight)`.

### Layer Masks (`Layer.LayerLayerMaskMipmap`)

A layer mask is a second offscreen reached the same way but via `LayerLayerMaskMipmap` instead of `LayerRenderMipmap`.  A mask follows the LAYER's color mode (same zlib + 4-byte-prefix framing): an **8-bit** grayscale plane (`256·256 = 65536` bytes) on RGBA/greyscale layers, or a **1-bit** plane (`256·256/8 = 8192` bytes, MSB-first) on monochrome layers. The reader normalizes either to a per-pixel 0..255 plane (a 1-bit mask expands bit-set -> 255).  The mask offscreen is canvas-anchored identically to the color offscreen (`LayerMaskOffset + LayerMaskOffscrOffset = LayerRenderOffscrOffset`), so **mask tile `(col,row)` aligns exactly with color tile `(col,row)`** — no offset math is needed.

The reader bakes the mask into alpha: `outputAlpha = colorAlpha * maskByte / 255`.  A color tile with no co-located stored mask tile is left fully visible, because the mask `Attribute`'s `InitColor` is all-bits-set -> 255 (white reveals).  Baking keeps the neutral `SourceArt` model unchanged (no separate mask field); a future model could carry the mask separately if needed.  Verified on Monochrome.clip (a 1-bit mask reveals via InitColor with a partial hide) composited with a clipping layer that draws white where the 1-bit value is set.

### Clipping (`Layer.LayerClip`)

`LayerClip != 0` means "clip to the layer below" (Photoshop/CSP clipping mask), surfaced directly as `SourceLayer.clipped`.  This is distinct from a raster layer mask.  Verified on `ret.clip` (Triangle 1 clips to Ellipse 1).

## 4b. Layer kinds (`Layer.LayerType` -> `SourceLayerKind`)

`LayerType` tells which layers carry ingestible pixels; the reader surfaces it as
`SourceLayer.kind` so a raster-only consumer can ingest rasters and knowingly skip the rest:

| LayerType    | kind         | raster?                                         |
| ------------ | ------------ | ----------------------------------------------- |
| `1`, `3`     | `Raster`     | yes — render raster is persisted and decodes    |
| `0` (text)   | `Text`       | no — text object; content regenerated on load   |
| `0` (vector) | `Vector`     | no — vector object; content regenerated on load |
| `1584`       | `Fill`       | no — Paper / solid-fill, procedural             |
| `4098`       | `Adjustment` | no — procedural correction/filter               |

OBJECT layers (type `0`) store their content as text/vector data, NOT a raster: verified on ClippingMaskLayers.clip — all four mip levels and the thumbnail of the text and vector layers reference `extrnlid` GUIDs absent from the file (96 offscreen references but only 33 chunks exist). They are read as placeholder-raster leaves with the correct `kind`. Recovering their pixels would require rasterizing the object data (out of scope, and this project's consumer is raster-only) or the user rasterizing the layer in CSP first (making it type `1`). Text vs vector is told apart by the `TextLayerType` column (text) / `VectorObjectList` (vector).

Visibility: `Layer.LayerVisibility` is a bitfield whose bit 0 is "shown", values 1 and 3 are visible, and 2 is hidden.  Surfaced as `SourceLayer.visible` and `SourceGroup.visible`; a hidden folder hides its whole subtree (effective visibility is left to the consumer).

Schema-version robustness: the CLIP SQLite schema GAINS columns across Clip Studio versions.  `TextLayerType` exists in 2026 files, but not files from 2022. So `selectAllLayers` uses only columns present in every supported version, and version-specific lookups (text-layer ids) run as isolated queries whose failure is swallowed (absent column ⇒ feature simply unavailable).  Any new column added to a query must be checked against the oldest sample or split out the same way.

## 5. Known gaps / future refinement

- The mask is baked into alpha (lossy): the neutral model has no separate mask channel, so a mask cannot be round-tripped or toggled after import.
- Folder blend / opacity / pass-through are now carried on `SourceGroup`, but actually compositing them (group isolation, applying folder blend) and resolving which base a clip layer binds to are left to the consumer — this reader only exposes the attributes and flags.
- Only the base (full-resolution) mipmap level is read; the lower-res levels are ignored.
- Color mode: RGBA, greyscale, and monochrome are decoded (§4).  16-bit/HDR (`CanvasChannelBytes` 2/4) is not implemented and is not pursued.  CSP is effectively 8-bit-only in practice; such a tile would be an unrecognized size and skipped (the layer would come through blank).
- Monochrome "value >= 128 draws white": a monochrome pixel's 1-bit value (1 = white, 0 = black) is decoded straight; how a white-drawing clipping layer composites onto its base is a consumer concern (this reader exposes each layer's pixels + the clip flag, not the composite).
