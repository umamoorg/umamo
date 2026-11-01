package org.umamo.edit.structure

import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.PuppetModel

/*
 * The one Blender-style naming rule every id and display name an edit mints takes: a base name, then its first free
 * ".NNN"-suffixed form when the base is taken, so repeated duplicates and creations never collide.
 */

/**
 * The first free ".NNN"-suffixed form of [base] (d -> d.001, d.002, ...).
 *
 * @param String base The source id or name.
 * @param Function taken Whether a candidate is already in use.
 * @return String The first free candidate.
 */
internal fun firstFreeSuffixed(base: String, taken: (String) -> Boolean): String {
	var counter = 1

	while (true) {
		val candidate = "$base.${counter.toString().padStart(3, '0')}"

		if (!taken(candidate)) {
			return candidate
		}

		counter++
	}
}

/**
 * A deformer id for a new deformer: [base] itself when no deformer has it, else its first free ".NNN" form.
 *
 * @param String base The base id.
 * @return DeformerId The free id.
 */
fun PuppetModel.freshDeformerId(base: String): DeformerId {
	val taken = { candidate: String -> deformerById.containsKey(DeformerId(candidate)) }

	return DeformerId(if (taken(base)) firstFreeSuffixed(base, taken) else base)
}

/**
 * A display name for a new deformer: [base] itself when no deformer is named it, else its first free ".NNN" form.
 *
 * @param String base The base name.
 * @return String The free name.
 */
fun PuppetModel.freshDeformerName(base: String): String {
	val taken = { candidate: String -> deformers.any { deformer -> deformer.name == candidate } }

	return if (taken(base)) firstFreeSuffixed(base, taken) else base
}