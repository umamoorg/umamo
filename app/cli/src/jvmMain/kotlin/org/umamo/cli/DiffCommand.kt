package org.umamo.cli

import org.umamo.interop.EntityDiff
import org.umamo.interop.GlueDiff
import org.umamo.interop.diffPuppetModels
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.ParameterGroupId
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PartId

/*
 * The diff subcommand: the semantic per-entity difference between two models, through the same
 * diffPuppetModels the CMO3 export reconcile dispatches on.
 */

/**
 * Runs `diff <a> <b>`: imports both files (either format, both normalized to canvas space by the
 * import path) and prints the per-category difference.  Exit stays 0 either way - a diff is a
 * report, not a failure.
 *
 * @param List arguments The subcommand's arguments.
 * @return Int The exit code.
 */
internal fun runDiff(arguments: List<String>): Int {
	if (arguments.size != 2) {
		throw CliUsageException("Usage: diff <a> <b>")
	}
	val baseline = importPuppet(loadInput(arguments[0]))
	val edited = importPuppet(loadInput(arguments[1]))
	val diff = diffPuppetModels(baseline, edited)
	if (diff.isEmpty) {
		println("identical")
		return 0
	}
	printCategory("parameters", diff.parameters)
	printCategory("parameterGroups", diff.parameterGroups)
	printCategory("parts", diff.parts)
	printCategory("deformers", diff.deformers)
	printCategory("drawables", diff.drawables)
	if (diff.glues.isNotEmpty()) {
		println("[glues]")
		for (glueDiff in diff.glues) {
			val key = "${glueDiff.meshA.raw}~${glueDiff.meshB.raw}#${glueDiff.ordinal}"
			when (glueDiff) {
				is GlueDiff.Created -> println("+ $key")
				is GlueDiff.Deleted -> println("- $key")
				is GlueDiff.Changed -> println("~ $key ${glueDiff.fields.sorted()}")
			}
		}
	}
	if (diff.document.isNotEmpty()) {
		println("[document]")
		println("~ ${diff.document.sorted()}")
	}
	return 0
}

/**
 * Prints one entity category's diffs: `+ id` created, `- id` deleted, `~ id [FIELDS]` changed.
 *
 * @param String label The category heading.
 * @param List diffs   The category's entries (skipped entirely when empty).
 */
private fun <TId, TField : Comparable<TField>> printCategory(label: String, diffs: List<EntityDiff<TId, TField>>) {
	if (diffs.isEmpty()) {
		return
	}
	println("[$label]")
	for (entityDiff in diffs) {
		when (entityDiff) {
			is EntityDiff.Created -> println("+ ${formatEntityId(entityDiff.id)}")
			is EntityDiff.Deleted -> println("- ${formatEntityId(entityDiff.id)}")
			is EntityDiff.Changed -> println("~ ${formatEntityId(entityDiff.id)} ${entityDiff.fields.sorted()}")
		}
	}
}

/**
 * The bare id string of a typed entity id - the value-class toString ("PartId(raw=...)") is noise
 * in a report meant for humans and diff scripts.
 *
 * @param Any? id The typed entity id.
 * @return String The raw id text.
 */
private fun formatEntityId(id: Any?): String =
	when (id) {
		is ParameterId -> id.raw
		is ParameterGroupId -> id.raw
		is PartId -> id.raw
		is DeformerId -> id.raw
		is DrawableId -> id.raw
		else -> id.toString()
	}