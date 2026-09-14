package org.umamo.edit

/*
 * The merge's face on the operation settings strip: its one setting, where the survivor lands, as a
 * choice row the rigger can switch after the fact.  The keys are declared here, in the module that
 * runs the merge, so the UI's label table names constants rather than repeating strings.
 */

/** The parameter key and the choice label-key prefix the merge's row carries. */
object MergeParameterKeys {
	/** The Merge At row: which vertex the survivor lands on. */
	const val TARGET = "merge.target"

	/** The label-key prefix of the row's choices; a target's [MergeTarget.parameterKey] follows it. */
	const val TARGET_CHOICE_PREFIX = "merge.target."
}

/** The stable choice key of this target, as the strip's row stores it. */
val MergeTarget.parameterKey: String
	get() =
		when (this) {
			MergeTarget.AtCenter -> "center"
			MergeTarget.AtFirst -> "first"
			MergeTarget.AtLast -> "last"
		}

/**
 * The strip's rows for a merge that landed at [target]: the Merge At choice over every target.
 *
 * @param MergeTarget target Where the survivor landed.
 * @return List The one row.
 */
fun mergeParameters(target: MergeTarget): List<OperatorParameter> =
	listOf(
		OperatorParameter.ChoiceParameter(
			MergeParameterKeys.TARGET,
			MergeParameterKeys.TARGET,
			target.parameterKey,
			MergeTarget.entries.map { candidate -> ParameterChoice(candidate.parameterKey, MergeParameterKeys.TARGET_CHOICE_PREFIX + candidate.parameterKey) },
		),
	)

/**
 * The target the strip's rows name, or [fallback] when the row is absent or names no known target.
 *
 * @param List        parameters The strip's rows.
 * @param MergeTarget fallback   The target the merge first ran with.
 * @return MergeTarget The target to re-merge at.
 */
fun mergeTargetOf(parameters: List<OperatorParameter>, fallback: MergeTarget): MergeTarget {
	val key = parameters.choiceValue(MergeParameterKeys.TARGET, fallback.parameterKey)
	return MergeTarget.entries.firstOrNull { candidate -> candidate.parameterKey == key } ?: fallback
}