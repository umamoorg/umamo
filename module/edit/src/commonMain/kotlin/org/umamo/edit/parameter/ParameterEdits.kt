package org.umamo.edit.parameter

import org.umamo.edit.EditorSession
import org.umamo.edit.ParameterChange
import org.umamo.edit.ParameterSelection
import org.umamo.runtime.eval.wrapOrClamp
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.ParameterLink
import org.umamo.runtime.model.PuppetModel

/**
 * A copy of this model with parameter [id]'s range set to [min]..[max], normalized so the minimum never
 * exceeds the maximum whatever the entry order, and its default clamped into it.  The live pose is editor
 * state outside the model, so bringing it back into range is the session's concern.
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
 * A copy of this model with parameter [id]'s repeat set to [repeat].
 *
 * @param ParameterId id The parameter to change.
 * @param Boolean repeat Whether the axis wraps past its limits.
 * @return PuppetModel This model with the flag updated, or this same instance when it already holds or [id] is unknown.
 */
fun PuppetModel.withParameterRepeat(id: ParameterId, repeat: Boolean): PuppetModel {
	if (parameters.none { parameter -> parameter.id == id && parameter.repeat != repeat }) {
		return this
	}

	return copy(parameters = parameters.map { parameter -> if (parameter.id == id) parameter.copy(repeat = repeat) else parameter })
}

/**
 * A copy of this model with parameter [id]'s note set to [note], verbatim.
 *
 * @param ParameterId id The parameter to change.
 * @param String note The note, empty for none.
 * @return PuppetModel This model with the note updated, or this same instance when it already holds or [id] is unknown.
 */
fun PuppetModel.withParameterNote(id: ParameterId, note: String): PuppetModel {
	if (parameters.none { parameter -> parameter.id == id && parameter.note != note }) {
		return this
	}

	return copy(parameters = parameters.map { parameter -> if (parameter.id == id) parameter.copy(note = note) else parameter })
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

/** Whether the parameter has a range to move through.  A flat one drives nothing, so an editor gives it no control. */
val Parameter.isAnimatable: Boolean get() = max > min

/**
 * The ids of this model's animatable parameters.
 *
 * @return Set<ParameterId> The ids.
 */
fun PuppetModel.animatableParameterIds(): Set<ParameterId> = parameters.filter { parameter -> parameter.isAnimatable }.mapTo(HashSet()) { parameter -> parameter.id }

/**
 * The links whose two members are both animatable: the ones that can be driven as one two-axis control.  A link
 * with a flat member stays in the model, and still occupies both its members, but is not one of these.
 *
 * @param Set<ParameterId> animatable The model's animatable ids, for a caller that already holds them.
 * @return List<ParameterLink> The links, in the model's order.
 */
fun PuppetModel.animatableLinks(animatable: Set<ParameterId> = animatableParameterIds()): List<ParameterLink> =
	parameterLinks.filter { link -> link.horizontal in animatable && link.vertical in animatable }

/**
 * Sets parameter [id]'s range and default, and brings its live pose value back into the new range, as one
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

	// The live value comes back into the resulting (normalized) range the way the evaluator reads it, so the
	// pose stays valid: clamped on a normal axis, wrapped on a repeating one.
	val parameter = newModel.parameters.firstOrNull { it.id == id }
	val newPose =
		if (parameter != null) {
			val current = pose.value[id]
			val inRange = current?.let { value -> parameter.wrapOrClamp(value) }

			if (inRange != null && inRange != current) {
				pose.value + (id to inRange)
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
 * Turns parameter [id]'s repeat on or off as one undo step.  The pose is untouched, since a value inside the
 * range is valid on either kind of axis, and a request that changes nothing records no step.
 *
 * @param ParameterId id The parameter to change.
 * @param Boolean repeat Whether the axis wraps past its limits.
 */
fun EditorSession.setParameterRepeat(id: ParameterId, repeat: Boolean) {
	mutate(ParameterChange.SetRepeat(id, repeat)) { current -> current.withParameterRepeat(id, repeat) }
}

/**
 * Sets parameter [id]'s note from what the rigger typed, as one undo step.  The pose is untouched, and a
 * request that changes nothing records no step.
 *
 * @param ParameterId id The parameter to change.
 * @param String note The typed note; whitespace alone clears it.
 */
fun EditorSession.setParameterNote(id: ParameterId, note: String) {
	val typed = typedNote(note)
	mutate(ParameterChange.SetNote(id, typed)) { current -> current.withParameterNote(id, typed) }
}

/** A line break in any platform's form, each of which a typed note holds as one space. */
private val NOTE_LINE_BREAK = Regex("\r\n|\r|\n")

/**
 * The note a typed text becomes: one paragraph with its ends trimmed.  A note holds no line breaks, and an
 * imported note never passes through here, so it keeps whatever edge whitespace it was written with.
 *
 * @param String text The text as typed.
 * @return String The note to store.
 */
private fun typedNote(text: String): String = text.replace(NOTE_LINE_BREAK, " ").trim()

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