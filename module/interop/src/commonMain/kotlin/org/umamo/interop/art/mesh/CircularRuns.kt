package org.umamo.interop.art.mesh

/**
 * The maximal runs of one value in a sequence of flags, each as its indices in order.  Around a
 * circular sequence the reading starts just after an entry of the other value, so no run is cut in two
 * where the sequence starts; a circular sequence holding only the value is one run from index 0.
 *
 * @param BooleanArray flags    The flags.
 * @param Boolean      wanted   The value the runs hold.
 * @param Boolean      circular Whether the last entry is followed by the first.
 * @return List<IntArray> The runs, in reading order.
 */
internal fun runsOf(flags: BooleanArray, wanted: Boolean, circular: Boolean): List<IntArray> {
	val count = flags.size
	val start = if (circular && count > 0) (flags.indexOfFirst { flag -> flag != wanted } + 1) % count else 0
	val runs = ArrayList<IntArray>()
	var runStart = -1

	for (position in 0..count) {
		val holds = position < count && flags[(start + position) % count] == wanted

		if (holds && runStart < 0) {
			runStart = position
		} else if (!holds && runStart >= 0) {
			val first = runStart
			runs.add(IntArray(position - first) { offset -> (start + first + offset) % count })
			runStart = -1
		}
	}

	return runs
}