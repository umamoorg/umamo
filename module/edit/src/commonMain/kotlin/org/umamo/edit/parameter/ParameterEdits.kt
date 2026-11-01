package org.umamo.edit.parameter

import org.umamo.edit.EditorSession
import org.umamo.edit.ParameterChange
import org.umamo.edit.ParameterSelection
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.ParameterLink
import org.umamo.runtime.model.PuppetModel

/**
 * A copy of this model with parameter [id]'s range set to [min]..[max], normalized so the minimum never
 * exceeds the maximum whatever the entry order, and its default clamped into it.  The live pose is editor
 * state outside the model, so re-clamping it is the session's concern.
 *
 * @param ParameterId id The parameter to retarget.
 * @param Float min The requested minimum.
 * @param Float default The requested default (clamped into the resulting range).
 * @param Float max The requested maximum.
 * @return PuppetModel This model with the parameter's range and default updated, or this if unchanged.
 */
fun PuppetModel.withParameterRange(id: ParameterId, min: Float, default: Float, max: Float): PuppetModel {
	val low = minOf(min, max)
	val high = maxOf(min, max)
	val clampedDefault = default.coerceIn(low, high)
	val updated =
		parameters.map { parameter ->
			if (parameter.id == id) {
				parameter.copy(min = low, max = high, default = clampedDefault)
			} else {
				parameter
			}
		}

	if (updated == parameters) {
		return this
	}

	return copy(parameters = updated)
}

/**
 * A copy of this model with the parameter link [horizontal]->[vertical] added ([linked] = true) or
 * removed ([linked] = false).  This edit is the invariant keeper - any UI-side validity check is advisory
 * only - so a request that would pair a parameter twice, with itself, or with an unknown id returns this
 * same instance.
 *
 * // CMO3: CParameterSource.combined - the first member flags the pair, the partner is the next
 * // parameter in document order (see Cmo3Import's link derivation).
 *
 * @param ParameterId horizontal The X-axis (upper) parameter.
 * @param ParameterId vertical The Y-axis parameter (the next parameter below in panel order).
 * @param Boolean linked True to create the link, false to remove it.
 * @return PuppetModel This model with the link added / removed, or this same instance when refused.
 */
fun PuppetModel.withParameterLink(horizontal: ParameterId, vertical: ParameterId, linked: Boolean): PuppetModel {
	if (linked) {
		if (horizontal == vertical) {
			return this
		}

		val knownIds = parameters.map { parameter -> parameter.id }.toSet()

		if (horizontal !in knownIds || vertical !in knownIds) {
			return this
		}

		val linkedMembers = parameterLinks.flatMap { link -> listOf(link.horizontal, link.vertical) }.toSet()

		if (horizontal in linkedMembers || vertical in linkedMembers) {
			return this
		}

		return copy(parameterLinks = parameterLinks + ParameterLink(horizontal, vertical))
	}

	val remaining = parameterLinks.filterNot { link -> link.horizontal == horizontal && link.vertical == vertical }

	if (remaining.size == parameterLinks.size) {
		return this
	}

	return copy(parameterLinks = remaining)
}

/**
 * Sets parameter [id]'s range and default, and re-clamps its live pose value into the new range, as one
 * undo step so undo restores both together.
 *
 * @param ParameterId id The parameter to retarget.
 * @param Float min The requested minimum.
 * @param Float default The requested default (clamped into the resulting range).
 * @param Float max The requested maximum.
 */
fun EditorSession.setParameterRange(id: ParameterId, min: Float, default: Float, max: Float) {
	val newModel = model.value.withParameterRange(id, min, default, max)

	if (newModel === model.value) {
		return
	}

	// Re-clamp the live value into the resulting (normalized) range so the pose stays valid.
	val parameter = newModel.parameters.firstOrNull { it.id == id }
	val newPose =
		if (parameter != null) {
			val current = pose.value[id]
			val clamped = current?.coerceIn(parameter.min, parameter.max)

			if (clamped != null && clamped != current) {
				pose.value + (id to clamped)
			} else {
				pose.value
			}
		} else {
			pose.value
		}
	val change = ParameterChange.SetRange(id, parameter?.min ?: min, parameter?.default ?: default, parameter?.max ?: max)
	commitStep(change, model = newModel, pose = newPose)
}

/**
 * Links parameter [horizontal] with [vertical] (the next parameter below it in panel order) into
 * one 2D pad, or removes that link, as one undo step.  The pose is untouched: a link only changes how
 * the pair presents.
 *
 * @param ParameterId horizontal The X-axis (upper) parameter.
 * @param ParameterId vertical The Y-axis parameter.
 * @param Boolean linked True to create the link, false to remove it.
 */
fun EditorSession.setParameterLink(horizontal: ParameterId, vertical: ParameterId, linked: Boolean) {
	val newModel = model.value.withParameterLink(horizontal, vertical, linked)

	if (newModel === model.value) {
		return
	}

	val target = parameterSelection.value
	val narrowedTarget =
		if (!linked && target.ids.size > 1) {
			// A pad targets BOTH its axes; once they are two separate sliders that reads as a multi-selection
			// the panel cannot otherwise produce, so the target narrows to the one that was active.  Carried
			// in the SAME step, so the pushed snapshot holds the narrowed target and a later redo cannot
			// restore the multi-selection.
			target.active?.let { ParameterSelection.of(it) } ?: ParameterSelection()
		} else {
			target
		}
	commitStep(ParameterChange.SetLink(horizontal, vertical, linked), model = newModel, parameterSelection = narrowedTarget)
}