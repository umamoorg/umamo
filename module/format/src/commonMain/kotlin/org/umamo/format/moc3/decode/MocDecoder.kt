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
import org.umamo.format.moc3.model.BlendShapeTarget
import org.umamo.format.moc3.model.Deformer
import org.umamo.format.moc3.model.Glue
import org.umamo.format.moc3.model.GlueVertexPair
import org.umamo.format.moc3.model.KeyformAxis
import org.umamo.format.moc3.model.KeyformBinding
import org.umamo.format.moc3.model.Offscreen
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
 *     model (no interpolation/cascade). Blend shapes (moc 5+) / offscreens (moc 6) are left to raw
 *     section access.
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
		val artMeshOpacity = sections.floatArray(Section.ARTMESH_OPACITY)
		val artMeshDrawOrder = sections.floatArray(Section.ARTMESH_DRAW_ORDER)
		val uvData = floatsOf(model, Sections.UV_DATA)
		val indexData = shortsOf(model, Sections.INDEX_DATA)
		val maskData = intsOf(model, Sections.MASK_INDEX_DATA)
		var vertexBase = 0
		var indexBase = 0
		var maskBase = 0
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

		// ---- render-order groups ----
		val groupList =
			decodeRenderOrderGroups(sections, model.countInfo.getOrElse(Sections.CI_RENDER_ORDER_GROUPS) { 0 })

		// ---- blend shapes (moc 4+) / offscreens (moc 6) ----
		val blendShapeList =
			decodeBlendShapes(sections, model.parameterCount, keyPositions, warpToDeformer, rotationToDeformer)
		val offscreenList = decodeOffscreens(sections, model.countInfo.getOrElse(Sections.CI_OFFSCREENS) { 0 })

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
		)
	}

	/**
	 * Decodes the blend-shape records (moc 4+ for meshes/warps, moc 5+ for rotations).
	 *
	 * @param MocSections sections           The model's typed sections.
	 * @param Int         parameterCount     Number of parameters (sizes the per-parameter binding ranges).
	 * @param FloatArray  keyPositions       The shared key-position table.
	 * @param List<Int>   warpToDeformer     Maps a warp local index to its deformer index.
	 * @param List<Int>   rotationToDeformer Maps a rotation local index to its deformer index.
	 * @return List<BlendShape> The decoded blend-shape records (empty when absent).
	 */
	private fun decodeBlendShapes(
		sections: MocSections,
		parameterCount: Int,
		keyPositions: FloatArray,
		warpToDeformer: List<Int>,
		rotationToDeformer: List<Int>,
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

		val blendShapes = ArrayList<BlendShape>()

		/**
		 * Emits one [BlendShape] per record in `[recordStart, recordStart + recordCount)` for [objectIndex].
		 *
		 * @param BlendShapeTarget target      Which kind of object these records deform.
		 * @param Int              objectIndex The deformer/drawable index the records target.
		 * @param Int              recordStart First record index for this object.
		 * @param Int              recordCount Number of records for this object.
		 */
		fun emit(target: BlendShapeTarget, objectIndex: Int, recordStart: Int, recordCount: Int) {
			for (recordIndex in recordStart until recordStart + recordCount) {
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
				val objectIndex =
					toDeformer?.getOrElse(objectIndices[groupIndex]) { objectIndices[groupIndex] }
						?: objectIndices[groupIndex]
				emit(target, objectIndex, recordStarts[groupIndex], recordCounts[groupIndex])
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
		return blendShapes
	}

	/**
	 * Decodes the offscreen render targets (moc 6).
	 *
	 * @param MocSections sections The model's typed sections.
	 * @param Int         count    Number of offscreens from CountInfo.
	 * @return List<Offscreen> The decoded offscreens (empty when absent).
	 */
	private fun decodeOffscreens(sections: MocSections, count: Int): List<Offscreen> {
		if (count == 0 || !sections.isPresent(Section.OFFSCREEN_OWNER_PART)) {
			return emptyList()
		}
		val owner = sections.intArray(Section.OFFSCREEN_OWNER_PART)
		val flags = sections.byteArray(Section.OFFSCREEN_CONSTANT_FLAGS)
		val blendModes = sections.intArray(Section.OFFSCREEN_BLEND_MODE) // one packed value per offscreen
		val maskCounts = sections.intArray(Section.OFFSCREEN_MASK_COUNT)
		return (0 until count).map { offscreenIndex ->
			Offscreen(
				owner[offscreenIndex],
				flags[offscreenIndex].toInt() and 0xFF,
				blendModes[offscreenIndex],
				maskCounts[offscreenIndex],
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
