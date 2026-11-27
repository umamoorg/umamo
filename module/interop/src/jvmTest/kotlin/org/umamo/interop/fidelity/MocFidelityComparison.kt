package org.umamo.interop.fidelity

import org.umamo.format.moc3.MocDocument
import org.umamo.format.moc3.model.BlendShapeKeyform
import org.umamo.format.moc3.model.BlendShapeTarget
import org.umamo.format.moc3.model.Rgb
import org.umamo.format.moc3.model.RotationDeformer
import org.umamo.format.moc3.model.WarpDeformer
import kotlin.math.abs

/**
 * The relative deviation a compared value may carry: |a - b| / max(1, |a|, |b|).
 *
 * The magnitude is floored at 1 because a value near zero, a delta or a lattice offset, is computed
 * beside canvas-scale ones, so its rounding error is absolute rather than relative to its own size.
 * Every same-format round trip on the corpus, and every CMO3 export against the editor's own bake of it,
 * stays under 8.1e-6; the smallest real loss measured on the corpus is about 3e-3.
 */
internal const val FIDELITY_RELATIVE_TOLERANCE = 1e-5f

/**
 * A union-of-keys sampling past this many points is not attempted, and the grid is reported as
 * unverifiable rather than passed.
 */
private const val MAX_SAMPLE_POINTS = 60_000L

/** The kinds of object a fidelity comparison walks, plus the three document-level records. */
internal enum class MocObjectKind {
	PARAMETER,
	PART,
	WARP,
	ROTATION,
	ART_MESH,
	GLUE,
	OFFSCREEN,
	BLEND_SHAPE,
	CANVAS,
	RENDER_TREE,

	/** A parent's sub-parts in part-table order, keyed `part:` and the parent part's id, or [ROOT_PART_TREE]. */
	PART_TREE,
}

/** The [MocObjectKind.PART_TREE] id of the sub-parts no part owns. */
internal const val ROOT_PART_TREE = "root"

/** The attribute names a comparison reports, shared with the matchers in [KnownExportLoss]. */
internal object MocAttribute {
	const val MINIMUM = "minimum"
	const val MAXIMUM = "maximum"
	const val DEFAULT = "default"
	const val TYPE = "type"
	const val REPEAT = "repeat"
	const val PARENT_PART = "parent part"
	const val PARENT_DEFORMER = "parent deformer"
	const val VISIBLE = "visible"
	const val ENABLED = "enabled"
	const val LATTICE = "lattice"
	const val MODE = "mode"
	const val BASE_ANGLE = "base angle"
	const val TEXTURE = "texture"
	const val FLAGS = "flags"
	const val EXTENDED_BLEND = "extended blend"
	const val VERTEX_COUNT = "vertex count"
	const val UVS = "uvs"
	const val TRIANGLES = "triangles"
	const val MASKS = "masks"
	const val ID = "id"
	const val PAIRS = "pairs"
	const val BLEND_MODE = "blend mode"
	const val MASK_COUNT = "mask count"
	const val NEUTRAL_KEY = "neutral key"
	const val LIMITS = "limits"
	const val PIXELS_PER_UNIT = "pixels per unit"
	const val ORIGIN_X = "origin x"
	const val ORIGIN_Y = "origin y"
	const val WIDTH = "width"
	const val HEIGHT = "height"
}

/** The keyform channel names a comparison reports. */
internal object MocChannel {
	const val DRAW_ORDER = "draw order"
	const val LATTICE = "lattice"
	const val OPACITY = "opacity"
	const val MULTIPLY_COLOR = "multiply color"
	const val SCREEN_COLOR = "screen color"
	const val ORIGIN = "origin"
	const val ANGLE = "angle"
	const val SCALE = "scale"
	const val FLIP = "flip"
	const val POSITIONS = "positions"
	const val INTENSITY = "intensity"
	const val DELTA = "delta"
	const val MULTIPLY_COLOR_DELTA = "multiply color delta"
	const val SCREEN_COLOR_DELTA = "screen color delta"
}

/** How one object differs between the reference document and the candidate. */
internal sealed interface DivergenceAspect {
	/** The reference has the object and the candidate does not. */
	data object Missing : DivergenceAspect

	/** The candidate has an object the reference does not. */
	data object Extra : DivergenceAspect

	/** An attribute differs; [expected] is the reference's value and [actual] the candidate's. */
	data class Attribute(val name: String, val expected: Any?, val actual: Any?) : DivergenceAspect

	/** The reference's keyforms carry a channel the candidate's do not. */
	data class ChannelLost(val channel: String) : DivergenceAspect

	/** The candidate's keyforms carry a channel the reference's do not, zero in every cell or not. */
	data class ChannelGained(val channel: String, val allZero: Boolean) : DivergenceAspect

	/** A channel evaluates differently somewhere on the grid, by more than the tolerance. */
	data class Value(val channel: String, val relativeDeviation: Float) : DivergenceAspect

	/** A keyform list does not fill its binding's grid, on either side. */
	data object GridMalformed : DivergenceAspect

	/** The two grids differ in shape and their union of keys is too large to sample. */
	data object GridUnverifiable : DivergenceAspect

	/** A render-order group lists its shared children differently. */
	data object RenderTree : DivergenceAspect

	/** A parent lists the sub-parts both documents place under it in a different part-table order. */
	data object PartOrder : DivergenceAspect
}

/**
 * One difference between two documents, on one object.
 *
 * @property MocObjectKind kind The object's kind.
 * @property String id The object's identity, the same in both documents.
 * @property DivergenceAspect aspect What differs.
 * @property String detail A human-readable account for a failure message.
 */
internal data class MocDivergence(val kind: MocObjectKind, val id: String, val aspect: DivergenceAspect, val detail: String = "") {
	/**
	 * The one-line account a gate prints for this divergence.
	 *
	 * @return String The kind, id, aspect, and detail.
	 */
	fun describe(): String {
		val aspectText =
			when (aspect) {
				DivergenceAspect.Missing -> "missing"
				DivergenceAspect.Extra -> "extra"
				is DivergenceAspect.Attribute -> "${aspect.name} ${shown(aspect.expected)} -> ${shown(aspect.actual)}"
				is DivergenceAspect.ChannelLost -> "channel ${aspect.channel} lost"
				is DivergenceAspect.ChannelGained -> "channel ${aspect.channel} gained (${if (aspect.allZero) "all zero" else "non-zero"})"
				is DivergenceAspect.Value -> "${aspect.channel} deviates by ${aspect.relativeDeviation} relative"
				DivergenceAspect.GridMalformed -> "grid malformed"
				DivergenceAspect.GridUnverifiable -> "grid changed and too large to verify"
				DivergenceAspect.RenderTree -> "render tree differs"
				DivergenceAspect.PartOrder -> "sub-part order differs"
			}

		return "$kind $id: $aspectText" + (if (detail.isEmpty()) "" else " ($detail)")
	}
}

/**
 * The identity a blend shape record is compared under: its target's kind and id and its parameter's id.
 *
 * @param BlendShapeTarget target The record's target kind.
 * @param String? targetId The target object's id.
 * @param String? parameterId The record's parameter id.
 * @return String The comparison identity, before any ordinal for a repeated record.
 */
internal fun blendShapeObjectId(target: BlendShapeTarget, targetId: String?, parameterId: String?): String = "$target:$targetId:$parameterId"

/**
 * Everything that differs between [reference] and [candidate], object by object.
 *
 * Objects are matched by kind and id, never by position: two files may order their objects differently
 * and still describe the same rig, so the order of an object list is not compared here.  The one order
 * that is compared is each parent's sub-parts in part-table order, since that is the only place a moc
 * records how sibling parts stack in the parts panel; the editor's bake and Umamo's export lay the table
 * out differently overall but keep it.  A keyform grid is judged by what it evaluates to, not by its
 * shape, so a grid whose keys changed without changing any value passes; both grids are evaluated at
 * every point of the union of their keys, which for multilinear grids decides agreement everywhere
 * between them too.  The canvas is compared exactly, and the render tree on the
 * objects both documents carry, since a missing or extra object is reported once already.
 *
 * @param MocDocument reference The document the candidate should reproduce.
 * @param MocDocument candidate The document under test.
 * @param Set ignoredAttributes Attribute names not to compare, for a path that cannot carry them.
 * @return List The divergences, in reference object order, then the extras.
 */
internal fun compareMocDocuments(reference: MocDocument, candidate: MocDocument, ignoredAttributes: Set<String> = emptySet()): List<MocDivergence> {
	val divergences = ArrayList<MocDivergence>()
	compareCanvas(reference, candidate, ignoredAttributes, divergences)
	val referenceObjects = fidelityObjectsOf(reference)
	val candidateObjects = fidelityObjectsOf(candidate)
	val candidateByKey = candidateObjects.associateBy { candidateObject -> candidateObject.key }
	val referenceKeys = referenceObjects.mapTo(HashSet()) { referenceObject -> referenceObject.key }

	for (referenceObject in referenceObjects) {
		val candidateObject = candidateByKey[referenceObject.key]

		if (candidateObject == null) {
			divergences.add(MocDivergence(referenceObject.kind, referenceObject.id, DivergenceAspect.Missing))

			continue
		}

		for ((name, expected) in referenceObject.attributes) {
			val actual = candidateObject.attributes[name]

			if (name !in ignoredAttributes && !sameAttribute(expected, actual)) {
				divergences.add(MocDivergence(referenceObject.kind, referenceObject.id, DivergenceAspect.Attribute(name, expected, actual)))
			}
		}

		val referenceGrid = referenceObject.grid
		val candidateGrid = candidateObject.grid

		if (referenceGrid != null && candidateGrid != null) {
			compareGrids(referenceObject, referenceGrid, candidateGrid, divergences)
		}
	}

	for (candidateObject in candidateObjects) {
		if (candidateObject.key !in referenceKeys) {
			divergences.add(MocDivergence(candidateObject.kind, candidateObject.id, DivergenceAspect.Extra))
		}
	}

	compareRenderTrees(reference, candidate, divergences)
	comparePartTrees(reference, candidate, divergences)

	return divergences
}

/**
 * A keyform grid in comparable form: its axes by parameter id, and each cell's channels by name.
 *
 * @property List parameterIds Each axis's parameter id, first axis fastest.
 * @property List keys Each axis's key positions.
 * @property List cells Each cell's channel values, in binding order.
 * @property Boolean malformed Whether the keyforms failed to fill the binding's grid.
 */
private class FidelityGrid(val parameterIds: List<String>, val keys: List<FloatArray>, val cells: List<Map<String, FloatArray>>, val malformed: Boolean)

/**
 * One object in comparable form.
 *
 * @property MocObjectKind kind The object's kind.
 * @property String id Its identity across documents.
 * @property Map attributes Its non-keyform attributes, by name.
 * @property FidelityGrid? grid Its keyforms, or null for an object that has none.
 */
private class FidelityObject(val kind: MocObjectKind, val id: String, val attributes: Map<String, Any?>, val grid: FidelityGrid?) {
	val key: String get() = "$kind\u0001$id"
}

/** Compares the two canvases field by field; a null canvas on either side compares as one divergence. */
private fun compareCanvas(reference: MocDocument, candidate: MocDocument, ignoredAttributes: Set<String>, divergences: MutableList<MocDivergence>) {
	val referenceCanvas = reference.canvas
	val candidateCanvas = candidate.canvas

	if (referenceCanvas == null || candidateCanvas == null) {
		if (referenceCanvas != candidateCanvas) {
			divergences.add(MocDivergence(MocObjectKind.CANVAS, "canvas", DivergenceAspect.Attribute("canvas", referenceCanvas, candidateCanvas)))
		}

		return
	}

	val fields =
		listOf(
			Triple(MocAttribute.PIXELS_PER_UNIT, referenceCanvas.pixelsPerUnit, candidateCanvas.pixelsPerUnit),
			Triple(MocAttribute.ORIGIN_X, referenceCanvas.originX, candidateCanvas.originX),
			Triple(MocAttribute.ORIGIN_Y, referenceCanvas.originY, candidateCanvas.originY),
			Triple(MocAttribute.WIDTH, referenceCanvas.width, candidateCanvas.width),
			Triple(MocAttribute.HEIGHT, referenceCanvas.height, candidateCanvas.height),
		)

	for ((name, expected, actual) in fields) {
		if (name !in ignoredAttributes && expected != actual) {
			divergences.add(MocDivergence(MocObjectKind.CANVAS, "canvas", DivergenceAspect.Attribute(name, expected, actual)))
		}
	}
}

/** Compares one matched object's keyform grids, channel by channel. */
private fun compareGrids(owner: FidelityObject, reference: FidelityGrid, candidate: FidelityGrid, divergences: MutableList<MocDivergence>) {
	if (reference.malformed || candidate.malformed) {
		divergences.add(
			MocDivergence(owner.kind, owner.id, DivergenceAspect.GridMalformed, "reference cells=${reference.cells.size} candidate cells=${candidate.cells.size}"),
		)

		return
	}

	val referenceChannels = reference.cells.first().keys
	val candidateChannels = candidate.cells.first().keys

	for (channel in referenceChannels - candidateChannels) {
		divergences.add(MocDivergence(owner.kind, owner.id, DivergenceAspect.ChannelLost(channel)))
	}

	for (channel in candidateChannels - referenceChannels) {
		val allZero = candidate.cells.all { cell -> cell[channel]?.all { value -> value == 0f } != false }
		divergences.add(MocDivergence(owner.kind, owner.id, DivergenceAspect.ChannelGained(channel, allZero)))
	}

	val sharedChannels = referenceChannels.intersect(candidateChannels).toList()

	if (sharedChannels.isEmpty()) {
		return
	}

	val worstByChannel =
		if (sameAxes(reference, candidate)) {
			worstCellDeviation(reference, candidate, sharedChannels)
		} else {
			worstSampledDeviation(reference, candidate, sharedChannels)
		}

	if (worstByChannel == null) {
		divergences.add(MocDivergence(owner.kind, owner.id, DivergenceAspect.GridUnverifiable, "axes ${reference.parameterIds} -> ${candidate.parameterIds}"))

		return
	}

	for ((channel, worst) in worstByChannel) {
		if (worst > FIDELITY_RELATIVE_TOLERANCE) {
			divergences.add(MocDivergence(owner.kind, owner.id, DivergenceAspect.Value(channel, worst)))
		}
	}
}

/** Whether the two grids have the same axes, in the same order, with the same keys. */
private fun sameAxes(reference: FidelityGrid, candidate: FidelityGrid): Boolean =
	reference.parameterIds == candidate.parameterIds &&
		reference.keys.size == candidate.keys.size &&
		reference.keys.indices.all { axisIndex -> reference.keys[axisIndex].contentEquals(candidate.keys[axisIndex]) }

/** The worst relative deviation per channel between same-shaped grids, compared cell for cell. */
private fun worstCellDeviation(reference: FidelityGrid, candidate: FidelityGrid, channels: List<String>): Map<String, Float> {
	val worstByChannel = LinkedHashMap<String, Float>()

	for (channel in channels) {
		var worst = 0f

		for (cellIndex in reference.cells.indices) {
			worst = maxOf(worst, relativeDeviation(reference.cells[cellIndex][channel], candidate.cells.getOrNull(cellIndex)?.get(channel)))
		}

		worstByChannel[channel] = worst
	}

	return worstByChannel
}

/**
 * The worst relative deviation per channel between differently shaped grids, evaluated at every point of
 * the union of their keys, or null when that union is larger than [MAX_SAMPLE_POINTS].
 */
private fun worstSampledDeviation(reference: FidelityGrid, candidate: FidelityGrid, channels: List<String>): Map<String, Float>? {
	val referenceAxes = reference.parameterIds.zip(reference.keys).toMap()
	val candidateAxes = candidate.parameterIds.zip(candidate.keys).toMap()
	val parameters = (reference.parameterIds + candidate.parameterIds).distinct()
	val keyLists =
		parameters.map { parameter ->
			(referenceAxes[parameter]?.toList().orEmpty() + candidateAxes[parameter]?.toList().orEmpty()).distinct().sorted()
		}
	val pointCount = keyLists.fold(1L) { product, keys -> product * keys.size }

	if (pointCount > MAX_SAMPLE_POINTS) {
		return null
	}

	val worstByChannel = LinkedHashMap<String, Float>()
	val coordinate = IntArray(parameters.size)

	for (pointIndex in 0 until pointCount.toInt()) {
		var remainder = pointIndex

		for (axisIndex in parameters.indices) {
			coordinate[axisIndex] = remainder % keyLists[axisIndex].size
			remainder /= keyLists[axisIndex].size
		}

		val point = HashMap<String, Float>(parameters.size * 2)

		for (axisIndex in parameters.indices) {
			point[parameters[axisIndex]] = keyLists[axisIndex][coordinate[axisIndex]]
		}

		for (channel in channels) {
			val deviation = relativeDeviation(sampleGrid(reference, point, channel), sampleGrid(candidate, point, channel))
			worstByChannel[channel] = maxOf(worstByChannel[channel] ?: 0f, deviation)
		}
	}

	return worstByChannel
}

/**
 * The largest component deviation between two values, relative to max(1, |a|, |b|), or infinity when
 * either is absent or their sizes differ.
 */
private fun relativeDeviation(expected: FloatArray?, actual: FloatArray?): Float {
	if (expected == null || actual == null || expected.size != actual.size) {
		return if (expected == null && actual == null) 0f else Float.POSITIVE_INFINITY
	}

	var worst = 0f

	for (component in expected.indices) {
		val difference = abs(expected[component] - actual[component])

		if (difference.isNaN()) {
			return Float.POSITIVE_INFINITY
		}

		worst = maxOf(worst, difference / maxOf(1f, abs(expected[component]), abs(actual[component])))
	}

	return worst
}

/**
 * One channel of [grid] evaluated at [point] by multilinear interpolation, or null when a cell the point
 * needs lacks the channel or disagrees on its size.  A parameter the grid has no axis for is ignored, and a
 * value outside an axis's keys is clamped to its end.
 */
private fun sampleGrid(grid: FidelityGrid, point: Map<String, Float>, channel: String): FloatArray? {
	val axisCount = grid.keys.size
	val lowerIndex = IntArray(axisCount)
	val upperIndex = IntArray(axisCount)
	val fraction = FloatArray(axisCount)

	for (axisIndex in 0 until axisCount) {
		val keys = grid.keys[axisIndex]
		val value = point[grid.parameterIds[axisIndex]] ?: keys[0]
		val last = keys.size - 1

		if (value <= keys[0]) {
			lowerIndex[axisIndex] = 0
			upperIndex[axisIndex] = 0
		} else if (value >= keys[last]) {
			lowerIndex[axisIndex] = last
			upperIndex[axisIndex] = last
		} else {
			var keyIndex = 0

			while (keyIndex < last && keys[keyIndex + 1] <= value) {
				keyIndex++
			}

			lowerIndex[axisIndex] = keyIndex

			if (keys[keyIndex] == value) {
				upperIndex[axisIndex] = keyIndex
			} else {
				upperIndex[axisIndex] = keyIndex + 1
				fraction[axisIndex] = (value - keys[keyIndex]) / (keys[keyIndex + 1] - keys[keyIndex])
			}
		}
	}

	var result: FloatArray? = null

	for (corner in 0 until (1 shl axisCount)) {
		var weight = 1f
		var linear = 0
		var stride = 1

		for (axisIndex in 0 until axisCount) {
			val upper = (corner shr axisIndex) and 1 == 1
			val axisWeight = if (upper) fraction[axisIndex] else 1f - fraction[axisIndex]

			if (axisWeight == 0f) {
				weight = 0f

				break
			}

			weight *= axisWeight
			linear += (if (upper) upperIndex[axisIndex] else lowerIndex[axisIndex]) * stride
			stride *= grid.keys[axisIndex].size
		}

		if (weight == 0f) {
			continue
		}

		val cell = grid.cells.getOrNull(linear)?.get(channel) ?: return null
		val target = result ?: FloatArray(cell.size).also { created -> result = created }

		if (target.size != cell.size) {
			return null
		}

		for (component in cell.indices) {
			target[component] += weight * cell[component]
		}
	}

	return result
}

/** Whether two attribute values agree: float arrays within the tolerance, everything else by equality. */
private fun sameAttribute(expected: Any?, actual: Any?): Boolean =
	if (expected is FloatArray && actual is FloatArray) {
		relativeDeviation(expected, actual) <= FIDELITY_RELATIVE_TOLERANCE
	} else {
		expected == actual
	}

/** A value as a failure message shows it, with arrays abbreviated. */
private fun shown(value: Any?): String =
	when (value) {
		is FloatArray -> "[${value.size} floats]"
		is List<*> -> if (value.size > 8) "[${value.size} items]" else value.toString()
		else -> value.toString()
	}

/** Compares the render-order groups on the objects both documents carry, group by group. */
private fun compareRenderTrees(reference: MocDocument, candidate: MocDocument, divergences: MutableList<MocDivergence>) {
	val referenceTree = renderTreeOf(reference)
	val candidateTree = renderTreeOf(candidate)
	val sharedLabels = (referenceTree.keys + referenceTree.values.flatten()).toSet().intersect((candidateTree.keys + candidateTree.values.flatten()).toSet())

	for ((owner, referenceChildren) in referenceTree) {
		val candidateChildren = candidateTree[owner] ?: continue
		val expected = referenceChildren.filter { label -> label in sharedLabels }
		val actual = candidateChildren.filter { label -> label in sharedLabels }

		if (expected != actual) {
			divergences.add(MocDivergence(MocObjectKind.RENDER_TREE, owner, DivergenceAspect.RenderTree, "${shown(expected)} -> ${shown(actual)}"))
		}
	}
}

/**
 * Compares each parent's sub-parts in part-table order, on the sub-parts both documents place under that
 * parent.  A part missing from one side, or moved to another parent, is reported once already, as Missing,
 * Extra, or its parent part, so it is left out here rather than read as a reorder of its old siblings.
 */
private fun comparePartTrees(reference: MocDocument, candidate: MocDocument, divergences: MutableList<MocDivergence>) {
	val referenceTree = partTreeOf(reference)
	val candidateTree = partTreeOf(candidate)

	for ((parent, referenceChildren) in referenceTree) {
		val candidateChildren = candidateTree[parent] ?: continue
		val shared = referenceChildren.toSet().intersect(candidateChildren.toSet())
		val expected = referenceChildren.filter { partId -> partId in shared }
		val actual = candidateChildren.filter { partId -> partId in shared }

		if (expected != actual) {
			val position = expected.indices.firstOrNull { index -> expected[index] != actual.getOrNull(index) } ?: expected.size
			divergences.add(
				MocDivergence(
					MocObjectKind.PART_TREE,
					parent,
					DivergenceAspect.PartOrder,
					"first difference at $position: ${expected.getOrNull(position)} -> ${actual.getOrNull(position)}; ${shown(expected)} -> ${shown(actual)}",
				),
			)
		}
	}
}

/**
 * Each part's sub-part ids in part-table order, keyed `part:` and the parent's id, or by [ROOT_PART_TREE]
 * for the parts at the root.  A parent index outside the table counts as the root, as the import reads it.
 */
private fun partTreeOf(document: MocDocument): Map<String, List<String>> =
	document.parts.groupBy(
		keySelector = { part -> document.parts.getOrNull(part.parentPartIndex)?.id?.let { parentId -> "part:$parentId" } ?: ROOT_PART_TREE },
		valueTransform = { part -> part.id },
	)

/**
 * Each render-order group's children by label, keyed by the part that owns the group, or by a root
 * ordinal for a group no part owns.
 */
private fun renderTreeOf(document: MocDocument): Map<String, List<String>> {
	val ownerByGroup = HashMap<Int, String>()

	for (group in document.renderOrderGroups) {
		for (child in group.children) {
			if (child.kind == 1) {
				ownerByGroup[child.groupIndex] = "part:" + document.parts.getOrNull(child.index)?.id
			}
		}
	}

	var rootCount = 0

	return document.renderOrderGroups
		.mapIndexed { groupIndex, group ->
			(ownerByGroup[groupIndex] ?: "ROOT${rootCount++}") to
				group.children.map { child ->
					if (child.kind == 0) {
						"mesh:" + document.artMeshes.getOrNull(child.index)?.id
					} else {
						"part:" + document.parts.getOrNull(child.index)?.id
					}
				}
		}.toMap()
}

/** A color as a three-component channel, or null when the keyform carries none. */
private fun rgbChannel(color: Rgb?): FloatArray? = color?.let { present -> floatArrayOf(present.r, present.g, present.b) }

/** The non-null channels of one keyform, by name. */
private fun channelsOf(vararg pairs: Pair<String, FloatArray?>): Map<String, FloatArray> =
	buildMap {
		for ((name, value) in pairs) {
			if (value != null) {
				put(name, value)
			}
		}
	}

/** Every object of [document] in comparable form: parameters, parts, deformers, art meshes, glues, offscreens, and blend shapes. */
private fun fidelityObjectsOf(document: MocDocument): List<FidelityObject> {
	/** The grid of one object, its keyforms laid over its binding's axes. */
	fun gridOf(bindingIndex: Int, cells: List<Map<String, FloatArray>>): FidelityGrid {
		val axes = document.keyformBinding(bindingIndex)?.axes.orEmpty()
		val size = axes.fold(1) { product, axis -> product * axis.keyPositions.size }

		return if (cells.size == size) {
			FidelityGrid(axes.map { axis -> document.parameters[axis.parameterIndex].id }, axes.map { axis -> axis.keyPositions }, cells, false)
		} else {
			FidelityGrid(emptyList(), emptyList(), cells.take(1), cells.size != 1)
		}
	}

	fun partId(index: Int) = document.parts.getOrNull(index)?.id

	fun deformerId(index: Int) = document.deformers.getOrNull(index)?.id

	fun meshId(index: Int) = document.artMeshes.getOrNull(index)?.id

	val objects = ArrayList<FidelityObject>()

	for (parameter in document.parameters) {
		objects.add(
			FidelityObject(
				MocObjectKind.PARAMETER,
				parameter.id,
				mapOf(
					MocAttribute.MINIMUM to parameter.minimumValue,
					MocAttribute.MAXIMUM to parameter.maximumValue,
					MocAttribute.DEFAULT to parameter.defaultValue,
					MocAttribute.TYPE to parameter.type,
					MocAttribute.REPEAT to parameter.repeats,
				),
				null,
			),
		)
	}

	for (part in document.parts) {
		objects.add(
			FidelityObject(
				MocObjectKind.PART,
				part.id,
				mapOf(MocAttribute.PARENT_PART to partId(part.parentPartIndex), MocAttribute.VISIBLE to part.isVisible),
				gridOf(part.keyformBindingIndex, part.drawOrderKeyforms.map { drawOrder -> mapOf(MocChannel.DRAW_ORDER to floatArrayOf(drawOrder)) }),
			),
		)
	}

	for (deformer in document.deformers) {
		val shared =
			mapOf(
				MocAttribute.PARENT_PART to partId(deformer.parentPartIndex),
				MocAttribute.PARENT_DEFORMER to deformerId(deformer.parentDeformerIndex),
				MocAttribute.VISIBLE to deformer.isVisible,
				MocAttribute.ENABLED to deformer.isEnabled,
			)

		when (deformer) {
			is WarpDeformer -> {
				objects.add(
					FidelityObject(
						MocObjectKind.WARP,
						deformer.id,
						shared + mapOf(MocAttribute.LATTICE to "${deformer.rows}x${deformer.columns}", MocAttribute.MODE to deformer.mode),
						gridOf(
							deformer.keyformBindingIndex,
							deformer.keyforms.map { keyform ->
								channelsOf(
									MocChannel.LATTICE to keyform.controlPoints,
									MocChannel.OPACITY to floatArrayOf(keyform.opacity),
									MocChannel.MULTIPLY_COLOR to rgbChannel(keyform.multiplyColor),
									MocChannel.SCREEN_COLOR to rgbChannel(keyform.screenColor),
								)
							},
						),
					),
				)
			}

			is RotationDeformer -> {
				objects.add(
					FidelityObject(
						MocObjectKind.ROTATION,
						deformer.id,
						shared + mapOf(MocAttribute.BASE_ANGLE to deformer.baseAngle),
						gridOf(
							deformer.keyformBindingIndex,
							deformer.keyforms.map { keyform ->
								channelsOf(
									MocChannel.ORIGIN to floatArrayOf(keyform.originX, keyform.originY),
									MocChannel.ANGLE to floatArrayOf(keyform.angle),
									MocChannel.SCALE to floatArrayOf(keyform.scale),
									MocChannel.FLIP to floatArrayOf(if (keyform.reflectX) 1f else 0f, if (keyform.reflectY) 1f else 0f),
									MocChannel.OPACITY to floatArrayOf(keyform.opacity),
									MocChannel.MULTIPLY_COLOR to rgbChannel(keyform.multiplyColor),
									MocChannel.SCREEN_COLOR to rgbChannel(keyform.screenColor),
								)
							},
						),
					),
				)
			}
		}
	}

	for (mesh in document.artMeshes) {
		objects.add(
			FidelityObject(
				MocObjectKind.ART_MESH,
				mesh.id,
				mapOf(
					MocAttribute.TEXTURE to mesh.textureIndex,
					MocAttribute.FLAGS to mesh.constantFlags,
					MocAttribute.EXTENDED_BLEND to mesh.extendedBlend,
					MocAttribute.VISIBLE to mesh.isVisible,
					MocAttribute.ENABLED to mesh.isEnabled,
					MocAttribute.PARENT_PART to partId(mesh.parentPartIndex),
					MocAttribute.PARENT_DEFORMER to deformerId(mesh.parentDeformerIndex),
					MocAttribute.VERTEX_COUNT to mesh.vertexCount,
					MocAttribute.UVS to mesh.vertexUvs,
					MocAttribute.TRIANGLES to mesh.triangleIndices.toList(),
					// The import drops a mask column's -1 placeholders and the export does not invent them back.
					MocAttribute.MASKS to mesh.maskDrawableIndices.filter { index -> index >= 0 }.mapNotNull { index -> meshId(index) }.sorted(),
				),
				gridOf(
					mesh.keyformBindingIndex,
					mesh.keyforms.map { keyform ->
						channelsOf(
							MocChannel.POSITIONS to keyform.vertexPositions,
							MocChannel.OPACITY to floatArrayOf(keyform.opacity),
							MocChannel.DRAW_ORDER to floatArrayOf(keyform.drawOrder),
							MocChannel.MULTIPLY_COLOR to rgbChannel(keyform.multiplyColor),
							MocChannel.SCREEN_COLOR to rgbChannel(keyform.screenColor),
						)
					},
				),
			),
		)
	}

	for (glue in document.glues) {
		// Keyed by its meshes, so a glue whose id changed is still matched and its id reported.
		objects.add(
			FidelityObject(
				MocObjectKind.GLUE,
				"${meshId(glue.meshAIndex)}+${meshId(glue.meshBIndex)}",
				mapOf(MocAttribute.ID to glue.id, MocAttribute.PAIRS to glue.pairs),
				gridOf(glue.keyformBindingIndex, glue.intensityKeyforms.map { intensity -> mapOf(MocChannel.INTENSITY to floatArrayOf(intensity)) }),
			),
		)
	}

	for (offscreen in document.offscreens) {
		// An offscreen's keyforms ride its owner part's grid (docs/format/MOC3.md §5.6).
		val owner = document.parts.getOrNull(offscreen.ownerPartIndex)
		objects.add(
			FidelityObject(
				MocObjectKind.OFFSCREEN,
				"offscreen@${owner?.id}",
				mapOf(
					MocAttribute.FLAGS to offscreen.constantFlags,
					MocAttribute.BLEND_MODE to offscreen.blendMode,
					MocAttribute.MASK_COUNT to offscreen.maskCount,
					MocAttribute.MASKS to offscreen.maskIndices.filter { index -> index >= 0 }.mapNotNull { index -> meshId(index) }.sorted(),
				),
				gridOf(
					owner?.keyformBindingIndex ?: 0,
					offscreen.keyforms.map { keyform ->
						channelsOf(
							MocChannel.OPACITY to floatArrayOf(keyform.opacity),
							MocChannel.MULTIPLY_COLOR to rgbChannel(keyform.multiplyColor),
							MocChannel.SCREEN_COLOR to rgbChannel(keyform.screenColor),
						)
					},
				),
			),
		)
	}

	val ordinalByBlendShape = HashMap<String, Int>()

	for (blendShape in document.blendShapes) {
		val parameterId = document.parameters.getOrNull(blendShape.parameterIndex)?.id
		val targetId =
			when (blendShape.target) {
				BlendShapeTarget.WARP, BlendShapeTarget.ROTATION -> deformerId(blendShape.targetIndex)
				BlendShapeTarget.ART_MESH -> meshId(blendShape.targetIndex)
				BlendShapeTarget.PART -> partId(blendShape.targetIndex)
			}
		val baseId = blendShapeObjectId(blendShape.target, targetId, parameterId)
		val ordinal = ordinalByBlendShape.merge(baseId, 1, Int::plus) ?: 1
		val cells =
			blendShape.keyforms.map { keyform ->
				when (keyform) {
					is BlendShapeKeyform.Mesh -> {
						channelsOf(
							MocChannel.DELTA to keyform.form.vertexPositions + floatArrayOf(keyform.form.opacity, keyform.form.drawOrder),
							MocChannel.MULTIPLY_COLOR_DELTA to rgbChannel(keyform.form.multiplyColor),
							MocChannel.SCREEN_COLOR_DELTA to rgbChannel(keyform.form.screenColor),
						)
					}

					is BlendShapeKeyform.Warp -> {
						channelsOf(
							MocChannel.DELTA to keyform.form.controlPoints + floatArrayOf(keyform.form.opacity),
							MocChannel.MULTIPLY_COLOR_DELTA to rgbChannel(keyform.form.multiplyColor),
							MocChannel.SCREEN_COLOR_DELTA to rgbChannel(keyform.form.screenColor),
						)
					}

					is BlendShapeKeyform.Rotation -> {
						channelsOf(
							MocChannel.DELTA to floatArrayOf(keyform.form.originX, keyform.form.originY, keyform.form.angle, keyform.form.scale, keyform.form.opacity),
							MocChannel.MULTIPLY_COLOR_DELTA to rgbChannel(keyform.form.multiplyColor),
							MocChannel.SCREEN_COLOR_DELTA to rgbChannel(keyform.form.screenColor),
						)
					}

					is BlendShapeKeyform.Part -> {
						mapOf(MocChannel.DELTA to floatArrayOf(keyform.drawOrderDelta))
					}
				}
			}
		val wellFormed = cells.isNotEmpty() && cells.size == blendShape.keyPositions.size && parameterId != null
		objects.add(
			FidelityObject(
				MocObjectKind.BLEND_SHAPE,
				if (ordinal == 1) baseId else "$baseId~$ordinal",
				mapOf(
					MocAttribute.NEUTRAL_KEY to blendShape.neutralKeyIndex,
					MocAttribute.LIMITS to
						blendShape.limits
							.map { limit -> listOf(document.parameters.getOrNull(limit.parameterIndex)?.id, limit.keyPositions.toList(), limit.weights.toList()).toString() }
							.sorted(),
				),
				if (wellFormed) {
					FidelityGrid(listOf("blend:$parameterId"), listOf(blendShape.keyPositions), cells, false)
				} else {
					FidelityGrid(emptyList(), emptyList(), cells.take(1), true)
				},
			),
		)
	}

	return objects
}