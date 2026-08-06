package org.umamo.format.moc3.decode

import org.umamo.format.moc3.MocDocument
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

		// Deformers
		val decodedDeformers = DeformerDecoder(sections, bindings, colorTables, keyformValues).decodeAll(deformerCount)
		val deformerList = decodedDeformers.deformers

		// ---- art meshes ----
		val artMeshKeyformBinding = sections.intArray(Section.ARTMESH_KEYFORM_BINDING)
		val artMeshKeyformBase = sections.intArray(Section.ARTMESH_KEYFORM_BASE)
		val artMeshParentDeformer = sections.intArray(Section.ARTMESH_PARENT_DEFORMER)
		val artMeshColorBase =
			if (sections.isPresent(Section.ARTMESH_COLOR_BASE)) sections.intArray(Section.ARTMESH_COLOR_BASE) else null
		// MOC3 v6 §5.6 s153: per-drawable packed extended blend (0 = legacy constant-flags blend).
		val artMeshExtendedBlend =
			if (sections.isPresent(Section.ARTMESH_EXTENDED_BLEND)) sections.intArray(Section.ARTMESH_EXTENDED_BLEND) else null
		// s37 is the visibility toggle (pinned by joining miku_verycursed against its CMO3 twin); s38 is
		// 1 on every drawable of every corpus sample and is carried only so a bake reproduces it.
		val artMeshIsVisible = sections.intArray(Section.ARTMESH_IS_VISIBLE)
		val artMeshIsEnabled = sections.intArray(Section.ARTMESH_IS_ENABLED)
		val uvData = sections.floatArray(Section.ARTMESH_UV_DATA)
		val indexData = sections.shortArray(Section.ARTMESH_INDEX_DATA)
		val maskData = sections.intArray(Section.MASK_INDEX_DATA)
		// MOC3 v6 §5.6 section 80: the OFFSCREEN mask entries are the block's PREFIX and the
		// drawables' masks follow (pinned on Model A against the CMO3 ground truth + the runtime's
		// s158 addressing, which offsets from the block start).  Pre-v6 there is no prefix.
		val offscreenMaskTotal =
			if (sections.isPresent(Section.OFFSCREEN_MASK_COUNT)) {
				sections.intArray(Section.OFFSCREEN_MASK_COUNT).sum()
			} else {
				0
			}
		var vertexBase = 0
		var indexBase = 0
		var maskBase = offscreenMaskTotal
		val artMeshList =
			drawables.mapIndexed { drawableIndex, drawable ->
				val vertexCount = drawable.vertexCount
				val uvs = uvData.copyOfRange(vertexBase * 2, vertexBase * 2 + vertexCount * 2)
				val triangleIndices = indexData.copyOfRange(indexBase, indexBase + drawable.indexCount)
				val maskIndices = maskData.copyOfRange(maskBase, maskBase + drawable.maskCount)
				vertexBase += vertexCount
				indexBase += drawable.indexCount
				maskBase += drawable.maskCount
				val keyformBinding = artMeshKeyformBinding[drawableIndex]
				val keyformBase = artMeshKeyformBase[drawableIndex]
				val gridSize = bindings.binding(keyformBinding).gridSize
				val keyforms =
					(0 until gridSize).map { gridIndex ->
						val positionOffset = keyformValues.positionIndex[keyformBase + gridIndex]
						ArtMeshKeyform(
							keyformValues.positionValues.copyOfRange(positionOffset, positionOffset + vertexCount * 2),
							keyformValues.artMeshOpacity[keyformBase + gridIndex],
							keyformValues.artMeshDrawOrder[keyformBase + gridIndex],
							colorTables.multiplyForKeyform(artMeshColorBase?.get(drawableIndex), gridIndex),
							colorTables.screenForKeyform(artMeshColorBase?.get(drawableIndex), gridIndex),
						)
					}
				ArtMesh(
					drawable.id,
					drawable.textureIndex,
					drawable.constantFlags,
					artMeshExtendedBlend?.get(drawableIndex) ?: 0,
					// Default to visible: a stripped file omitting the flags means "nothing is hidden".
					artMeshIsVisible.getOrElse(drawableIndex) { 1 } != 0,
					artMeshIsEnabled.getOrElse(drawableIndex) { 1 } != 0,
					drawable.parentPartIndex,
					artMeshParentDeformer[drawableIndex],
					uvs,
					triangleIndices,
					maskIndices,
					keyformBinding,
					keyforms,
				)
			}

		// ---- glue ----
		val glueList = decodeGlues(sections)
		// Register glue bindings in the cache like every other object kind: a glue names a binding
		// from the same shared table (MOC3.md §5.6), and without this a glue-exclusive binding would
		// be missing from MocDocument.keyformBinding (dropping the glue's parameter-driven intensity
		// downstream) AND from MocDocument.bindings (dropping its table rows from a re-bake).
		for (glue in glueList) {
			bindings.binding(glue.keyformBindingIndex)
		}

		// ---- render-order groups ----
		val groupList =
			decodeRenderOrderGroups(sections, model.countInfo.getOrElse(Sections.CI_RENDER_ORDER_GROUPS) { 0 })

		// ---- blend shapes (moc 4+) / offscreens (moc 6) ----
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
	 * Decodes the blend-shape records (MOC3 v4+ for meshes/warps, MOC3 v5+ for rotations and parts):
	 * the binding structure, each record's blend-weight limits expanded from the deduplicated
	 * sub-binding pool, and the typed per-key delta payloads lifted from [deltaTables].
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
		// owning parameter per blend-shape binding (explicit begin/count ranges per parameter)
		val parameterBegin = sections.intArray(Section.BLENDSHAPE_PARAMETER_BEGIN)
		val parameterBindingCount = sections.intArray(Section.BLENDSHAPE_PARAMETER_COUNT)
		val bindingCountTotal = (0 until parameterCount).sumOf { parameterBindingCount[it] }
		val bindingOwner = IntArray(bindingCountTotal)
		for (parameterIndex in 0 until parameterCount) {
			for (bindingIndex in 0 until parameterBindingCount[parameterIndex]) {
				bindingOwner[parameterBegin[parameterIndex] + bindingIndex] = parameterIndex
			}
		}
		val bindingKeyOffset = sections.intArray(Section.BLENDSHAPE_BINDING_KEY_OFFSET)
		val bindingKeyCount = sections.intArray(Section.BLENDSHAPE_BINDING_KEY_COUNT)
		val bindingNeutral = sections.intArray(Section.BLENDSHAPE_BINDING_NEUTRAL)
		val recordBinding = sections.intArray(Section.BLENDSHAPE_RECORD_BINDING)
		val recordBase = sections.intArray(Section.BLENDSHAPE_RECORD_BASE)

		// MOC3 v4+ §5.6 sections 123/124 + 131-136: blend-weight limit sub-bindings. Records range
		// into SUB_INDEX, whose entries reference a deduplicated pool of (parameter, keys, weights)
		// curves; the decoder expands the pool per record. Absent tables decode as no limits.
		val recordSubstart = sections.intArray(Section.BLENDSHAPE_RECORD_SUBSTART)
		val recordCornerCount = sections.intArray(Section.BLENDSHAPE_RECORD_CORNER_COUNT)
		val hasSubTables = sections.isPresent(Section.BLENDSHAPE_SUB_INDEX)
		val subIndex = if (hasSubTables) sections.intArray(Section.BLENDSHAPE_SUB_INDEX) else IntArray(0)
		val subParameter = if (hasSubTables) sections.intArray(Section.BLENDSHAPE_SUB_PARAMETER) else IntArray(0)
		val subKeyOffset = if (hasSubTables) sections.intArray(Section.BLENDSHAPE_SUB_KEY_OFFSET) else IntArray(0)
		val subKeyCount = if (hasSubTables) sections.intArray(Section.BLENDSHAPE_SUB_KEY_COUNT) else IntArray(0)
		val subKeys = if (hasSubTables) sections.floatArray(Section.BLENDSHAPE_SUB_KEYS) else FloatArray(0)
		val subWeights = if (hasSubTables) sections.floatArray(Section.BLENDSHAPE_SUB_WEIGHT_VALUES) else FloatArray(0)

		// MOC3 §5.6 sections 108-113: the color tables' delta region follows the base rows (and, on
		// MOC3 v6, the offscreen keyform prefix), holding one row per (record, key) for warp, mesh,
		// and rotation records in global record order - part records own no color rows.  The region
		// anchors at the content-derived base-row total (BlendDeltaTables.colorDeltaRowStart);
		// anchoring at table length minus the delta total drifts by the element region's 64-byte
		// zero padding (2 rows on Model A, 10 on Model C - caught by their authored color morphs).
		val recordCount = recordBinding.size
		val partOwnedRecord = BooleanArray(recordCount)
		if (sections.isPresent(Section.BLENDSHAPE_PART_OBJECT)) {
			val partRecordStarts = sections.intArray(Section.BLENDSHAPE_PART_RECORD_START)
			val partRecordCounts = sections.intArray(Section.BLENDSHAPE_PART_RECORD_COUNT)
			for (groupIndex in partRecordStarts.indices) {
				val partRecordEnd = partRecordStarts[groupIndex] + partRecordCounts[groupIndex]
				for (recordIndex in partRecordStarts[groupIndex] until partRecordEnd) {
					partOwnedRecord[recordIndex] = true
				}
			}
		}
		// Only records referenced by a warp/mesh/rotation object group have their color rows read
		// (and stored): the record tables carry a few TRAILING records referenced by no group on
		// every blend corpus model, and the stored region ends at the referenced records' rows
		// (modelA: table 1808 = read extent 1806 + 2 rows padding; modelC: 2208 = 2198 + 10).
		val colorReadRecord = BooleanArray(recordCount)

		/**
		 * Marks the records one object group's ranges reference, so the delta-region presence check
		 * below can measure the read extent over exactly the rows that will be dereferenced.
		 *
		 * @param Section objectSection The group's per-object index section.
		 * @param Section startSection  The group's per-object record-start section.
		 * @param Section countSection  The group's per-object record-count section.
		 */
		fun markColorReadRecords(objectSection: Section, startSection: Section, countSection: Section) {
			if (!sections.isPresent(objectSection)) {
				return
			}
			val recordStarts = sections.intArray(startSection)
			val recordCounts = sections.intArray(countSection)
			for (groupIndex in recordStarts.indices) {
				for (recordIndex in recordStarts[groupIndex] until recordStarts[groupIndex] + recordCounts[groupIndex]) {
					if (recordIndex in 0 until recordCount) {
						colorReadRecord[recordIndex] = true
					}
				}
			}
		}
		markColorReadRecords(Section.BLENDSHAPE_WARP_OBJECT, Section.BLENDSHAPE_WARP_RECORD_START, Section.BLENDSHAPE_WARP_RECORD_COUNT)
		markColorReadRecords(Section.BLENDSHAPE_MESH_OBJECT, Section.BLENDSHAPE_MESH_RECORD_START, Section.BLENDSHAPE_MESH_RECORD_COUNT)
		markColorReadRecords(
			Section.BLENDSHAPE_ROTATION_OBJECT,
			Section.BLENDSHAPE_ROTATION_RECORD_START,
			Section.BLENDSHAPE_ROTATION_RECORD_COUNT,
		)

		// The delta region is a later format addition: a 4.2-era bake with blend shapes carries the
		// base rows only (corpus: Azxiana.moc3, V42 - CountInfo 23/24 there count base + prefix rows
		// while fields 7-9 still include the deltas).  Detect presence by whether the tables cover
		// the referenced records' read extent, and decode absent deltas as null colors, like any
		// absent section.
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
		val colorDeltasPresent = deltaTables.colorTables.isPresent && requiredRowEnd <= deltaTables.colorTables.rowCount
		val recordColorRow = IntArray(recordCount) { -1 }
		if (colorDeltasPresent) {
			var colorRowCursor = deltaTables.colorDeltaRowStart
			for (recordIndex in 0 until recordCount) {
				if (!partOwnedRecord[recordIndex]) {
					recordColorRow[recordIndex] = colorRowCursor
					colorRowCursor += bindingKeyCount[recordBinding[recordIndex]]
				}
			}
		}

		/**
		 * Multiply-color delta at key [keyIndex] of record [recordIndex], or null when the model
		 * carries no color tables (or the record is part-owned).
		 *
		 * @param Int recordIndex The record's index in the record tables.
		 * @param Int keyIndex    The key's index within the record's binding.
		 * @return Rgb? The multiply-color delta row, or null.
		 */
		fun deltaMultiply(recordIndex: Int, keyIndex: Int): Rgb? {
			val colorRow = recordColorRow[recordIndex]
			// Guarded here rather than folded into the row accessor: a negative row is the "no delta
			// rows" sentinel, and offsetting it by keyIndex would land on a real row.
			return if (colorRow < 0) {
				null
			} else {
				deltaTables.colorTables.multiplyAtRow(colorRow + keyIndex)
			}
		}

		/**
		 * Screen-color delta at key [keyIndex] of record [recordIndex], or null when the model
		 * carries no color tables (or the record is part-owned).
		 *
		 * @param Int recordIndex The record's index in the record tables.
		 * @param Int keyIndex    The key's index within the record's binding.
		 * @return Rgb? The screen-color delta row, or null.
		 */
		fun deltaScreen(recordIndex: Int, keyIndex: Int): Rgb? {
			val colorRow = recordColorRow[recordIndex]
			// Same sentinel guard as deltaMultiply above.
			return if (colorRow < 0) {
				null
			} else {
				deltaTables.colorTables.screenAtRow(colorRow + keyIndex)
			}
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
		fun keyformsFor(
			target: BlendShapeTarget,
			localObjectIndex: Int,
			recordIndex: Int,
			keyCount: Int,
		): List<BlendShapeKeyform> =
			(0 until keyCount).map { keyIndex ->
				val deltaRow = recordBase[recordIndex] + keyIndex
				when (target) {
					BlendShapeTarget.WARP -> {
						// MOC3 §5.6: warp delta rows index packed position blocks via section 60 into 71.
						val controlPointCount = deltaTables.warpControlPointCounts[localObjectIndex]
						val positionOffset = deltaTables.keyformValues.warpPositionIndex[deltaRow]
						BlendShapeKeyform.Warp(
							WarpKeyform(
								deltaTables.keyformValues.positionValues.copyOfRange(
									positionOffset,
									positionOffset + controlPointCount * 2,
								),
								deltaTables.keyformValues.warpOpacity[deltaRow],
								deltaMultiply(recordIndex, keyIndex),
								deltaScreen(recordIndex, keyIndex),
							),
						)
					}
					BlendShapeTarget.ART_MESH -> {
						// MOC3 §5.6: mesh delta rows index packed position blocks via section 70 into 71.
						val vertexCount = deltaTables.drawableVertexCounts[localObjectIndex]
						val positionOffset = deltaTables.keyformValues.positionIndex[deltaRow]
						BlendShapeKeyform.Mesh(
							ArtMeshKeyform(
								deltaTables.keyformValues.positionValues.copyOfRange(
									positionOffset,
									positionOffset + vertexCount * 2,
								),
								deltaTables.keyformValues.artMeshOpacity[deltaRow],
								deltaTables.keyformValues.artMeshDrawOrder[deltaRow],
								deltaMultiply(recordIndex, keyIndex),
								deltaScreen(recordIndex, keyIndex),
							),
						)
					}
					BlendShapeTarget.ROTATION -> {
						// MOC3 §5.6: rotation delta rows sit directly in the affine tables 61-67.
						BlendShapeKeyform.Rotation(
							RotationKeyform(
								deltaTables.keyformValues.rotationOriginX[deltaRow],
								deltaTables.keyformValues.rotationOriginY[deltaRow],
								deltaTables.keyformValues.rotationAngle[deltaRow],
								deltaTables.keyformValues.rotationScale[deltaRow],
								deltaTables.keyformValues.rotationReflectX[deltaRow] != 0,
								deltaTables.keyformValues.rotationReflectY[deltaRow] != 0,
								deltaTables.keyformValues.rotationOpacity[deltaRow],
								deltaMultiply(recordIndex, keyIndex),
								deltaScreen(recordIndex, keyIndex),
							),
						)
					}
					// MOC3 §5.6: part delta rows are draw-order floats in section 58.
					BlendShapeTarget.PART -> BlendShapeKeyform.Part(deltaTables.keyformValues.partDrawOrder[deltaRow])
				}
			}

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

		val blendShapes = ArrayList<BlendShape>()

		/**
		 * Emits one [BlendShape] per record in `[recordStart, recordStart + recordCount)` for [objectIndex].
		 *
		 * @param BlendShapeTarget target           Which kind of object these records deform.
		 * @param Int              localObjectIndex The target's kind-local index (sizes the delta payloads).
		 * @param Int              objectIndex      The deformer/drawable/part index the records target.
		 * @param Int              recordStart      First record index for this object.
		 * @param Int              recordCountForObject Number of records for this object.
		 */
		fun emit(
			target: BlendShapeTarget,
			localObjectIndex: Int,
			objectIndex: Int,
			recordStart: Int,
			recordCountForObject: Int,
		) {
			for (recordIndex in recordStart until recordStart + recordCountForObject) {
				val bindingIndex = recordBinding[recordIndex]
				val keys =
					keyPositions.copyOfRange(
						bindingKeyOffset[bindingIndex],
						bindingKeyOffset[bindingIndex] + bindingKeyCount[bindingIndex],
					)
				blendShapes.add(
					BlendShape(
						target,
						objectIndex,
						bindingOwner[bindingIndex],
						keys,
						bindingNeutral[bindingIndex],
						recordBase[recordIndex],
						limitsFor(recordIndex),
						keyformsFor(target, localObjectIndex, recordIndex, bindingKeyCount[bindingIndex]),
					),
				)
			}
		}

		/**
		 * Emits the blend-shape records for one target kind (warp / mesh / rotation).
		 *
		 * @param Section          objectSection The per-object index section for this group.
		 * @param Section          startSection  The per-object record-start section.
		 * @param Section          countSection  The per-object record-count section.
		 * @param BlendShapeTarget target        The target kind these records deform.
		 * @param List<Int>?       toDeformer    Local-index → deformer-index map, or null when the
		 *                                        object index is already a drawable index.
		 */
		fun emitGroup(
			objectSection: Section,
			startSection: Section,
			countSection: Section,
			target: BlendShapeTarget,
			toDeformer: List<Int>?,
		) {
			if (!sections.isPresent(objectSection)) {
				return
			}
			val objectIndices = sections.intArray(objectSection)
			val recordStarts = sections.intArray(startSection)
			val recordCounts = sections.intArray(countSection)
			for (groupIndex in objectIndices.indices) {
				val localObjectIndex = objectIndices[groupIndex]
				val objectIndex = toDeformer?.getOrElse(localObjectIndex) { localObjectIndex } ?: localObjectIndex
				emit(target, localObjectIndex, objectIndex, recordStarts[groupIndex], recordCounts[groupIndex])
			}
		}
		emitGroup(
			Section.BLENDSHAPE_WARP_OBJECT,
			Section.BLENDSHAPE_WARP_RECORD_START,
			Section.BLENDSHAPE_WARP_RECORD_COUNT,
			BlendShapeTarget.WARP,
			warpToDeformer,
		)
		emitGroup(
			Section.BLENDSHAPE_MESH_OBJECT,
			Section.BLENDSHAPE_MESH_RECORD_START,
			Section.BLENDSHAPE_MESH_RECORD_COUNT,
			BlendShapeTarget.ART_MESH,
			null,
		)
		emitGroup(
			Section.BLENDSHAPE_ROTATION_OBJECT,
			Section.BLENDSHAPE_ROTATION_RECORD_START,
			Section.BLENDSHAPE_ROTATION_RECORD_COUNT,
			BlendShapeTarget.ROTATION,
			rotationToDeformer,
		)
		// MOC3 v5+ §5.6 sections 143-145: part blend shapes (the object index is a part index).
		emitGroup(
			Section.BLENDSHAPE_PART_OBJECT,
			Section.BLENDSHAPE_PART_RECORD_START,
			Section.BLENDSHAPE_PART_RECORD_COUNT,
			BlendShapeTarget.PART,
			null,
		)
		return blendShapes
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
