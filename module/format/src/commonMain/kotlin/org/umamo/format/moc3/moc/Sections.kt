package org.umamo.format.moc3.moc

/**
 * Stable `.moc3` section-table indices and CountInfo field indices.
 *
 * EN: The section-offset table at `0x40` holds one `u32` file offset per section; an index's
 *     semantic meaning is fixed across versions (newer versions only add higher indices). These are
 *     the indices needed to interpret the structural ("static") sections; the deformer/keyform/glue/
 *     blend-shape/offscreen sections carry per-frame math that is out of scope for read/write and is
 *     preserved verbatim.
 * JA: セクション索引は全バージョンで固定。
 *
 * @see <a href="https://docs.umamo.org/format/MOC3.md">MOC3.md §section map</a>
 */
public object Sections {
	/** The data region (and section index 0, CountInfo) always begins here; the header+table fill [0, this). */
	public const val DATA_SECTION_BEGIN: Int = 1984 // 0x7C0

	/** Fixed width, in bytes, of every ID record (ASCII, NUL-terminated, zero-padded). */
	public const val ID_STRIDE: Int = 64

	// ---- section indices (spec 09 §4 + runtime source map) ----
	public const val COUNTINFO: Int = 0
	public const val CANVAS: Int = 1
	public const val PART_ID: Int = 3
	public const val PART_PARENT: Int = 9
	public const val DRAW_ID: Int = 33
	public const val DRAW_PARENT: Int = 39
	public const val DRAW_TEXTURE: Int = 41
	public const val DRAW_CONSTANT_FLAG: Int = 42
	public const val DRAW_VERTEX_COUNT: Int = 43
	public const val DRAW_INDEX_COUNT: Int = 46
	public const val DRAW_MASK_COUNT: Int = 48
	public const val PARAM_ID: Int = 50
	public const val PARAM_MAX: Int = 51 // NB: Max before Min
	public const val PARAM_MIN: Int = 52
	public const val PARAM_DEFAULT: Int = 53
	public const val PARAM_REPEAT: Int = 54
	public const val UV_DATA: Int = 78
	public const val INDEX_DATA: Int = 79
	public const val MASK_INDEX_DATA: Int = 80
	public const val PARAM_KEY_COUNT: Int = 104
	public const val PARAM_TYPE: Int = 114 // moc 4+

	// ---- CountInfo (section 0) u32 indices ----
	public const val CI_PARTS: Int = 0
	public const val CI_DEFORMERS: Int = 1
	public const val CI_WARPS: Int = 2
	public const val CI_ROTATIONS: Int = 3
	public const val CI_DRAWABLES: Int = 4
	public const val CI_PARAMETERS: Int = 5
	public const val CI_RENDER_ORDER_GROUPS: Int = 18
	public const val CI_RENDER_ORDER_CHILDREN: Int = 19
	public const val CI_GLUES: Int = 20
	public const val CI_BLENDSHAPE_WARPS: Int = 27
	public const val CI_BLENDSHAPE_MESHES: Int = 28
	public const val CI_BLENDSHAPE_ROTATIONS: Int = 33
	public const val CI_OFFSCREENS: Int = 35
}
