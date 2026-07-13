# CMO3 File Format

`.cmo3` is the project file of the **Live2D Cubism Editor (Modeler)**.  It is a custom container called **CAFF** (magic `"CAFF"`) that bundles:

1. a `main.xml` document holding the entire model object graph, serialized by the editor's reflection-based XML serializer, and
2. a set of embedded **PNG** files — the imported artwork (the PSD's layers, decomposed to one PNG per layer) plus thumbnails and texture-atlas tiles.

This spec describes the on-disk format only; it was derived empirically and validated byte-for-byte against a real `.cmo3` sample.

> The same CAFF container is used by sibling formats (`.can3` animation, etc.); only the embedded payloads differ.  This format document only describes CMO3 specifics.

---

## 1. Container: CAFF

### Conventions

- **All multi-byte integers are big-endian.**
- **Obfuscation = XOR.** The header stores a 32-bit `obfuscateKey`. A value `W` bytes wide is XOR-ed with the low `W` bytes of the key:
	- `byte  ^= key & 0xFF`
	- `short ^= key & 0xFFFF`
	- `int   ^= key & 0xFFFFFFFF`
	- `long  ^= ((key << 32) | key)`
	- byte arrays / string bytes: each byte `^= key & 0xFF`
- `obfuscateKey == 0` means _not obfuscated_. When obfuscation is on, the key is random, but is chosen so `key & 0x7F != 0`. In the sample, `key = 0xD7FC71B1` (so the string XOR byte is `0xB1`).
- **The header and preview block are never obfuscated.** Only the file table and the file blobs are (each blob individually, gated by its own `isObfuscated` flag).
- **`skip(n)`** below means _n literal bytes that are read/written and ignored_ (zero-filled by the writer) — a reserved gap, **not** alignment padding.
- **Strings** = a 7-bit **varint** length (MSB = continuation, big-endian groups; see the byte layout below) followed by that many UTF-8 bytes (XOR-obfuscated with the key).

### Byte layout

```
offset  size  field                     notes
------  ----  ------------------------  ------------------------------------------
HEADER (not obfuscated)
  0     4     archive_identifier        "CAFF"
  4     3     archive_version           u8[3], = {0,0,0}
  7     4     format_identifier         char[4], "----" in cmo3 (default)
 11     3     format_version            u8[3], = {0,0,0}
 14     4     obfuscateKey              i32; 0 => no obfuscation, else XOR key
 18     8     (skip 8)                  reserved gap

PREVIEW (not obfuscated)
 26     1     imageFormat               ImageType: 0=NOT_INIT,1=PNG,127=NO_PREVIEW
 27     1     colorType                 ColorType: 0=NOT_INIT,1=ARGB,2=RGB,127=NO_PREVIEW
 28     2     (skip 2)                  reserved gap
 30     2     width                     i16
 32     2     height                    i16
 34     8     startPos                  i64, byte offset of preview PNG in file
 42     4     fileSize                  i32
 46     8     (skip 8)                  reserved gap
                                        (sample has NO_PREVIEW, all zero)

FILE TABLE (obfuscated with obfuscateKey)
 54     4     count                     i32, number of file entries
        ...   count × FileInfo:
                varint+bytes  filePath  (UTF-8)
                varint+bytes  tag       ("" or "main_xml")
                8   startPos            i64  (see note below)
                4   fileSize            i32  (stored/compressed size of the blob)
                1   isObfuscated        bool (1 byte)
                1   compressOption      u8: 16=RAW, 33=FAST(zip L1), 37=SMALL(zip L5)
                8   (skip 8)            reserved gap

FILE BLOBS
        ...   the raw bytes of each file, concatenated, at the offsets in the table
        2     guard bytes               0x62 0x63 ('b','c') written after the last blob
        ...   (writer fixup region; the reader never reads past the blobs)
```

### `startPos` caveat

The on-disk `startPos` in the sample decodes to `0x28038E4E_xxxxxxxx`; the **high 32 bits are a constant writer artifact and must be masked off**. The **low 32 bits are the real file offset**. This is verified by chaining: `offset(file[i]) + fileSize(file[i]) == offset(file[i+1])` for all entries. Parsers should use `startPos & 0xFFFFFFFF`.

(An editor writer revision writes the offset via a seek-back "fixup" pass that would produce high-bits = key; the sample came from a slightly different revision. Either way, the low word is authoritative and offsets are < 4 GB.)

### Compression

- `RAW` (16): blob is the file's raw bytes (PNGs are stored RAW — already compressed).
- `FAST` (33) / `SMALL` (37): blob is a **Java `ZipOutputStream` stream** containing a single entry named `contents`. Decompress with raw DEFLATE (`zlib -15`) starting after the local file header — Python's `zipfile` rejects it because Java writes a streaming data-descriptor zip. `main.xml` uses `FAST`.
- De-obfuscate (XOR) **before** decompressing.

---

## 2. Sample inventory (`Erica Tamamo.cmo3`, 33,904,442 bytes)

```
obfuscateKey = 0xD7FC71B1   format_id = "----"   preview = NO_PREVIEW
count = 926 entries:
	1   main.xml            tag="main_xml"  FAST  obfuscated  →  23,751,804 bytes XML
	180 imageFileBuf*.png   tag=""          RAW   obfuscated  =  26.3 MB  (layer pixel data, INT_ARGB)
	745 image*.png          tag=""          RAW   obfuscated  =  0.78 MB  (icons/thumbnails, atlas tiles)
```

---

## 3. Payload: `main.xml` — the serialized model

### Document shape

```xml
<?xml version="1.0" encoding="UTF-8"?>
<?version CModelSource:13?>          <!-- per-class schema versions, one PI per class -->
<?version CArtMeshSource:4?>
<?version SerializeFormatVersion:2?>
...
<root fileFormatVersion="...">
	<shared> ... </shared>             <!-- flat pool of referenceable objects -->
	<main>  <CModelSource .../>  </main>   <!-- the model root object graph -->
</root>
```

`fileFormatVersion` is a packed decimal whose leading digit (value ÷ 100 000 000) is the Cubism
major generation.  Corpus samples: `401010001` and `400050002` (Cubism 4.x editors), `501030000`
(Cubism 5.x).  This attribute — not the CAFF header's `formatVersion`/`archiveVersion` triples,
which are `{0,0,0}` in real files — is the schema generation a version probe should report.

### Serializer mechanics

- An XML **element tag = the object's class name**, mapped to a short alias (e.g. `CModelSource`, `CArtMeshSource`, `CWarpDeformerSource`).  Classes without an alias use their **fully-qualified name** (e.g. `com.live2d.cubism.doc.model.texture.textureAtlas.ModelImageEntry`, which appears verbatim as an element tag in the data).
- **`xs.n`** — the field name this element fills in its parent object.
- **`xs.id`** — id of a referenceable object (lives under `<shared>`).
- **`xs.ref`** — a reference to an `xs.id` instead of inlining the object.  IDs look like `#427`. Objects used by more than one owner are hoisted into `<shared>`.
- **`xs.idx`** — the object's index within the shared pool.
- `<?version Class:N?>` PIs record each class's schema version so the reader can migrate older files.
- A class's field **set and order can differ between schema versions** (fields are added, removed, or reordered, e.g. `FilterMode.owner` moved to the front in 5.3).  A faithful round-trip must reproduce each object's child sequence as read rather than imposing a fixed field order.

### Primitive & collection tags

| tag     | type                                     |     | tag                       | type                           |
| ------- | ---------------------------------------- | --- | ------------------------- | ------------------------------ |
| `i`     | int                                      |     | `int-array`               | int[]                          |
| `f`     | float                                    |     | `float-array`             | float[]                        |
| `d`     | double                                   |     | `double-array`            | double[]                       |
| `l`     | long                                     |     | `long-array`              | long[]                         |
| `short` | short                                    |     | `short-array`             | short[]                        |
| `byte`  | byte                                     |     | `byte-array`              | byte[]                         |
| `char`  | char                                     |     | `char-array`              | char[]                         |
| `b`     | boolean                                  |     | `bool-array`              | boolean[]                      |
| `s`     | String                                   |     | `array_list`              | ArrayList                      |
| `null`  | null ref                                 |     | `carray_list`             | `CArrayList`                   |
| `entry` | map entry                                |     | `hash_map` / `linked_map` | maps (`count`,`keyType` attrs) |
| `file`  | `File` (two shapes — see note)           |     | `linked_set`              | LinkedHashSet (`count` attr)   |

**The `<file>` element has two shapes** under the same tag, dispatched on the presence of the `path` attribute: `<file path="imageFileBuf.png"/>` names an **embedded CAFF blob** (the `path` attribute → an archive entry), whereas `<file>C:\path\to.psd</file>` stores a **plain filesystem path as element text** with no blob.  The former carries the layer/atlas PNG pixels; the latter is an external reference back to a source file on the importing machine, used by `CLayeredImage.psdFile` (see §4).

Scalar fields serialize as typed child elements (`<f xs.n="opacity">1.0</f>`) unless the property is attribute-annotated (`name="value"`); object/collection fields become child elements.  Domain value-types mostly follow the same reflective child-element shape — `<GVector2>` / `<CPoint>` (`<f>` / `<i>` children), `<CRect>` (`<i>` children), `<CColor>` (serializes empty) — with `CAffine` the custom-serialized exception, packed as attributes: `<CAffine m00=.. m01=.. m02=.. m10=.. m11=.. m12=..>`.

### Model object graph (under `<main><CModelSource>`)

`CModelSource` aggregates the editable model: `CParameterSourceSet` (parameters), `CDrawableSourceSet` → `CArtMeshSource` (mesh drawables: `int-array` indices + `float-array` vertices + UVs), `CDeformerSourceSet` → `CWarpDeformerSource` / `CRotationDeformerSource`, `CAffecterSourceSet` → `CGlueSource`, `CPartSourceSet` → `CPartSource`, `CParameterGroupSet`, `CPhysicsSettingsSourceSet`, `CTextureManager`, `CImageCanvas`, `CModelInfo`, plus editor settings.  Keyforms/animation bindings appear as `KeyformBindingSource`, `KeyformGridSource`, `KeyOnParameter`, `CControllerCurve`, etc.  Every domain object that can be cross-referenced carries a typed **GUID** object (`CDrawableGuid`, `CDeformerGuid`, `CPartGuid`, `CParameterGuid`, …) with `uuid`/`note`.

### Parameters & combined (2D) links

Each `CParameterSource` (under `CParameterSourceSet._sources`, one per axis, in editor display order) carries: `CParameterId id` (`idstr`, e.g. `ParamAngleX` — a format-level identifier, kept verbatim for interop), `s name` (the localizable display label, e.g. `Angle X`), `f minValue` / `maxValue` / `defaultValue`, `i decimalPlaces`, `f snapEpsilon`, `b isRepeat`, `Type paramType` (`NORMAL`, …), `b combined`, and `CParameterGroupGuid parentGroupGuid` (the folder it sits in, or a `#0` ref for none).

**Combined ("linked") 2D parameters.**  Two parameters can be linked into a single 2D pad — the editor's nine-point grid, e.g. head `Angle X` + `Angle Y`.  The link is encoded purely positionally, not by an explicit pair reference:

- `combined=true` is set on **only the first member** of the pair — the **horizontal (X)** axis.
- The **immediately following `CParameterSource`** in document order is the **vertical (Y)** axis; its own `combined` stays `false`.

A reader reconstructs the pairs by walking the source set in order and, at each `combined=true`, pairing it with the next source (then skipping that consumed partner so back-to-back pairs are not mis-read).  This matches the editor's rule "topmost parameter = horizontal axis, bottommost = vertical axis", and reproduces exactly the `[[X, Y], …]` pairs the runtime `cdi3.json` sidecar lists under `CombinedParameters`.

`combined` is independent of `parentGroupGuid`: a `CParameterGroup` (via `CParameterGroupSet`) is a named **folder** for organising the panel (e.g. "Eye", "Folder 1"), not the 2D-link mechanism — linked members typically share an ordinary folder with unlinked siblings.

**Parameter folders (`CParameterGroup`).**  The panel's folder organisation is a tree, encoded the same way as the parts tree.  `CModelSource.rootParameterGroup` is a hidden top `CParameterGroup`; `CModelSource.parameterGroupSet` (`CParameterGroupSet._groups`) holds every group.  Each `CParameterGroup` carries: `s name` (the folder label — user data, not localized; **not unique**, e.g. a model may have two "Eye" folders, so identity is the group's `guid` / `CParameterGroupId id`), `b folderIsOpened` (the editor's saved expand/collapse state), `CParameterGroupGuid parentGroupGuid` (the enclosing group, for nesting), and `_childGuids` — an **ordered** list of `Guid`s (each a parameter or a nested group) giving the editor's panel order.  A reader reconstructs the panel by walking `rootParameterGroup._childGuids` in order, resolving each entry to a `CParameterSource` (a leaf) or a `CParameterGroup` (recurse).  This is authoritative for panel order; each `CParameterSource.parentGroupGuid` is a redundant back-pointer to the same membership.  Example: `Erica Tamamo`'s root has 71 children — 67 loose parameters interleaved with 4 subgroups ("Eye" ×2, "Folder 1" with 40 params, "Tail Rotation").

---

## 4. Relationship to the PSD: layer images, model images & the texture atlas

On import Cubism decomposes each PSD into an editable representation and, for rendering, packs it into a texture atlas.  Three distinct image concepts therefore live in `main.xml`, chained by GUID references, and the editor can display the model from either end of that chain.  Additional PNG formatted images can also be imported as separately referenced textures.  This section traces the chain and the toggle between the two display modes.

### The three levels of image indirection

1. **Imported artwork** — a `CLayeredImage` tree of `CLayerGroup` / `CLayer` nodes in `<shared>` (one tree per imported file).  Each `CLayer` carries `CRect` (position/size), `CLayerIdentifier`, a blend mode (`CBlend_Normal`, `CBlend_Multiply`, …), a `CImageIcon` thumbnail, its owning `CLayeredImage`, and its opacity — the raw, editable artwork.  The class and its fields keep **legacy PSD naming** (`psdFile`, `psdBytes`, …) from when PSD was the only import format; a non-PSD import (e.g. a flat PNG) reuses the same structure but collapses to a single image layer — a root `CLayerGroup` wrapping one `CLayer`.
2. **Model images** (`CModelImage`) — the *combined layer image* a drawable samples in layer mode.  A `CModelImage` is composited from one or more source `CLayer`s by its `inputFilterEnv` (a `CLayerSelectorMap` mapping the layered image to a list of `CLayerInputData`, each naming a `CLayer` + `CAffine`).  Its baked result is a `CImageResource` (`_filteredImage`) pointing at one embedded `imageFileBuf*.png`.  Model images are pooled under `CModelImageGroup`s (one per imported PSD), held by the `CTextureManager`.
3. **Texture atlas** (`CTextureAtlas`) — a single packed page (here `TextureAtlas1`, 8192×8192) whose pixels are one `CImageResource` (`cachedAtlasImage`, wrapped at runtime as a `GTexture2D`).  Its `modelImages` list holds one `ModelImageEntry` per packed model image, giving that image's placement inside the page.

### The imported source — `CLayeredImage` → the original artwork

`CLayeredImage` (one per imported file, held via `LayeredImageWrapper` by `CTextureManager._rawImages`) is the root of the decomposed artwork and the only object that points back at the source file.  Besides its pixel dimensions (`width` / `height`), the layer tree (`_rootLayer` → `CLayerGroup`, plus a flat `layerSet`), and `icon16` / `icon64` thumbnails, it records where the artwork came from:

- **`psdFile`** — a `<file>` element whose **text content is the absolute path of the source on the importing machine**, e.g. `<file xs.n="psdFile">C:\Users\username\Desktop\Erica Tamamo.psd</file>`.  This is the *external-reference* shape of `<file>` (`textPath`): no `path=` attribute and no embedded blob — contrast the `<file path="imageFileBuf.png"/>` shape (`archivePath`) used for the layer PNGs, which names a CAFF entry.  It is only a breadcrumb back to the artist's disk; the source file itself is **not** stored in the `.cmo3`.
- **`psdBytes`** — an optional embedded copy of the raw source bytes.  `null` in the sample (the source is not bundled); it holds the bytes only when the editor is told to embed the source.
- **`psdFileLastModified`** — the source's modification time (epoch-ms) at import, so the editor can detect an out-of-date import and offer to reload from `psdFile`.
- **`guid`** (`CLayeredImageGuid`) — the join key that ties this raw image to its `CModelImageGroup` (`_linkedRawImageGuids`) and to the `CLayerSelectorMap._imageToLayerInput` keys that build each `CModelImage`.

**Legacy `psd*` naming.**  The assumption is that these field names date from when Cubism probably only supported PSD import format.  Most likely a later editor added flat raster import(PNG, etc.) and reused the same `CLayeredImage` structure.  The source does not have to be a PSD and `name` simply mirrors the basename and file extension.  A non-PSD import brings no layer hierarchy of its own so it collapses to a single image layer.  Example: `Illustration3.png` (`#392`, 252×143) is a `CLayeredImage` whose `_rootLayer` `CLayerGroup` (`#393`) wraps one `CLayer` (`#395`).  `Erica Tamamo.cmo3`'s four imports (two `.psd`, two `.png`):

| `CLayeredImage` | `name`             | `psdFile` (source path)                       | `psdFileLastModified` |
| --------------- | ------------------ | --------------------------------------------- | --------------------- |
| `#5`            | `Erica Tamamo.psd` | `C:\Users\username\Desktop\Erica Tamamo.psd`  | 1651463098582         |
| `#392`          | `Illustration3.png`| `C:\Users\username\Desktop\Illustration3.png` | 1654022952465         |
| `#396`          | `Illustration3.png`| `C:\Users\username\Desktop\Illustration3.png` | 1654030612810         |
| `#401`          | `Extra parts.psd`  | `C:\Users\username\Downloads\Extra parts.psd` | 1654344449887         |

**Layer pixels & the positional PNG mapping.**  Each `CImageResource` (`width`, `height`, `type=INT_ARGB`, `imageFileBuf_size`) has a `<file path="imageFileBuf.png">` child pointing at one embedded PNG.  The `<file>` `path` is the _logical_ base name; the archive de-duplicates it to a unique entry (`imageFileBuf`, `imageFileBuf_0`, …, `imageFileBuf_179`).  **The mapping is positional**: the i-th `CImageResource` ↔ the i-th `imageFileBuf*` blob (verified: blob byte-sizes equal `imageFileBuf_size`, decoded PNG dimensions equal the element's `width`/`height`).  The 180-entry `imageFileBuf` pool holds the model-image pixel data **and the rendered atlas page** (in the sample, `imageFileBuf_175.png` is the 8192² page); the smaller `image*.png` blobs are 16×16 icons and thumbnails.

> The username of the original modeller of `Erica Tamamo.cmo3` has been redacted for privacy.

### How a drawable references its texture

Every `CArtMeshSource` (drawable) carries, in its `_extensions`, a `CTextureInputExtension` whose `_textureInputs` list holds **both** ways of sampling the same artwork:

- `CTextureInput_ModelImage` — references a `CModelImageGuid` (`_modelImageGuid`) → the **combined layer image**.
- `CTextureInput_TextureAtlasRegion` — references a `CTextureAtlasGuid` (`textureAtlasGuid`) plus an `inputImageLocalToCanvasTransform` (`CAffine`) → the **atlas region**.

A sibling field `currentTextureInputData` points at whichever of the two is currently active.  The **`CModelImageGuid` is the join key**: the same GUID appears on the drawable's model-image input, on the `CModelImage` in the group pool, and on the atlas's `ModelImageEntry`.  That is how a drawable, its layer image, and its atlas slot are tied together — there is no direct drawable→atlas-tile pointer; both paths go through the model-image GUID.

### The display-mode toggle (combined layers ⇆ texture atlas)

The editor's *"show combined layer images"* vs *"show texture atlas"* view is a single boolean on the texture manager:

> `CTextureManager.isTextureInputModelImageMode` — `true` = **combined layer-image mode** (drawables sample their original per-layer PNGs); `false` = **texture-atlas mode** (drawables sample the packed atlas page).  In the sample it is `false`.

Flipping it retargets each drawable's `currentTextureInputData` between its `CTextureInput_ModelImage` and its `CTextureInput_TextureAtlasRegion`.  This is the toggle used to spot atlas quality problems — seams, colour bleeding, insufficient resolution, or a drawable left out of the atlas: the combined-layer view is the ground truth, the atlas view is what actually ships, and comparing them reveals where packing degraded the artwork.  A drawable that carries **only** a `CTextureInput_ModelImage` (its `_textureInputs` count is `1`) has no atlas region — it was never packed — and always renders from its layer image; in the sample the drawables "lash 1", "lash 2" and "6" are exactly these unpacked cases.

### Worked example — part "Front hair", drawable "1"

Real IDs from `Erica Tamamo.cmo3` (objects in the `<shared>` pool):

```
CPartSource "Front hair"  guid CPartGuid #1835            (73 child drawables)
   ▲ parentGuid
CArtMeshSource "1"  #1828
   └─ CTextureInputExtension #1829
        _textureInputs (2):
          CTextureInput_ModelImage         _modelImageGuid ─► #1699 ┐
          CTextureInput_TextureAtlasRegion  #1831                   │ join key #1699
             textureAtlasGuid ─► #1830                              │
             inputImageLocalToCanvasTransform  m02=-4545.03 m12=-6175.03
        currentTextureInputData ─► #1831   (atlas region — matches isTextureInputModelImageMode=false)

combined-layer side (#1699):
  CModelImage "1"  guid #1699   (in CModelImageGroup #428 "Erica Tamamo")
     inputFilterEnv → CLayer #222 "1"   (of CLayeredImage #5, the imported PSD)
     _filteredImage → CImageResource #427   309×439  → <file imageFileBuf.png>

atlas side (#1699):
  CTextureAtlas "TextureAtlas1" #1698   8192×8192   guid #1830
     cachedAtlasImage → CImageResource #1827   8192×8192 → <file imageFileBuf_175.png>
     modelImages → ModelImageEntry  modelImageGuid #1699
         atlasLocalToCanvasTransform    m02=-4545.03 m12=-6175.03   (= the drawable's region transform)
         materialLocalToAtlasTransform  position (6742.03, 6710.03)  (packing origin in the 8192² page → UVs)
```

Note the drawable's `inputImageLocalToCanvasTransform` equals the atlas entry's `atlasLocalToCanvasTransform`; the entry's `materialLocalToAtlasTransform` (position/scale/rotation within the page) is what yields the region's UV rectangle in the packed texture.

### Sample inventory

`Erica Tamamo.cmo3` imports **4** artwork sources, two PSD files and two PNG files, which creates four `CLayeredImage` + `CModelImageGroup` pairs under `CTextureManager` onto a **4500×6500** canvas.  The artwork decomposes to **176** `CModelImage`s, of which **128** are packed into the single `TextureAtlas1` (8192×8192) page.  PSD group folders and empty layers become `CLayerGroup` / `LayerSet` nodes with no `imageFileBuf`; the **180** `imageFileBuf*` blobs carry the model image pixel data plus the one rendered atlas page.

---

## 5. Quick reference: decode order

1. Read & verify the unobfuscated header → get `obfuscateKey`.
2. Read the unobfuscated preview block.
3. XOR-decode the file table → list of `(path, tag, offset&0xFFFFFFFF, size, isObfuscated, compress)`.
4. For each blob: slice `[offset, offset+size)`; if `isObfuscated`, XOR every byte with `key&0xFF`; if `compress != RAW`, raw-DEFLATE after the zip local header.
5. The `main_xml`-tagged entry is the model XML; `imageFileBuf*`/`image*` are PNGs.