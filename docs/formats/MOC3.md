# MOC3 File Format

`.moc3` is the **runtime** model file of **Live2D Cubism** exported by the editor targeting the Cubism SDK Runetime (the editor project file is `.cmo3`; see [CMO3.md](CMO3.md)).  It is a flat, little-endian binary blob laid out as a **struct-of-arrays** so the runtime can load it by placing the verbatim bytes in an aligned buffer and pointing at the sections in place (zero-copy).  A model also ships with plain-JSON sidecars (`model3.json` and friends💚); those are described in §6.

This specifcation describes the on-disk format only; it was derived empirically and validated byte-for-byte against real `.moc3` samples.  It was also verified by observing the behavior of the official Cubism SDK Core runtime.

---

## 1. Conventions

- **All multi-byte integers and floats are little-endian.**  Floats are IEEE-754 32-bit.
- **No obfuscation, no compression** (unlike the CMO3/CAFF container).
- IDs are fixed **64-byte** records (see §5).
- The file is padded with zero bytes so its **total size is a multiple of 64**.

---

## 2. Header (64 bytes)

| Offset | Size | Field       | Value                                      |
| -----: | ---: | ----------- | ------------------------------------------ |
| `0x00` |    4 | magic       | ASCII `MOC3` (`4D 4F 43 33`)               |
| `0x04` |    1 | version     | Format Version Byte, `1..6` (See §3)       |
| `0x05` |    1 | isBigEndian | `0` For Little-Endian (Every Shipped File) |
| `0x06` |   58 | reserved    | Zero                                       |

The section-offset table (§4) begins immediately after, at `0x40`.

---

## 3. Version Gating

The version byte selects which sections exist; newer versions only **add** sections at higher table indices, so a reader written for the latest version transparently reads older files.

| Byte | Editor  | Adds                                                                                           |
| ---: | ------- | ---------------------------------------------------------------------------------------------- |
|    1 | 3.0–3.2 | Base Section Set                                                                               |
|    2 | 3.3     | (Parameter Group Extras)                                                                       |
|    3 | 4.0     | —                                                                                              |
|    4 | 4.2     | Warp/Rotation Deformer color & Extra Groups; `Parameter.Types`; blend shapes (art-mesh & warp) |
|    5 | 5.0     | Blend Shapes                                                                                   |
|    6 | 5.3     | Offscreen Rendering                                                                            |

---

## 4. Section-offset Table

At `0x40` is a flat array of **`u32` absolute file offsets**, one per section, in a fixed order.  A section's index has a **stable meaning across versions**.  In editor output the table is dense (each present section gets a non-zero offset); a `0` would mark an absent section.  The table is followed by zero padding up to the first data section, which begins at a **fixed, 64-byte-aligned offset that depends on the exporting editor**: `0x7C0` (1984) for moc ≤ 5, `0x16C0` (5824) for moc 6. The header + table region is sized to comfortably hold the version's section count; read the first section's offset from `table[0]` rather than assuming a constant.

Sections are stored in table order and partition the rest of the file: section _k_ occupies `[offset[k], offset[k+1])` (the last section runs to end-of-file), including any internal/trailing padding. Section index 0 (CountInfo) is therefore at `0x7C0`.

> Because the sections partition `[0x7C0, EOF)` and the table is regenerated from the section sizes, an unedited file re-serializes **byte-for-byte**.

---

## 5. Structural Sections

### 5.1 CountInfo — section index 0

A `u32[]` of element counts — `u32[32]` for moc ≤ 5, `u32[64]` for moc 6. Indices:

| u32 index | Meaning                                                                           |
| --------: | --------------------------------------------------------------------------------- |
|         0 | Parts                                                                             |
|         1 | Deformers (Warp + Rotation)                                                       |
|         2 | Warp Deformers                                                                    |
|         3 | Rotation Deformers                                                                |
|         4 | Drawables (Artmeshes)                                                             |
|         5 | Parameters                                                                        |
|         6 | Total Part Keyforms                                                               |
|         7 | Total Warp Keyforms                                                               |
|         8 | Total Rotation Keyforms                                                           |
|         9 | Total Art-Mesh Keyforms                                                           |
|        10 | Total Packed Position-Value Floats (§5.6)                                         |
|        11 | Total Keyform-Binding Slots (Σ Axes)                                              |
|        12 | Keyform-Binding Count                                                             |
|        13 | Total Parameter-Bindings (Deduped)                                                |
|        14 | Total Key Positions (`key_positions`)                                             |
|        15 | 2 × Total Vertices                                                                |
|        16 | Total Triangle Indices                                                            |
|        17 | Total Mask Indices                                                                |
|        18 | Render-Order Groups                                                               |
|        19 | Render-Order Children                                                             |
|        20 | Glue (Affecter) Entries                                                           |
|        21 | 2 × Total Glue Vertex Pairs                                                       |
|        22 | Total Glue Intensity Keyforms                                                     |
|     23–33 | Blend-Shape Totals (Delta Keyforms, Bindings, Records, Warp/Mesh/Rotation Counts) |
|        35 | Offscreens (V6)                                                                   |

These counts size every parallel array below.

### 5.2 Validated Section Map

`P` = parameter count, `T` = part count, `D` = drawable count (from CountInfo).  ID sections are arrays of 64-byte records; numeric sections are tight little-endian arrays.

| Index | Contents                     | Element    |     Stride |
| ----: | ---------------------------- | ---------- | ---------: |
|     0 | CountInfo                    | `u32[]`    |          — |
|     1 | CanvasInfo                   | `f32[6]`   |          — |
|     3 | Part IDs                     | `char[64]` |       64·T |
|     9 | Part parent indices          | `i32`      |        4·T |
|    33 | Drawable IDs                 | `char[64]` |       64·D |
|    39 | Drawable parent-part indices | `i32`      |        4·D |
|    41 | Drawable texture indices     | `i32`      |        4·D |
|    42 | Drawable constant flags      | `u8`       |        1·D |
|    43 | Drawable vertex counts       | `i32`      |        4·D |
|    46 | Drawable index counts        | `i32`      |        4·D |
|    48 | Drawable mask counts         | `i32`      |        4·D |
|    50 | Parameter IDs                | `char[64]` |       64·P |
|    51 | Parameter maximum values     | `f32`      |        4·P |
|    52 | Parameter minimum values     | `f32`      |        4·P |
|    53 | Parameter default values     | `f32`      |        4·P |
|    54 | Parameter repeat flags       | `i32`      |        4·P |
|    78 | Drawable vertex UVs          | `f32×2`    |   8·Σverts |
|    79 | Drawable triangle indices    | `u16`      | 2·Σindices |
|    80 | Drawable mask indices        | `i32`      |   4·Σmasks |
|   114 | Parameter types              | `i32`      |  4·P (v4+) |

Note the **maximum-before-minimum** order at 51/52. The remaining indices hold the deformation payload (deformers, keyforms, parameter bindings, glue, blend shapes, offscreens) — see §5.6.  The per-frame interpolation/deformation *math* is out of scope (the runtime performs it); the on-disk tables are documented below and read into the typed model.

### 5.3 CanvasInfo (index 1)

`f32[6] = { pixelsPerUnit, originX, originY, canvasWidth, canvasHeight, 0 }` (pixel units).

### 5.4 ID records

Every ID is a fixed **64-byte** record: the identifier as ASCII, a `NUL` terminator, then zero padding.

### 5.5 Constant flags & parameter types

`Drawable constant flags` (index 42) is a `u8` bitmask: `1` additive blend, `2` multiplicative blend, `4` double-sided, `8` inverted mask. `Parameter types` (index 114, present from v4) is an `i32` per parameter: `0` normal, `1` blend-shape.  `Parameter repeat flags` (index 54) is an `i32` per parameter: `1` wraps the value into `[min,max)` instead of clamping (a moc 5.3 feature; `0` in every earlier-version sample).

### 5.6 Deformation sections

The remaining sections drive deformation.  Some indices shift by version; the values below are for moc 4 (`-1` = absent in that version). `W`=warp count, `R`=rotation count, `Df`=`W+R` deformers, `G`=glue count (from CountInfo indices 2/3/1/20). `TABLE` arrays carry no length field — they are indexed by the base/offset tables, so a reader spans the whole section slice.

|       Index | Contents                                                                                                                    | Element           | Sizing                  |
| ----------: | --------------------------------------------------------------------------------------------------------------------------- | ----------------- | ----------------------- |
|          16 | Deformer parent index (-1 = root)                                                                                           | `i32`             | Df                      |
|          17 | Deformer type (0=warp, 1=rotation)                                                                                          | `i32`             | Df                      |
|          18 | Deformer type-local index                                                                                                   | `i32`             | Df                      |
|          19 | Warp keyform-binding index                                                                                                  | `i32`             | W                       |
|          20 | Warp keyform base                                                                                                           | `i32`             | W                       |
|          22 | Warp control-point count `(rows+1)(cols+1)`                                                                                 | `i32`             | W                       |
|          23 | Warp grid rows                                                                                                              | `i32`             | W                       |
|          24 | Warp grid columns                                                                                                           | `i32`             | W                       |
|         101 | Warp interpolation mode (v3+)                                                                                               | `i32`             | W                       |
|         105 | Warp color base (v4+)                                                                                                       | `i32`             | W                       |
|          25 | Rotation keyform-binding index                                                                                              | `i32`             | R                       |
|          26 | Rotation keyform base                                                                                                       | `i32`             | R                       |
|          28 | Rotation base angle (deg)                                                                                                   | `f32`             | R                       |
|         106 | Rotation color base (v4+)                                                                                                   | `i32`             | R                       |
|          34 | Art-mesh keyform-binding index                                                                                              | `i32`             | D                       |
|          35 | Art-mesh keyform base                                                                                                       | `i32`             | D                       |
|          36 | Art-mesh keyform count (grid size)                                                                                          | `i32`             | D                       |
|          40 | Art-mesh parent deformer (-1 = direct)                                                                                      | `i32`             | D                       |
|         107 | Art-mesh color base (v4+)                                                                                                   | `i32`             | D                       |
|           4 | Part keyform-binding index                                                                                                  | `i32`             | T                       |
|           5 | Part keyform base                                                                                                           | `i32`             | T                       |
|          57 | Per-parameter binding count                                                                                                 | `i32`             | P                       |
|         104 | Per-parameter key count (v4+)                                                                                               | `i32`             | P                       |
|    83/84/85 | Render-order group render count / max / min child draw order                                                                | `i32`             | per group               |
|       72-77 | Keyform-binding grid (slot/start/count/keyOff/keyCnt/keyPos)                                                                | `i32`/`f32`       | TABLE                   |
|       60/70 | Keyform → packed-position offset — **warps use 60, art meshes use 70** (both index into 71)                                 | `i32`             | TABLE                   |
|          71 | Packed keyform `(x,y)` values — warp control-point blocks then art-mesh vertex blocks, each padded to 16 floats             | `f32`             | TABLE                   |
| 58/59/61-69 | Part draw-order (58); warp/rotation/art-mesh opacity; art-mesh draw-order; rotation angle/origin/scale/reflect-x/y keyforms | `f32`/`i32`       | TABLE                   |
|     108-113 | Multiply/screen color channel tables (v4+)                                                                                  | `f32`             | TABLE                   |
| 82/86/87/88 | Render-order group child count/kind/index/group-index                                                                       | `i32`             | TABLE (per group/child) |
|      91-100 | Glue keyform-binding/keys/mesh pair/vertex pairs/weights/intensities                                                        | `i32`/`i16`/`f32` | G / TABLE               |
|     115-148 | Blend-shape per-param ranges, records, bindings (v4+ meshes/warps, v5+ rotations)                                           | `i32`/`f32`       | Various                 |
|     155-161 | Offscreen owner/flags/blend/mask-count/opacity (v6)                                                                         | `i32`/`u8`/`f32`  | Offscreen               |


Blend-shape records are shared across warp→mesh→rotation; each is driven by one parameter (key positions in the shared key table) relative to a neutral key, with additive deltas based at `RECORD_BASE` in the same value tables as base keyforms. The **offscreen blend mode** is stored as a single packed `i32` per offscreen (the runtime unpacks it into separate color/alpha modes).  The BS rotation-object table is section **146** in both v5 and v6.

The blend-shape delta keyforms are **appended after the base keyforms** in the shared value tables — the packed position values (71), the keyform-index tables (60/70), the rotation affine tables (62–67), the per-keyform opacity/draw-order (59/61/68/69) and (v5+) the color tables (108–113). Likewise the mask-index block (80) holds the drawables' masks then (v6) the offscreens' masks. So for a model with blend shapes or offscreens these table sizes exceed the base-keyform totals; the per-object `base` fields still index the base prefix.

A **keyform binding** is the interpolation grid: parameter `p` owns `bindCount[p]` (index 57) parameter-bindings (cumulative); each parameter-binding has `keyCnt` key positions at `keyPos[keyOff…]`. A binding (indices 72-74) lists the parameter-bindings that drive it; its grid size is the product of their key counts. An object (art-mesh/warp/rotation/part/glue) names a binding and a `base`; its per-keyform values live at `base + grid` (and, for geometry, via the position-index table into the packed value array).  Deformers carry **no IDs** in moc3 (referenced by index).

---

## 6. JSON sidecars

The editor exports these plain-JSON (UTF-8, tab-indented) files alongside the `.moc3`; the manifest references the rest by path.  Keys are PascalCase, properties in the order below, and **integral floats are written without a trailing `.0`** (e.g. `"Y": -1`).  Optional keys are omitted when unset.

### 6.1 `model3.json` - Manifest

Example JSON
```json
{
	"Version": 3,
	"FileReferences": {
		"Moc": "x.moc3",
		"Textures": ["x.4096/texture_00.png", "..."],
		"Pose": "...",
		"Physics": "x.physics3.json",
		"UserData": "x.userdata3.json",
		"DisplayInfo": "x.cdi3.json",
		"Expressions": ["..."],
		"Motions": {
			"Idle": ["..."]
		}
	},
	"Groups": [
		{
			"Target": "Parameter",
			"Name": "EyeBlink",
			"Ids": ["..."]
		}
	],
	"HitAreas": [
		{
			"Name": "Head",
			"Id": "D_REF.HEAD"
		}
	]
}
```

### 6.2 `physics3.json` - Physics Rig

Example JSON
```json
{
	"Version": 3,
	"Meta": {
		"PhysicsSettingCount": 53,
		"TotalInputCount": 112,
		"TotalOutputCount": 127,
		"VertexCount": 193,
		"Fps": 60,
		"EffectiveForces": {
			"Gravity": {
				"X": 0,
				"Y": -1
			},
			"Wind": {
				"X": 0,
				"Y": 0
			}
		},
		"PhysicsDictionary": [
			{
				"Id": "PhysicsSetting1",
				"Name": "tailfastanim"
			}
		],
	},
	
	"PhysicsSettings": [
		{
			"Id": "PhysicsSetting1",
			"Input": [
				{
					"Source": {
						"Target": "Parameter",
						"Id": "tailonfast"
					},
					"Weight": 75,
					"Type": "Angle",
					"Reflect": false
				},
				{
					"Source": {
						"Target": "Parameter",
						"Id": "fastanim"
					},
					"Weight": 25,
					"Type": "Angle",
					"Reflect": false
				}
			],
			"Output": [
				{
					"Destination": {
						"Target": "Parameter",
						"Id": "fastphys"
					},
					"VertexIndex": 1,
					"Scale": 51.149,
					"Weight": 100,
					"Type": "Angle",
					"Reflect": false
				}
			],
			"Vertices": [
				{
					"Position": {
						"X": 0,
						"Y": 0
					},
					"Mobility": 0.8,
					"Delay": 1,
					"Acceleration": 1,
					"Radius": 0
				},
				{
					"Position": {
						"X": 0,
						"Y": 10
					},
					"Mobility": 0.8,
					"Delay": 1,
					"Acceleration": 1,
					"Radius": 10
				}
			],
			"Normalization": {
				"Position": {
					"Minimum": -10,
					"Default": 0,
					"Maximum": 10
				},
				"Angle": {
					"Minimum": -120,
					"Default": 0,
					"Maximum": 120
				}
			}
		},
	],
}
```

### 6.3 `cdi3.json` - Display Info

Example JSON
```json
{
	"Version": 3,
	"Parameters": [
		{
			"Id": "Param83",
			"GroupId": "ParamGroup2",
			"Name": "ShrinkToggle"
		},
	],
	"ParameterGroups": [
		{
			"Id": "ParamGroup2",
			"GroupId": "",
			"Name": "Toggles"
		},
	],
	"Parts": [
		{
			"Id": "Part2",
			"Name": "mock art"
		},
	],
	"CombinedParameters": [
		[
			"ParamAngleX",
			"ParamAngleY"
		],
	]
}
```

Names and grouping are primarily for the editor and display purposes in the application consuming the runtime.

### 6.4 `userdata3.json` — per-object user data

Example JSON
```json
{
	"Version": 3,
	"Meta": {
		"UserDataCount": 40,
		"TotalUserDataSize": 1000
	},
	"UserData": [
		{
			"Target": "ArtMesh",
			"Id": "glow",
			"Value": "vts_ignore_scene_lighting"
		},
	],
}
```

The application using the SDK runtime consumes the `Target == "ArtMesh"` entries.  For example, the user data shown above is flag that the end user program VTube Studio will consume and use to ignore scene lighting for that art mesh.

---

## 7. Decode / encode recipe

1. Verify magic `MOC3`, `version` in `1..6`, `isBigEndian == 0`.
2. Read the offset table from `0x40` (run of non-zero `u32` until the zero padding).
3. Slice section _k_ as `[offset[k], offset[k+1])` (last → EOF). Decode CountInfo (section 0) to size every other section; interpret the structural sections per §5; keep the rest verbatim.
4. To re-encode: write the 64-byte header, the `u32` offsets, zero-pad the table region to the version's first-section offset (`0x7C0` for moc ≤5, `0x16C0` for moc 6 — the runtime reads a fixed-size table, so this region must be reserved), then the sections (64-byte aligned) in table order, with the whole file padded to 64 bytes. (For an unedited model, preserving the original offsets/padding reproduces the input byte-for-byte.)
