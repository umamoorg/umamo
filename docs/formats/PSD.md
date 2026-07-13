# PSD File Format

`.psd` is the native document format of **Adobe Photoshop**.   It is a flat binary file (big-endian) holding a header, a global color table, image-resource metadata, the **layer stack** with each layer's pixels, and a flattened composite.

Umamo reads `.psd` as a source-art format alongside KRA and CLIP: it decodes layers into the neutral `SourceArt` model (canvas size + ordered layers with bounds, opacity, blend, a stable id (the Photoshop `lyid` layer id when present, else name+order), and RGBA8888 pixels).  Scope is **read-only**.

> **Provenance.** The byte layout below is the public Adobe Photoshop File Format Specification.  Umamo's pixel decoder (channel decompression and assembly) is a Kotlin port of the BSD-3-licensed TwelveMonkeys `imageio-psd` reader; see [CREDITS.md](../../CREDITS.md).  The layer-record parser and the neutral-model mapping are Umamo's own.  Umamo is licensed under GPLv3.

> **Supported scope.** 8- and 16-bit **RGB/RGBA** and **Grayscale**, **Indexed-color**, and 1-bit **Bitmap**, with all four channel compressions (raw, RLE, ZIP, ZIP+prediction).  16-bit is reduced to 8-bit (high byte).  **CMYK, Lab, Multichannel, Duotone, and 32-bit float are rejected** with a clear error — they are not used for 2D puppet art and their conversions need ICC color management we deliberately omit.  Only PSD version 1 is read; PSB (version 2, 64-bit fields) is rejected.

A reader takes the whole file as one `ByteArray` (a `.psd`'s working set fits in memory) and walks it with absolute offsets.  All multi-byte integers are **big-endian**.

---

## 1. File Header (26 bytes @ +0x00)

| Offset | Type    | Field     | Notes                                |
| ------ | ------- | --------- | ------------------------------------ |
| +0     | byte[4] | Signature | `8BPS` (`0x38 0x42 0x50 0x53`)       |
| +4     | u16     | Version   | 1 = PSD, 2 = PSB (Umamo rejects PSB) |
| +6     | byte[6] | Reserved  | Zero                                 |
| +12    | u16     | Channels  | Color channels incl. alpha (1..56)   |
| +14    | i32     | Rows      | Image height in pixels               |
| +18    | i32     | Columns   | Image width in pixels                |
| +22    | u16     | Depth     | Bits per channel: 1, 8, 16, or 32    |
| +24    | u16     | Mode      | Color mode (see §5)                  |


Canvas size comes straight from `Columns`/`Rows` — Umamo does **not** decode the flattened composite image (the final section) at all.

---

## 2. Color Mode Data and Image Resources

Two length-prefixed sections follow the header, each a `u32 length` then `length` bytes:

- **Color Mode Data** (@ +26).  Empty (length 0) for every mode except **Indexed**, where it is the 256-entry palette: 768 bytes, **non-interleaved** — all 256 red bytes, then all 256 green, then all 256 blue.  Umamo keeps these bytes and indexes them as `red[i] = data[i]`, `green[i] = data[256+i]`, `blue[i] = data[512+i]` (with `paletteSize = length / 3`).
- **Image Resources.**  Thumbnails, resolution, EXIF/XMP, ICC profile, etc.  Umamo skips this section entirely.

---

## 3. Layer records (Layer and Mask Information)

After the Image Resources comes the Layer and Mask Information section:

```
u32  Layer and Mask Information length
u32  Layer Info length
i16  Layer count        (negative => first alpha channel is the merged transparency; take |count|)
     repeat |count| times:  Layer record   (see below)
     Channel image data for every layer, in order   (see §4)
```

Each **Layer record**:

| Type                      | Field                 | Notes                                                                                                                        |
| ------------------------- | --------------------- | ---------------------------------------------------------------------------------------------------------------------------- |
| i32 ×4                    | Top,Left,Bottom,Right | Layer rectangle, top-left origin; width=right-left                                                                           |
| u16                       | Channel Count         | Then that many channel-info entries                                                                                          |
| { i16 id; u32 length } ×n | Channel Info          | id: 0/1/2 color, -1 alpha, -2/-3 masks; length = bytes of the channel's data block in §4 (incl. its 2-byte compression code) |
| byte[4]                   | Blend Signature       | `8BIM`                                                                                                                       |
| byte[4]                   | Blend Key             | E.g. `norm`, `mul `, `scrn` (see §3.1)                                                                                       |
| u8                        | Opacity               | 0..255                                                                                                                       |
| u8                        | Clipping              | 0 = base, 1 = clipped to the layer below                                                                                     |
| u8                        | Flags                 | Bit 0x02 set = layer hidden (eye off)                                                                                        |
| u8                        | Filler                | 0                                                                                                                            |
| u32                       | Extra Data Length     | Bounds the mask + blend ranges + name + additional info                                                                      |
| ...                       | Layer Mask Data       | U32-length-prefixed                                                                                                          |
| ...                       | Blend Ranges          | U32-length-prefixed                                                                                                          |
| Pascal string             | Layer Name            | Legacy ASCII (u8 length + bytes)                                                                                             |
| ...                       | Additional Info       | Blocks of `8BIM`/`8B64` + 4-char key + u32 length + data                                                                     |


**Folder structure** is carried by the `lsct` (Section Divider Setting) additional-info block: type `0` = normal layer, `1` = open folder header, `2` = closed folder header, `3` = bounding divider (the hidden "</Layer group>" that closes a group).  Umamo scans the extra-data region for an `lsct` block behind a valid `8BIM`/`8B64` signature and reads the u32 type that follows its length field.  A folder header whose blend key is `pass` is a pass-through folder.

Records are stored **bottom-to-top**.  Umamo walks them in reverse (top-to-bottom) so a folder header opens its group before its children and the bounding divider closes it; folder markers are structural and are not emitted as drawable layers.

Umamo also reads the **`lyid`** additional-info block (a 4-byte int) — Adobe's per-layer Layer ID, which "stays with the layer for the life of the layer" (stable across rename and reorder) — and uses it as the layer's stable identity when present.

### 3.1 Blend mode keys → neutral blend

`norm`→Normal, `dark`→Darken, `mul `→Multiply, `idiv`→ColorBurn, `lbrn`→LinearBurn, `dkCl`→DarkerColor,
`lite`→Lighten, `scrn`→Screen, `div `→ColorDodge, `lddg`→Add, `lgCl`→LighterColor, `over`→Overlay,
`sLit`→SoftLight, `hLit`→HardLight, `vLit`→VividLight, `lLit`→LinearLight, `pLit`→PinLight,
`hMix`→HardMix, `diff`→Difference, `smud`→Exclusion, `fsub`→Subtract, `fdiv`→Divide, `hue `→Hue,
`sat `→Saturation, `colr`→Color, `lum `→Luminosity.  Unknown keys and `diss` (Dissolve) → Normal.

---

## 4. Channel image data

The channel data of every layer follows all the records, stored **sequentially** in the same order: for each layer, for each of its channels (in channel-info order), one block of exactly that channel's `length` bytes.  Umamo precomputes each layer's absolute data offset by accumulating these lengths, so it can seek to any layer directly.

Each channel block is:

```
u16  Compression     (0 = raw, 1 = RLE/PackBits, 2 = ZIP, 3 = ZIP with prediction)
...  the channel's sample bytes for one plane (length - 2 bytes)
```

A decoded plane is `height × rowBytes`, where `rowBytes = width` (8-bit), `width*2` (16-bit), or `(width+7)/8` (1-bit).

- **Raw (0).** The sample bytes verbatim.
- **RLE (1).** A table of per-row compressed byte counts (`height` × u16), then Apple **PackBits** runs: a control byte `n` of 0..127 copies the next `n+1` bytes; -127..-1 repeats the next byte `1-n` times; -128 is a no-op.  Each row decodes to `rowBytes`.
- **ZIP (2).** A standard zlib stream (header + Adler-32) inflating to `height × rowBytes`.
- **ZIP with prediction (3).** As ZIP, then the **horizontal differencing predictor** is un-applied per row: each sample is the running sum of the differences along its row (TIFF 6.0 §14; one sample per pixel for planar channels).  Umamo supports this for 8- and 16-bit samples.

Channels with id `< -1` (user/vector masks) are skipped; their block length still advances the cursor.

---

## 5. Color modes and assembly (→ RGBA8888, straight alpha)

| Mode         | Value | Depth(s) | Assembly                                                   | Umamo    |
| ------------ | ----- | -------- | ---------------------------------------------------------- | -------- |
| Bitmap       | 0     | 1        | Single bit plane; set bit = black, clear = white; opaque   | Yes      |
| Grayscale    | 1     | 8/16     | Channel 0 → R=G=B; channel -1 → alpha (else opaque)        | Yes      |
| Indexed      | 2     | 8        | Channel 0 = palette index → RGB via §2 palette; -1 → alpha | Yes      |
| RGB          | 3     | 8/16     | Channels 0/1/2 → R/G/B; -1 → alpha (else opaque)           | Yes      |
| CMYK         | 4     | —        | Needs ICC conversion                                       | Rejected |
| Multichannel | 7     | —        | —                                                          | Rejected |
| Duotone      | 8     | —        | —                                                          | Rejected |
| Lab          | 9     | —        | Needs ICC conversion                                       | Rejected |


16-bit samples are reduced to 8-bit by keeping the high (big-endian first) byte.  All output is **straight (non-premultiplied) alpha**, top row first, cropped to the layer's bounds — the compositor applies layer opacity itself.

---

## 6. Umamo implementation notes

- Code: `module/format/src/jvmAndroidMain/kotlin/org/umamo/format/psd/` — `PsdLayerRecords.kt` (header, palette, records, channel-data offsets), `PsdRaster.kt` (decompression + assembly, the TwelveMonkeys port), `PsdReader.kt` (folder walk + neutral-model mapping; registered in `FormatRegistry`).
- Lives in `jvmAndroidMain` (not `jvmMain`): it needs only `java.nio` and `java.util.zip`, both present on Android, so PSD import works on tablets as well as desktop.
- PSD carries a per-layer id in the `lyid` additional-layer-info block — Adobe's Layer ID, which "stays with the layer for the life of the layer" (stable across rename and reorder).  Umamo uses it as the layer's stable identity when present.  It is **writer-dependent**, though: Photoshop writes it, but some exporters (Clip Studio Paint, Krita-to-PSD, GIMP) may omit it, so the reader falls back to **name + order** when `lyid` is absent.  CLIP and KRA always carry stable ids, so they remain the most reliable re-import sources.
- Detection is by the `8BPS` magic at +0x00, with a `.psd` extension fallback in `FormatRegistry`.
