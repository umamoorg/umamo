package org.umamo.format.moc3.encode

import org.umamo.format.moc3.MocDocument
import org.umamo.format.moc3.io.LittleEndianWriter
import org.umamo.format.moc3.moc.Section
import org.umamo.format.moc3.moc.Sections
import org.umamo.format.moc3.model.BlendShape
import org.umamo.format.moc3.model.BlendShapeKeyform
import org.umamo.format.moc3.model.BlendShapeLimit
import org.umamo.format.moc3.model.BlendShapeTarget
import org.umamo.format.moc3.model.RenderOrderChild
import org.umamo.format.moc3.model.Rgb
import org.umamo.format.moc3.model.RotationDeformer
import org.umamo.format.moc3.model.WarpDeformer

/**
 * Lowers a [MocDocument] back to section byte-arrays (the semantic half of the bake). This covers
 * the structural + topology sections - those that map directly from object fields and therefore
 * reconstruct byte-for-byte: counts, canvas, IDs, parameter ranges/types, drawable attributes,
 * UV/triangle/mask data, deformer parent/type/grid dims, and the object→keyform-binding references.
 *
 * The layout-dependent sections (per-object keyform bases and the packed keyform value tables /
 * binding grid) are not synthesized here - they require reproducing a value-table packing and are
 * the remaining bake work; until then a full bake carries them through from a decoded model.
 *
 * @see <a href="https://docs.umamo.org/format/MOC3.md">MOC3.md §5, §7</a>
 */
public object MocLowering {
	/**
	 * Synthesizes the structural/topology sections from [doc], keyed by section-table index.
	 *
	 * @param MocDocument doc The semantic model.
	 * @return Map Section index → element-region bytes (no trailing padding).
	 */
	public fun structuralSections(doc: MocDocument): Map<Int, ByteArray> {
		val version = doc.version
		val out = LinkedHashMap<Int, ByteArray>()

		fun put(index: Int, bytes: ByteArray) {
			if (index >= 0) {
				out[index] = bytes
			}
		}

		fun put(section: Section, bytes: ByteArray) = put(section.indexIn(version), bytes)

		doc.canvas?.let { canvas ->
			put(
				Sections.CANVAS,
				floats(canvas.pixelsPerUnit, canvas.originX, canvas.originY, canvas.width, canvas.height, 0f),
			)
		}

		// parameters
		put(Sections.PARAM_ID, idRecords(doc.parameters.map { it.id }))
		put(Sections.PARAM_MAX, floatList(doc.parameters.map { it.maximumValue }))
		put(Sections.PARAM_MIN, floatList(doc.parameters.map { it.minimumValue }))
		put(Sections.PARAM_DEFAULT, floatList(doc.parameters.map { it.defaultValue }))
		// Repeat flags are not retained in the semantic model (the runtime ignores them; every shipped
		// sample is all-zero). Emit zeros so the section is present and correctly sized.
		put(Sections.PARAM_REPEAT, intList(List(doc.parameters.size) { 0 }))
		if (doc.parameters.any { it.type != null }) {
			put(
				Sections.PARAM_TYPE,
				intList(doc.parameters.map { it.type!!.number }),
			)
		}

		// parts
		put(Sections.PART_ID, idRecords(doc.parts.map { it.id }))
		put(Sections.PART_PARENT, intList(doc.parts.map { it.parentPartIndex }))
		put(Section.PART_KEYFORM_BINDING, intList(doc.parts.map { it.keyformBindingIndex }))

		// drawables (art meshes) + topology
		put(Sections.DRAW_ID, idRecords(doc.artMeshes.map { it.id }))
		put(Sections.DRAW_TEXTURE, intList(doc.artMeshes.map { it.textureIndex }))
		put(Sections.DRAW_CONSTANT_FLAG, ByteArray(doc.artMeshes.size) { doc.artMeshes[it].constantFlags.toByte() })
		put(Sections.DRAW_VERTEX_COUNT, intList(doc.artMeshes.map { it.vertexCount }))
		put(Sections.DRAW_INDEX_COUNT, intList(doc.artMeshes.map { it.triangleIndices.size }))
		put(Sections.DRAW_MASK_COUNT, intList(doc.artMeshes.map { it.maskDrawableIndices.size }))
		put(Sections.DRAW_PARENT, intList(doc.artMeshes.map { it.parentPartIndex }))
		put(Section.ARTMESH_PARENT_DEFORMER, intList(doc.artMeshes.map { it.parentDeformerIndex }))
		put(Section.ARTMESH_KEYFORM_BINDING, intList(doc.artMeshes.map { it.keyformBindingIndex }))
		put(
			Section.ARTMESH_KEYFORM_COUNT,
			intList(doc.artMeshes.map { doc.keyformBinding(it.keyformBindingIndex)?.gridSize ?: 1 }),
		)
		put(Sections.UV_DATA, floatConcat(doc.artMeshes) { it.vertexUvs })
		put(Sections.INDEX_DATA, u16Concat(doc.artMeshes) { it.triangleIndices })
		// The mask-index block holds the drawables' mask lists, then (moc 6) the offscreens' mask
		// lists as a suffix (MOC3 §5.6 section 80).  The suffix synthesizes from the typed
		// Offscreen.maskIndices; a doc predating the typed extraction (index count != maskCount)
		// carries the section instead.
		if (doc.offscreens.all { it.maskIndices.size == it.maskCount }) {
			val maskIndexValues = ArrayList<Int>()
			for (mesh in doc.artMeshes) {
				mesh.maskDrawableIndices.forEach { maskIndexValues.add(it) }
			}
			for (offscreen in doc.offscreens) {
				offscreen.maskIndices.forEach { maskIndexValues.add(it) }
			}
			put(Sections.MASK_INDEX_DATA, intList(maskIndexValues))
		}

		// deformers (unified list + per-type)
		put(Section.DEFORMER_PARENT, intList(doc.deformers.map { it.parentDeformerIndex }))
		put(Section.DEFORMER_TYPE, intList(doc.deformers.map { if (it is WarpDeformer) 0 else 1 }))
		// per-deformer index within its type group (warps vs rotations), in deformer-list order
		val localIndex = IntArray(doc.deformers.size)
		run {
			var nextWarpLocal = 0
			var nextRotationLocal = 0
			for ((deformerIndex, deformer) in doc.deformers.withIndex()) {
				localIndex[deformerIndex] = if (deformer is WarpDeformer) nextWarpLocal++ else nextRotationLocal++
			}
		}
		put(Section.DEFORMER_LOCAL_INDEX, intList(localIndex.toList()))
		val warps = doc.deformers.filterIsInstance<WarpDeformer>()
		val rotations = doc.deformers.filterIsInstance<RotationDeformer>()
		put(Section.WARP_CONTROL_POINT_COUNT, intList(warps.map { (it.rows + 1) * (it.columns + 1) }))
		put(Section.WARP_ROWS, intList(warps.map { it.rows }))
		put(Section.WARP_COLUMNS, intList(warps.map { it.columns }))
		put(Section.WARP_MODE, intList(warps.map { it.mode }))
		put(Section.WARP_KEYFORM_BINDING, intList(warps.map { it.keyformBindingIndex }))
		put(Section.ROTATION_BASE_ANGLE, floatList(rotations.map { it.baseAngle }))
		put(Section.ROTATION_KEYFORM_BINDING, intList(rotations.map { it.keyformBindingIndex }))

		// glue topology (mesh pair + binding)
		put(Section.GLUE_MESH_A, intList(doc.glues.map { it.meshAIndex }))
		put(Section.GLUE_MESH_B, intList(doc.glues.map { it.meshBIndex }))
		put(Section.GLUE_KEYFORM_BINDING, intList(doc.glues.map { it.keyformBindingIndex }))

		return out
	}

	/**
	 * Synthesizes the keyform value tables from [doc], keyed by section-table index. These are
	 * the layout-dependent geometry/scalar tables; the packing is deterministic (warp keyform blocks
	 * then mesh blocks in `POS_VALUES`, each padded to 16 floats; bases are cumulative), so they
	 * reconstruct byte-for-byte. The keyform-binding grid (param-binding dedup) is not synthesized
	 * here.
	 *
	 * @param MocDocument doc The semantic model.
	 * @return Map Section index → element-region bytes.
	 */
	public fun valueTableSections(doc: MocDocument): Map<Int, ByteArray> {
		val version = doc.version
		val out = LinkedHashMap<Int, ByteArray>()

		fun put(section: Section, bytes: ByteArray) {
			val index = section.indexIn(version)
			if (index >= 0) {
				out[index] = bytes
			}
		}

		val warps = doc.deformers.filterIsInstance<WarpDeformer>()
		val rotations = doc.deformers.filterIsInstance<RotationDeformer>()

		// POS_VALUES: warp control-point keyform blocks first, then art-mesh vertex keyform blocks,
		// each padded to a 16-float (64-byte) boundary. Index tables record each block's float offset.
		val positionValues = LittleEndianWriter(64 * 1024)
		val warpPositionIndex = ArrayList<Int>()
		val warpKeyformBase = IntArray(warps.size)
		val warpOpacity = ArrayList<Float>()
		for ((warpIndex, warp) in warps.withIndex()) {
			warpKeyformBase[warpIndex] = warpPositionIndex.size
			for (keyform in warp.keyforms) {
				warpPositionIndex.add(positionValues.position / 4)
				keyform.controlPoints.forEach(positionValues::writeFloat32)
				padTo16Floats(positionValues, keyform.controlPoints.size)
				warpOpacity.add(keyform.opacity)
			}
		}
		val meshPositionIndex = ArrayList<Int>()
		val meshKeyformBase = IntArray(doc.artMeshes.size)
		val meshOpacity = ArrayList<Float>()
		val meshDrawOrder = ArrayList<Float>()
		for ((meshIndex, mesh) in doc.artMeshes.withIndex()) {
			meshKeyformBase[meshIndex] = meshPositionIndex.size
			for (keyform in mesh.keyforms) {
				meshPositionIndex.add(positionValues.position / 4)
				keyform.vertexPositions.forEach(positionValues::writeFloat32)
				padTo16Floats(positionValues, keyform.vertexPositions.size)
				meshOpacity.add(keyform.opacity)
				meshDrawOrder.add(keyform.drawOrder)
			}
		}

		// Blend-shape delta rows append AFTER the base keyforms in the same tables (MOC3 §5.6):
		// POS_VALUES gains warp-record then mesh-record delta blocks (16-float padded like the base
		// blocks), the per-kind index/scalar tables gain one row per (record, key) in global record
		// order, so the per-object bases above stay pure base-prefix indices.
		val blendLayout = if (doc.blendShapes.isEmpty()) null else BlendShapeLayout(doc)
		if (blendLayout != null) {
			for (record in blendLayout.warpRecords) {
				for (keyform in record.keyforms) {
					val warpDelta = (keyform as? BlendShapeKeyform.Warp)?.form ?: continue
					warpPositionIndex.add(positionValues.position / 4)
					warpDelta.controlPoints.forEach(positionValues::writeFloat32)
					padTo16Floats(positionValues, warpDelta.controlPoints.size)
					warpOpacity.add(warpDelta.opacity)
				}
			}
			for (record in blendLayout.meshRecords) {
				for (keyform in record.keyforms) {
					val meshDelta = (keyform as? BlendShapeKeyform.Mesh)?.form ?: continue
					meshPositionIndex.add(positionValues.position / 4)
					meshDelta.vertexPositions.forEach(positionValues::writeFloat32)
					padTo16Floats(positionValues, meshDelta.vertexPositions.size)
					meshOpacity.add(meshDelta.opacity)
					meshDrawOrder.add(meshDelta.drawOrder)
				}
			}
		}

		put(Section.WARP_KEYFORM_BASE, intList(warpKeyformBase.toList()))
		put(Section.ARTMESH_KEYFORM_BASE, intList(meshKeyformBase.toList()))

		// rotation deformers: per-keyform affine tables, base = cumulative rotation keyforms.
		val rotationKeyformBase = IntArray(rotations.size)
		val rotationAngle = ArrayList<Float>()
		val rotationOriginX = ArrayList<Float>()
		val rotationOriginY = ArrayList<Float>()
		val rotationScale = ArrayList<Float>()
		val rotationReflectX = ArrayList<Int>()
		val rotationReflectY = ArrayList<Int>()
		val rotationOpacity = ArrayList<Float>()
		var rotationKeyformCounter = 0
		for ((rotationIndex, rotation) in rotations.withIndex()) {
			rotationKeyformBase[rotationIndex] = rotationKeyformCounter
			for (keyform in rotation.keyforms) {
				rotationAngle.add(keyform.angle)
				rotationOriginX.add(keyform.originX)
				rotationOriginY.add(keyform.originY)
				rotationScale.add(keyform.scale)
				rotationReflectX.add(if (keyform.reflectX) 1 else 0)
				rotationReflectY.add(if (keyform.reflectY) 1 else 0)
				rotationOpacity.add(keyform.opacity)
				rotationKeyformCounter++
			}
		}
		put(Section.ROTATION_KEYFORM_BASE, intList(rotationKeyformBase.toList()))
		if (blendLayout != null) {
			// MOC3 §5.6: rotation delta rows sit directly in the affine tables 61-67 after the base rows.
			for (record in blendLayout.rotationRecords) {
				for (keyform in record.keyforms) {
					val rotationDelta = (keyform as? BlendShapeKeyform.Rotation)?.form ?: continue
					rotationAngle.add(rotationDelta.angle)
					rotationOriginX.add(rotationDelta.originX)
					rotationOriginY.add(rotationDelta.originY)
					rotationScale.add(rotationDelta.scale)
					rotationReflectX.add(if (rotationDelta.reflectX) 1 else 0)
					rotationReflectY.add(if (rotationDelta.reflectY) 1 else 0)
					rotationOpacity.add(rotationDelta.opacity)
				}
			}
		}

		put(Section.KEYFORM_POSITION_VALUES, positionValues.toByteArray())
		put(Section.WARP_KEYFORM_INDEX, intList(warpPositionIndex))
		put(Section.WARP_OPACITY, floatList(warpOpacity))
		put(Section.KEYFORM_POSITION_INDEX, intList(meshPositionIndex))
		put(Section.ARTMESH_OPACITY, floatList(meshOpacity))
		put(Section.ARTMESH_DRAW_ORDER, floatList(meshDrawOrder))
		put(Section.ROTATION_ANGLE, floatList(rotationAngle))
		put(Section.ROTATION_ORIGIN_X, floatList(rotationOriginX))
		put(Section.ROTATION_ORIGIN_Y, floatList(rotationOriginY))
		put(Section.ROTATION_SCALE, floatList(rotationScale))
		put(Section.ROTATION_REFLECT_X, intList(rotationReflectX))
		put(Section.ROTATION_REFLECT_Y, intList(rotationReflectY))
		put(Section.ROTATION_OPACITY, floatList(rotationOpacity))

		// parts: per-part draw-order keyform table + cumulative base (a static part has a single value).
		// MOC3 v5+ §5.6: part-owned blend-shape records append draw-order delta rows to section 58.
		val partDrawOrder = ArrayList<Float>()
		val partKeyformBase = IntArray(doc.parts.size)
		for ((partIndex, part) in doc.parts.withIndex()) {
			partKeyformBase[partIndex] = partDrawOrder.size
			part.drawOrderKeyforms.forEach { partDrawOrder.add(it) }
		}
		if (blendLayout != null) {
			for (record in blendLayout.partRecords) {
				for (keyform in record.keyforms) {
					val partDelta = keyform as? BlendShapeKeyform.Part ?: continue
					partDrawOrder.add(partDelta.drawOrderDelta)
				}
			}
		}
		put(Section.PART_KEYFORM_BASE, intList(partKeyformBase.toList()))
		put(Section.PART_DRAW_ORDER, floatList(partDrawOrder))
		return out
	}

	/**
	 * Synthesizes the color-table, glue, render-order, and offscreen sections from [doc] (those with
	 * a deterministic packing), keyed by section index. color keyforms are packed in deformer order
	 * (warps/rotations interleaved), then art meshes; v4+ only.
	 *
	 * @param MocDocument doc The semantic model.
	 * @return Map Section index → element-region bytes.
	 */
	public fun auxiliarySections(doc: MocDocument): Map<Int, ByteArray> {
		val version = doc.version
		val out = LinkedHashMap<Int, ByteArray>()

		fun put(section: Section, bytes: ByteArray) {
			val index = section.indexIn(version)
			if (index >= 0) {
				out[index] = bytes
			}
		}

		// ---- color tables (v4+) ----
		// Layout (MOC3 §5.6): a moc-6 offscreen keyform PREFIX, then the base rows (deformers in
		// unified order, then meshes), then the blend records' color delta rows (global record
		// order, part records excluded - parts own no color rows).  Synthesized whenever any typed
		// color data exists; a doc whose offscreens/blends predate the typed extraction (empty
		// keyform lists) falls back to carrying via the size guard below.
		val offscreenKeyformsTyped = doc.offscreens.all { it.keyforms.isNotEmpty() }
		val hasColor =
			(offscreenKeyformsTyped || doc.offscreens.isEmpty()) &&
				(
					doc.deformers.any { deformer ->
						(deformer is WarpDeformer && deformer.keyforms.any { it.multiplyColor != null }) ||
							(deformer is RotationDeformer && deformer.keyforms.any { it.multiplyColor != null })
					} ||
						doc.artMeshes.any { mesh -> mesh.keyforms.any { it.multiplyColor != null } } ||
						doc.offscreens.any { offscreen -> offscreen.keyforms.any { it.multiplyColor != null } }
				)
		if (hasColor) {
			val multiplyRed = ArrayList<Float>()
			val multiplyGreen = ArrayList<Float>()
			val multiplyBlue = ArrayList<Float>()
			val screenRed = ArrayList<Float>()
			val screenGreen = ArrayList<Float>()
			val screenBlue = ArrayList<Float>()

			/**
			 * Appends one keyform's multiply/screen colors, defaulting to white/black when absent.
			 *
			 * @param Rgb? multiplyColor The keyform's multiply color (null → white identity).
			 * @param Rgb? screenColor   The keyform's screen color (null → black identity).
			 */
			fun appendColors(multiplyColor: Rgb?, screenColor: Rgb?) {
				val multiply = multiplyColor ?: Rgb(1f, 1f, 1f)
				val screen = screenColor ?: Rgb(0f, 0f, 0f)
				multiplyRed.add(multiply.r)
				multiplyGreen.add(multiply.g)
				multiplyBlue.add(multiply.b)
				screenRed.add(screen.r)
				screenGreen.add(screen.g)
				screenBlue.add(screen.b)
			}

			/**
			 * Appends one blend delta row's colors; an unauthored delta channel is ZERO, not the
			 * channel's identity (MOC3 §5.6: delta rows are neutral-relative).
			 *
			 * @param Rgb? multiplyColor The delta row's multiply color (null → zero).
			 * @param Rgb? screenColor   The delta row's screen color (null → zero).
			 */
			fun appendDeltaColors(multiplyColor: Rgb?, screenColor: Rgb?) {
				appendColors(multiplyColor ?: Rgb(0f, 0f, 0f), screenColor ?: Rgb(0f, 0f, 0f))
			}

			// moc 6: the offscreen keyform rows prefix the tables, shifting every base below.
			for (offscreen in doc.offscreens) {
				offscreen.keyforms.forEach { appendColors(it.multiplyColor, it.screenColor) }
			}
			val warpColorBase = ArrayList<Int>()
			val rotationColorBase = ArrayList<Int>()
			for (deformer in doc.deformers) {
				when (deformer) {
					is WarpDeformer -> {
						warpColorBase.add(multiplyRed.size)
						deformer.keyforms.forEach { appendColors(it.multiplyColor, it.screenColor) }
					}

					is RotationDeformer -> {
						rotationColorBase.add(multiplyRed.size)
						deformer.keyforms.forEach { appendColors(it.multiplyColor, it.screenColor) }
					}
				}
			}
			val meshColorBase = ArrayList<Int>()
			for (mesh in doc.artMeshes) {
				meshColorBase.add(multiplyRed.size)
				mesh.keyforms.forEach { appendColors(it.multiplyColor, it.screenColor) }
			}
			if (doc.blendShapes.isNotEmpty()) {
				val blendLayout = BlendShapeLayout(doc)
				for (record in blendLayout.recordsInFileOrder) {
					if (record.target == BlendShapeTarget.PART) {
						continue
					}
					for (keyform in record.keyforms) {
						when (keyform) {
							is BlendShapeKeyform.Warp -> appendDeltaColors(keyform.form.multiplyColor, keyform.form.screenColor)
							is BlendShapeKeyform.Mesh -> appendDeltaColors(keyform.form.multiplyColor, keyform.form.screenColor)
							is BlendShapeKeyform.Rotation -> appendDeltaColors(keyform.form.multiplyColor, keyform.form.screenColor)
							is BlendShapeKeyform.Part -> Unit
						}
					}
				}
			}
			put(Section.WARP_COLOR_BASE, intList(warpColorBase))
			put(Section.ROTATION_COLOR_BASE, intList(rotationColorBase))
			put(Section.ARTMESH_COLOR_BASE, intList(meshColorBase))
			put(Section.COLOR_MULTIPLY_R, floatList(multiplyRed))
			put(Section.COLOR_MULTIPLY_G, floatList(multiplyGreen))
			put(Section.COLOR_MULTIPLY_B, floatList(multiplyBlue))
			put(Section.COLOR_SCREEN_R, floatList(screenRed))
			put(Section.COLOR_SCREEN_G, floatList(screenGreen))
			put(Section.COLOR_SCREEN_B, floatList(screenBlue))
		}

		// ---- glue value tables ----
		if (doc.glues.isNotEmpty()) {
			val vertexStart = ArrayList<Int>()
			val glueVertexCount = ArrayList<Int>()
			val keyOffset = ArrayList<Int>()
			val keyCount = ArrayList<Int>()
			val weights = ArrayList<Float>()
			val indices = ArrayList<Short>()
			val intensities = ArrayList<Float>()
			for (glue in doc.glues) {
				vertexStart.add(weights.size)
				glueVertexCount.add(glue.pairs.size * 2)
				for (pair in glue.pairs) {
					weights.add(pair.weightA)
					weights.add(pair.weightB)
					indices.add(pair.vertexA.toShort())
					indices.add(pair.vertexB.toShort())
				}
				keyOffset.add(intensities.size)
				keyCount.add(glue.intensityKeyforms.size)
				glue.intensityKeyforms.forEach { intensities.add(it) }
			}
			put(Section.GLUE_VERTEX_START, intList(vertexStart))
			put(Section.GLUE_VERTEX_COUNT, intList(glueVertexCount))
			put(Section.GLUE_KEY_OFFSET, intList(keyOffset))
			put(Section.GLUE_KEY_COUNT, intList(keyCount))
			put(Section.GLUE_WEIGHTS, floatList(weights))
			put(Section.GLUE_INTENSITIES, floatList(intensities))
			put(Section.GLUE_VERTEX_INDICES, shortList(indices))
		}

		// ---- render-order group tree ----
		if (doc.renderOrderGroups.isNotEmpty()) {
			val groups = doc.renderOrderGroups
			val childCount = ArrayList<Int>()
			val childKind = ArrayList<Int>()
			val childIndex = ArrayList<Int>()
			val childGroupIndex = ArrayList<Int>()
			for (group in groups) {
				childCount.add(group.children.size)
				for (child in group.children) {
					childKind.add(child.kind)
					childIndex.add(child.index)
					childGroupIndex.add(child.groupIndex)
				}
			}
			put(Section.RENDER_ORDER_CHILD_COUNT, intList(childCount))
			put(Section.RENDER_ORDER_CHILD_KIND, intList(childKind))
			put(Section.RENDER_ORDER_CHILD_INDEX, intList(childIndex))
			put(Section.RENDER_ORDER_GROUP_INDEX, intList(childGroupIndex))

			// Per-group render count (83) and child draw-order extent (84/85), which the runtime reads
			// (the Umamo C++ Runtime recomputes them, so they are absent from its map). A kind-0 child's draw order is its
			// mesh's; a kind-1 child's is its sub-group part's; the render count recurses into sub-groups and
			// counts an extra slot for a sub-group part that owns an offscreen. Draw order = floor(0.001+value).
			val ownerParts = doc.offscreens.map { it.ownerPartIndex }.toHashSet()
			val renderCountMemo = HashMap<Int, Int>()

			/**
			 * Total render-index count of group [groupIndex] (recursive, with offscreen-owner slots).
			 *
			 * @param Int groupIndex The render-order group index.
			 * @return Int The recursive render count.
			 */
			fun renderCount(groupIndex: Int): Int =
				renderCountMemo.getOrPut(groupIndex) {
					groups[groupIndex].children.sumOf { child ->
						if (child.kind == 0) {
							1
						} else {
							(if (child.index in ownerParts) 1 else 0) + renderCount(child.groupIndex)
						}
					}
				}

			/**
			 * The integer draw order of one render-order [child] (its mesh's, or its sub-group part's).
			 *
			 * @param RenderOrderChild child A render-order child.
			 * @return Int The floored draw order (`floor(0.001 + value)`).
			 */
			fun childDrawOrder(child: RenderOrderChild): Int {
				val drawOrderValue =
					if (child.kind == 0) {
						doc.artMeshes[child.index].keyforms.first().drawOrder
					} else {
						doc.parts[child.index].drawOrderKeyforms.first()
					}
				return (0.001f + drawOrderValue).toInt()
			}

			val renderCounts = ArrayList<Int>()
			val maxDraw = ArrayList<Int>()
			val minDraw = ArrayList<Int>()
			for (groupIndex in groups.indices) {
				renderCounts.add(renderCount(groupIndex))
				val childOrders = groups[groupIndex].children.map(::childDrawOrder)
				maxDraw.add(childOrders.maxOrNull() ?: 500)
				minDraw.add(childOrders.minOrNull() ?: 500)
			}
			put(Section.RENDER_ORDER_GROUP_RENDER_COUNT, intList(renderCounts))
			put(Section.RENDER_ORDER_GROUP_MAX_DRAW_ORDER, intList(maxDraw))
			put(Section.RENDER_ORDER_GROUP_MIN_DRAW_ORDER, intList(minDraw))
		}

		// ---- offscreens (per-object scalar sections + keyform tables) ----
		if (doc.offscreens.isNotEmpty()) {
			put(Section.OFFSCREEN_OWNER_PART, intList(doc.offscreens.map { it.ownerPartIndex }))
			put(
				Section.OFFSCREEN_CONSTANT_FLAGS,
				ByteArray(doc.offscreens.size) { doc.offscreens[it].constantFlags.toByte() },
			)
			put(Section.OFFSCREEN_BLEND_MODE, intList(doc.offscreens.map { it.blendMode }))
			put(Section.OFFSCREEN_MASK_COUNT, intList(doc.offscreens.map { it.maskCount }))
			// 152: the per-part inverse of OFFSCREEN_OWNER_PART (-1 for offscreen-less parts).
			val offscreenByPart = IntArray(doc.parts.size) { -1 }
			for ((offscreenIndex, offscreen) in doc.offscreens.withIndex()) {
				if (offscreen.ownerPartIndex in offscreenByPart.indices) {
					offscreenByPart[offscreen.ownerPartIndex] = offscreenIndex
				}
			}
			put(Section.OFFSCREEN_BY_PART, intList(offscreenByPart.toList()))
			// 158: cumulative mask base (the scan of 159; MOC3 §5.6, OffscreenKeyformProbeTest).
			val maskBases = ArrayList<Int>()
			var maskBaseCursor = 0
			for (offscreen in doc.offscreens) {
				maskBases.add(maskBaseCursor)
				maskBaseCursor += offscreen.maskCount
			}
			put(Section.OFFSCREEN_MASK_BASE, intList(maskBases))
			// Keyform tables only when the typed extraction populated them (else carried).
			if (doc.offscreens.all { it.keyforms.isNotEmpty() }) {
				val offscreenOpacities = doc.offscreens.flatMap { offscreen -> offscreen.keyforms.map { it.opacity } }
				put(Section.OFFSCREEN_OPACITY, floatList(offscreenOpacities))
				// 162/163: keyform → color-prefix row maps; identity because the offscreen keyform
				// rows ARE the color tables' prefix, in offscreen order.
				val identityRows = (0 until offscreenOpacities.size).toList()
				put(Section.OFFSCREEN_KEYFORM_MULTIPLY_ROW, intList(identityRows))
				put(Section.OFFSCREEN_KEYFORM_SCREEN_ROW, intList(identityRows))
			}
		}
		return out
	}

	/**
	 * Synthesizes the keyform-binding grid sections from [doc] (byte-exact). Reproduces the
	 * editor's parameter-binding dedup: a parameter-binding is a unique `(parameter, key positions)`
	 * pair; they are grouped by owning parameter and, within a parameter, ordered by first occurrence
	 * scanning keyform-bindings in index order.
	 *
	 * @param MocDocument doc The semantic model.
	 * @return Map Section index → element-region bytes.
	 */
	public fun keyformGridSections(doc: MocDocument): Map<Int, ByteArray> {
		val version = doc.version
		val out = LinkedHashMap<Int, ByteArray>()

		fun put(section: Section, bytes: ByteArray) {
			val index = section.indexIn(version)
			if (index >= 0) {
				out[index] = bytes
			}
		}

		val parameterCount = doc.parameters.size
		val bindingCount = (doc.bindings.maxOfOrNull { it.index } ?: -1) + 1

		// per keyform-binding (index order): its axes as (param, keys)
		val bindingAxes =
			(0 until bindingCount).map { bindingIndex ->
				doc.keyformBinding(bindingIndex)?.axes?.map { it.parameterIndex to it.keyPositions.toList() }
					?: emptyList()
			}
		// distinct (param, keys) per parameter, first-occurrence order
		val keySetsByParameter = Array(parameterCount) { LinkedHashSet<List<Float>>() }
		for (axes in bindingAxes) {
			for ((parameterIndex, keys) in axes) {
				keySetsByParameter[parameterIndex].add(keys)
			}
		}
		// assign parameter-binding indices grouped by parameter
		val parameterBindingIndex = HashMap<Pair<Int, List<Float>>, Int>()
		val parameterBindingOrder = ArrayList<List<Float>>()
		val parameterBindingCount = IntArray(parameterCount)
		for (parameterIndex in 0 until parameterCount) {
			parameterBindingCount[parameterIndex] = keySetsByParameter[parameterIndex].size
			for (keys in keySetsByParameter[parameterIndex]) {
				parameterBindingIndex[parameterIndex to keys] = parameterBindingOrder.size
				parameterBindingOrder.add(keys)
			}
		}

		val keyOffset = ArrayList<Int>()
		val keyCount = ArrayList<Int>()
		val keyPositions = ArrayList<Float>()
		for (keys in parameterBindingOrder) {
			keyOffset.add(keyPositions.size)
			keyCount.add(keys.size)
			keyPositions.addAll(keys)
		}
		val keyformBindingStart = ArrayList<Int>()
		val keyformBindingCount = ArrayList<Int>()
		val keyformBindingSlot = ArrayList<Int>()
		for (bindingIndex in 0 until bindingCount) {
			keyformBindingStart.add(keyformBindingSlot.size)
			keyformBindingCount.add(bindingAxes[bindingIndex].size)
			for ((parameterIndex, keys) in bindingAxes[bindingIndex]) {
				keyformBindingSlot.add(parameterBindingIndex.getValue(parameterIndex to keys))
			}
		}

		put(Section.PARAMETER_BINDING_COUNT, intList(parameterBindingCount.toList()))
		put(Section.BINDING_KEY_OFFSET, intList(keyOffset))
		put(Section.BINDING_KEY_COUNT, intList(keyCount))
		if (doc.blendShapes.isEmpty()) {
			// Blend-free path: the main-grid dedup keys, optionally followed by the per-parameter
			// sorted-union region that some v1/v3 editor builds append (carried on the document as
			// keyPositionsHasParameterUnion; MOC3 §5.6).  Section 104 (PARAM_KEY_COUNT) is NOT written
			// on these older files, so - unlike the blend branch below - it is not emitted here.
			val allKeyPositions = ArrayList(keyPositions)
			if (doc.keyPositionsHasParameterUnion) {
				for (parameterIndex in 0 until parameterCount) {
					val unionKeys = mutableSetOf<Float>()
					for (keys in keySetsByParameter[parameterIndex]) {
						unionKeys.addAll(keys)
					}
					allKeyPositions.addAll(unionKeys.sorted())
				}
			}
			put(Section.KEY_POSITIONS, floatList(allKeyPositions))
		} else {
			// MOC3 §5.6: on a blend model KEY_POSITIONS is THREE regions - the main-grid dedup keys,
			// the blend bindings' key runs (binding order; section 117 offsets point here), and per
			// parameter the sorted union of its main-grid axis keys and blend-binding keys, whose
			// per-parameter counts are section 104 (PARAM_KEY_COUNT).
			val blendLayout = BlendShapeLayout(doc)
			val allKeyPositions = ArrayList(keyPositions)
			for (bindingKeys in blendLayout.bindingKeys) {
				bindingKeys.forEach { allKeyPositions.add(it) }
			}
			val parameterKeyCounts = ArrayList<Int>()
			for (parameterIndex in 0 until parameterCount) {
				val unionKeys = mutableSetOf<Float>()
				for (keys in keySetsByParameter[parameterIndex]) {
					unionKeys.addAll(keys)
				}
				for (bindingIndex in blendLayout.bindingKeys.indices) {
					if (blendLayout.bindingOwnerParameter[bindingIndex] == parameterIndex) {
						blendLayout.bindingKeys[bindingIndex].forEach { unionKeys.add(it) }
					}
				}
				parameterKeyCounts.add(unionKeys.size)
				allKeyPositions.addAll(unionKeys.sorted())
			}
			put(Section.KEY_POSITIONS, floatList(allKeyPositions))
			out[Sections.PARAM_KEY_COUNT] = intList(parameterKeyCounts)
		}
		put(Section.KEYFORM_BINDING_SLOT, intList(keyformBindingSlot))
		put(Section.KEYFORM_BINDING_START, intList(keyformBindingStart))
		put(Section.KEYFORM_BINDING_COUNT, intList(keyformBindingCount))
		return out
	}

	/**
	 * Synthesizes the CountInfo block (section 0) from [doc]: the per-object-kind counts and the
	 * cumulative totals the runtime allocates its working buffers from.  The per-kind keyform
	 * totals (6-10, 14) INCLUDE the blend-shape delta rows (MOC3 §5.6: CountInfo counts the full
	 * shared tables, base plus delta), and fields 23-36 carry the blend-shape and offscreen totals.
	 *
	 * @param MocDocument doc The semantic model.
	 * @return ByteArray The CountInfo element-region bytes (`u32[fieldCount]`).
	 */
	public fun countInfoSection(doc: MocDocument): ByteArray {
		val warps = doc.deformers.filterIsInstance<WarpDeformer>()
		val rotations = doc.deformers.filterIsInstance<RotationDeformer>()
		val blendLayout = if (doc.blendShapes.isEmpty()) null else BlendShapeLayout(doc)

		fun padded(coordinateCount: Int): Int = (coordinateCount + 15) / 16 * 16

		/**
		 * Sums the record keys of [records] (the per-kind delta-row total).
		 *
		 * @param List<BlendShape>? records One kind's records, or null when the doc has no blends.
		 * @return Int The delta-row total.
		 */
		fun deltaRowsOf(records: List<BlendShape>?): Int = records?.sumOf { it.keyPositions.size } ?: 0
		val positionFloatCount =
			warps.sumOf { warp -> warp.keyforms.sumOf { padded(it.controlPoints.size) } } +
				doc.artMeshes.sumOf { mesh -> mesh.keyforms.sumOf { padded(it.vertexPositions.size) } } +
				(
					blendLayout?.let { layout ->
						layout.warpRecords.sumOf { record ->
							record.keyforms.filterIsInstance<BlendShapeKeyform.Warp>().sumOf { padded(it.form.controlPoints.size) }
						} +
							layout.meshRecords.sumOf { record ->
								record.keyforms.filterIsInstance<BlendShapeKeyform.Mesh>().sumOf { padded(it.form.vertexPositions.size) }
							}
					} ?: 0
				)
		val bindingGrid = ParameterBindingGrid(doc)
		val fieldCount = if (doc.version.byteValue >= 6) 64 else 32
		val countInfo = IntArray(fieldCount)
		countInfo[0] = doc.parts.size
		countInfo[1] = doc.deformers.size
		countInfo[2] = warps.size
		countInfo[3] = rotations.size
		countInfo[4] = doc.artMeshes.size
		countInfo[5] = doc.parameters.size
		countInfo[6] = doc.parts.sumOf { it.drawOrderKeyforms.size } + deltaRowsOf(blendLayout?.partRecords)
		countInfo[7] = warps.sumOf { it.keyforms.size } + deltaRowsOf(blendLayout?.warpRecords)
		countInfo[8] = rotations.sumOf { it.keyforms.size } + deltaRowsOf(blendLayout?.rotationRecords)
		countInfo[9] = doc.artMeshes.sumOf { it.keyforms.size } + deltaRowsOf(blendLayout?.meshRecords)
		countInfo[10] = positionFloatCount
		countInfo[11] = doc.bindings.sumOf { it.axes.size } // total keyform-binding slots
		countInfo[12] = doc.bindings.size
		countInfo[13] = bindingGrid.totalParamBindings
		// 14 counts KEY_POSITIONS' full three-region extent on a blend model: main-grid dedup keys,
		// blend binding key runs, and the per-parameter unions (whose counts are section 104).
		countInfo[14] = bindingGrid.totalKeyPositions
		if (blendLayout != null) {
			val mainKeysByParameter = HashMap<Int, LinkedHashSet<Float>>()
			for (binding in doc.bindings) {
				for (axis in binding.axes) {
					mainKeysByParameter.getOrPut(axis.parameterIndex) { LinkedHashSet() }.addAll(axis.keyPositions.toList())
				}
			}
			var unionKeyTotal = 0
			for (parameterIndex in doc.parameters.indices) {
				val unionKeys = HashSet<Float>(mainKeysByParameter[parameterIndex].orEmpty())
				for (bindingIndex in blendLayout.bindingKeys.indices) {
					if (blendLayout.bindingOwnerParameter[bindingIndex] == parameterIndex) {
						blendLayout.bindingKeys[bindingIndex].forEach { unionKeys.add(it) }
					}
				}
				unionKeyTotal += unionKeys.size
			}
			countInfo[14] += blendLayout.bindingKeys.sumOf { it.size } + unionKeyTotal
		} else if (doc.keyPositionsHasParameterUnion) {
			// Blend-free file carrying the optional union region (MOC3 §5.6): CI14 counts it too.
			val mainKeysByParameter = HashMap<Int, LinkedHashSet<Float>>()
			for (binding in doc.bindings) {
				for (axis in binding.axes) {
					mainKeysByParameter.getOrPut(axis.parameterIndex) { LinkedHashSet() }.addAll(axis.keyPositions.toList())
				}
			}
			countInfo[14] += doc.parameters.indices.sumOf { parameterIndex -> mainKeysByParameter[parameterIndex]?.size ?: 0 }
		}
		countInfo[15] = 2 * doc.artMeshes.sumOf { it.vertexCount }
		countInfo[16] = doc.artMeshes.sumOf { it.triangleIndices.size }
		// 17 sizes MASK_INDEX_DATA, which on moc 6 includes the offscreens' mask suffix (§5.6).
		countInfo[17] = doc.artMeshes.sumOf { it.maskDrawableIndices.size } + doc.offscreens.sumOf { it.maskCount }
		countInfo[18] = doc.renderOrderGroups.size
		countInfo[19] = doc.renderOrderGroups.sumOf { it.children.size }
		countInfo[20] = doc.glues.size
		countInfo[21] = 2 * doc.glues.sumOf { it.pairs.size }
		countInfo[22] = doc.glues.sumOf { it.intensityKeyforms.size }

		/**
		 * Writes CountInfo field [fieldIndex] when the version's block is wide enough.
		 *
		 * @param Int fieldIndex The CountInfo word index.
		 * @param Int value      The field value.
		 */
		fun putField(fieldIndex: Int, value: Int) {
			if (fieldIndex < fieldCount) {
				countInfo[fieldIndex] = value
			}
		}
		val offscreenKeyformTotal = doc.offscreens.sumOf { it.keyforms.size }
		// 23/24: total color-table rows = the delta-inclusive per-kind keyform totals plus the
		// moc-6 offscreen keyform prefix (MOC3 §5.6).
		val colorRowTotal = countInfo[7] + countInfo[8] + countInfo[9] + offscreenKeyformTotal
		if (blendLayout != null || doc.offscreens.isNotEmpty()) {
			putField(23, colorRowTotal)
			putField(24, colorRowTotal)
		}
		if (blendLayout != null) {
			putField(25, blendLayout.bindingKeys.size)
			putField(26, blendLayout.recordsInFileOrder.size)
			putField(27, blendLayout.warpRecords.map { it.targetIndex }.distinct().size)
			putField(28, blendLayout.meshRecords.map { it.targetIndex }.distinct().size)
			putField(29, blendLayout.recordsInFileOrder.sumOf { it.limits.size })
			putField(30, blendLayout.pool.size)
			putField(31, blendLayout.pool.sumOf { it.keyPositions.size })
			putField(32, blendLayout.partRecords.map { it.targetIndex }.distinct().size)
			putField(33, blendLayout.rotationRecords.map { it.targetIndex }.distinct().size)
			// 34: glue blend shapes - corpus-empty, unmodeled (stays zero).
		}
		putField(35, doc.offscreens.size)
		putField(36, offscreenKeyformTotal)
		return intList(countInfo.toList())
	}

	/**
	 * Synthesizes the blend-shape record/binding/limit sections from [doc] (MOC3 v4+ §5.6:
	 * 115-136 plus the per-kind object trios 125-130/143-148).  Bindings and the limit sub-binding
	 * pool are re-deduplicated from the per-record expansions (the decoder expands the pool per
	 * record); RECORD_BASE is recomputed as the per-kind running cursor after the base keyforms,
	 * which reproduces the decoded values for an unedited document.
	 *
	 * @param MocDocument doc The semantic model.
	 * @return Map Section index → element-region bytes (empty map when the doc has no blend shapes).
	 */
	public fun blendShapeSections(doc: MocDocument): Map<Int, ByteArray> {
		if (doc.blendShapes.isEmpty()) {
			return emptyMap()
		}
		val version = doc.version
		val out = LinkedHashMap<Int, ByteArray>()

		fun put(section: Section, bytes: ByteArray) {
			val index = section.indexIn(version)
			if (index >= 0) {
				out[index] = bytes
			}
		}

		val layout = BlendShapeLayout(doc)

		// 115/116: per-parameter binding ranges.  A binding-less parameter stores begin = 0, NOT
		// the running cumulative (probed on Model A/B/C - unlike SUBSTART's carry convention).
		put(Section.BLENDSHAPE_PARAMETER_BEGIN, intList(layout.parameterBegin.toList()))
		put(Section.BLENDSHAPE_PARAMETER_COUNT, intList(layout.parameterBindingCount.toList()))

		// 117-119: per-binding key runs (absolute float offsets into KEY_POSITIONS' second region,
		// after the main-grid keys) + neutral indices.
		val mainGridKeyTotal = ParameterBindingGrid(doc).totalKeyPositions
		val bindingKeyOffsets = ArrayList<Int>()
		var bindingKeyCursor = mainGridKeyTotal
		for (bindingKeys in layout.bindingKeys) {
			bindingKeyOffsets.add(bindingKeyCursor)
			bindingKeyCursor += bindingKeys.size
		}
		put(Section.BLENDSHAPE_BINDING_KEY_OFFSET, intList(bindingKeyOffsets))
		put(Section.BLENDSHAPE_BINDING_KEY_COUNT, intList(layout.bindingKeys.map { it.size }))
		put(Section.BLENDSHAPE_BINDING_NEUTRAL, intList(layout.bindingNeutral.toList()))

		// 120-122: per-record binding refs, value-table bases (per-kind running cursors after the
		// base keyforms), and the redundant key-count copy.
		put(Section.BLENDSHAPE_RECORD_BINDING, intList(layout.bindingIndexOfRecord.toList()))
		val warps = doc.deformers.filterIsInstance<WarpDeformer>()
		val rotations = doc.deformers.filterIsInstance<RotationDeformer>()
		val kindCursors =
			hashMapOf(
				BlendShapeTarget.WARP to warps.sumOf { it.keyforms.size },
				BlendShapeTarget.ART_MESH to doc.artMeshes.sumOf { it.keyforms.size },
				BlendShapeTarget.ROTATION to rotations.sumOf { it.keyforms.size },
				BlendShapeTarget.PART to doc.parts.sumOf { it.drawOrderKeyforms.size },
			)
		val recordBases = ArrayList<Int>()
		for (record in layout.recordsInFileOrder) {
			val cursor = kindCursors.getValue(record.target)
			recordBases.add(cursor)
			kindCursors[record.target] = cursor + record.keyPositions.size
		}
		put(Section.BLENDSHAPE_RECORD_BASE, intList(recordBases))
		put(Section.BLENDSHAPE_RECORD_KEY_COUNT, intList(layout.recordsInFileOrder.map { it.keyPositions.size }))

		// 123/124 + 131-136: limit corner refs into the deduplicated sub-binding pool.  SUBSTART is
		// running-cumulative (cornerless records carry the running value).
		val substarts = ArrayList<Int>()
		val cornerCounts = ArrayList<Int>()
		val subIndices = ArrayList<Int>()
		var cornerCursor = 0
		for (record in layout.recordsInFileOrder) {
			substarts.add(cornerCursor)
			cornerCounts.add(record.limits.size)
			cornerCursor += record.limits.size
			for (limit in record.limits) {
				subIndices.add(layout.poolIndexOf(limit))
			}
		}
		put(Section.BLENDSHAPE_RECORD_SUBSTART, intList(substarts))
		put(Section.BLENDSHAPE_RECORD_CORNER_COUNT, intList(cornerCounts))
		if (layout.pool.isNotEmpty()) {
			val subKeyOffsets = ArrayList<Int>()
			val subKeyCounts = ArrayList<Int>()
			val subKeys = ArrayList<Float>()
			val subWeights = ArrayList<Float>()
			for (poolEntry in layout.pool) {
				subKeyOffsets.add(subKeys.size)
				subKeyCounts.add(poolEntry.keyPositions.size)
				poolEntry.keyPositions.forEach { subKeys.add(it) }
				poolEntry.weights.forEach { subWeights.add(it) }
			}
			put(Section.BLENDSHAPE_SUB_INDEX, intList(subIndices))
			put(Section.BLENDSHAPE_SUB_PARAMETER, intList(layout.pool.map { it.parameterIndex }))
			put(Section.BLENDSHAPE_SUB_KEY_OFFSET, intList(subKeyOffsets))
			put(Section.BLENDSHAPE_SUB_KEY_COUNT, intList(subKeyCounts))
			put(Section.BLENDSHAPE_SUB_KEYS, floatList(subKeys))
			put(Section.BLENDSHAPE_SUB_WEIGHT_VALUES, floatList(subWeights))
		}

		// Per-kind object trios: objects owning records (kind-local indices ascending), with each
		// object's record range as GLOBAL record indices.
		fun putTrio(
			records: List<BlendShape>,
			recordOffset: Int,
			localIndexOf: (Int) -> Int,
			objectSection: Section,
			startSection: Section,
			countSection: Section,
		) {
			if (records.isEmpty()) {
				return
			}
			val objectIndices = ArrayList<Int>()
			val recordStarts = ArrayList<Int>()
			val recordCounts = ArrayList<Int>()
			for ((recordOrdinal, record) in records.withIndex()) {
				val localObjectIndex = localIndexOf(record.targetIndex)
				if (objectIndices.isEmpty() || objectIndices.last() != localObjectIndex) {
					objectIndices.add(localObjectIndex)
					recordStarts.add(recordOffset + recordOrdinal)
					recordCounts.add(1)
				} else {
					recordCounts[recordCounts.size - 1] = recordCounts.last() + 1
				}
			}
			put(objectSection, intList(objectIndices))
			put(startSection, intList(recordStarts))
			put(countSection, intList(recordCounts))
		}

		val meshRecordOffset = layout.warpRecords.size
		val partRecordOffset = meshRecordOffset + layout.meshRecords.size
		val rotationRecordOffset = partRecordOffset + layout.partRecords.size
		putTrio(
			layout.warpRecords,
			0,
			{ deformerIndex -> layout.warpLocalByDeformer[deformerIndex] ?: deformerIndex },
			Section.BLENDSHAPE_WARP_OBJECT,
			Section.BLENDSHAPE_WARP_RECORD_START,
			Section.BLENDSHAPE_WARP_RECORD_COUNT,
		)
		putTrio(
			layout.meshRecords,
			meshRecordOffset,
			{ drawableIndex -> drawableIndex },
			Section.BLENDSHAPE_MESH_OBJECT,
			Section.BLENDSHAPE_MESH_RECORD_START,
			Section.BLENDSHAPE_MESH_RECORD_COUNT,
		)
		putTrio(
			layout.partRecords,
			partRecordOffset,
			{ partIndex -> partIndex },
			Section.BLENDSHAPE_PART_OBJECT,
			Section.BLENDSHAPE_PART_RECORD_START,
			Section.BLENDSHAPE_PART_RECORD_COUNT,
		)
		putTrio(
			layout.rotationRecords,
			rotationRecordOffset,
			{ deformerIndex -> layout.rotationLocalByDeformer[deformerIndex] ?: deformerIndex },
			Section.BLENDSHAPE_ROTATION_OBJECT,
			Section.BLENDSHAPE_ROTATION_RECORD_START,
			Section.BLENDSHAPE_ROTATION_RECORD_COUNT,
		)
		return out
	}

	/**
	 * The blend-shape file layout derived from a document's records: the global record order
	 * (warp, mesh, part, then rotation records - objects ascending by kind-local index, verified
	 * on the corpus), the deduplicated binding list (grouped by parameter ascending, first
	 * occurrence within a parameter), and the deduplicated limit sub-binding pool (sorted by
	 * gating-parameter index; the within-parameter tie-break is lexicographic over keys then
	 * weights - an ASSUMPTION, the corpus never shows two curves on one parameter).
	 */
	private class BlendShapeLayout(doc: MocDocument) {
		val warpRecords: List<BlendShape>
		val meshRecords: List<BlendShape>
		val partRecords: List<BlendShape>
		val rotationRecords: List<BlendShape>
		val recordsInFileOrder: List<BlendShape>
		val warpLocalByDeformer: Map<Int, Int>
		val rotationLocalByDeformer: Map<Int, Int>
		val bindingKeys: List<FloatArray>
		val bindingNeutral: IntArray
		val bindingOwnerParameter: IntArray
		val parameterBegin: IntArray
		val parameterBindingCount: IntArray
		val bindingIndexOfRecord: IntArray
		val pool: List<BlendShapeLimit>
		private val poolIndexByValue: Map<List<Any>, Int>

		init {
			val warpLocal = HashMap<Int, Int>()
			val rotationLocal = HashMap<Int, Int>()
			var nextWarpLocal = 0
			var nextRotationLocal = 0
			for ((deformerIndex, deformer) in doc.deformers.withIndex()) {
				if (deformer is WarpDeformer) {
					warpLocal[deformerIndex] = nextWarpLocal++
				} else {
					rotationLocal[deformerIndex] = nextRotationLocal++
				}
			}
			warpLocalByDeformer = warpLocal
			rotationLocalByDeformer = rotationLocal
			warpRecords =
				doc.blendShapes.filter { it.target == BlendShapeTarget.WARP }
					.sortedBy { warpLocal[it.targetIndex] ?: Int.MAX_VALUE }
			meshRecords = doc.blendShapes.filter { it.target == BlendShapeTarget.ART_MESH }.sortedBy { it.targetIndex }
			partRecords = doc.blendShapes.filter { it.target == BlendShapeTarget.PART }.sortedBy { it.targetIndex }
			rotationRecords =
				doc.blendShapes.filter { it.target == BlendShapeTarget.ROTATION }
					.sortedBy { rotationLocal[it.targetIndex] ?: Int.MAX_VALUE }
			recordsInFileOrder = warpRecords + meshRecords + partRecords + rotationRecords

			// Distinct bindings: (parameter, keys, neutral), first-occurrence order re-sorted by
			// parameter (stable, so within-parameter order stays first-occurrence).
			data class BindingIdentity(val parameterIndex: Int, val keys: List<Float>, val neutralKeyIndex: Int)

			val discoveredBindings = LinkedHashSet<BindingIdentity>()
			for (record in recordsInFileOrder) {
				discoveredBindings.add(
					BindingIdentity(record.parameterIndex, record.keyPositions.toList(), record.neutralKeyIndex),
				)
			}
			val orderedBindings = discoveredBindings.toList().sortedBy { it.parameterIndex }
			bindingKeys = orderedBindings.map { it.keys.toFloatArray() }
			bindingNeutral = IntArray(orderedBindings.size) { bindingIndex -> orderedBindings[bindingIndex].neutralKeyIndex }
			bindingOwnerParameter = IntArray(orderedBindings.size) { bindingIndex -> orderedBindings[bindingIndex].parameterIndex }
			val bindingIndexByIdentity = orderedBindings.withIndex().associate { (bindingIndex, identity) -> identity to bindingIndex }
			bindingIndexOfRecord =
				IntArray(recordsInFileOrder.size) { recordIndex ->
					val record = recordsInFileOrder[recordIndex]
					bindingIndexByIdentity.getValue(
						BindingIdentity(record.parameterIndex, record.keyPositions.toList(), record.neutralKeyIndex),
					)
				}
			parameterBegin = IntArray(doc.parameters.size)
			parameterBindingCount = IntArray(doc.parameters.size)
			var bindingCursor = 0
			for (parameterIndex in doc.parameters.indices) {
				parameterBindingCount[parameterIndex] =
					orderedBindings.count { it.parameterIndex == parameterIndex }
				// Binding-less parameters store 0, not the running cumulative (corpus-probed).
				parameterBegin[parameterIndex] = if (parameterBindingCount[parameterIndex] > 0) bindingCursor else 0
				bindingCursor += parameterBindingCount[parameterIndex]
			}

			// The limit sub-binding pool, deduplicated by value.  Sorted by gating-parameter index
			// (corpus-observed); keys-then-weights lexicographic tie-break within a parameter is a
			// documented assumption (MOC3.md §5.6) - no corpus sample carries two curves on one
			// parameter to discriminate.
			fun identityOf(limit: BlendShapeLimit): List<Any> =
				listOf(limit.parameterIndex, limit.keyPositions.toList(), limit.weights.toList())

			val distinctLimits = LinkedHashMap<List<Any>, BlendShapeLimit>()
			for (record in recordsInFileOrder) {
				for (limit in record.limits) {
					distinctLimits.getOrPut(identityOf(limit)) { limit }
				}
			}

			fun lexicographic(left: FloatArray, right: FloatArray): Int {
				val sharedLength = minOf(left.size, right.size)
				for (elementIndex in 0 until sharedLength) {
					val order = left[elementIndex].compareTo(right[elementIndex])
					if (order != 0) {
						return order
					}
				}
				return left.size.compareTo(right.size)
			}
			pool =
				distinctLimits.values.sortedWith(
					Comparator { left, right ->
						val byParameter = left.parameterIndex.compareTo(right.parameterIndex)
						if (byParameter != 0) {
							return@Comparator byParameter
						}
						val byKeys = lexicographic(left.keyPositions, right.keyPositions)
						if (byKeys != 0) {
							return@Comparator byKeys
						}
						lexicographic(left.weights, right.weights)
					},
				)
			poolIndexByValue = pool.withIndex().associate { (poolIndex, poolEntry) -> identityOf(poolEntry) to poolIndex }
		}

		/**
		 * The pool index of [limit] (matched by value).
		 *
		 * @param BlendShapeLimit limit A per-record expanded limit curve.
		 * @return Int The deduplicated pool index.
		 */
		fun poolIndexOf(limit: BlendShapeLimit): Int =
			poolIndexByValue.getValue(listOf(limit.parameterIndex, limit.keyPositions.toList(), limit.weights.toList()))
	}

	/** The parameter-binding dedup totals (shared by the grid lowering and CountInfo). */
	private class ParameterBindingGrid(doc: MocDocument) {
		val totalParamBindings: Int
		val totalKeyPositions: Int

		init {
			val keySetsByParameter = Array(doc.parameters.size) { LinkedHashSet<List<Float>>() }
			for (binding in doc.bindings) {
				for (axis in binding.axes) {
					keySetsByParameter[axis.parameterIndex].add(axis.keyPositions.toList())
				}
			}
			totalParamBindings = keySetsByParameter.sumOf { it.size }
			totalKeyPositions = keySetsByParameter.sumOf { keySet -> keySet.sumOf { it.size } }
		}
	}

	/**
	 * Encodes [values] as a packed little-endian `i16[]`.
	 *
	 * @param List<Short> values The shorts to write.
	 * @return ByteArray The packed bytes.
	 */
	private fun shortList(values: List<Short>): ByteArray {
		val writer = LittleEndianWriter(values.size * 2)
		values.forEach { shortValue ->
			writer.writeU8(shortValue.toInt() and 0xFF)
			writer.writeU8((shortValue.toInt() shr 8) and 0xFF)
		}
		return writer.toByteArray()
	}

	/**
	 * Zero-pads [writer] so a block of [count] floats reaches the next 16-float (64-byte) boundary.
	 *
	 * @param LittleEndianWriter writer The value-table writer to pad.
	 * @param Int                count  The number of floats just written in this block.
	 */
	private fun padTo16Floats(writer: LittleEndianWriter, count: Int) {
		val padCount = ((count + 15) / 16 * 16) - count
		repeat(padCount) { writer.writeFloat32(0f) }
	}

	// ---- element writers ----

	/**
	 * Encodes [values] as a packed little-endian `f32[]`.
	 *
	 * @param Float values The floats to write.
	 * @return ByteArray The packed bytes.
	 */
	private fun floats(vararg values: Float): ByteArray {
		val writer = LittleEndianWriter(values.size * 4)
		values.forEach(writer::writeFloat32)
		return writer.toByteArray()
	}

	/**
	 * Encodes [values] as a packed little-endian `f32[]`.
	 *
	 * @param List<Float> values The floats to write.
	 * @return ByteArray The packed bytes.
	 */
	private fun floatList(values: List<Float>): ByteArray {
		val writer = LittleEndianWriter(values.size * 4)
		values.forEach(writer::writeFloat32)
		return writer.toByteArray()
	}

	/**
	 * Encodes [values] as a packed little-endian `i32[]`.
	 *
	 * @param List<Int> values The integers to write.
	 * @return ByteArray The packed bytes.
	 */
	private fun intList(values: List<Int>): ByteArray {
		val writer = LittleEndianWriter(values.size * 4)
		values.forEach(writer::writeInt32)
		return writer.toByteArray()
	}

	/**
	 * Encodes [ids] as fixed [Sections.ID_STRIDE]-byte NUL-terminated, zero-padded ID records.
	 *
	 * @param List<String> ids The identifiers to write.
	 * @return ByteArray The packed ID records.
	 */
	private fun idRecords(ids: List<String>): ByteArray {
		val writer = LittleEndianWriter(ids.size * Sections.ID_STRIDE)
		for (id in ids) {
			val bytes = id.encodeToByteArray()
			require(bytes.size < Sections.ID_STRIDE) { "id too long for 64-byte record: $id" }
			writer.writeBytes(bytes)
			writer.zeroPad(Sections.ID_STRIDE - bytes.size)
		}
		return writer.toByteArray()
	}

	/**
	 * Concatenates each item's selected `f32[]` into one packed little-endian block.
	 *
	 * @param List<T>          items  The items to draw arrays from.
	 * @param (T) -> FloatArray select The per-item float array selector.
	 * @return ByteArray The packed bytes.
	 */
	private fun <T> floatConcat(items: List<T>, select: (T) -> FloatArray): ByteArray {
		val writer = LittleEndianWriter(items.sumOf { select(it).size } * 4)
		for (item in items) {
			select(item).forEach(writer::writeFloat32)
		}
		return writer.toByteArray()
	}

	/**
	 * Concatenates each item's selected `i32[]` into one packed little-endian block.
	 *
	 * @param List<T>        items  The items to draw arrays from.
	 * @param (T) -> IntArray select The per-item int array selector.
	 * @return ByteArray The packed bytes.
	 */
	private fun <T> intConcat(items: List<T>, select: (T) -> IntArray): ByteArray {
		val writer = LittleEndianWriter(items.sumOf { select(it).size } * 4)
		for (item in items) {
			select(item).forEach(writer::writeInt32)
		}
		return writer.toByteArray()
	}

	/**
	 * Concatenates each item's selected `i16[]` into one packed little-endian block.
	 *
	 * @param List<T>          items  The items to draw arrays from.
	 * @param (T) -> ShortArray select The per-item short array selector.
	 * @return ByteArray The packed bytes.
	 */
	private fun <T> u16Concat(items: List<T>, select: (T) -> ShortArray): ByteArray {
		val writer = LittleEndianWriter(items.sumOf { select(it).size } * 2)
		for (item in items) {
			select(item).forEach { shortValue ->
				writer.writeU8(shortValue.toInt() and 0xFF)
				writer.writeU8((shortValue.toInt() shr 8) and 0xFF)
			}
		}
		return writer.toByteArray()
	}
}
