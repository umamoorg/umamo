package org.umamo.format.moc3.encode

import org.umamo.format.moc3.MocDocument
import org.umamo.format.moc3.io.LittleEndianWriter
import org.umamo.format.moc3.moc.Section
import org.umamo.format.moc3.moc.Sections
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
 * EN: The layout-dependent sections (per-object keyform bases and the packed keyform value tables /
 *     binding grid) are not synthesized here - they require reproducing a value-table packing and are
 *     the remaining bake work; until then a full bake carries them through from a decoded model.
 * JA: 構造・トポロジ系セクションを意味モデルから復元する（バイト一致）。
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
		// The mask-index block holds the drawables' mask lists, then (moc 6) the offscreens' mask lists.
		// We don't model offscreen masks, so only synthesize when there are no offscreens (else carry).
		if (doc.offscreens.isEmpty()) {
			put(Sections.MASK_INDEX_DATA, intConcat(doc.artMeshes) { it.maskDrawableIndices })
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

		// Per-object bases index only the base-keyform prefix of the shared tables, so they are
		// independent of blend shapes and always synthesized. The shared keyform value TABLES below
		// (positions, indices, per-keyform scalars) are extended by blend-shape delta keyforms
		// (appended after the base keyforms), which are not yet synthesized - so for a blend-shape
		// model they are omitted here and carried from the reference (full, including the BS deltas).
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

		if (doc.blendShapes.isEmpty()) {
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
		}

		// parts: per-part draw-order keyform table + cumulative base (a static part has a single value).
		val partDrawOrder = ArrayList<Float>()
		val partKeyformBase = IntArray(doc.parts.size)
		for ((partIndex, part) in doc.parts.withIndex()) {
			partKeyformBase[partIndex] = partDrawOrder.size
			part.drawOrderKeyforms.forEach { partDrawOrder.add(it) }
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

		// ---- color tables (v4+): deformers in unified order, then meshes ----
		// On moc 6 the shared color tables are prefixed by the offscreens' keyform-interpolated
		// colors (which we don't model yet), shifting every base; and v5+ blend shapes append color
		// delta keyforms. Only synthesize when both are absent (else carry the full table from a reference).
		val hasColor =
			doc.offscreens.isEmpty() &&
				doc.blendShapes.isEmpty() &&
				(
					doc.deformers.any { deformer ->
						(deformer is WarpDeformer && deformer.keyforms.any { it.multiplyColor != null }) ||
							(deformer is RotationDeformer && deformer.keyforms.any { it.multiplyColor != null })
					} ||
						doc.artMeshes.any { mesh -> mesh.keyforms.any { it.multiplyColor != null } }
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
			// (relive recomputes them, so they are absent from its map). A kind-0 child's draw order is its
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

		// ---- offscreens (per-object scalar sections) ----
		if (doc.offscreens.isNotEmpty()) {
			put(Section.OFFSCREEN_OWNER_PART, intList(doc.offscreens.map { it.ownerPartIndex }))
			put(
				Section.OFFSCREEN_CONSTANT_FLAGS,
				ByteArray(doc.offscreens.size) { doc.offscreens[it].constantFlags.toByte() },
			)
			put(Section.OFFSCREEN_BLEND_MODE, intList(doc.offscreens.map { it.blendMode }))
			put(Section.OFFSCREEN_MASK_COUNT, intList(doc.offscreens.map { it.maskCount }))
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
		// KEY_POSITIONS is shared with blend-shape bindings (main-grid keys, then BS keys). We only
		// synthesize the main-grid keys, so emit the table only when there are no blend shapes; our
		// keyOffset/keyCount still index the (carried) full table's prefix correctly otherwise.
		if (doc.blendShapes.isEmpty()) {
			put(Section.KEY_POSITIONS, floatList(keyPositions))
		}
		put(Section.KEYFORM_BINDING_SLOT, intList(keyformBindingSlot))
		put(Section.KEYFORM_BINDING_START, intList(keyformBindingStart))
		put(Section.KEYFORM_BINDING_COUNT, intList(keyformBindingCount))
		return out
	}

	/**
	 * Synthesizes the CountInfo block (section 0) from [doc]: the per-object-kind counts and the
	 * cumulative totals the runtime allocates its working buffers from. Fields 0..22 are derived
	 * directly from the object model and are byte-exact for blend-shape/offscreen-free models;
	 * the blend-shape/offscreen totals (fields 23+) are left zero (they require the not-yet-synthesized
	 * blend-shape value tables - a model with those uses the reference-carrying bake instead).
	 *
	 * @param MocDocument doc The semantic model.
	 * @return ByteArray The CountInfo element-region bytes (`u32[fieldCount]`).
	 */
	public fun countInfoSection(doc: MocDocument): ByteArray {
		val warps = doc.deformers.filterIsInstance<WarpDeformer>()
		val rotations = doc.deformers.filterIsInstance<RotationDeformer>()

		fun padded(coordinateCount: Int): Int = (coordinateCount + 15) / 16 * 16
		val positionFloatCount =
			warps.sumOf { warp -> warp.keyforms.sumOf { padded(it.controlPoints.size) } } +
				doc.artMeshes.sumOf { mesh -> mesh.keyforms.sumOf { padded(it.vertexPositions.size) } }
		val bindingGrid = ParameterBindingGrid(doc)
		val fieldCount = if (doc.version.byteValue >= 6) 64 else 32
		val countInfo = IntArray(fieldCount)
		countInfo[0] = doc.parts.size
		countInfo[1] = doc.deformers.size
		countInfo[2] = warps.size
		countInfo[3] = rotations.size
		countInfo[4] = doc.artMeshes.size
		countInfo[5] = doc.parameters.size
		countInfo[6] = doc.parts.sumOf { it.drawOrderKeyforms.size }
		countInfo[7] = warps.sumOf { it.keyforms.size }
		countInfo[8] = rotations.sumOf { it.keyforms.size }
		countInfo[9] = doc.artMeshes.sumOf { it.keyforms.size }
		countInfo[10] = positionFloatCount
		countInfo[11] = doc.bindings.sumOf { it.axes.size } // total keyform-binding slots
		countInfo[12] = doc.bindings.size
		countInfo[13] = bindingGrid.totalParamBindings
		countInfo[14] = bindingGrid.totalKeyPositions // main-grid keys (blend-shape models append BS keys here too)
		countInfo[15] = 2 * doc.artMeshes.sumOf { it.vertexCount }
		countInfo[16] = doc.artMeshes.sumOf { it.triangleIndices.size }
		countInfo[17] = doc.artMeshes.sumOf { it.maskDrawableIndices.size }
		countInfo[18] = doc.renderOrderGroups.size
		countInfo[19] = doc.renderOrderGroups.sumOf { it.children.size }
		countInfo[20] = doc.glues.size
		countInfo[21] = 2 * doc.glues.sumOf { it.pairs.size }
		countInfo[22] = doc.glues.sumOf { it.intensityKeyforms.size }
		return intList(countInfo.toList())
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
