package org.umamo.ui.kit.container

/*
 * The search rule every searchable stack of SectionCards shares (the Properties panel, the keybindings page): which
 * cards a query leaves standing, and which of their rows.  Pure so it is unit-testable without Compose; the
 * composable caller resolves the localized titles and row terms, and this decides the match.
 */

/**
 * Reports whether any string in [haystack] contains [query] (case-insensitive); a blank query matches everything.
 *
 * @param List haystack The already-resolved localized strings to test.
 * @param String query The user's search text.
 * @return Boolean True when the query is blank or any haystack entry contains it.
 */
fun matchesQuery(haystack: List<String>, query: String): Boolean {
	val trimmed = query.trim()

	if (trimmed.isEmpty()) {
		return true
	}

	return haystack.any { candidate -> candidate.contains(trimmed, ignoreCase = true) }
}

/**
 * Whether a section is shown for a query, and which of its rows to draw.  A section that built no rows is never
 * shown: it has nothing to say, and a header-only card is noise.  Otherwise a blank query shows the whole section, a
 * query that matches the section's title also shows every row (the whole section is relevant), and any other query
 * shows only the rows whose terms match, hiding the section entirely when none do.
 *
 * @property Boolean shown Whether the section appears at all.
 * @property List visibleRowIndices The indices (into the section's row list) to draw, in order.
 */
class SectionVisibility(val shown: Boolean, val visibleRowIndices: List<Int>)

/**
 * Resolves per-row search visibility for one section from its already-localized [title] and [rowTerms] (one term
 * list per row).
 *
 * @param String title The section's localized heading.
 * @param List rowTerms Each row's localized search terms, in row order.
 * @param String query The user's search text.
 * @return SectionVisibility Whether the section shows and which rows to draw.
 */
fun sectionVisibility(title: String, rowTerms: List<List<String>>, query: String): SectionVisibility {
	// A section that built no rows has no content to show, and a title match must not resurrect it, or searching
	// would surface an empty card.  This is how a section opts out where it does not apply (Transform against a
	// Part or a warp deformer).
	if (rowTerms.isEmpty()) {
		return SectionVisibility(false, emptyList())
	}

	val trimmed = query.trim()
	val allRows = rowTerms.indices.toList()

	if (trimmed.isEmpty() || matchesQuery(listOf(title), trimmed)) {
		return SectionVisibility(true, allRows)
	}

	val matching = allRows.filter { index -> matchesQuery(rowTerms[index], trimmed) }

	return SectionVisibility(matching.isNotEmpty(), matching)
}