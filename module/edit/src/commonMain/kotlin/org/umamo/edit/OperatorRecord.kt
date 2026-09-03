package org.umamo.edit

/*
 * The record behind "adjust the last operation": Blender's redo strip, without Blender's undo-and-rerun.
 *
 * An operation that has settings runs at once with its defaults and registers one of these.  Editing
 * a parameter re-runs the operation from the base snapshot the record holds and lands the result over
 * the operation's own history step (History.amendTop), so an adjustment costs one run of the operation
 * and nothing is replayed.  Everything here is plain data the UI renders and the session owns; nothing
 * about Compose or any one operation may appear in this file.
 */

/** The unit a numeric parameter is shown in. */
enum class ParameterUnit {
	None,
	Pixels,
	Degrees,
	Percent,
}

/**
 * One editable setting of an adjustable operation.
 *
 * Carries a stable [key] the operation reads its value back by and a [labelKey] the UI maps to a
 * localized label (the [Change.labelKey] convention, so this module stays presentation-free).  The
 * value is typed per kind; the UI renders one row per kind and never needs to know the operation.
 */
sealed interface OperatorParameter {
	/** The stable identity the operation reads the value back by. */
	val key: String

	/** The stable key the UI resolves to the row's localized label. */
	val labelKey: String

	/**
	 * A real-valued setting with a range, a scrub step, and a display unit.
	 *
	 * @property String        key      See [OperatorParameter.key].
	 * @property String        labelKey See [OperatorParameter.labelKey].
	 * @property Float         value    The current value.
	 * @property Float         min      The smallest value the row accepts.
	 * @property Float         max      The largest value the row accepts.
	 * @property Float         step     The scrub / chevron increment.
	 * @property ParameterUnit unit     The display unit.
	 */
	data class FloatParameter(
		override val key: String,
		override val labelKey: String,
		val value: Float,
		val min: Float,
		val max: Float,
		val step: Float = 1f,
		val unit: ParameterUnit = ParameterUnit.None,
	) : OperatorParameter

	/**
	 * An integer setting with a range, a scrub step, and a display unit.
	 *
	 * @property String        key      See [OperatorParameter.key].
	 * @property String        labelKey See [OperatorParameter.labelKey].
	 * @property Int           value    The current value.
	 * @property Int           min      The smallest value the row accepts.
	 * @property Int           max      The largest value the row accepts.
	 * @property Int           step     The scrub / chevron increment.
	 * @property ParameterUnit unit     The display unit.
	 */
	data class IntParameter(
		override val key: String,
		override val labelKey: String,
		val value: Int,
		val min: Int,
		val max: Int,
		val step: Int = 1,
		val unit: ParameterUnit = ParameterUnit.None,
	) : OperatorParameter

	/**
	 * An on / off setting.
	 *
	 * @property String  key      See [OperatorParameter.key].
	 * @property String  labelKey See [OperatorParameter.labelKey].
	 * @property Boolean value    The current value.
	 */
	data class BooleanParameter(
		override val key: String,
		override val labelKey: String,
		val value: Boolean,
	) : OperatorParameter

	/**
	 * A pick from a closed list of choices.
	 *
	 * @property String key      See [OperatorParameter.key].
	 * @property String labelKey See [OperatorParameter.labelKey].
	 * @property String value    The key of the chosen entry of [choices].
	 * @property List   choices  The entries, in display order.
	 */
	data class ChoiceParameter(
		override val key: String,
		override val labelKey: String,
		val value: String,
		val choices: List<ParameterChoice>,
	) : OperatorParameter
}

/**
 * One entry of a [OperatorParameter.ChoiceParameter].
 *
 * @property String  key      The stable value the operation reads back.
 * @property String? labelKey The key the UI resolves to the entry's label, or null to show [key] verbatim
 *   (a page size, a number the rigger recognises).
 */
data class ParameterChoice(
	val key: String,
	val labelKey: String? = null,
)

/**
 * The parameter list with the entry at [key] replaced by [updated].
 *
 * @param String            key     The entry to replace.
 * @param OperatorParameter updated Its replacement (normally a copy with a new value).
 * @return List The list with that one entry swapped; unchanged when no entry has the key.
 */
fun List<OperatorParameter>.withParameter(key: String, updated: OperatorParameter): List<OperatorParameter> =
	map { parameter -> if (parameter.key == key) updated else parameter }

/**
 * The integer value of the parameter at [key], or [fallback] when absent or of another kind.
 *
 * @param String key      The entry to read.
 * @param Int    fallback The value when the list carries no such integer.
 * @return Int The value.
 */
fun List<OperatorParameter>.intValue(key: String, fallback: Int): Int =
	(firstOrNull { parameter -> parameter.key == key } as? OperatorParameter.IntParameter)?.value ?: fallback

/**
 * The boolean value of the parameter at [key], or [fallback] when absent or of another kind.
 *
 * @param String  key      The entry to read.
 * @param Boolean fallback The value when the list carries no such flag.
 * @return Boolean The value.
 */
fun List<OperatorParameter>.booleanValue(key: String, fallback: Boolean): Boolean =
	(firstOrNull { parameter -> parameter.key == key } as? OperatorParameter.BooleanParameter)?.value ?: fallback

/**
 * The chosen key of the choice parameter at [key], or [fallback] when absent or of another kind.
 *
 * @param String key      The entry to read.
 * @param String fallback The value when the list carries no such choice.
 * @return String The chosen entry's key.
 */
fun List<OperatorParameter>.choiceValue(key: String, fallback: String): String =
	(firstOrNull { parameter -> parameter.key == key } as? OperatorParameter.ChoiceParameter)?.value ?: fallback

/**
 * The one operation that may still be adjusted: what it committed, what it ran from, its current
 * settings, and how to run it again.
 *
 * Immutable; an adjustment replaces the session's record with [withParameters], and the two share an
 * [identity] so a rerun launched against the earlier value still lands (see
 * [EditorSession.amendLastCommit]).  The [baseSnapshot] is held here rather than looked up in the
 * history because a cap of one drops it from the stack the moment the operation pushes.
 *
 * @property Change         change       The change the operation committed; its label titles the strip.
 * @property String?        areaId       The area the operation ran in, or null for one that ran nowhere in
 *   particular (the shell shows it above the status bar).
 * @property List           parameters   The current settings, in display order.
 * @property EditorSnapshot baseSnapshot The state the operation ran from.
 * @property Function       rerun        Runs the operation again from [baseSnapshot] under the record it is
 *   given (whose [parameters] are the new values) and lands through [EditorSession.amendLastCommit].  The
 *   client's closure, which owns whatever expensive inputs it retained and launches its own coroutine if
 *   it needs one.
 * @property Any            identity     Shared by every value of one registration, so a stale value can be
 *   told from a cleared record.
 */
class AdjustableOperation internal constructor(
	val change: Change,
	val areaId: String?,
	val parameters: List<OperatorParameter>,
	val baseSnapshot: EditorSnapshot,
	val rerun: (AdjustableOperation) -> Unit,
	internal val identity: Any,
) {
	/**
	 * This record with new settings, keeping everything else including the identity.
	 *
	 * @param List parameters The new settings.
	 * @return AdjustableOperation The updated record.
	 */
	fun withParameters(parameters: List<OperatorParameter>): AdjustableOperation =
		AdjustableOperation(change, areaId, parameters, baseSnapshot, rerun, identity)
}