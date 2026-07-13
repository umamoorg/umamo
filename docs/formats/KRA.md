# KRA File Format

`.kra` is the native project file of **Krita**, the open-source painting application by KDE. It is a **ZIP** archive bundling:

1. an XML document (`maindoc.xml`) describing the image and its layer stack, and
2. one **tiled, LZF-compressed raster file per layer** that carries pixel data, plus thumbnails, ICC profiles, and a flattened composite.

Umamo reads `.kra` as a peer source-art format alongside PSD: it decodes paint layers into the neutral `SourceArt` model (canvas size + ordered layers with bounds, opacity, blend, stable id, and RGBA8888 pixels).  Scope is **read-only**.

> **Provenance.** Krita is open source, so this specification is ported directly from Krita's own GPLv3 licensed code.  Source citations below point at the Krita tree (`git@invent.kde.org:graphics/krita.git`).  Umamo's reader is an independent Kotlin reimplementation against this specification and it is not a copy of Krita's code.  Umamo is licensed under GPLv3 as well.

---

## 1. Container: ZIP

A `.kra` is an ordinary ZIP archive (written via KDE's `KoStore`; see `plugins/impex/libkra/kra_converter.cpp`). Typical entries, where `<image>` is the value of the `<IMAGE name=…>` attribute (see §2):

| File                                   | Type                                  |  Comment                            |
| -------------------------------------- | ------------------------------------- | ----------------------------------- |
| mimetype                               | "application/x-krita"                 | (Stored First, Uncompressed)        |
| maindoc.xml                            | The Image + Layer Tree                | (Also Aliased "Root")               |
| documentinfo.xml                       | Author/Title Metadata                 | (Not Needed)                        |
| preview.png                            | Small Thumbnail                       | (Not Needed)                        |
| mergedimage.png                        | Flattened RGBA Composite              | (Ground Truth; Not Used For Output) |
| <image>/layers/<filename>              | Tiled Pixel Data For One Raster Layer |                                     |
| <image>/layers/<filename>.defaultpixel | Default Fill Color                    | (Pixelsize Bytes)                   |
| <image>/layers/<filename>.icc          | Per-Layer ICC Profile                 | (Not Needed)                        |
| <image>/annotations/icc                | Image ICC Profile                     | (Not Needed)                        |

- The per-layer data file name is the layer's `filename` attribute joined under `<image>/layers/` (e.g. image `Unnamed`, layer `filename="layer4"` → `Unnamed/layers/layer4`).
- `mimetype` content is the literal string `application/x-krita` (note: the `<IMAGE mime>` attribute instead reads `application/x-kra`).
- A reader can index every entry by name into a map; the ZIP is sequential but a `.kra`'s working set fits in memory.

---

## 2. `maindoc.xml` — the image and layer tree

### Document shape

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE DOC PUBLIC '-//KDE//DTD krita 2.0//EN' 'http://www.calligra.org/DTD/krita-2.0.dtd'>
<DOC xmlns="http://www.calligra.org/DTD/krita" kritaVersion="5.2.9" editor="Krita" syntaxVersion="2.0">
	<IMAGE name="Unnamed" width="4096" height="2160" colorspacename="RGBA" x-res="300" y-res="300" …>
		<layers>
			<layer nodetype="grouplayer" name="/" filename="layer2" uuid="{…}" x="0" y="0" …>
				<layers>
					<layer nodetype="paintlayer" name="art" filename="layer4" colorspacename="RGBA" opacity="255" visible="1" x="-12" y="-652" compositeop="normal" uuid="{…}"/>
				</layers>
			</layer>
		</layers>
	</IMAGE>
</DOC>
```

> **DOCTYPE warning.** `maindoc.xml` declares an external DTD. A default XML parser will try to fetch that URL and fail (offline, or it no longer serves a valid DTD). Disable external-DTD loading (`http://apache.org/xml/features/nonvalidating/load-external-dtd` = false) and/or install a no-op `EntityResolver` before parsing.

### Two Schema Variants

Both appear in real files and must be handled:

|                         | Newer (syntaxVersion 2.x)           | Legacy (syntaxVersion 1.x)         |
| ----------------------- | ----------------------------------- | ---------------------------------- |
| Namespace (`xmlns`)     | `http://www.calligra.org/DTD/krita` | `http://www.koffice.org/DTD/krita` |
| Layer container element | `<layers>` (lowercase)              | `<LAYERS>` (uppercase)             |
| Node-class attribute    | `nodetype`                          | `layertype`                        |

Because the default namespace differs (and would namespace-qualify every element), match elements by **local name, case-insensitively** and ignore namespaces. Attributes carry no namespace, so read them by plain name. Read the node class from `nodetype` falling back to `layertype`.

### `<IMAGE>` Attributes Used

| Attribute         | Meaning                                                      |
| ----------------- | ------------------------------------------------------------ |
| `name`            | Image name; also the `<image>/layers/` directory prefix      |
| `width`, `height` | Canvas size in pixels                                        |
| `colorspacename`  | Image color space (per-layer `colorspacename` may override)  |

(`x-res` / `y-res` are DPI; `profile`, `mime`, `description`, `proofing-*` are not needed for ingest.)

### `<layer>` Attributes Used

| Attribute                | Meaning                                                                   |
| ------------------------ | ------------------------------------------------------------------------- |
| `nodetype` / `layertype` | Node class (see §3)                                                       |
| `name`                   | Layer name (display + fuzzy re-import match)                              |
| `filename`               | Base name of this layer's data file under `<image>/layers/`               |
| `uuid`                   | `{GUID}`, **stable across renames/reorders** — the re-import identity key |
| `opacity`                | 0–255 (normalise to 0.0–1.0)                                              |
| `visible`                | `1`/`0`                                                                   |
| `x`, `y`                 | Layer offset on the canvas, **may be negative** (see §6)                  |
| `colorspacename`         | This layer's color space (channel order + depth; see §5)                  |
| `compositeop`            | Blend-mode id (see below)                                                 |
| `channelflags`           | Per-channel enabled mask; encodes "Inherit Alpha" (see below)             |

`compositeop` → blend mapping (others degrade to normal): `normal` → Normal, `multiply` → Multiply, `screen` → Screen, `add` / `linear_dodge` → Add.

### Channel Masking and Clipping ("Inherit Alpha")

The **`channelflags`** attribute is a per-channel enable mask: one character per channel in channel-index order, `1` = enabled, `0` = disabled (more precisely, only `0` disables — any other character enables, and indices past the string default to enabled), or **empty**, meaning all channels enabled (the default).  A disabled channel passes through from the content below instead of being written by this layer.

The channel-index order is the color space's own (the `addChannel` sequence), which is **not** RGBA order: for RGB it is **Blue, Green, Red, Alpha**, and for GRAYA it is **Gray, Alpha**.  So in `"1110"` the disabled bit is the *alpha* (index 3), and in `"0111"` it is *blue* (index 0).

Krita's **Inherit Alpha** toggle — the closest analog to a PSD clipping mask, where the layer is clipped to the alpha of the content below it within its group — is simply this mask with the **alpha channel disabled** (`KisLayer::alphaChannelDisabled()`).

| `channelflags`  | Meaning                                                                        |
| --------------- | ------------------------------------------------------------------------------ |
| `""` / `"1111"` | All channels on → full mask, not clipped                                       |
| `"1110"`        | RGBA alpha off → **Inherit Alpha (clipped)** — real Krita comic-template value |
| `"0111"`        | RGBA blue (index 0) off, alpha on → color-channel masking, **not** clipped     |
| `"1101"`        | RGBA red (index 2) off                                                         |
| `"10"`          | GRAYA alpha off → **Inherit Alpha (clipped)**                                  |

Umamo surfaces the full mask on the neutral model as `ChannelMask(red, green, blue, alpha)` (re-ordered out of Krita's channel order; for GRAYA the single gray bit maps onto R, G, and B together, since the raster expands gray to RGB).  The alpha bit is additionally mapped onto the neutral `clipped` flag (`clipped = !channelMask.alpha`).  Clipping is an approximation: PSD clips to the single base layer directly below, whereas Krita clips to the composite of everything below in the group.

> Sources: `libs/image/kis_layer.cc:334-339` (`alphaChannelDisabled`); `plugins/impex/libkra/kis_kra_utils.cpp` (`flagsToString` / `stringToFlags`, default token `'0'`); channel order in `plugins/color/lcms2engine/colorspaces/rgb_u8/RgbU8ColorSpace.cpp` and `gray_u8/GrayU8ColorSpace.cpp`.

> Sources: `plugins/impex/libkra/kis_kra_saver.cpp` (image element), `kis_kra_savexml_visitor.cpp` (layer elements), `kis_kra_tags.h` (attribute name constants), `kis_kra_loader.cpp` (loader side).

---

## 3. Layer (node) Types

The `nodetype` / `layertype` value names the node class. Only some carry simple raster pixel data:

| nodetype                                                                           | carries raster?              | Umamo handling                                     |
| ---------------------------------------------------------------------------------- | ---------------------------- | -------------------------------------------------- |
| `paintlayer`                                                                       | yes                          | decoded into a `SourceLayer`                       |
| `grouplayer`                                                                       | no (a container)             | recursed into for hierarchy; not itself a drawable |
| `adjustmentlayer`, `generatorlayer`                                                | no (filter/generator config) | skipped                                            |
| `clonelayer`, `shapelayer`, `filelayer`, `referenceimages`                         | no / indirect                | skipped                                            |
| `transparencymask`, `selectionmask`, `colorizemask`, `filtermask`, `transformmask` | mask data                    | skipped                                            |

Group layers nest their own `<layers>`/`<LAYERS>` container. A group (and some other types) may still carry a `filename` attribute, but for skipped types it must **not** be opened as paint data.

> Source: `plugins/impex/libkra/kis_kra_tags.h` (the `*Value` node-type strings).

---

## 4. Layer Data File: Tiled Rasters

Krita's canvas is unbounded; each raster layer stores its pixels as a sparse grid of fixed **64×64** tiles, present only where the layer has content. The data file (`<image>/layers/<filename>`) is a tiny ASCII header followed by binary tiles.

### Header (one `KEY VALUE` per `\n`-terminated line)

```
VERSION 2
TILEWIDTH 64
TILEHEIGHT 64
PIXELSIZE <bytesPerPixel>
DATA <tileCount>
```

`PIXELSIZE` is the authoritative bytes-per-pixel (channel count × bytes per channel). `tileDataSize = PIXELSIZE × TILEWIDTH × TILEHEIGHT`.

### Per-tile Framing (Repeated `DATA` Times)

```
<x>,<y>,LZF,<dataSize>\n          tile header line (comma-separated)
<dataSize bytes>                  payload: 1 flag byte + tile data
```

- `x`, `y` are the tile's top-left coordinate in **paint-device-local** space (a multiple of 64; may be negative).
- The compression name is always `LZF`.
- `dataSize` is the total payload length **including** the leading flag byte.
- Payload byte 0 is the flag: `0` = **RAW** (the next `tileDataSize` bytes are the tile verbatim, already interleaved), `1` = **COMPRESSED** (the rest is an LZF stream that inflates to `tileDataSize` bytes in **planar** order — see §5).

> Sources: header in `libs/image/tiles3/kis_tiled_data_manager.cc:181-196`; per-tile framing and the `RAW_DATA_FLAG`/`COMPRESSED_DATA_FLAG` constants in `libs/image/tiles3/swap/kis_tile_compressor_2.cpp`; tile dimension constants in `libs/image/tiles3/kis_tile_data.cc:24-25`.

---

## 5. Decoding Tile Pixels

### LZF decompression

Krita uses a fast LZF variant (a Lempel–Ziv byte coder). The decoder reads a stream of control bytes:

- **Control byte `c`.** Let `len = c >> 5` and `ctrl = c + 1`.
- If `ctrl < 33` (i.e. top 3 bits are 0): a **literal run** of `ctrl` raw bytes follows; copy them out.
- Otherwise a **back reference**: `len -= 1`; reference position `ref = out_pos − ((c & 31) << 8) − 1`; if `len == 6`, read one more byte and add it to `len`; then `ref −= next_byte`. Copy `len + 3` bytes from `ref` (overlapping copies are allowed and expand runs).

Output must be written strictly left to right because back references read already-decoded bytes. Decoded length must equal `tileDataSize`.

> Source: `libs/image/tiles3/swap/kis_lzf_compression.cpp:173-236` (`lzff_decompress`).

### Delinearisation (planar → interleaved)

Before compressing, Krita reorders interleaved pixel bytes into a **planar** layout (all of channel 0 for every pixel, then all of channel 1, …) because per-channel runs compress better. Reading reverses it. With `stride = tileDataSize / PIXELSIZE` (the pixel count):

```
interleaved[pixel × PIXELSIZE + channel] = planar[channel × stride + pixel]
```

RAW tiles are already interleaved and skip this step; only COMPRESSED tiles are delinearised.

> Source: `libs/image/tiles3/swap/kis_abstract_compression.cpp:22-66` (`linearizeColors` / `delinearizeColors`).

### Channel Order and Bit Depth (→ RGBA8888)

The interleaved native pixel layout depends on the layer's `colorspacename`. Channel order differs between integer and float color spaces:

| Family | Depth                                                 | Byte Order (Per Pixel)    | → RGBA8888                         |
| ------ | ----------------------------------------------------- | ------------------------- | ---------------------------------- |
| RGBA   | 8-bit (`KoBgrU8Traits`)                               | B, G, R, A                | Swap To R,G,B,A                    |
| RGBA   | 16-bit (`KoBgrU16Traits`, little-endian)              | B, G, R, A (2 bytes each) | Take Each Channel's MSB            |
| RGBA   | 32-bit float (`KoRgbColorSpaceTraits`, little-endian) | R, G, B, A (4 bytes each) | Clamp 0..1, ×255                   |
| GRAYA  | 8 / 16 / 32-bit (`KoGrayColorSpaceTraits`)            | Gray, Alpha               | Replicate Gray → R=G=B, Keep Alpha |

Key facts (verified in Krita's traits headers): **integer RGB is BGRA** (`red_pos=2, green_pos=1, blue_pos=0`), while **float RGB is RGBA** (`red_pos=0`). Grayscale is `gray_pos=0` then alpha. The neutral model is RGBA8888 straight-alpha, so higher depths are down-converted (lossy by the model's contract).

`colorspacename` resolution: family by prefix (`RGBA…` / `GRAYA…`); depth by suffix scan (`F32` → float32, `F16` → unsupported, any `16` → uint16, else uint8). Cross-check the implied pixel size against the tile header's `PIXELSIZE`. CMYK/LAB/XYZ/YCbCr are out of scope (raise a clear error).

> Sources: `libs/pigment/KoBgrColorSpaceTraits.h`, `KoRgbColorSpaceTraits.h`, `KoGrayColorSpaceTraits.h`; color-space → traits wiring in `libs/pigment/colorspaces/KoRgb{U8,U16}ColorSpace.h`.

### `.defaultpixel`

A sibling file `<filename>.defaultpixel` holds `PIXELSIZE` bytes (same native order) giving the color for areas inside the layer's extent not covered by a tile. Absent ⇒ transparent. Convert it the same way and pre-fill the assembled buffer with it.

> Source: `plugins/impex/libkra/kis_kra_load_visitor.cpp:594-607`.

---

## 6. Coordinate Model and Layer Assembly

Tile coordinates are in the layer's paint-device-local space. The layer's canvas position is an **additional** offset applied via the `x` / `y` attributes (`node->setX(x); node->setY(y)`):

```
canvas position = tile coordinate + (layer.x, layer.y)
```

Both `x` and `y` may be negative (the canvas is unbounded; layers can extend off-canvas).

To assemble one layer into a cropped RGBA8888 raster:

1. Parse every tile; compute the bounding box of all tile rects (min left/top, max right/bottom) in paint-device space.
2. Allocate `(width × height × 4)` bytes; pre-fill with the converted default pixel (a transparent default leaves the zero-init buffer).
3. Blit each tile into the buffer at `(tile.x − minLeft, tile.y − minTop)`, converting each pixel to RGBA8888.
4. The layer's canvas bounds are `(minLeft + layer.x, minTop + layer.y, width, height)`, top-left origin.

> Source: `plugins/impex/libkra/kis_kra_loader.cpp:1005-1006` (`setX`/`setY`).

### Stack Order

Within a `<layers>` container, document order is **top-to-bottom** (the first `<layer>` is the topmost; e.g. `load_test.kra`'s first layer is literally named "topmost layer"). A depth-first traversal collecting paint layers in document order yields the flattened top-to-bottom stack; assign draw order with top-most = 0. For a painter's-algorithm draw list (bottom-to-top), reverse it — matching the PSD reader's contract.

---

## 7. Quick Reference: Decode Order

1. Unzip all entries into a name → bytes map.
2. Parse `maindoc.xml` (external DTD disabled) → `<DOC>` → `<IMAGE>`; read `name`, `width`, `height`, `colorspacename`.
3. Depth-first walk the layer tree (match `layers`/`layer` by local name); collect `paintlayer`s in document order, building each one's group path from ancestor group names.
4. For each paint layer: read `<image>/layers/<filename>`; parse the tile header; for each tile, read the flag and RAW-copy or LZF-decompress + delinearise to interleaved native pixels.
5. Resolve the color format from `colorspacename` + `PIXELSIZE`; read `.defaultpixel`; assemble the cropped RGBA8888 raster and compute canvas bounds (tile bbox + layer `x`/`y`).
6. Map fields: `id = uuid`, `visible` (`@visible != "0"`), `opacity/255`, `compositeop` → blend, `channelflags` → `ChannelMask` (and `clipped = !channelMask.alpha`; see §2); emit the layer list bottom-to-top.  Emit a `SourceGroup` per `grouplayer` (path, `visible`, `opacity`, `compositeop` → blend, `passthrough`), keyed by slash-joined path, so folder-level visibility survives the flattening.

---

## 8. Umamo Implementation Notes

- The reader lives in `:format` `jvmAndroidMain` (`org.umamo.format.kra`): it needs only `java.util.zip` + JDOM, both available on Android, so KRA is the first art-ingest format usable on tablets (PSD is desktop-only via `javax.imageio`).
- Files: `KraReader` (ZIP + XML + assembly), `KraTileData` (tile framing), `KraLzf` (LZF + delinearise, pure Kotlin), `KraColorModel` (color conversion).
- Supported: paint layers; group layers (surfaced as `SourceGroup` for folder metadata, not as drawables); per-layer and per-folder visibility (`@visible`); RGBA / GRAYA in 8/16/32-bit; per-channel masking via `channelflags` (surfaced as `ChannelMask`), including Inherit Alpha clipping (see §2). Not yet: non-paint node types; mask raster; CMYK/LAB and other color models; group Inherit-Alpha (group `clipped` is left false — its channelflags need the resolved color space).
- Re-import: the reader exposes the stable `uuid` via `LayerId`, and `:reimport` carries a matching `LayerKey.KraLayerId(uuid)` binding key (alongside `ClipLayerId` / `PsdNamePath`). The reconciler that consumes it is still stubbed, so the key is in place for the future re-import workflow but not yet exercised.
- Tests: pure unit tests for LZF/delinearise and the channelflags → clipped mapping, plus a corpus-gated end-to-end reader test (point `-Dkra.sample=…` at a `.kra`, or drop one under `test/corpus/krita/`). Channel order was validated to a 100% pixel match against a file's own embedded `mergedimage.png`; the clipped mapping was validated against Krita's comic templates (`channelflags="1110"` layers).
