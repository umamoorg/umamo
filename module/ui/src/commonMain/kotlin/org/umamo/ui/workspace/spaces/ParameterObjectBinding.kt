package org.umamo.ui.workspace.spaces

import org.umamo.edit.Selection
import org.umamo.edit.SelectionTarget
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.Part
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.PuppetModel

/*
 * The object -> parameters relation behind the "show only the selected object's parameters" filter. A
 * parameter affects an object when it is an axis of a keyform grid that deforms it. Because a drawable is
 * usually deformed by its parent deformer chain rather than keyed directly, the EFFECTIVE set unions the
 * object's own axes with every ancestor deformer's axes (and, for a part, its member drawables' effective
 * sets). Compose-free so it can be unit-tested; every axis references one parameter by KeyformAxis.parameterId.
 *
 * 選択オブジェクトに影響するパラメータ集合（自身のキーフォーム軸＋親デフォーマ連鎖の軸）。
 */

/**
 * The set of parameter ids that effectively drive [selection] in [puppet]: for each selected target, the
 * union of the object's own keyform-grid axes and, following the deformer chain, its ancestor deformers'
 * axes. A selected part contributes its draw-order-group axes plus the effective set of every drawable it
 * contains. Empty when the selection is empty.
 *
 * @param PuppetModel puppet The loaded rig.
 * @param Selection selection The current selection (drawables / deformers / parts).
 * @return Set<ParameterId> Every parameter that affects any selected object.
 */
internal fun effectiveParameterIds(puppet: PuppetModel, selection: Selection): Set<ParameterId> {
	if (selection.isEmpty) {
		return emptySet()
	}
	val deformerById = puppet.deformers.associateBy { deformer -> deformer.id }
	val drawableById = puppet.drawables.associateBy { drawable -> drawable.id }
	val partById = puppet.parts.associateBy { part -> part.id }
	val result = HashSet<ParameterId>()

	// Walks the deformer nesting from [startDeformerId] up through parents, adding each deformer's axes
	// and blend-shape drivers. A visited guard defends against a malformed cyclic parent chain.
	fun addDeformerChainAxes(startDeformerId: DeformerId?) {
		val visited = HashSet<DeformerId>()
		var current = startDeformerId
		while (current != null && visited.add(current)) {
			val deformer = deformerById[current] ?: break
			deformer.keyformAxes().forEach { axis -> result.add(axis.parameterId) }
			deformer.blendShapeDrivers().forEach { parameterId -> result.add(parameterId) }
			current = deformer.parent
		}
	}

	// A drawable's effective set: its own mesh keyform axes and blend-shape drivers plus its whole
	// parent deformer chain's axes.
	fun addDrawableEffective(drawable: Drawable) {
		drawable.keyforms?.axes?.forEach { axis -> result.add(axis.parameterId) }
		drawable.blendShapes.forEach { binding -> result.add(binding.parameterId) }
		addDeformerChainAxes(drawable.parentDeformerId)
	}

	for (target in selection.targets) {
		when (target) {
			is SelectionTarget.Drawable -> drawableById[target.id]?.let { drawable -> addDrawableEffective(drawable) }
			// Start at the deformer itself so its own axes join its ancestors'.
			is SelectionTarget.Deformer -> addDeformerChainAxes(target.id)
			is SelectionTarget.Part ->
				partById[target.id]?.let { part ->
					part.drawOrderGrid?.axes?.forEach { axis -> result.add(axis.parameterId) }
					collectPartDrawables(part, partById).forEach { drawableId ->
						drawableById[drawableId]?.let { drawable -> addDrawableEffective(drawable) }
					}
				}
		}
	}
	return result
}

/**
 * A parameter's key marks: the parameter values it is keyed at, split by source. Grid keys come from
 * keyform-grid axes (rendered as circle points); blend keys come from blend-shape bindings (square
 * points). Each list is sorted ascending and deduplicated. A parameter with neither has no map entry.
 *
 * @property List gridKeys  The keyform-grid axis key values (circle points), sorted ascending.
 * @property List blendKeys The blend-shape binding key values (square points), sorted ascending.
 */
internal data class ParameterKeyMarks(
	val gridKeys: List<Float>,
	val blendKeys: List<Float>,
)

/**
 * The per-parameter key marks for this rig: for each parameter id keyed by any object, the sorted,
 * deduplicated union of the parameter values it is keyed at, split into keyform-grid keys and
 * blend-shape keys. One pass over every drawable, deformer, and part (the same objects
 * [effectiveParameterIds] walks); a parameter absent from the result has no keys yet. Compose-free so
 * the panel can compute it once per model and unit-test it.
 *
 * @return Map<ParameterId, ParameterKeyMarks> Each keyed parameter's grid + blend key marks.
 */
internal fun PuppetModel.parameterKeyMarks(): Map<ParameterId, ParameterKeyMarks> {
	val gridKeysByParameter = HashMap<ParameterId, MutableSet<Float>>()
	val blendKeysByParameter = HashMap<ParameterId, MutableSet<Float>>()

	fun addGridKeys(parameterId: ParameterId, keys: FloatArray) {
		val values = gridKeysByParameter.getOrPut(parameterId) { sortedSetOf() }
		keys.forEach { keyValue -> values.add(keyValue) }
	}

	fun addBlendKeys(parameterId: ParameterId, keys: FloatArray) {
		val values = blendKeysByParameter.getOrPut(parameterId) { sortedSetOf() }
		keys.forEach { keyValue -> values.add(keyValue) }
	}

	for (drawable in drawables) {
		drawable.keyforms?.axes?.forEach { axis -> addGridKeys(axis.parameterId, axis.keys) }
		drawable.blendShapes.forEach { binding -> addBlendKeys(binding.parameterId, binding.keys) }
	}
	for (deformer in deformers) {
		deformer.keyformAxes().forEach { axis -> addGridKeys(axis.parameterId, axis.keys) }
		deformer.blendShapeBindingKeys().forEach { (parameterId, keys) -> addBlendKeys(parameterId, keys) }
	}
	for (part in parts) {
		part.drawOrderGrid?.axes?.forEach { axis -> addGridKeys(axis.parameterId, axis.keys) }
	}

	val keyedParameters = gridKeysByParameter.keys + blendKeysByParameter.keys
	return keyedParameters.associateWith { parameterId ->
		ParameterKeyMarks(
			gridKeys = gridKeysByParameter[parameterId]?.toList() ?: emptyList(),
			blendKeys = blendKeysByParameter[parameterId]?.toList() ?: emptyList(),
		)
	}
}

/** This deformer's keyform axes (empty when it is unkeyed), regardless of kind. */
private fun Deformer.keyformAxes(): List<KeyformAxis> =
	when (this) {
		is Deformer.Warp -> keyforms?.axes ?: emptyList()
		is Deformer.Rotation -> keyforms?.axes ?: emptyList()
	}

/** This deformer's blend-shape bindings as (driving parameter, key values) pairs (empty when none). */
private fun Deformer.blendShapeBindingKeys(): List<Pair<ParameterId, FloatArray>> =
	when (this) {
		is Deformer.Warp -> blendShapes.map { binding -> binding.parameterId to binding.keys }
		is Deformer.Rotation -> blendShapes.map { binding -> binding.parameterId to binding.keys }
	}

/**
 * The parameters driving this deformer's blend-shape bindings (empty when it has none). Limit-curve
 * constraint parameters deliberately do NOT count as edges - a limit gates a binding's weight but
 * does not key the object itself.
 */
private fun Deformer.blendShapeDrivers(): List<ParameterId> =
	when (this) {
		is Deformer.Warp -> blendShapes.map { binding -> binding.parameterId }
		is Deformer.Rotation -> blendShapes.map { binding -> binding.parameterId }
	}

/** Every drawable id under [part]'s organisational subtree, descending into sub-parts. */
private fun collectPartDrawables(part: Part, partById: Map<PartId, Part>): List<DrawableId> {
	val result = ArrayList<DrawableId>()

	fun walk(children: List<OrgChild>) {
		for (child in children) {
			when (child) {
				is OrgChild.Drawable -> result.add(child.id)
				is OrgChild.Part -> partById[child.id]?.let { subPart -> walk(subPart.children) }
			}
		}
	}
	walk(part.children)
	return result
}
