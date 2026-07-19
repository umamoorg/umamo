package org.umamo.format.moc3.decode

import org.umamo.format.moc3.MocDocument
import org.umamo.format.moc3.io.LittleEndianReader
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
 * EN: Reads the typed Layer-1 sections and follows the base/index tables - it does not evaluate the
 *     model (no interpolation/cascade). Blend shapes (moc 4+) and offscreens (moc 6) are assembled
 *     too; only the residual unknown sections (154, 160) are left to raw access.
 * JA: Layer-1 を意味モデルへ組み立てる（評価は行わない）。
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

		// ---- keyform-binding grid scaffolding ----
		val bindingCountPerParameter = sections.intArray(Section.PARAMETER_BINDING_COUNT)
		val owningParameter = IntArray(bindingCountPerParameter.sum())
		run {
			var bindingSlot = 0
			for (parameterIndex in bindingCountPerParameter.indices) {
				repeat(bindingCountPerParameter[parameterIndex]) { owningParameter[bindingSlot++] = parameterIndex }
			}
		}
		val keyOffset = sections.intArray(Section.BINDING_KEY_OFFSET)
		val keyCount = sections.intArray(Section.BINDING_KEY_COUNT)
		val keyPositions = sections.floatArray(Section.KEY_POSITIONS)
		val keyformBindingSlot = sections.intArray(Section.KEYFORM_BINDING_SLOT)
		val keyformBindingStart = sections.intArray(Section.KEYFORM_BINDING_START)
		val keyformBindingCount = sections.intArray(Section.KEYFORM_BINDING_COUNT)

		val bindingCache = HashMap<Int, KeyformBinding>()

		/**
		 * Resolves (and caches) the keyform binding at index [keyformBinding] into its parameter axes.
		 *
		 * @param Int keyformBinding A keyform-binding index referenced by an object.
		 * @return KeyformBinding The resolved binding (its controlling parameter axes + key positions).
		 */
		fun binding(keyformBinding: Int): KeyformBinding =
			bindingCache.getOrPut(keyformBinding) {
				val start = keyformBindingStart[keyformBinding]
				val axes =
					(0 until keyformBindingCount[keyformBinding]).map { axisIndex ->
						val bindingSlot = keyformBindingSlot[start + axisIndex]
						KeyformAxis(
							owningParameter[bindingSlot],
							keyPositions.copyOfRange(
								keyOffset[bindingSlot],
								keyOffset[bindingSlot] + keyCount[bindingSlot],
							),
						)
					}
				KeyformBinding(keyformBinding, axes)
			}

		// Materialize every stored binding record up front, not only those objects reference: the file
		// allocates CountInfo[12] records, and a mesh-less model carries a single EMPTY binding
		// (0 axes) that only static parts point at - lazy by-reference registration would drop it and
		// shrink the re-synthesized binding sections + CountInfo (probed on the ModelWithOffscreen
		// family).  MOC3 §5.1 CountInfo field 12.
		repeat(model.countInfo.getOrElse(Sections.CI_KEYFORM_BINDINGS) { 0 }) { bindingIndex ->
			binding(bindingIndex)
		}

		// ---- value tables ----
		val positionIndex =
			sections.intArray(Section.KEYFORM_POSITION_INDEX) // art-mesh keyform -> packed-position offset
		val warpPositionIndex =
			sections.intArray(Section.WARP_KEYFORM_INDEX) // warp keyform -> packed-position offset (distinct table)
		val positionValues = sections.floatArray(Section.KEYFORM_POSITION_VALUES)
		val colorPresent = sections.isPresent(Section.COLOR_MULTIPLY_R)
		val multiplyR = sections.floatArray(Section.COLOR_MULTIPLY_R)
		val multiplyG = sections.floatArray(Section.COLOR_MULTIPLY_G)
		val multiplyB = sections.floatArray(Section.COLOR_MULTIPLY_B)
		val screenR = sections.floatArray(Section.COLOR_SCREEN_R)
		val screenG = sections.floatArray(Section.COLOR_SCREEN_G)
		val screenB = sections.floatArray(Section.COLOR_SCREEN_B)

		/**
		 * Multiply-color at keyform [gridIndex] of an object whose color table starts at [colorBase].
		 *
		 * @param Int? colorBase The object's base index into the color tables (null/-1 when uncolored).
		 * @param Int  gridIndex The keyform's grid index.
		 * @return Rgb? The multiply color, or null when color is absent.
		 */
		fun multiply(colorBase: Int?, gridIndex: Int): Rgb? =
			if (colorPresent && colorBase != null && colorBase >= 0) {
				Rgb(
					multiplyR[colorBase + gridIndex],
					multiplyG[colorBase + gridIndex],
					multiplyB[colorBase + gridIndex],
				)
			} else {
				null
			}

		/**
		 * Screen-color at keyform [gridIndex] of an object whose color table starts at [colorBase].
		 *
		 * @param Int? colorBase The object's base index into the color tables (null/-1 when uncolored).
		 * @param Int  gridIndex The keyform's grid index.
		 * @return Rgb? The screen color, or null when color is absent.
		 */
		fun screen(colorBase: Int?, gridIndex: Int): Rgb? =
			if (colorPresent && colorBase != null && colorBase >= 0) {
				Rgb(
					screenR[colorBase + gridIndex],
					screenG[colorBase + gridIndex],
					screenB[colorBase + gridIndex],
				)
			} else {
				null
			}

		// ---- parts ----
		val partKeyformBinding = sections.intArray(Section.PART_KEYFORM_BINDING)
		val partKeyformBase = sections.intArray(Section.PART_KEYFORM_BASE)
		val partDrawOrder = sections.floatArray(Section.PART_DRAW_ORDER)
		val partList =
			model.parts().mapIndexed { partIndex, part ->
				val keyformBinding = partKeyformBinding[partIndex] // for parts, 0 means static (no binding)
				val gridSize = if (keyformBinding <= 0) 1 else binding(keyformBinding).gridSize
				Part(
					part.id,
					part.parentPartIndex,
					keyformBinding,
					FloatArray(gridSize) { keyIndex -> partDrawOrder[partKeyformBase[partIndex] + keyIndex] },
				)
			}

		// ---- deformers ----
		val deformerType = sections.intArray(Section.DEFORMER_TYPE)
		val deformerParent = sections.intArray(Section.DEFORMER_PARENT)
		val warpKeyformBinding = sections.intArray(Section.WARP_KEYFORM_BINDING)
		val warpKeyformBase = sections.intArray(Section.WARP_KEYFORM_BASE)
		val warpRows = sections.intArray(Section.WARP_ROWS)
		val warpColumns = sections.intArray(Section.WARP_COLUMNS)
		val warpMode = if (sections.isPresent(Section.WARP_MODE)) sections.intArray(Section.WARP_MODE) else null
		val warpColorBase =
			if (sections.isPresent(Section.WARP_COLOR_BASE)) sections.intArray(Section.WARP_COLOR_BASE) else null
		val warpOpacity = sections.floatArray(Section.WARP_OPACITY)
		val rotationKeyformBinding = sections.intArray(Section.ROTATION_KEYFORM_BINDING)
		val rotationKeyformBase = sections.intArray(Section.ROTATION_KEYFORM_BASE)
		val rotationBaseAngle = sections.floatArray(Section.ROTATION_BASE_ANGLE)
		val rotationColorBase =
			if (sections.isPresent(Section.ROTATION_COLOR_BASE)) sections.intArray(Section.ROTATION_COLOR_BASE) else null
		val rotationOpacity = sections.floatArray(Section.ROTATION_OPACITY)
		val rotationAngle = sections.floatArray(Section.ROTATION_ANGLE)
		val rotationOriginX = sections.floatArray(Section.ROTATION_ORIGIN_X)
		val rotationOriginY = sections.floatArray(Section.ROTATION_ORIGIN_Y)
		val rotationScale = sections.floatArray(Section.ROTATION_SCALE)
		val rotationReflectX = sections.intArray(Section.ROTATION_REFLECT_X)
		val rotationReflectY = sections.intArray(Section.ROTATION_REFLECT_Y)

		var nextWarpLocal = 0
		var nextRotationLocal = 0
		val warpToDeformer = ArrayList<Int>()
		val rotationToDeformer = ArrayList<Int>()
		val deformerList = ArrayList<Deformer>(deformerCount)
		for (deformerIndex in 0 until deformerCount) {
			if (deformerType[deformerIndex] == 0) {
				warpToDeformer.add(deformerIndex)
				val warpLocalIndex = nextWarpLocal++
				val keyformBinding = warpKeyformBinding[warpLocalIndex]
				val keyformBase = warpKeyformBase[warpLocalIndex]
				val controlPointCount = (warpRows[warpLocalIndex] + 1) * (warpColumns[warpLocalIndex] + 1)
				val gridSize = binding(keyformBinding).gridSize
				val keyforms =
					(0 until gridSize).map { gridIndex ->
						val positionOffset = warpPositionIndex[keyformBase + gridIndex]
						WarpKeyform(
							positionValues.copyOfRange(positionOffset, positionOffset + controlPointCount * 2),
							warpOpacity[keyformBase + gridIndex],
							multiply(warpColorBase?.get(warpLocalIndex), gridIndex),
							screen(warpColorBase?.get(warpLocalIndex), gridIndex),
						)
					}
				deformerList.add(
					WarpDeformer(
						deformerParent[deformerIndex],
						keyformBinding,
						warpRows[warpLocalIndex],
						warpColumns[warpLocalIndex],
						warpMode?.get(warpLocalIndex) ?: 0,
						keyforms,
					),
				)
			} else {
				rotationToDeformer.add(deformerIndex)
				val rotationLocalIndex = nextRotationLocal++
				val keyformBinding = rotationKeyformBinding[rotationLocalIndex]
				val keyformBase = rotationKeyformBase[rotationLocalIndex]
				val gridSize = binding(keyformBinding).gridSize
				val keyforms =
					(0 until gridSize).map { gridIndex ->
						RotationKeyform(
							rotationOriginX[keyformBase + gridIndex],
							rotationOriginY[keyformBase + gridIndex],
							rotationAngle[keyformBase + gridIndex],
							rotationScale[keyformBase + gridIndex],
							rotationReflectX[keyformBase + gridIndex] != 0,
							rotationReflectY[keyformBase + gridIndex] != 0,
							rotationOpacity[keyformBase + gridIndex],
							multiply(rotationColorBase?.get(rotationLocalIndex), gridIndex),
							screen(rotationColorBase?.get(rotationLocalIndex), gridIndex),
						)
					}
				deformerList.add(
					RotationDeformer(
						deformerParent[deformerIndex],
						keyformBinding,
						rotationBaseAngle[rotationLocalIndex],
						keyforms,
					),
				)
			}
		}

		// ---- art meshes ----
		val artMeshKeyformBinding = sections.intArray(Section.ARTMESH_KEYFORM_BINDING)
		val artMeshKeyformBase = sections.intArray(Section.ARTMESH_KEYFORM_BASE)
		val artMeshParentDeformer = sections.intArray(Section.ARTMESH_PARENT_DEFORMER)
		val artMeshColorBase =
			if (sections.isPresent(Section.ARTMESH_COLOR_BASE)) sections.intArray(Section.ARTMESH_COLOR_BASE) else null
		// MOC3 v6 §5.6 s153: per-drawable packed extended blend (0 = legacy constant-flags blend).
		val artMeshExtendedBlend =
			if (sections.isPresent(Section.ARTMESH_EXTENDED_BLEND)) sections.intArray(Section.ARTMESH_EXTENDED_BLEND) else null
		val artMeshOpacity = sections.floatArray(Section.ARTMESH_OPACITY)
		val artMeshDrawOrder = sections.floatArray(Section.ARTMESH_DRAW_ORDER)
		val uvData = floatsOf(model, Sections.UV_DATA)
		val indexData = shortsOf(model, Sections.INDEX_DATA)
		val maskData = intsOf(model, Sections.MASK_INDEX_DATA)
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
				val gridSize = binding(keyformBinding).gridSize
				val keyforms =
					(0 until gridSize).map { gridIndex ->
						val positionOffset = positionIndex[keyformBase + gridIndex]
						ArtMeshKeyform(
							positionValues.copyOfRange(positionOffset, positionOffset + vertexCount * 2),
							artMeshOpacity[keyformBase + gridIndex],
							artMeshDrawOrder[keyformBase + gridIndex],
							multiply(artMeshColorBase?.get(drawableIndex), gridIndex),
							screen(artMeshColorBase?.get(drawableIndex), gridIndex),
						)
					}
				ArtMesh(
					drawable.id,
					drawable.textureIndex,
					drawable.constantFlags,
					artMeshExtendedBlend?.get(drawableIndex) ?: 0,
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
			binding(glue.keyformBindingIndex)
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
				positionIndex = positionIndex,
				warpPositionIndex = warpPositionIndex,
				positionValues = positionValues,
				warpOpacity = warpOpacity,
				artMeshOpacity = artMeshOpacity,
				artMeshDrawOrder = artMeshDrawOrder,
				partDrawOrder = partDrawOrder,
				rotationOriginX = rotationOriginX,
				rotationOriginY = rotationOriginY,
				rotationAngle = rotationAngle,
				rotationScale = rotationScale,
				rotationReflectX = rotationReflectX,
				rotationReflectY = rotationReflectY,
				rotationOpacity = rotationOpacity,
				colorPresent = colorPresent,
				multiplyR = multiplyR,
				multiplyG = multiplyG,
				multiplyB = multiplyB,
				screenR = screenR,
				screenG = screenG,
				screenB = screenB,
				warpControlPointCounts =
					IntArray(warpRows.size) { warpLocalIndex ->
						(warpRows[warpLocalIndex] + 1) * (warpColumns[warpLocalIndex] + 1)
					},
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
			decodeBlendShapes(sections, model.parameterCount, keyPositions, warpToDeformer, rotationToDeformer, blendDeltaTables)
		// The offscreen mask entries are the PREFIX of MASK_INDEX_DATA, addressed per offscreen by
		// s158 (the cumulative scan of s159, offset from the block start - MOC3 §5.6 section 80).
		val offscreenList =
			decodeOffscreens(
				sections,
				model.countInfo.getOrElse(Sections.CI_OFFSCREENS) { 0 },
				partList,
				colorPresent,
				multiplyR,
				multiplyG,
				multiplyB,
				screenR,
				screenG,
				screenB,
				maskData,
			)

		// KEY_POSITIONS (77) optionally trails the parameter-binding dedup region with a per-parameter
		// sorted-union of the main-grid axis keys on some blend-free v1/v3 files (an editor-version
		// artifact - MOC3 §5.6).  Detect it as any nonzero key beyond that dedup (main-grid) region;
		// zero padding beyond the region reads as absent.  Only meaningful on a blend-free file (a
		// blend model carries the region unconditionally, handled by the blend lowering path).
		val mainGridKeyTotal =
			run {
				val keySetsByParameter = HashMap<Int, LinkedHashSet<List<Float>>>()
				for (resolvedBinding in bindingCache.values) {
					for (axis in resolvedBinding.axes) {
						keySetsByParameter.getOrPut(axis.parameterIndex) { LinkedHashSet() }.add(axis.keyPositions.toList())
					}
				}
				keySetsByParameter.values.sumOf { keySets -> keySets.sumOf { it.size } }
			}
		val keyPositionsHasParameterUnion =
			blendShapeList.isEmpty() &&
				(mainGridKeyTotal until keyPositions.size).any { keyIndex -> keyPositions[keyIndex] != 0f }

		return MocDocument(
			version = model.version,
			canvas = model.canvasInfo,
			parameters = model.parameters(),
			keyformBindings = bindingCache.toMap(),
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
	 * The shared keyform value tables (plus per-object payload sizes) a blend-shape record's delta
	 * rows are read from. Bundled so [decodeBlendShapes] can lift the per-key delta payloads
	 * without a dozen loose parameters; every array is the same instance [decode] read for the
	 * base keyforms (MOC3 §5.6: delta rows are appended after the base rows in the same tables).
	 */
	private class BlendDeltaTables(
		val positionIndex: IntArray,
		val warpPositionIndex: IntArray,
		val positionValues: FloatArray,
		val warpOpacity: FloatArray,
		val artMeshOpacity: FloatArray,
		val artMeshDrawOrder: FloatArray,
		val partDrawOrder: FloatArray,
		val rotationOriginX: FloatArray,
		val rotationOriginY: FloatArray,
		val rotationAngle: FloatArray,
		val rotationScale: FloatArray,
		val rotationReflectX: IntArray,
		val rotationReflectY: IntArray,
		val rotationOpacity: FloatArray,
		val colorPresent: Boolean,
		val multiplyR: FloatArray,
		val multiplyG: FloatArray,
		val multiplyB: FloatArray,
		val screenR: FloatArray,
		val screenG: FloatArray,
		val screenB: FloatArray,
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
	 * Decodes the blend-shape records (moc 4+ for meshes/warps, moc 5+ for rotations and parts):
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
		// moc 6, the offscreen keyform prefix), holding one row per (record, key) for warp, mesh,
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
		val recordColorRow = IntArray(recordCount) { -1 }
		if (deltaTables.colorPresent) {
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
			return if (colorRow < 0) {
				null
			} else {
				Rgb(
					deltaTables.multiplyR[colorRow + keyIndex],
					deltaTables.multiplyG[colorRow + keyIndex],
					deltaTables.multiplyB[colorRow + keyIndex],
				)
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
			return if (colorRow < 0) {
				null
			} else {
				Rgb(
					deltaTables.screenR[colorRow + keyIndex],
					deltaTables.screenG[colorRow + keyIndex],
					deltaTables.screenB[colorRow + keyIndex],
				)
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
						val positionOffset = deltaTables.warpPositionIndex[deltaRow]
						BlendShapeKeyform.Warp(
							WarpKeyform(
								deltaTables.positionValues.copyOfRange(
									positionOffset,
									positionOffset + controlPointCount * 2,
								),
								deltaTables.warpOpacity[deltaRow],
								deltaMultiply(recordIndex, keyIndex),
								deltaScreen(recordIndex, keyIndex),
							),
						)
					}
					BlendShapeTarget.ART_MESH -> {
						// MOC3 §5.6: mesh delta rows index packed position blocks via section 70 into 71.
						val vertexCount = deltaTables.drawableVertexCounts[localObjectIndex]
						val positionOffset = deltaTables.positionIndex[deltaRow]
						BlendShapeKeyform.Mesh(
							ArtMeshKeyform(
								deltaTables.positionValues.copyOfRange(
									positionOffset,
									positionOffset + vertexCount * 2,
								),
								deltaTables.artMeshOpacity[deltaRow],
								deltaTables.artMeshDrawOrder[deltaRow],
								deltaMultiply(recordIndex, keyIndex),
								deltaScreen(recordIndex, keyIndex),
							),
						)
					}
					BlendShapeTarget.ROTATION -> {
						// MOC3 §5.6: rotation delta rows sit directly in the affine tables 61-67.
						BlendShapeKeyform.Rotation(
							RotationKeyform(
								deltaTables.rotationOriginX[deltaRow],
								deltaTables.rotationOriginY[deltaRow],
								deltaTables.rotationAngle[deltaRow],
								deltaTables.rotationScale[deltaRow],
								deltaTables.rotationReflectX[deltaRow] != 0,
								deltaTables.rotationReflectY[deltaRow] != 0,
								deltaTables.rotationOpacity[deltaRow],
								deltaMultiply(recordIndex, keyIndex),
								deltaScreen(recordIndex, keyIndex),
							),
						)
					}
					// MOC3 §5.6: part delta rows are draw-order floats in section 58.
					BlendShapeTarget.PART -> BlendShapeKeyform.Part(deltaTables.partDrawOrder[deltaRow])
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
	 * @param MocSections sections          The model's typed sections.
	 * @param Int         count             Number of offscreens from CountInfo.
	 * @param List<Part>  parts             The decoded parts (their grids size the keyform runs).
	 * @param Boolean     colorPresent      Whether the shared color tables exist.
	 * @param FloatArray  multiplyR         Shared multiply-color red rows (§5.6 section 108).
	 * @param FloatArray  multiplyG         Shared multiply-color green rows (109).
	 * @param FloatArray  multiplyB         Shared multiply-color blue rows (110).
	 * @param FloatArray  screenR           Shared screen-color red rows (111).
	 * @param FloatArray  screenG           Shared screen-color green rows (112).
	 * @param FloatArray  screenB           Shared screen-color blue rows (113).
	 * @param IntArray    maskData          The full MASK_INDEX_DATA table (§5.6 section 80).
	 * @return List<Offscreen> The decoded offscreens (empty when absent).
	 */
	private fun decodeOffscreens(
		sections: MocSections,
		count: Int,
		parts: List<Part>,
		colorPresent: Boolean,
		multiplyR: FloatArray,
		multiplyG: FloatArray,
		multiplyB: FloatArray,
		screenR: FloatArray,
		screenG: FloatArray,
		screenB: FloatArray,
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
						if (colorPresent) Rgb(multiplyR[keyformRow], multiplyG[keyformRow], multiplyB[keyformRow]) else null,
						if (colorPresent) Rgb(screenR[keyformRow], screenG[keyformRow], screenB[keyformRow]) else null,
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

	/**
	 * Reads section [index] of [model] as a packed `f32[]` (whole-slice), or empty when absent.
	 *
	 * @param MocModel model A parsed container.
	 * @param Int      index A structural section index (a [Sections] constant).
	 * @return FloatArray The decoded values.
	 */
	private fun floatsOf(model: MocModel, index: Int): FloatArray {
		val raw = model.section(index) ?: return FloatArray(0)
		val reader = LittleEndianReader(raw)
		return FloatArray(raw.size / 4) { reader.readFloat32() }
	}

	/**
	 * Reads section [index] of [model] as a packed `i32[]` (whole-slice), or empty when absent.
	 *
	 * @param MocModel model A parsed container.
	 * @param Int      index A structural section index (a [Sections] constant).
	 * @return IntArray The decoded values.
	 */
	private fun intsOf(model: MocModel, index: Int): IntArray {
		val raw = model.section(index) ?: return IntArray(0)
		val reader = LittleEndianReader(raw)
		return IntArray(raw.size / 4) { reader.readInt32() }
	}

	/**
	 * Reads section [index] of [model] as a packed `i16[]` (whole-slice), or empty when absent.
	 *
	 * @param MocModel model A parsed container.
	 * @param Int      index A structural section index (a [Sections] constant).
	 * @return ShortArray The decoded values.
	 */
	private fun shortsOf(model: MocModel, index: Int): ShortArray {
		val raw = model.section(index) ?: return ShortArray(0)
		val reader = LittleEndianReader(raw)
		return ShortArray(raw.size / 2) { reader.readU16().toShort() }
	}
}
