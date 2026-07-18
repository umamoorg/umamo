package org.umamo.format.moc3.moc

/** Element width of a section's array. */
public enum class ElementType(public val size: Int) {
	/** unsigned 8-bit (flag bytes). */
	U8(1),

	/** signed 16-bit (glue vertex indices). */
	I16(2),

	/** signed 32-bit. */
	I32(4),

	/** unsigned 32-bit (CountInfo). */
	U32(4),

	/** IEEE-754 32-bit float. */
	F32(4),

	/** fixed 64-byte NUL-terminated ID record. */
	ID(64),
}

/**
 * How a section's element count is determined.
 *
 * EN: Per-object arrays are sized by a CountInfo count; "table" arrays (keyform values, key
 *     positions, glue pairs, …) have no single count field and are read across the whole section
 *     slice (the runtime indexes them via base/offset tables, not a length). Reading the full slice
 *     is lossless - trailing zero padding becomes trailing zero elements that the semantic layer
 *     never dereferences.
 */
public enum class Sizing {
	PER_PART,
	PER_DEFORMER,
	PER_WARP,
	PER_ROTATION,
	PER_DRAWABLE,
	PER_PARAMETER,
	PER_GLUE,
	PER_RENDER_ORDER_GROUP,
	PER_OFFSCREEN,
	PER_BLENDSHAPE_WARP,
	PER_BLENDSHAPE_MESH,
	PER_BLENDSHAPE_ROTATION,
	PER_BLENDSHAPE_PART,

	/** Read across the whole section slice (a table indexed elsewhere). */
	TABLE,
}

/**
 * The semantic sections of a `.moc3` beyond the structural set in [Sections], with their element
 * type, sizing rule, and section-table index per moc version.
 *
 * EN: The per-version indices are on-disk facts (which table slot holds which data, recovered from
 *     the runtime). `index[version-1]` gives the slot; `-1` means the section is absent in that
 *     version. Structural sections (IDs, counts, canvas, parameters, parts, drawables, topology) are
 *     handled directly in [MocModel]/[Sections]; this enum covers the deformation payload.
 * JA: セクション索引（バージョン別）・要素型・サイズ規則。
 *
 * @see <a href="https://docs.umamo.org/format/MOC3.md">MOC3.md §section map</a>
 */
public enum class Section(
	public val element: ElementType,
	public val sizing: Sizing,
	private vararg val indexByVersion: Int, // [v1,v2,v3,v4,v5,v6]; -1 = absent
) {
	// --- deformers (unified list) ---
	DEFORMER_PARENT(ElementType.I32, Sizing.PER_DEFORMER, 16, 16, 16, 16, 16, 16),
	DEFORMER_TYPE(ElementType.I32, Sizing.PER_DEFORMER, 17, 17, 17, 17, 17, 17),

	/** Per-deformer 0-based index within its type group (warps vs rotations), in deformer-list order. */
	DEFORMER_LOCAL_INDEX(ElementType.I32, Sizing.PER_DEFORMER, 18, 18, 18, 18, 18, 18),

	// --- warp deformers ---
	WARP_KEYFORM_BINDING(ElementType.I32, Sizing.PER_WARP, 19, 19, 19, 19, 19, 19),
	WARP_KEYFORM_BASE(ElementType.I32, Sizing.PER_WARP, 20, 20, 20, 20, 20, 20),

	/** Per-warp control-point count `(rows+1)·(columns+1)` (the runtime validates it against rows/cols). */
	WARP_CONTROL_POINT_COUNT(ElementType.I32, Sizing.PER_WARP, 22, 22, 22, 22, 22, 22),
	WARP_ROWS(ElementType.I32, Sizing.PER_WARP, 23, 23, 23, 23, 23, 23),
	WARP_COLUMNS(ElementType.I32, Sizing.PER_WARP, 24, 24, 24, 24, 24, 24),
	WARP_MODE(ElementType.I32, Sizing.PER_WARP, -1, -1, 101, 101, 101, 101),
	WARP_COLOR_BASE(ElementType.I32, Sizing.PER_WARP, -1, -1, -1, 105, 105, 105),
	WARP_OPACITY(ElementType.F32, Sizing.TABLE, 59, 59, 59, 59, 59, 59),

	/** Keyform → packed-position offset table for warps/rotations (distinct from art meshes' [KEYFORM_POSITION_INDEX]). */
	WARP_KEYFORM_INDEX(ElementType.I32, Sizing.TABLE, 60, 60, 60, 60, 60, 60),

	// --- rotation deformers ---
	ROTATION_KEYFORM_BINDING(ElementType.I32, Sizing.PER_ROTATION, 25, 25, 25, 25, 25, 25),
	ROTATION_KEYFORM_BASE(ElementType.I32, Sizing.PER_ROTATION, 26, 26, 26, 26, 26, 26),
	ROTATION_COLOR_BASE(ElementType.I32, Sizing.PER_ROTATION, -1, -1, -1, 106, 106, 106),
	ROTATION_BASE_ANGLE(ElementType.F32, Sizing.PER_ROTATION, 28, 28, 28, 28, 28, 28),
	ROTATION_OPACITY(ElementType.F32, Sizing.TABLE, 61, 61, 61, 61, 61, 61),
	ROTATION_ANGLE(ElementType.F32, Sizing.TABLE, 62, 62, 62, 62, 62, 62),
	ROTATION_ORIGIN_X(ElementType.F32, Sizing.TABLE, 63, 63, 63, 63, 63, 63),
	ROTATION_ORIGIN_Y(ElementType.F32, Sizing.TABLE, 64, 64, 64, 64, 64, 64),
	ROTATION_SCALE(ElementType.F32, Sizing.TABLE, 65, 65, 65, 65, 65, 65),
	ROTATION_REFLECT_X(ElementType.I32, Sizing.TABLE, 66, 66, 66, 66, 66, 66),
	ROTATION_REFLECT_Y(ElementType.I32, Sizing.TABLE, 67, 67, 67, 67, 67, 67),

	// --- art-mesh deformation refs ---
	ARTMESH_KEYFORM_BINDING(ElementType.I32, Sizing.PER_DRAWABLE, 34, 34, 34, 34, 34, 34),
	ARTMESH_KEYFORM_BASE(ElementType.I32, Sizing.PER_DRAWABLE, 35, 35, 35, 35, 35, 35),

	/** Per-drawable keyform count (its keyform-binding grid size). */
	ARTMESH_KEYFORM_COUNT(ElementType.I32, Sizing.PER_DRAWABLE, 36, 36, 36, 36, 36, 36),
	ARTMESH_COLOR_BASE(ElementType.I32, Sizing.PER_DRAWABLE, -1, -1, -1, 107, 107, 107),
	ARTMESH_PARENT_DEFORMER(ElementType.I32, Sizing.PER_DRAWABLE, 40, 40, 40, 40, 40, 40),
	ARTMESH_OPACITY(ElementType.F32, Sizing.TABLE, 68, 68, 68, 68, 68, 68),
	ARTMESH_DRAW_ORDER(ElementType.F32, Sizing.TABLE, 69, 69, 69, 69, 69, 69),

	// --- shared keyform value tables ---
	KEYFORM_POSITION_INDEX(ElementType.I32, Sizing.TABLE, 70, 70, 70, 70, 70, 70),
	KEYFORM_POSITION_VALUES(ElementType.F32, Sizing.TABLE, 71, 71, 71, 71, 71, 71),

	// --- keyform-binding grid ---
	PARAMETER_BINDING_COUNT(ElementType.I32, Sizing.PER_PARAMETER, 57, 57, 57, 57, 57, 57),
	BINDING_KEY_OFFSET(ElementType.I32, Sizing.TABLE, 75, 75, 75, 75, 75, 75),
	BINDING_KEY_COUNT(ElementType.I32, Sizing.TABLE, 76, 76, 76, 76, 76, 76),
	KEYFORM_BINDING_SLOT(ElementType.I32, Sizing.TABLE, 72, 72, 72, 72, 72, 72),
	KEYFORM_BINDING_START(ElementType.I32, Sizing.TABLE, 73, 73, 73, 73, 73, 73),
	KEYFORM_BINDING_COUNT(ElementType.I32, Sizing.TABLE, 74, 74, 74, 74, 74, 74),
	KEY_POSITIONS(ElementType.F32, Sizing.TABLE, 77, 77, 77, 77, 77, 77),

	// --- parts ---
	PART_KEYFORM_BINDING(ElementType.I32, Sizing.PER_PART, 4, 4, 4, 4, 4, 4),
	PART_KEYFORM_BASE(ElementType.I32, Sizing.PER_PART, 5, 5, 5, 5, 5, 5),
	PART_DRAW_ORDER(ElementType.F32, Sizing.TABLE, 58, 58, 58, 58, 58, 58),

	// --- color channel tables (v4+) ---
	COLOR_MULTIPLY_R(ElementType.F32, Sizing.TABLE, -1, -1, -1, 108, 108, 108),
	COLOR_MULTIPLY_G(ElementType.F32, Sizing.TABLE, -1, -1, -1, 109, 109, 109),
	COLOR_MULTIPLY_B(ElementType.F32, Sizing.TABLE, -1, -1, -1, 110, 110, 110),
	COLOR_SCREEN_R(ElementType.F32, Sizing.TABLE, -1, -1, -1, 111, 111, 111),
	COLOR_SCREEN_G(ElementType.F32, Sizing.TABLE, -1, -1, -1, 112, 112, 112),
	COLOR_SCREEN_B(ElementType.F32, Sizing.TABLE, -1, -1, -1, 113, 113, 113),

	// --- render-order group tree ---
	RENDER_ORDER_CHILD_COUNT(ElementType.I32, Sizing.PER_RENDER_ORDER_GROUP, 82, 82, 82, 82, 82, 82),

	/** Per-group total render-index count (recursive: drawables + offscreen slots + sub-group totals). */
	RENDER_ORDER_GROUP_RENDER_COUNT(ElementType.I32, Sizing.PER_RENDER_ORDER_GROUP, 83, 83, 83, 83, 83, 83),

	/** Per-group maximum child draw order (drawable draw order, or a sub-group's part draw order). */
	RENDER_ORDER_GROUP_MAX_DRAW_ORDER(ElementType.I32, Sizing.PER_RENDER_ORDER_GROUP, 84, 84, 84, 84, 84, 84),

	/** Per-group minimum child draw order. */
	RENDER_ORDER_GROUP_MIN_DRAW_ORDER(ElementType.I32, Sizing.PER_RENDER_ORDER_GROUP, 85, 85, 85, 85, 85, 85),
	RENDER_ORDER_CHILD_KIND(ElementType.I32, Sizing.TABLE, 86, 86, 86, 86, 86, 86),
	RENDER_ORDER_CHILD_INDEX(ElementType.I32, Sizing.TABLE, 87, 87, 87, 87, 87, 87),
	RENDER_ORDER_GROUP_INDEX(ElementType.I32, Sizing.TABLE, 88, 88, 88, 88, 88, 88),

	// --- glue ---
	GLUE_KEYFORM_BINDING(ElementType.I32, Sizing.PER_GLUE, 91, 91, 91, 91, 91, 91),
	GLUE_KEY_OFFSET(ElementType.I32, Sizing.PER_GLUE, 92, 92, 92, 92, 92, 92),
	GLUE_KEY_COUNT(ElementType.I32, Sizing.PER_GLUE, 93, 93, 93, 93, 93, 93),
	GLUE_MESH_A(ElementType.I32, Sizing.PER_GLUE, 94, 94, 94, 94, 94, 94),
	GLUE_MESH_B(ElementType.I32, Sizing.PER_GLUE, 95, 95, 95, 95, 95, 95),
	GLUE_VERTEX_START(ElementType.I32, Sizing.PER_GLUE, 96, 96, 96, 96, 96, 96),
	GLUE_VERTEX_COUNT(ElementType.I32, Sizing.PER_GLUE, 97, 97, 97, 97, 97, 97),
	GLUE_WEIGHTS(ElementType.F32, Sizing.TABLE, 98, 98, 98, 98, 98, 98),
	GLUE_VERTEX_INDICES(ElementType.I16, Sizing.TABLE, 99, 99, 99, 99, 99, 99),
	GLUE_INTENSITIES(ElementType.F32, Sizing.TABLE, 100, 100, 100, 100, 100, 100),

	// --- blend shapes (moc 4+ positions / 5+ scalar fields) ---
	BLENDSHAPE_WARP_OBJECT(ElementType.I32, Sizing.PER_BLENDSHAPE_WARP, -1, -1, -1, 125, 125, 125),
	BLENDSHAPE_WARP_RECORD_START(ElementType.I32, Sizing.PER_BLENDSHAPE_WARP, -1, -1, -1, 126, 126, 126),
	BLENDSHAPE_WARP_RECORD_COUNT(ElementType.I32, Sizing.PER_BLENDSHAPE_WARP, -1, -1, -1, 127, 127, 127),
	BLENDSHAPE_MESH_OBJECT(ElementType.I32, Sizing.PER_BLENDSHAPE_MESH, -1, -1, -1, 128, 128, 128),
	BLENDSHAPE_MESH_RECORD_START(ElementType.I32, Sizing.PER_BLENDSHAPE_MESH, -1, -1, -1, 129, 129, 129),
	BLENDSHAPE_MESH_RECORD_COUNT(ElementType.I32, Sizing.PER_BLENDSHAPE_MESH, -1, -1, -1, 130, 130, 130),

	// v6 index is 146 (same as v5); the Umamo C++ Runtime's kSrcLayout maps it to the degenerate/empty slot 143.
	BLENDSHAPE_ROTATION_OBJECT(ElementType.I32, Sizing.PER_BLENDSHAPE_ROTATION, -1, -1, -1, -1, 146, 146),
	BLENDSHAPE_ROTATION_RECORD_START(ElementType.I32, Sizing.PER_BLENDSHAPE_ROTATION, -1, -1, -1, -1, 147, 147),
	BLENDSHAPE_ROTATION_RECORD_COUNT(ElementType.I32, Sizing.PER_BLENDSHAPE_ROTATION, -1, -1, -1, -1, 148, 148),
	BLENDSHAPE_RECORD_BINDING(ElementType.I32, Sizing.TABLE, -1, -1, -1, 120, 120, 120),
	BLENDSHAPE_RECORD_BASE(ElementType.I32, Sizing.TABLE, -1, -1, -1, 121, 121, 121),
	BLENDSHAPE_RECORD_SUBSTART(ElementType.I32, Sizing.TABLE, -1, -1, -1, 123, 123, 123),
	BLENDSHAPE_RECORD_CORNER_COUNT(ElementType.I32, Sizing.TABLE, -1, -1, -1, 124, 124, 124),

	// MOC3 v4+ §5.6: redundant per-record copy of the record's binding key count (corpus-confirmed
	// against Model A/Model B/Model C including v6 - see MorphTargetJoinProbeTest).
	BLENDSHAPE_RECORD_KEY_COUNT(ElementType.I32, Sizing.TABLE, -1, -1, -1, 122, 122, 122),

	// MOC3 v4+ §5.6: blend-weight limit ("blend shape limit") sub-binding tables. A record's
	// SUBSTART/CORNER_COUNT range into SUB_INDEX yields refs into a DEDUPLICATED sub-binding pool;
	// each pool entry maps another parameter (SUB_PARAMETER) through an end-clamped piecewise-linear
	// (SUB_KEYS, SUB_WEIGHT_VALUES) curve addressed by SUB_KEY_OFFSET/COUNT. The record's weight
	// multiplier is the MINIMUM over its refs. v4/v5 indices confirmed against Model B/Model C (v5) and
	// the C-runtime layout; the v6 column is assumed = v5 pending a v6 constraint bake (every confirmed
	// blend section is index-identical v5 -> v6, incl. section 122 above).
	BLENDSHAPE_SUB_INDEX(ElementType.I32, Sizing.TABLE, -1, -1, -1, 131, 131, 131),
	BLENDSHAPE_SUB_PARAMETER(ElementType.I32, Sizing.TABLE, -1, -1, -1, 132, 132, 132),
	BLENDSHAPE_SUB_KEY_OFFSET(ElementType.I32, Sizing.TABLE, -1, -1, -1, 133, 133, 133),
	BLENDSHAPE_SUB_KEY_COUNT(ElementType.I32, Sizing.TABLE, -1, -1, -1, 134, 134, 134),
	BLENDSHAPE_SUB_KEYS(ElementType.F32, Sizing.TABLE, -1, -1, -1, 135, 135, 135),
	BLENDSHAPE_SUB_WEIGHT_VALUES(ElementType.F32, Sizing.TABLE, -1, -1, -1, 136, 136, 136),

	// MOC3 v5+ §5.6: part blend shapes (Model C carries one). v6 column assumed = v5 (same caveat).
	BLENDSHAPE_PART_OBJECT(ElementType.I32, Sizing.PER_BLENDSHAPE_PART, -1, -1, -1, -1, 143, 143),
	BLENDSHAPE_PART_RECORD_START(ElementType.I32, Sizing.PER_BLENDSHAPE_PART, -1, -1, -1, -1, 144, 144),
	BLENDSHAPE_PART_RECORD_COUNT(ElementType.I32, Sizing.PER_BLENDSHAPE_PART, -1, -1, -1, -1, 145, 145),
	BLENDSHAPE_BINDING_KEY_OFFSET(ElementType.I32, Sizing.TABLE, -1, -1, -1, 117, 117, 117),
	BLENDSHAPE_BINDING_KEY_COUNT(ElementType.I32, Sizing.TABLE, -1, -1, -1, 118, 118, 118),
	BLENDSHAPE_BINDING_NEUTRAL(ElementType.I32, Sizing.TABLE, -1, -1, -1, 119, 119, 119),
	BLENDSHAPE_PARAMETER_BEGIN(ElementType.I32, Sizing.PER_PARAMETER, -1, -1, -1, 115, 115, 115),
	BLENDSHAPE_PARAMETER_COUNT(ElementType.I32, Sizing.PER_PARAMETER, -1, -1, -1, 116, 116, 116),

	// --- offscreen rendering (moc 6) ---
	OFFSCREEN_OWNER_PART(ElementType.I32, Sizing.PER_OFFSCREEN, -1, -1, -1, -1, -1, 155),
	OFFSCREEN_CONSTANT_FLAGS(ElementType.U8, Sizing.PER_OFFSCREEN, -1, -1, -1, -1, -1, 156),
	OFFSCREEN_BLEND_MODE(ElementType.I32, Sizing.PER_OFFSCREEN, -1, -1, -1, -1, -1, 157),
	OFFSCREEN_MASK_COUNT(ElementType.I32, Sizing.PER_OFFSCREEN, -1, -1, -1, -1, -1, 159),
	OFFSCREEN_OPACITY(ElementType.F32, Sizing.TABLE, -1, -1, -1, -1, -1, 161),

	/** Per-part index of the offscreen it owns, -1 when none (the inverse of [OFFSCREEN_OWNER_PART]). */
	OFFSCREEN_BY_PART(ElementType.I32, Sizing.PER_PART, -1, -1, -1, -1, -1, 152),

	/**
	 * Per-offscreen cumulative base into the offscreen mask suffix of MASK_INDEX_DATA (successive
	 * diffs equal [OFFSCREEN_MASK_COUNT]; Model A probe, OffscreenKeyformProbeTest).
	 */
	OFFSCREEN_MASK_BASE(ElementType.I32, Sizing.PER_OFFSCREEN, -1, -1, -1, -1, -1, 158),

	/**
	 * Offscreen keyform → color-table row maps (multiply / screen). Observed identity on the corpus
	 * (the offscreen keyforms ARE the color tables' prefix rows, in offscreen order); the multiply
	 * vs screen split is assumed from the per-object color-base precedent, pending a discriminating
	 * sample. An offscreen's keyforms ride its owner part's keyform grid (Σ owner grid == CountInfo
	 * 36) - there is no offscreen keyform-binding section.
	 */
	OFFSCREEN_KEYFORM_MULTIPLY_ROW(ElementType.I32, Sizing.TABLE, -1, -1, -1, -1, -1, 162),
	OFFSCREEN_KEYFORM_SCREEN_ROW(ElementType.I32, Sizing.TABLE, -1, -1, -1, -1, -1, 163),
	;

	/**
	 * Section-table index of this section for a moc [version], or -1 if absent.
	 *
	 * @param MocVersion version The moc version.
	 * @return Int The section index, or -1.
	 */
	public fun indexIn(version: MocVersion): Int = indexByVersion[version.byteValue - 1]
}
