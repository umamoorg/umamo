package org.umamo.format.moc3.decode

import org.umamo.format.moc3.MocDocument
import org.umamo.format.moc3.moc.MocDrawable
import org.umamo.format.moc3.moc.MocModel
import org.umamo.format.moc3.moc.MocSections
import org.umamo.format.moc3.moc.Section
import org.umamo.format.moc3.moc.Sections
import org.umamo.format.moc3.model.ArtMesh
import org.umamo.format.moc3.model.ArtMeshKeyform
import org.umamo.format.moc3.model.BlendShape
import org.umamo.format.moc3.model.BlendShapeKeyform
import org.umamo.format.moc3.model.BlendShapeLimit
import org.umamo.format.moc3.model.BlendShapeTarget
import org.umamo.format.moc3.model.Deformer
import org.umamo.format.moc3.model.Glue
import org.umamo.format.moc3.model.GlueVertexPair
import org.umamo.format.moc3.model.KeyformAxis
import org.umamo.format.moc3.model.KeyformBinding
import org.umamo.format.moc3.model.Offscreen
import org.umamo.format.moc3.model.OffscreenKeyform
import org.umamo.format.moc3.model.Part
import org.umamo.format.moc3.model.RenderOrderChild
import org.umamo.format.moc3.model.RenderOrderGroup
import org.umamo.format.moc3.model.Rgb
import org.umamo.format.moc3.model.RotationDeformer
import org.umamo.format.moc3.model.RotationKeyform
import org.umamo.format.moc3.model.WarpDeformer
import org.umamo.format.moc3.model.WarpKeyform

/**
 * Resolves a parsed [MocModel] into the semantic [MocDocument]: the keyform-binding grid and each
 * object's per-keyform values (vertex positions, opacity, draw-order, color, deformer transforms).
 *
 * Reads the typed Layer-1 sections and follows the base/index tables - it does not evaluate the
 * model (no interpolation/cascade). Blend shapes (moc 4+) and offscreens (moc 6) are assembled
 * too.  Every section index is modeled in [Section], so nothing is left to raw access.
 *
 * @see <a href="https://docs.umamo.org/format/MOC3.md">MOC3.md §5.6</a>
 */
public object MocDecoder {
	/**
	 * Decodes [model] into a [MocDocument].
	 *
	 * @param MocModel model A parsed `.moc3` container.
	 * @return MocDocument The semantic model.
	 */
	public fun decode(model: MocModel): MocDocument {
		val sections = model.sections
		val deformerCount = model.deformerCount
		val drawables = model.drawables()

		val bindings = BindingResolver(sections)
		bindings.registerStoredBindings(model.countInfo.getOrElse(Sections.CI_KEYFORM_BINDINGS) { 0 })

		// ---- value tables ----
		val colorTables = ColorTables(sections)
		val keyformValues = KeyformValueTables(sections)

		// PARTS
		val partKeyformBinding = sections.intArray(Section.PART_KEYFORM_BINDING)
		val partKeyformBase = sections.intArray(Section.PART_KEYFORM_BASE)
		// s7/s8 are a pair whose split is unpinned (both are 1 corpus-wide); take s7 and default to
		// visible when a stripped file omits it.
		val partVisible = sections.intArray(Section.PART_VISIBLE_ARTMESHES)
		val partList =
			model.parts().mapIndexed { partIndex, part ->
				val keyformBinding = partKeyformBinding[partIndex] // for parts, 0 means static (no binding)
				val gridSize = bindings.staticAwareGridSize(keyformBinding)
				Part(
					part.id,
					part.parentPartIndex,
					keyformBinding,
					FloatArray(gridSize) { keyIndex -> keyformValues.partDrawOrder[partKeyformBase[partIndex] + keyIndex] },
					isVisible = partVisible.getOrElse(partIndex) { 1 } != 0,
				)
			}

		// DEFORMERS
		val decodedDeformers = DeformerDecoder(sections, bindings, colorTables, keyformValues).decodeAll(deformerCount)
		val deformerList = decodedDeformers.deformers

		// ART MESHES
		// MASK_INDEX_DATA is shared: the offscreen entries are its prefix and the drawables' masks
		// follow, so both decoders read the one table (MOC3 v6 §5.6 section 80).
		val maskData = sections.intArray(Section.MASK_INDEX_DATA)
		val artMeshList = ArtMeshDecoder(sections, bindings, colorTables, keyformValues, maskData).decodeAll(drawables)

		// GLUES
		val glueList = decodeGlues(sections)
		// Register glue bindings in the cache like every other object kind: a glue names a binding
		// from the same shared table (MOC3.md §5.6), and without this a glue-exclusive binding would
		// be missing from MocDocument.keyformBinding (dropping the glue's parameter-driven intensity
		// downstream) AND from MocDocument.bindings (dropping its table rows from a re-bake).
		for (glue in glueList) {
			bindings.binding(glue.keyformBindingIndex)
		}

		// RENDER-ORDER GROUPS
		val groupList =
			decodeRenderOrderGroups(sections, model.countInfo.getOrElse(Sections.CI_RENDER_ORDER_GROUPS) { 0 })

		// BLEND SHAPES (MOC3 v4+)
		// MOC3 §5.6: blend delta rows share the base keyforms' value tables (appended after the
		// base rows at each record's RECORD_BASE), so the extraction needs the same tables plus
		// each target object's payload size (warp control-point count, drawable vertex count).
		val blendDeltaTables =
			BlendDeltaTables(
				keyformValues = keyformValues,
				colorTables = colorTables,
				warpControlPointCounts = decodedDeformers.warpControlPointCounts,
				drawableVertexCounts = IntArray(drawables.size) { drawableIndex -> drawables[drawableIndex].vertexCount },
				colorDeltaRowStart =
					model.countInfo.getOrElse(Sections.CI_OFFSCREEN_KEYFORMS) { 0 } +
						deformerList.sumOf { deformer ->
							when (deformer) {
								is WarpDeformer -> deformer.keyforms.size
								is RotationDeformer -> deformer.keyforms.size
							}
						} +
						artMeshList.sumOf { it.keyforms.size },
			)
		val blendShapeList =
			decodeBlendShapes(
				sections,
				model.parameterCount,
				bindings.keyPositions,
				decodedDeformers.warpToDeformer,
				decodedDeformers.rotationToDeformer,
				blendDeltaTables,
			)

		// OFFSCREENS (MOC3 v6)
		// The offscreen mask entries are the PREFIX of MASK_INDEX_DATA, addressed per offscreen by
		// s158 (the cumulative scan of s159, offset from the block start - MOC3 §5.6 section 80).
		val offscreenList =
			decodeOffscreens(
				sections,
				model.countInfo.getOrElse(Sections.CI_OFFSCREENS) { 0 },
				partList,
				colorTables,
				maskData,
			)

		// Probed last, and deliberately so: it measures the trailing region against the bindings
		// registered by every object kind above, the glue pass included.
		val keyPositionsHasParameterUnion = bindings.hasParameterUnionRegion(blendShapeList.isEmpty())

		return MocDocument(
			version = model.version,
			canvas = model.canvasInfo,
			parameters = model.parameters(),
			keyformBindings = bindings.collected,
			parts = partList,
			deformers = deformerList,
			artMeshes = artMeshList,
			glues = glueList,
			renderOrderGroups = groupList,
			blendShapes = blendShapeList,
			offscreens = offscreenList,
			keyPositionsHasParameterUnion = keyPositionsHasParameterUnion,
		)
	}

	/**
	 * Resolves keyform-binding indices into [KeyformBinding]s, and accumulates every one it resolves.
	 *
	 * This is deliberately an accumulator, not merely a cache: the set it has resolved BECOMES
	 * [MocDocument.keyformBindings], so registering a binding is how it reaches the document.  Callers
	 * therefore resolve through [binding] even when they only want the grid size, and the glue pass
	 * resolves bindings purely to register them.  Read [collected] and [mainGridKeyTotal] only after
	 * every object kind has been decoded, or the tail of the binding table goes missing.
	 */
	private class BindingResolver(sections: MocSections) {
		/**
		 * Owning parameter per binding slot, expanded from the per-parameter slot counts (MOC3 §5.6
		 * section 76): the slot table is a flat concatenation, so the parameter is positional.
		 */
		private val owningParameter: IntArray

		private val keyOffset: IntArray = sections.intArray(Section.BINDING_KEY_OFFSET)
		private val keyCount: IntArray = sections.intArray(Section.BINDING_KEY_COUNT)
		private val keyformBindingSlot: IntArray = sections.intArray(Section.KEYFORM_BINDING_SLOT)
		private val keyformBindingStart: IntArray = sections.intArray(Section.KEYFORM_BINDING_START)
		private val keyformBindingCount: IntArray = sections.intArray(Section.KEYFORM_BINDING_COUNT)
		private val resolved = HashMap<Int, KeyformBinding>()

		/** The shared key-position table (MOC3 §5.6 section 77); the blend path reads it too. */
		val keyPositions: FloatArray = sections.floatArray(Section.KEY_POSITIONS)

		init {
			val bindingCountPerParameter = sections.intArray(Section.PARAMETER_BINDING_COUNT)
			owningParameter = IntArray(bindingCountPerParameter.sum())
			var bindingSlot = 0
			for (parameterIndex in bindingCountPerParameter.indices) {
				repeat(bindingCountPerParameter[parameterIndex]) { owningParameter[bindingSlot++] = parameterIndex }
			}
		}

		/** Every binding resolved so far, which is what the document carries. */
		val collected: Map<Int, KeyformBinding> get() = resolved.toMap()

		/**
		 * Resolves the keyform binding at [keyformBindingIndex] into its parameter axes, registering it.
		 *
		 * @param Int keyformBindingIndex A keyform-binding index referenced by an object.
		 * @return KeyformBinding The resolved binding (its controlling parameter axes + key positions).
		 */
		fun binding(keyformBindingIndex: Int): KeyformBinding =
			resolved.getOrPut(keyformBindingIndex) {
				val start = keyformBindingStart[keyformBindingIndex]
				val axes =
					(0 until keyformBindingCount[keyformBindingIndex]).map { axisIndex ->
						val bindingSlot = keyformBindingSlot[start + axisIndex]
						KeyformAxis(
							owningParameter[bindingSlot],
							keyPositions.copyOfRange(
								keyOffset[bindingSlot],
								keyOffset[bindingSlot] + keyCount[bindingSlot],
							),
						)
					}
				KeyformBinding(keyformBindingIndex, axes)
			}

		/**
		 * The grid size of a binding, treating a part's 0 as "static" rather than as binding 0.
		 *
		 * @param Int keyformBindingIndex A part's keyform-binding index, where 0 means no binding.
		 * @return Int The keyform grid size, 1 when static.
		 */
		fun staticAwareGridSize(keyformBindingIndex: Int): Int =
			if (keyformBindingIndex <= 0) {
				1
			} else {
				binding(keyformBindingIndex).gridSize
			}

		/**
		 * Registers every binding record the file stores, not only those objects reference.
		 *
		 * The file allocates CountInfo[12] records, and a mesh-less model carries a single EMPTY
		 * binding (0 axes) that only static parts point at - lazy by-reference registration would drop
		 * it and shrink the re-synthesized binding sections + CountInfo (probed on the
		 * ModelWithOffscreen family).  MOC3 §5.1 CountInfo field 12.
		 *
		 * @param Int storedBindingCount The stored record count, CountInfo field 12.
		 */
		fun registerStoredBindings(storedBindingCount: Int) {
			repeat(storedBindingCount) { bindingIndex ->
				binding(bindingIndex)
			}
		}

		/**
		 * Total keys the deduplicated main-grid region of KEY_POSITIONS holds, over the bindings
		 * registered so far.  This is where the optional trailing union region begins.
		 *
		 * @return Int The main-grid key total.
		 */
		private fun mainGridKeyTotal(): Int {
			val keySetsByParameter = HashMap<Int, LinkedHashSet<List<Float>>>()
			for (resolvedBinding in resolved.values) {
				for (axis in resolvedBinding.axes) {
					keySetsByParameter.getOrPut(axis.parameterIndex) { LinkedHashSet() }.add(axis.keyPositions.toList())
				}
			}
			return keySetsByParameter.values.sumOf { keySets -> keySets.sumOf { it.size } }
		}

		/**
		 * Whether KEY_POSITIONS (77) trails its dedup region with the per-parameter sorted-union of the
		 * main-grid axis keys, an editor-version artifact on some blend-free v1/v3 files (MOC3 §5.6).
		 *
		 * Detected as any nonzero key beyond the dedup (main-grid) region; zero padding beyond the
		 * region reads as absent.  Only meaningful on a blend-free file - a blend model carries the
		 * region unconditionally, which the blend lowering path handles.
		 *
		 * @param Boolean isBlendFree Whether the model decoded no blend shapes.
		 * @return Boolean Whether the trailing union region is present.
		 */
		fun hasParameterUnionRegion(isBlendFree: Boolean): Boolean =
			isBlendFree && (mainGridKeyTotal() until keyPositions.size).any { keyIndex -> keyPositions[keyIndex] != 0f }
	}

	/**
	 * The shared per-keyform color tables (MOC3 §5.6 sections 108-113) and their row addressing.
	 *
	 * Every consumer reaches the same six tables by a different route - a base keyform as
	 * `colorBase + gridIndex`, an offscreen as one of the block's prefix rows, a blend-shape delta as
	 * a delta row - so the addressing is expressed once as [multiplyAtRow] / [screenAtRow] and the
	 * keyform form layers on top.  An absent color section yields a null color from every accessor,
	 * which is what keeps the presence check off the call sites.
	 */
	private class ColorTables(sections: MocSections) {
		/** Whether the model carries color tables at all; absent on a model with no color animation. */
		val isPresent: Boolean = sections.isPresent(Section.COLOR_MULTIPLY_R)

		private val multiplyR: FloatArray = sections.floatArray(Section.COLOR_MULTIPLY_R)
		private val multiplyG: FloatArray = sections.floatArray(Section.COLOR_MULTIPLY_G)
		private val multiplyB: FloatArray = sections.floatArray(Section.COLOR_MULTIPLY_B)
		private val screenR: FloatArray = sections.floatArray(Section.COLOR_SCREEN_R)
		private val screenG: FloatArray = sections.floatArray(Section.COLOR_SCREEN_G)
		private val screenB: FloatArray = sections.floatArray(Section.COLOR_SCREEN_B)

		/**
		 * Rows the tables hold, which the blend path probes against to decide whether the delta region
		 * was baked at all (a 4.2-era bake carries the base rows only).
		 */
		val rowCount: Int get() = multiplyR.size

		/**
		 * Multiply-color at absolute table row [row].
		 *
		 * @param Int row The absolute row index; negative means "this object has no color row".
		 * @return Rgb? The multiply color, or null when the tables are absent or [row] is negative.
		 */
		fun multiplyAtRow(row: Int): Rgb? =
			if (isPresent && row >= 0) {
				Rgb(multiplyR[row], multiplyG[row], multiplyB[row])
			} else {
				null
			}

		/**
		 * Screen-color at absolute table row [row].
		 *
		 * @param Int row The absolute row index; negative means "this object has no color row".
		 * @return Rgb? The screen color, or null when the tables are absent or [row] is negative.
		 */
		fun screenAtRow(row: Int): Rgb? =
			if (isPresent && row >= 0) {
				Rgb(screenR[row], screenG[row], screenB[row])
			} else {
				null
			}

		/**
		 * Multiply-color at keyform [gridIndex] of an object whose color table starts at [colorBase].
		 *
		 * @param Int? colorBase The object's base row (null or -1 when the object is uncolored).
		 * @param Int  gridIndex The keyform's grid index.
		 * @return Rgb? The multiply color, or null when color is absent.
		 */
		fun multiplyForKeyform(colorBase: Int?, gridIndex: Int): Rgb? = multiplyAtRow(keyformRow(colorBase, gridIndex))

		/**
		 * Screen-color at keyform [gridIndex] of an object whose color table starts at [colorBase].
		 *
		 * @param Int? colorBase The object's base row (null or -1 when the object is uncolored).
		 * @param Int  gridIndex The keyform's grid index.
		 * @return Rgb? The screen color, or null when color is absent.
		 */
		fun screenForKeyform(colorBase: Int?, gridIndex: Int): Rgb? = screenAtRow(keyformRow(colorBase, gridIndex))

		/**
		 * Resolves an object's keyform row, collapsing "uncolored" to the negative sentinel the
		 * row accessors already treat as absent.
		 *
		 * @param Int? colorBase The object's base row (null or -1 when the object is uncolored).
		 * @param Int  gridIndex The keyform's grid index.
		 * @return Int The absolute row, or -1 when the object carries no color.
		 */
		private fun keyformRow(colorBase: Int?, gridIndex: Int): Int =
			if (colorBase == null || colorBase < 0) {
				-1
			} else {
				colorBase + gridIndex
			}
	}

	/**
	 * The per-keyform value tables shared by the base keyforms and the blend-shape delta rows.
	 *
	 * MOC3 §5.6 appends a record's delta rows AFTER the base rows of these same tables, so a base
	 * keyform and a delta differ only in which row they address.  That is why they are read once here
	 * and handed to both paths.  Per-type structural tables (a warp's rows/columns, a drawable's UVs,
	 * any id or flag column) are NOT shared and stay with their own decoder.
	 */
	private class KeyformValueTables(sections: MocSections) {
		/** Art-mesh keyform -> packed-position offset (§5.6 section 70, indexing into 71). */
		val positionIndex: IntArray = sections.intArray(Section.KEYFORM_POSITION_INDEX)

		/** Warp keyform -> packed-position offset; a table distinct from [positionIndex] (section 60). */
		val warpPositionIndex: IntArray = sections.intArray(Section.WARP_KEYFORM_INDEX)

		/** The packed position blocks both index tables point into (section 71). */
		val positionValues: FloatArray = sections.floatArray(Section.KEYFORM_POSITION_VALUES)

		/** Part draw-order rows (section 58). */
		val partDrawOrder: FloatArray = sections.floatArray(Section.PART_DRAW_ORDER)

		/** Warp opacity rows (section 59). */
		val warpOpacity: FloatArray = sections.floatArray(Section.WARP_OPACITY)

		/** Art-mesh opacity rows (section 68). */
		val artMeshOpacity: FloatArray = sections.floatArray(Section.ARTMESH_OPACITY)

		/** Art-mesh draw-order rows (section 69). */
		val artMeshDrawOrder: FloatArray = sections.floatArray(Section.ARTMESH_DRAW_ORDER)

		/** Rotation opacity rows (section 61); a rotation delta indexes the affine tables directly. */
		val rotationOpacity: FloatArray = sections.floatArray(Section.ROTATION_OPACITY)

		/** Rotation angle rows (section 62). */
		val rotationAngle: FloatArray = sections.floatArray(Section.ROTATION_ANGLE)

		/** Rotation pivot X rows (section 63). */
		val rotationOriginX: FloatArray = sections.floatArray(Section.ROTATION_ORIGIN_X)

		/** Rotation pivot Y rows (section 64). */
		val rotationOriginY: FloatArray = sections.floatArray(Section.ROTATION_ORIGIN_Y)

		/** Rotation scale rows (section 65). */
		val rotationScale: FloatArray = sections.floatArray(Section.ROTATION_SCALE)

		/** Rotation X-reflection flags (section 66). */
		val rotationReflectX: IntArray = sections.intArray(Section.ROTATION_REFLECT_X)

		/** Rotation Y-reflection flags (section 67). */
		val rotationReflectY: IntArray = sections.intArray(Section.ROTATION_REFLECT_Y)
	}

	/** The decoded deformer block, plus the index maps and payload sizes the blend path needs. */
	private class DecodedDeformers(
		val deformers: List<Deformer>,
		/** Warp local index -> deformer index; blend-shape warp records are stored against the local one. */
		val warpToDeformer: List<Int>,
		/** Rotation local index -> deformer index, for the same reason. */
		val rotationToDeformer: List<Int>,
		/** Control points per warp, which sizes both a base warp keyform and a warp delta payload. */
		val warpControlPointCounts: IntArray,
	)

	/**
	 * Decodes the deformer block into typed [WarpDeformer]s and [RotationDeformer]s.
	 *
	 * Both kinds share one deformer index space and one common head (MOC3 §5.6 sections 11-15), but
	 * each kind's own tables are densely packed over only its own members.  So the walk advances the
	 * shared index while carrying a separate local cursor per kind, and every per-type read is by that
	 * local index, never the deformer index.  Those local indices are also how blend-shape records
	 * name their target, which is what [DecodedDeformers] carries the maps for.
	 */
	private class DeformerDecoder(
		sections: MocSections,
		private val bindings: BindingResolver,
		private val colorTables: ColorTables,
		private val keyformValues: KeyformValueTables,
	) {
		private val deformerType = sections.intArray(Section.DEFORMER_TYPE)
		private val deformerParent = sections.intArray(Section.DEFORMER_PARENT)

		// The block's common head (MOC3 §5.6 S11-S15).
		private val deformerId = sections.idArray(Section.DEFORMER_ID)

		// Section 12 is the same binding that the warp/rotation sections 19/25 have.
		// Warp and Rotation have their own keyform binding which duplicates the deformer binding.
		// We don't necessarily need it, but we read and write it since there is a chance it might cause a MOC3
		// validation error in the runtime.
		private val deformerKeyformBinding = sections.intArray(Section.DEFORMER_KEYFORM_BINDING)
		private val deformerIsVisible = sections.intArray(Section.DEFORMER_IS_VISIBLE)
		private val deformerIsEnabled = sections.intArray(Section.DEFORMER_IS_ENABLED)
		private val deformerParentPart = sections.intArray(Section.DEFORMER_PARENT_PART)

		private val warpKeyformBinding = sections.intArray(Section.WARP_KEYFORM_BINDING)
		private val warpKeyformBase = sections.intArray(Section.WARP_KEYFORM_BASE)
		private val warpRows = sections.intArray(Section.WARP_ROWS)
		private val warpColumns = sections.intArray(Section.WARP_COLUMNS)
		private val warpMode = if (sections.isPresent(Section.WARP_MODE)) sections.intArray(Section.WARP_MODE) else null
		private val warpColorBase =
			if (sections.isPresent(Section.WARP_COLOR_BASE)) sections.intArray(Section.WARP_COLOR_BASE) else null

		private val rotationKeyformBinding = sections.intArray(Section.ROTATION_KEYFORM_BINDING)
		private val rotationKeyformBase = sections.intArray(Section.ROTATION_KEYFORM_BASE)
		private val rotationBaseAngle = sections.floatArray(Section.ROTATION_BASE_ANGLE)
		private val rotationColorBase =
			if (sections.isPresent(Section.ROTATION_COLOR_BASE)) sections.intArray(Section.ROTATION_COLOR_BASE) else null

		/** Control points per warp: an (rows + 1) x (columns + 1) lattice of them. */
		private val warpControlPointCounts =
			IntArray(warpRows.size) { warpLocalIndex ->
				(warpRows[warpLocalIndex] + 1) * (warpColumns[warpLocalIndex] + 1)
			}

		/**
		 * Decodes every deformer, dispatching on the section 11 type column.
		 *
		 * @param Int deformerCount The model's deformer count.
		 * @return DecodedDeformers The deformers in index order, with the per-kind index maps.
		 */
		fun decodeAll(deformerCount: Int): DecodedDeformers {
			var nextWarpLocal = 0
			var nextRotationLocal = 0
			val warpToDeformer = ArrayList<Int>()
			val rotationToDeformer = ArrayList<Int>()
			val deformers = ArrayList<Deformer>(deformerCount)
			for (deformerIndex in 0 until deformerCount) {
				if (deformerType[deformerIndex] == 0) {
					warpToDeformer.add(deformerIndex)
					deformers.add(warpDeformer(deformerIndex, nextWarpLocal++))
				} else {
					rotationToDeformer.add(deformerIndex)
					deformers.add(rotationDeformer(deformerIndex, nextRotationLocal++))
				}
			}
			return DecodedDeformers(deformers, warpToDeformer, rotationToDeformer, warpControlPointCounts)
		}

		/**
		 * Builds one warp deformer and its FFD lattice keyforms.
		 *
		 * @param Int deformerIndex  Its index in the shared deformer space (the common head's row).
		 * @param Int warpLocalIndex Its index among warps (every per-warp table's row).
		 * @return WarpDeformer The decoded deformer.
		 */
		private fun warpDeformer(deformerIndex: Int, warpLocalIndex: Int): WarpDeformer {
			val keyformBinding = warpKeyformBinding[warpLocalIndex]
			val keyformBase = warpKeyformBase[warpLocalIndex]
			val controlPointCount = warpControlPointCounts[warpLocalIndex]
			val keyforms =
				(0 until bindings.binding(keyformBinding).gridSize).map { gridIndex ->
					val positionOffset = keyformValues.warpPositionIndex[keyformBase + gridIndex]
					WarpKeyform(
						keyformValues.positionValues.copyOfRange(positionOffset, positionOffset + controlPointCount * 2),
						keyformValues.warpOpacity[keyformBase + gridIndex],
						colorTables.multiplyForKeyform(warpColorBase?.get(warpLocalIndex), gridIndex),
						colorTables.screenForKeyform(warpColorBase?.get(warpLocalIndex), gridIndex),
					)
				}
			return WarpDeformer(
				id = deformerId.getOrElse(deformerIndex) { "" },
				keyformBindingIndex = keyformBinding,
				isVisible = deformerIsVisible.getOrElse(deformerIndex) { 1 } != 0,
				isEnabled = deformerIsEnabled.getOrElse(deformerIndex) { 1 } != 0,
				parentPartIndex = deformerParentPart.getOrElse(deformerIndex) { -1 },
				parentDeformerIndex = deformerParent[deformerIndex],
				rows = warpRows[warpLocalIndex],
				columns = warpColumns[warpLocalIndex],
				mode = warpMode?.get(warpLocalIndex) ?: 0,
				keyforms = keyforms,
			)
		}

		/**
		 * Builds one rotation deformer and its pivot-transform keyforms.
		 *
		 * @param Int deformerIndex      Its index in the shared deformer space (the common head's row).
		 * @param Int rotationLocalIndex Its index among rotations (every per-rotation table's row).
		 * @return RotationDeformer The decoded deformer.
		 */
		private fun rotationDeformer(deformerIndex: Int, rotationLocalIndex: Int): RotationDeformer {
			val keyformBinding = rotationKeyformBinding[rotationLocalIndex]
			val keyformBase = rotationKeyformBase[rotationLocalIndex]
			val keyforms =
				(0 until bindings.binding(keyformBinding).gridSize).map { gridIndex ->
					RotationKeyform(
						keyformValues.rotationOriginX[keyformBase + gridIndex],
						keyformValues.rotationOriginY[keyformBase + gridIndex],
						keyformValues.rotationAngle[keyformBase + gridIndex],
						keyformValues.rotationScale[keyformBase + gridIndex],
						keyformValues.rotationReflectX[keyformBase + gridIndex] != 0,
						keyformValues.rotationReflectY[keyformBase + gridIndex] != 0,
						keyformValues.rotationOpacity[keyformBase + gridIndex],
						colorTables.multiplyForKeyform(rotationColorBase?.get(rotationLocalIndex), gridIndex),
						colorTables.screenForKeyform(rotationColorBase?.get(rotationLocalIndex), gridIndex),
					)
				}
			return RotationDeformer(
				id = deformerId.getOrElse(deformerIndex) { "" },
				keyformBindingIndex = keyformBinding,
				isVisible = deformerIsVisible.getOrElse(deformerIndex) { 1 } != 0,
				isEnabled = deformerIsEnabled.getOrElse(deformerIndex) { 1 } != 0,
				parentPartIndex = deformerParentPart.getOrElse(deformerIndex) { -1 },
				parentDeformerIndex = deformerParent[deformerIndex],
				baseAngle = rotationBaseAngle[rotationLocalIndex],
				keyforms = keyforms,
			)
		}
	}

	/**
	 * Decodes the drawables into [ArtMesh]es: their geometry slices and per-keyform values.
	 *
	 * A drawable's UVs, triangle indices, and mask indices are not addressed by any index table -
	 * they are CONCATENATED per drawable in drawable order, so the decode carries a running cursor
	 * per table and advances it by that drawable's own counts.  The mask cursor is the one that does
	 * not start at zero: the offscreen mask entries are the block's prefix (MOC3 v6 §5.6 section 80).
	 */
	private class ArtMeshDecoder(
		sections: MocSections,
		private val bindings: BindingResolver,
		private val colorTables: ColorTables,
		private val keyformValues: KeyformValueTables,
		private val maskData: IntArray,
	) {
		private val keyformBindingIndex = sections.intArray(Section.ARTMESH_KEYFORM_BINDING)
		private val keyformBase = sections.intArray(Section.ARTMESH_KEYFORM_BASE)
		private val parentDeformer = sections.intArray(Section.ARTMESH_PARENT_DEFORMER)
		private val colorBase =
			if (sections.isPresent(Section.ARTMESH_COLOR_BASE)) sections.intArray(Section.ARTMESH_COLOR_BASE) else null

		// MOC3 v6 §5.6 s153: per-drawable packed extended blend (0 = legacy constant-flags blend).
		private val extendedBlend =
			if (sections.isPresent(Section.ARTMESH_EXTENDED_BLEND)) sections.intArray(Section.ARTMESH_EXTENDED_BLEND) else null

		// s37 is the visibility toggle (pinned by joining miku_verycursed against its CMO3 twin); s38 is
		// 1 on every drawable of every corpus sample and is carried only so a bake reproduces it.
		private val isVisible = sections.intArray(Section.ARTMESH_IS_VISIBLE)
		private val isEnabled = sections.intArray(Section.ARTMESH_IS_ENABLED)

		private val uvData = sections.floatArray(Section.ARTMESH_UV_DATA)
		private val indexData = sections.shortArray(Section.ARTMESH_INDEX_DATA)

		/**
		 * Where the drawables' mask entries begin: the offscreen entries are the block's PREFIX and
		 * the drawables' masks follow (pinned on Model A against the CMO3 ground truth + the runtime's
		 * s158 addressing, which offsets from the block start).  Pre-v6 there is no prefix.
		 */
		private val offscreenMaskTotal =
			if (sections.isPresent(Section.OFFSCREEN_MASK_COUNT)) {
				sections.intArray(Section.OFFSCREEN_MASK_COUNT).sum()
			} else {
				0
			}

		/**
		 * Decodes every drawable, walking the concatenated geometry tables in drawable order.
		 *
		 * @param List<MocDrawable> drawables The model's drawables, whose counts drive the cursors.
		 * @return List<ArtMesh> The decoded art meshes, in drawable order.
		 */
		fun decodeAll(drawables: List<MocDrawable>): List<ArtMesh> {
			var vertexBase = 0
			var indexBase = 0
			var maskBase = offscreenMaskTotal
			return drawables.mapIndexed { drawableIndex, drawable ->
				val vertexCount = drawable.vertexCount
				val uvs = uvData.copyOfRange(vertexBase * 2, vertexBase * 2 + vertexCount * 2)
				val triangleIndices = indexData.copyOfRange(indexBase, indexBase + drawable.indexCount)
				val maskIndices = maskData.copyOfRange(maskBase, maskBase + drawable.maskCount)
				vertexBase += vertexCount
				indexBase += drawable.indexCount
				maskBase += drawable.maskCount
				artMesh(drawableIndex, drawable, uvs, triangleIndices, maskIndices)
			}
		}

		/**
		 * Builds one art mesh from its already-sliced geometry plus its per-keyform values.
		 *
		 * @param Int          drawableIndex   The drawable's index (every per-drawable table's row).
		 * @param MocDrawable  drawable        The drawable header (id, texture, flags, counts).
		 * @param FloatArray   uvs             This drawable's slice of the UV table.
		 * @param ShortArray   triangleIndices This drawable's slice of the index table.
		 * @param IntArray     maskIndices     This drawable's slice of the mask table.
		 * @return ArtMesh The decoded art mesh.
		 */
		private fun artMesh(
			drawableIndex: Int,
			drawable: MocDrawable,
			uvs: FloatArray,
			triangleIndices: ShortArray,
			maskIndices: IntArray,
		): ArtMesh {
			val keyformBinding = keyformBindingIndex[drawableIndex]
			val base = keyformBase[drawableIndex]
			val keyforms =
				(0 until bindings.binding(keyformBinding).gridSize).map { gridIndex ->
					val positionOffset = keyformValues.positionIndex[base + gridIndex]
					ArtMeshKeyform(
						keyformValues.positionValues.copyOfRange(
							positionOffset,
							positionOffset + drawable.vertexCount * 2,
						),
						keyformValues.artMeshOpacity[base + gridIndex],
						keyformValues.artMeshDrawOrder[base + gridIndex],
						colorTables.multiplyForKeyform(colorBase?.get(drawableIndex), gridIndex),
						colorTables.screenForKeyform(colorBase?.get(drawableIndex), gridIndex),
					)
				}
			return ArtMesh(
				drawable.id,
				drawable.textureIndex,
				drawable.constantFlags,
				extendedBlend?.get(drawableIndex) ?: 0,
				// Default to visible: a stripped file omitting the flags means "nothing is hidden".
				isVisible.getOrElse(drawableIndex) { 1 } != 0,
				isEnabled.getOrElse(drawableIndex) { 1 } != 0,
				drawable.parentPartIndex,
				parentDeformer[drawableIndex],
				uvs,
				triangleIndices,
				maskIndices,
				keyformBinding,
				keyforms,
			)
		}
	}

	/**
	 * The per-object payload sizes and row anchor a blend-shape record's delta rows need, over the
	 * shared [KeyformValueTables]. Bundled so [decodeBlendShapes] can lift the per-key delta payloads
	 * without a dozen loose parameters.
	 */
	private class BlendDeltaTables(
		val keyformValues: KeyformValueTables,
		val colorTables: ColorTables,
		val warpControlPointCounts: IntArray,
		val drawableVertexCounts: IntArray,
		/**
		 * First color-table row of the blend delta region: the moc-6 offscreen keyform prefix plus
		 * every object's base keyform rows.  Computed from CONTENT, not from the table length - the
		 * raw element region is 64-byte zero-padded, so length-based anchoring drifts by the pad.
		 */
		val colorDeltaRowStart: Int,
	)

	/**
	 * The deduplicated blend-weight limit pool (MOC3 v4+ §5.6 sections 123/124 + 131-136).
	 *
	 * A record ranges into SUB_INDEX, whose entries name a shared pool of (parameter, keys, weights)
	 * curves; records commonly point at the same pool entry, so the pool is stored once and expanded
	 * per record here.  Absent tables decode as no limits, like any absent section.
	 */
	private class BlendLimitPool(sections: MocSections) {
		private val recordSubstart = sections.intArray(Section.BLENDSHAPE_RECORD_SUBSTART)
		private val recordCornerCount = sections.intArray(Section.BLENDSHAPE_RECORD_CORNER_COUNT)
		private val hasSubTables = sections.isPresent(Section.BLENDSHAPE_SUB_INDEX)
		private val subIndex = if (hasSubTables) sections.intArray(Section.BLENDSHAPE_SUB_INDEX) else IntArray(0)
		private val subParameter = if (hasSubTables) sections.intArray(Section.BLENDSHAPE_SUB_PARAMETER) else IntArray(0)
		private val subKeyOffset = if (hasSubTables) sections.intArray(Section.BLENDSHAPE_SUB_KEY_OFFSET) else IntArray(0)
		private val subKeyCount = if (hasSubTables) sections.intArray(Section.BLENDSHAPE_SUB_KEY_COUNT) else IntArray(0)
		private val subKeys = if (hasSubTables) sections.floatArray(Section.BLENDSHAPE_SUB_KEYS) else FloatArray(0)
		private val subWeights = if (hasSubTables) sections.floatArray(Section.BLENDSHAPE_SUB_WEIGHT_VALUES) else FloatArray(0)

		/**
		 * Expands one record's sub-binding refs into its limit curves (empty when uncapped).
		 *
		 * @param Int recordIndex The record's index in the record tables.
		 * @return List<BlendShapeLimit> The record's limits, pool entries expanded per record.
		 */
		fun limitsFor(recordIndex: Int): List<BlendShapeLimit> {
			if (!hasSubTables || recordIndex >= recordCornerCount.size) {
				return emptyList()
			}
			val cornerCount = recordCornerCount[recordIndex]
			if (cornerCount == 0) {
				return emptyList()
			}
			val cornerStart = recordSubstart[recordIndex]
			return (cornerStart until cornerStart + cornerCount).map { cornerIndex ->
				val subBinding = subIndex[cornerIndex]
				val keyOffset = subKeyOffset[subBinding]
				val keyCount = subKeyCount[subBinding]
				BlendShapeLimit(
					parameterIndex = subParameter[subBinding],
					keyPositions = subKeys.copyOfRange(keyOffset, keyOffset + keyCount),
					weights = subWeights.copyOfRange(keyOffset, keyOffset + keyCount),
				)
			}
		}
	}

	/**
	 * Per-record addressing into the color tables' blend-delta region (MOC3 §5.6 sections 108-113).
	 *
	 * The delta region follows the base rows (and, on MOC3 v6, the offscreen keyform prefix), holding
	 * one row per (record, key) for warp, mesh, and rotation records in global record order - part
	 * records own no color rows.  It anchors at the content-derived base-row total
	 * ([BlendDeltaTables.colorDeltaRowStart]); anchoring at table length minus the delta total drifts
	 * by the element region's 64-byte zero padding (2 rows on Model A, 10 on Model C - caught by
	 * their authored color morphs).
	 *
	 * The region is also a later format addition, so presence is probed rather than assumed: a
	 * 4.2-era bake with blend shapes carries the base rows only (corpus: Azxiana.moc3, V42 - CountInfo
	 * 23/24 there count base + prefix rows while fields 7-9 still include the deltas).  When the
	 * tables do not cover the read extent, every record resolves to the absent sentinel and the deltas
	 * decode as null colors.
	 */
	private class BlendColorDeltas(
		sections: MocSections,
		recordCount: Int,
		recordBinding: IntArray,
		bindingKeyCount: IntArray,
		deltaTables: BlendDeltaTables,
	) {
		private val colorTables: ColorTables = deltaTables.colorTables

		/** Each record's first delta row, or -1 when it owns none (part-owned, or the region absent). */
		private val recordColorRow: IntArray

		init {
			val partOwnedRecord = partOwnedRecords(sections, recordCount)
			val colorReadRecord = colorReadRecords(sections, recordCount)
			// Measure the read extent over exactly the rows that will be dereferenced: the record
			// tables carry a few TRAILING records referenced by no group on every blend corpus model,
			// and the stored region ends at the referenced records' rows (modelA: table 1808 = read
			// extent 1806 + 2 rows padding; modelC: 2208 = 2198 + 10).
			var requiredRowEnd = deltaTables.colorDeltaRowStart
			var rowProbeCursor = deltaTables.colorDeltaRowStart
			for (recordIndex in 0 until recordCount) {
				if (!partOwnedRecord[recordIndex]) {
					val rowEnd = rowProbeCursor + bindingKeyCount[recordBinding[recordIndex]]
					if (colorReadRecord[recordIndex] && rowEnd > requiredRowEnd) {
						requiredRowEnd = rowEnd
					}
					rowProbeCursor = rowEnd
				}
			}
			val rows = IntArray(recordCount) { -1 }
			if (colorTables.isPresent && requiredRowEnd <= colorTables.rowCount) {
				var colorRowCursor = deltaTables.colorDeltaRowStart
				for (recordIndex in 0 until recordCount) {
					if (!partOwnedRecord[recordIndex]) {
						rows[recordIndex] = colorRowCursor
						colorRowCursor += bindingKeyCount[recordBinding[recordIndex]]
					}
				}
			}
			recordColorRow = rows
		}

		/**
		 * Marks the records owned by a part object group, which carry no color rows.
		 *
		 * @param MocSections sections    The model's typed sections.
		 * @param Int         recordCount Total blend-shape records.
		 * @return BooleanArray One flag per record.
		 */
		private fun partOwnedRecords(sections: MocSections, recordCount: Int): BooleanArray {
			val owned = BooleanArray(recordCount)
			if (!sections.isPresent(Section.BLENDSHAPE_PART_OBJECT)) {
				return owned
			}
			val partRecordStarts = sections.intArray(Section.BLENDSHAPE_PART_RECORD_START)
			val partRecordCounts = sections.intArray(Section.BLENDSHAPE_PART_RECORD_COUNT)
			for (groupIndex in partRecordStarts.indices) {
				val partRecordEnd = partRecordStarts[groupIndex] + partRecordCounts[groupIndex]
				for (recordIndex in partRecordStarts[groupIndex] until partRecordEnd) {
					owned[recordIndex] = true
				}
			}
			return owned
		}

		/**
		 * Marks the records a warp, mesh, or rotation object group references - the only ones whose
		 * color rows are ever dereferenced.
		 *
		 * @param MocSections sections    The model's typed sections.
		 * @param Int         recordCount Total blend-shape records.
		 * @return BooleanArray One flag per record.
		 */
		private fun colorReadRecords(sections: MocSections, recordCount: Int): BooleanArray {
			val referenced = BooleanArray(recordCount)
			val groups =
				listOf(
					Triple(
						Section.BLENDSHAPE_WARP_OBJECT,
						Section.BLENDSHAPE_WARP_RECORD_START,
						Section.BLENDSHAPE_WARP_RECORD_COUNT,
					),
					Triple(
						Section.BLENDSHAPE_MESH_OBJECT,
						Section.BLENDSHAPE_MESH_RECORD_START,
						Section.BLENDSHAPE_MESH_RECORD_COUNT,
					),
					Triple(
						Section.BLENDSHAPE_ROTATION_OBJECT,
						Section.BLENDSHAPE_ROTATION_RECORD_START,
						Section.BLENDSHAPE_ROTATION_RECORD_COUNT,
					),
				)
			for ((objectSection, startSection, countSection) in groups) {
				if (!sections.isPresent(objectSection)) {
					continue
				}
				val recordStarts = sections.intArray(startSection)
				val recordCounts = sections.intArray(countSection)
				for (groupIndex in recordStarts.indices) {
					for (recordIndex in recordStarts[groupIndex] until recordStarts[groupIndex] + recordCounts[groupIndex]) {
						if (recordIndex in 0 until recordCount) {
							referenced[recordIndex] = true
						}
					}
				}
			}
			return referenced
		}

		/**
		 * Multiply-color delta at key [keyIndex] of record [recordIndex].
		 *
		 * @param Int recordIndex The record's index in the record tables.
		 * @param Int keyIndex    The key's index within the record's binding.
		 * @return Rgb? The multiply-color delta row, or null when the record owns no color rows.
		 */
		fun multiplyDelta(recordIndex: Int, keyIndex: Int): Rgb? {
			val colorRow = recordColorRow[recordIndex]
			// Guarded here rather than folded into the row accessor: a negative row is the "no delta
			// rows" sentinel, and offsetting it by keyIndex would land on a real row.
			return if (colorRow < 0) {
				null
			} else {
				colorTables.multiplyAtRow(colorRow + keyIndex)
			}
		}

		/**
		 * Screen-color delta at key [keyIndex] of record [recordIndex].
		 *
		 * @param Int recordIndex The record's index in the record tables.
		 * @param Int keyIndex    The key's index within the record's binding.
		 * @return Rgb? The screen-color delta row, or null when the record owns no color rows.
		 */
		fun screenDelta(recordIndex: Int, keyIndex: Int): Rgb? {
			val colorRow = recordColorRow[recordIndex]
			// Same sentinel guard as multiplyDelta above.
			return if (colorRow < 0) {
				null
			} else {
				colorTables.screenAtRow(colorRow + keyIndex)
			}
		}
	}

	/**
	 * Decodes the blend-shape records: the binding structure, each record's blend-weight limits, and
	 * the typed per-key delta payloads.
	 *
	 * The records live in one flat table addressed by per-kind object groups (MOC3 v4+ §5.6 for
	 * meshes/warps, v5+ for rotations and parts).  A group names its objects by KIND-LOCAL index,
	 * which is what sizes a delta payload, while the emitted [BlendShape] carries the deformer or
	 * drawable index instead - hence both travel together through the decode.
	 */
	private class BlendShapeDecoder(
		private val sections: MocSections,
		parameterCount: Int,
		private val keyPositions: FloatArray,
		private val deltaTables: BlendDeltaTables,
	) {
		private val bindingKeyOffset = sections.intArray(Section.BLENDSHAPE_BINDING_KEY_OFFSET)
		private val bindingKeyCount = sections.intArray(Section.BLENDSHAPE_BINDING_KEY_COUNT)
		private val bindingNeutral = sections.intArray(Section.BLENDSHAPE_BINDING_NEUTRAL)
		private val recordBinding = sections.intArray(Section.BLENDSHAPE_RECORD_BINDING)
		private val recordBase = sections.intArray(Section.BLENDSHAPE_RECORD_BASE)
		private val bindingOwner = resolveBindingOwners(sections, parameterCount)
		private val limitPool = BlendLimitPool(sections)
		private val colorDeltas =
			BlendColorDeltas(sections, recordBinding.size, recordBinding, bindingKeyCount, deltaTables)

		/** The shared value tables the delta rows live in; aliased to keep the payload reads legible. */
		private val keyformValues = deltaTables.keyformValues

		/**
		 * Resolves the owning parameter of every blend-shape binding.
		 *
		 * Unlike the main keyform bindings, whose slot table is a flat concatenation, these carry
		 * explicit begin/count ranges per parameter (MOC3 §5.6).
		 *
		 * @param MocSections sections       The model's typed sections.
		 * @param Int         parameterCount Number of parameters.
		 * @return IntArray The owning parameter index per binding.
		 */
		private fun resolveBindingOwners(sections: MocSections, parameterCount: Int): IntArray {
			val parameterBegin = sections.intArray(Section.BLENDSHAPE_PARAMETER_BEGIN)
			val parameterBindingCount = sections.intArray(Section.BLENDSHAPE_PARAMETER_COUNT)
			val owners = IntArray((0 until parameterCount).sumOf { parameterBindingCount[it] })
			for (parameterIndex in 0 until parameterCount) {
				for (bindingIndex in 0 until parameterBindingCount[parameterIndex]) {
					owners[parameterBegin[parameterIndex] + bindingIndex] = parameterIndex
				}
			}
			return owners
		}

		/**
		 * Decodes every object group's records, in warp, mesh, rotation, then part order.
		 *
		 * @param List<Int> warpToDeformer     Maps a warp local index to its deformer index.
		 * @param List<Int> rotationToDeformer Maps a rotation local index to its deformer index.
		 * @return List<BlendShape> The decoded records.
		 */
		fun decodeAll(warpToDeformer: List<Int>, rotationToDeformer: List<Int>): List<BlendShape> {
			val blendShapes = ArrayList<BlendShape>()
			blendShapes +=
				groupRecords(
					Section.BLENDSHAPE_WARP_OBJECT,
					Section.BLENDSHAPE_WARP_RECORD_START,
					Section.BLENDSHAPE_WARP_RECORD_COUNT,
					BlendShapeTarget.WARP,
					warpToDeformer,
				)
			blendShapes +=
				groupRecords(
					Section.BLENDSHAPE_MESH_OBJECT,
					Section.BLENDSHAPE_MESH_RECORD_START,
					Section.BLENDSHAPE_MESH_RECORD_COUNT,
					BlendShapeTarget.ART_MESH,
					null,
				)
			blendShapes +=
				groupRecords(
					Section.BLENDSHAPE_ROTATION_OBJECT,
					Section.BLENDSHAPE_ROTATION_RECORD_START,
					Section.BLENDSHAPE_ROTATION_RECORD_COUNT,
					BlendShapeTarget.ROTATION,
					rotationToDeformer,
				)
			// MOC3 v5+ §5.6 sections 143-145: part blend shapes (the object index is a part index).
			blendShapes +=
				groupRecords(
					Section.BLENDSHAPE_PART_OBJECT,
					Section.BLENDSHAPE_PART_RECORD_START,
					Section.BLENDSHAPE_PART_RECORD_COUNT,
					BlendShapeTarget.PART,
					null,
				)
			return blendShapes
		}

		/**
		 * Decodes the records of one target kind's object group.
		 *
		 * @param Section          objectSection The per-object index section for this group.
		 * @param Section          startSection  The per-object record-start section.
		 * @param Section          countSection  The per-object record-count section.
		 * @param BlendShapeTarget target        The target kind these records deform.
		 * @param List<Int>?       toDeformer    Local-index to deformer-index map, or null when the
		 *                                       object index already names the target directly.
		 * @return List<BlendShape> This group's records (empty when the section is absent).
		 */
		private fun groupRecords(
			objectSection: Section,
			startSection: Section,
			countSection: Section,
			target: BlendShapeTarget,
			toDeformer: List<Int>?,
		): List<BlendShape> {
			if (!sections.isPresent(objectSection)) {
				return emptyList()
			}
			val objectIndices = sections.intArray(objectSection)
			val recordStarts = sections.intArray(startSection)
			val recordCounts = sections.intArray(countSection)
			val groupShapes = ArrayList<BlendShape>()
			for (groupIndex in objectIndices.indices) {
				val localObjectIndex = objectIndices[groupIndex]
				val objectIndex = toDeformer?.getOrElse(localObjectIndex) { localObjectIndex } ?: localObjectIndex
				val recordStart = recordStarts[groupIndex]
				for (recordIndex in recordStart until recordStart + recordCounts[groupIndex]) {
					groupShapes.add(blendShape(target, localObjectIndex, objectIndex, recordIndex))
				}
			}
			return groupShapes
		}

		/**
		 * Builds one record's [BlendShape], resolving its binding and lifting its delta payloads.
		 *
		 * @param BlendShapeTarget target           Which kind of object this record deforms.
		 * @param Int              localObjectIndex The target's kind-local index (sizes the payloads).
		 * @param Int              objectIndex      The deformer/drawable/part index the record targets.
		 * @param Int              recordIndex      The record's index in the record tables.
		 * @return BlendShape The decoded record.
		 */
		private fun blendShape(
			target: BlendShapeTarget,
			localObjectIndex: Int,
			objectIndex: Int,
			recordIndex: Int,
		): BlendShape {
			val bindingIndex = recordBinding[recordIndex]
			val keys =
				keyPositions.copyOfRange(
					bindingKeyOffset[bindingIndex],
					bindingKeyOffset[bindingIndex] + bindingKeyCount[bindingIndex],
				)
			return BlendShape(
				target,
				objectIndex,
				bindingOwner[bindingIndex],
				keys,
				bindingNeutral[bindingIndex],
				recordBase[recordIndex],
				limitPool.limitsFor(recordIndex),
				keyformsFor(target, localObjectIndex, recordIndex, bindingKeyCount[bindingIndex]),
			)
		}

		/**
		 * Lifts one record's per-key delta payloads out of the shared value tables, at rows
		 * `recordBase + keyIndex` (MOC3 §5.6; the same tables the base keyforms use).
		 *
		 * @param BlendShapeTarget target           The record's target kind.
		 * @param Int              localObjectIndex The target's kind-local index (warp/rotation local,
		 *                                          drawable, or part index) - sizes the payload.
		 * @param Int              recordIndex      The record's index in the record tables.
		 * @param Int              keyCount         The record's binding key count.
		 * @return List<BlendShapeKeyform> One delta payload per key, kind matching [target].
		 */
		private fun keyformsFor(
			target: BlendShapeTarget,
			localObjectIndex: Int,
			recordIndex: Int,
			keyCount: Int,
		): List<BlendShapeKeyform> =
			(0 until keyCount).map { keyIndex ->
				val deltaRow = recordBase[recordIndex] + keyIndex
				when (target) {
					BlendShapeTarget.WARP -> warpDelta(localObjectIndex, recordIndex, keyIndex, deltaRow)
					BlendShapeTarget.ART_MESH -> meshDelta(localObjectIndex, recordIndex, keyIndex, deltaRow)
					BlendShapeTarget.ROTATION -> rotationDelta(recordIndex, keyIndex, deltaRow)
					// MOC3 §5.6: part delta rows are draw-order floats in section 58.
					BlendShapeTarget.PART -> BlendShapeKeyform.Part(keyformValues.partDrawOrder[deltaRow])
				}
			}

		/**
		 * One warp control-point delta payload.
		 *
		 * @param Int localObjectIndex The warp's local index, which sizes the lattice.
		 * @param Int recordIndex      The record's index in the record tables.
		 * @param Int keyIndex         The key's index within the record's binding.
		 * @param Int deltaRow         The record's row for this key.
		 * @return BlendShapeKeyform.Warp The delta payload.
		 */
		private fun warpDelta(localObjectIndex: Int, recordIndex: Int, keyIndex: Int, deltaRow: Int): BlendShapeKeyform.Warp {
			// MOC3 §5.6: warp delta rows index packed position blocks via section 60 into 71.
			val controlPointCount = deltaTables.warpControlPointCounts[localObjectIndex]
			val positionOffset = keyformValues.warpPositionIndex[deltaRow]
			return BlendShapeKeyform.Warp(
				WarpKeyform(
					keyformValues.positionValues.copyOfRange(positionOffset, positionOffset + controlPointCount * 2),
					keyformValues.warpOpacity[deltaRow],
					colorDeltas.multiplyDelta(recordIndex, keyIndex),
					colorDeltas.screenDelta(recordIndex, keyIndex),
				),
			)
		}

		/**
		 * One art-mesh vertex delta payload.
		 *
		 * @param Int localObjectIndex The drawable index, which sizes the vertex block.
		 * @param Int recordIndex      The record's index in the record tables.
		 * @param Int keyIndex         The key's index within the record's binding.
		 * @param Int deltaRow         The record's row for this key.
		 * @return BlendShapeKeyform.Mesh The delta payload.
		 */
		private fun meshDelta(localObjectIndex: Int, recordIndex: Int, keyIndex: Int, deltaRow: Int): BlendShapeKeyform.Mesh {
			// MOC3 §5.6: mesh delta rows index packed position blocks via section 70 into 71.
			val vertexCount = deltaTables.drawableVertexCounts[localObjectIndex]
			val positionOffset = keyformValues.positionIndex[deltaRow]
			return BlendShapeKeyform.Mesh(
				ArtMeshKeyform(
					keyformValues.positionValues.copyOfRange(positionOffset, positionOffset + vertexCount * 2),
					keyformValues.artMeshOpacity[deltaRow],
					keyformValues.artMeshDrawOrder[deltaRow],
					colorDeltas.multiplyDelta(recordIndex, keyIndex),
					colorDeltas.screenDelta(recordIndex, keyIndex),
				),
			)
		}

		/**
		 * One rotation affine delta payload.
		 *
		 * @param Int recordIndex The record's index in the record tables.
		 * @param Int keyIndex    The key's index within the record's binding.
		 * @param Int deltaRow    The record's row for this key.
		 * @return BlendShapeKeyform.Rotation The delta payload.
		 */
		private fun rotationDelta(recordIndex: Int, keyIndex: Int, deltaRow: Int): BlendShapeKeyform.Rotation =
			// MOC3 §5.6: rotation delta rows sit directly in the affine tables 61-67.
			BlendShapeKeyform.Rotation(
				RotationKeyform(
					keyformValues.rotationOriginX[deltaRow],
					keyformValues.rotationOriginY[deltaRow],
					keyformValues.rotationAngle[deltaRow],
					keyformValues.rotationScale[deltaRow],
					keyformValues.rotationReflectX[deltaRow] != 0,
					keyformValues.rotationReflectY[deltaRow] != 0,
					keyformValues.rotationOpacity[deltaRow],
					colorDeltas.multiplyDelta(recordIndex, keyIndex),
					colorDeltas.screenDelta(recordIndex, keyIndex),
				),
			)
	}

	/**
	 * Decodes the blend-shape records (MOC3 v4+ for meshes/warps, MOC3 v5+ for rotations and parts).
	 *
	 * @param MocSections      sections           The model's typed sections.
	 * @param Int              parameterCount     Number of parameters (sizes the per-parameter binding ranges).
	 * @param FloatArray       keyPositions       The shared key-position table.
	 * @param List<Int>        warpToDeformer     Maps a warp local index to its deformer index.
	 * @param List<Int>        rotationToDeformer Maps a rotation local index to its deformer index.
	 * @param BlendDeltaTables deltaTables        The shared value tables the delta rows live in.
	 * @return List<BlendShape> The decoded blend-shape records (empty when absent).
	 */
	private fun decodeBlendShapes(
		sections: MocSections,
		parameterCount: Int,
		keyPositions: FloatArray,
		warpToDeformer: List<Int>,
		rotationToDeformer: List<Int>,
		deltaTables: BlendDeltaTables,
	): List<BlendShape> {
		if (!sections.isPresent(Section.BLENDSHAPE_PARAMETER_BEGIN)) {
			return emptyList()
		}
		return BlendShapeDecoder(sections, parameterCount, keyPositions, deltaTables)
			.decodeAll(warpToDeformer, rotationToDeformer)
	}

	/**
	 * Decodes the offscreen render targets (moc 6), including their per-keyform opacity/color and
	 * mask indices. An offscreen's keyforms ride its owner part's keyform grid (no offscreen
	 * keyform-binding section exists; MOC3 §5.6, OffscreenKeyformProbeTest): opacity rows in
	 * section 161 and the color tables' PREFIX rows are laid out per offscreen in offscreen order.
	 *
	 * @param MocSections sections    The model's typed sections.
	 * @param Int         count       Number of offscreens from CountInfo.
	 * @param List<Part>  parts       The decoded parts (their grids size the keyform runs).
	 * @param ColorTables colorTables The shared color tables (§5.6 sections 108-113); an offscreen's
	 *                                rows are the block's prefix, so they address it by row directly.
	 * @param IntArray    maskData    The full MASK_INDEX_DATA table (§5.6 section 80).
	 * @return List<Offscreen> The decoded offscreens (empty when absent).
	 */
	private fun decodeOffscreens(
		sections: MocSections,
		count: Int,
		parts: List<Part>,
		colorTables: ColorTables,
		maskData: IntArray,
	): List<Offscreen> {
		if (count == 0 || !sections.isPresent(Section.OFFSCREEN_OWNER_PART)) {
			return emptyList()
		}
		val owner = sections.intArray(Section.OFFSCREEN_OWNER_PART)
		val flags = sections.byteArray(Section.OFFSCREEN_CONSTANT_FLAGS)
		val blendModes = sections.intArray(Section.OFFSCREEN_BLEND_MODE) // one packed value per offscreen
		val maskCounts = sections.intArray(Section.OFFSCREEN_MASK_COUNT)
		val maskBases = sections.intArray(Section.OFFSCREEN_MASK_BASE)
		val opacity = sections.floatArray(Section.OFFSCREEN_OPACITY)
		var keyformRowCursor = 0
		return (0 until count).map { offscreenIndex ->
			val keyformCount = parts[owner[offscreenIndex]].drawOrderKeyforms.size
			val keyforms =
				(0 until keyformCount).map { keyIndex ->
					val keyformRow = keyformRowCursor + keyIndex
					OffscreenKeyform(
						opacity[keyformRow],
						colorTables.multiplyAtRow(keyformRow),
						colorTables.screenAtRow(keyformRow),
					)
				}
			keyformRowCursor += keyformCount
			// MOC3 v6 §5.6 s158: the offscreen's masks sit at the s158 offset from the BLOCK START -
			// the offscreen entries are the block's prefix, before the drawables' masks (pinned on
			// Model A: pupil offscreens clip the Whites masks, matching the CMO3 clipGuidList).
			val maskIndices = maskData.copyOfRange(maskBases[offscreenIndex], maskBases[offscreenIndex] + maskCounts[offscreenIndex])
			Offscreen(
				owner[offscreenIndex],
				flags[offscreenIndex].toInt() and 0xFF,
				blendModes[offscreenIndex],
				maskCounts[offscreenIndex],
				keyforms,
				maskIndices,
			)
		}
	}

	/**
	 * Decodes the glues (seam-welds between art meshes).
	 *
	 * @param MocSections sections The model's typed sections.
	 * @return List<Glue> The decoded glues (empty when absent).
	 */
	private fun decodeGlues(sections: MocSections): List<Glue> {
		val meshA = sections.intArray(Section.GLUE_MESH_A)
		if (meshA.isEmpty()) {
			return emptyList()
		}
		val meshB = sections.intArray(Section.GLUE_MESH_B)
		val glueId = sections.idArray(Section.GLUE_ID)
		val keyformBinding = sections.intArray(Section.GLUE_KEYFORM_BINDING)
		val glueVertexStart = sections.intArray(Section.GLUE_VERTEX_START)
		val glueVertexCount = sections.intArray(Section.GLUE_VERTEX_COUNT)
		val keyOffset = sections.intArray(Section.GLUE_KEY_OFFSET)
		val keyCount = sections.intArray(Section.GLUE_KEY_COUNT)
		val weights = sections.floatArray(Section.GLUE_WEIGHTS)
		val indices = sections.shortArray(Section.GLUE_VERTEX_INDICES)
		val intensities = sections.floatArray(Section.GLUE_INTENSITIES)
		return (meshA.indices).map { glueIndex ->
			val vertexStartIndex = glueVertexStart[glueIndex]
			val pairCount = glueVertexCount[glueIndex] / 2
			val pairs =
				(0 until pairCount).map { pairIndex ->
					GlueVertexPair(
						indices[vertexStartIndex + 2 * pairIndex].toInt() and 0xFFFF,
						indices[vertexStartIndex + 2 * pairIndex + 1].toInt() and 0xFFFF,
						weights[vertexStartIndex + 2 * pairIndex],
						weights[vertexStartIndex + 2 * pairIndex + 1],
					)
				}
			Glue(
				// A stripped file without s90 leaves the id blank rather than failing the decode.
				glueId.getOrElse(glueIndex) { "" },
				meshA[glueIndex],
				meshB[glueIndex],
				keyformBinding[glueIndex],
				pairs,
				intensities.copyOfRange(keyOffset[glueIndex], keyOffset[glueIndex] + keyCount[glueIndex]),
			)
		}
	}

	/**
	 * Decodes the render-order group tree (the draw-order hierarchy).
	 *
	 * @param MocSections sections   The model's typed sections.
	 * @param Int         groupCount Number of render-order groups from CountInfo.
	 * @return List<RenderOrderGroup> The decoded groups (empty when absent).
	 */
	private fun decodeRenderOrderGroups(
		sections: MocSections,
		groupCount: Int,
	): List<RenderOrderGroup> {
		if (groupCount == 0) {
			return emptyList()
		}
		val childCount = sections.intArray(Section.RENDER_ORDER_CHILD_COUNT)
		val childKind = sections.intArray(Section.RENDER_ORDER_CHILD_KIND)
		val childIndex = sections.intArray(Section.RENDER_ORDER_CHILD_INDEX)
		val childGroupIndex = sections.intArray(Section.RENDER_ORDER_GROUP_INDEX)
		var childBase = 0
		return (0 until groupCount).map { groupIndex ->
			val children =
				(0 until childCount[groupIndex]).map { childOrdinal ->
					RenderOrderChild(
						childKind[childBase + childOrdinal],
						childIndex[childBase + childOrdinal],
						childGroupIndex[childBase + childOrdinal],
					)
				}
			childBase += childCount[groupIndex]
			RenderOrderGroup(children)
		}
	}
}
