package org.umamo.ui.action

/**
 * Scores how well one command matches a palette query; lower is better, null is no match.
 *
 * The tiers keep the intuitive result first: a query that starts the visible label ("ac" ->
 * "Actual Size") outranks a label whose later word starts with it ("Zoom In" for "in"), which
 * outranks a mere substring hit ("Workspace" for "ac"), which outranks a match found only in the
 * dotted command id. Matching is case-insensitive.
 *
 * @param String label     The command's resolved, user-visible label.
 * @param String commandId The command's dotted id (e.g. "view.zoomIn").
 * @param String query     The typed query (non-blank).
 * @return Int? The match tier (0 label prefix, 1 label word start, 2 label substring, 3 id
 *   substring), or null when the command matches nowhere.
 */
internal fun commandMatchScore(label: String, commandId: String, query: String): Int? =
	when {
		label.startsWith(query, ignoreCase = true) -> 0
		label.split(' ').any { word -> word.startsWith(query, ignoreCase = true) } -> 1
		label.contains(query, ignoreCase = true) -> 2
		commandId.contains(query, ignoreCase = true) -> 3
		else -> null
	}

/**
 * Filters and ranks palette entries for a query: non-matches drop, the rest sort by match tier
 * (see [commandMatchScore]) with alphabetical label order breaking ties.
 *
 * @param List     entries The candidate entries, in registry order.
 * @param String   query   The typed query (non-blank).
 * @param Function labelOf Extracts an entry's user-visible label.
 * @param Function idOf    Extracts an entry's dotted command id.
 * @return List The matching entries, best first.
 */
internal fun <TEntry> rankCommandMatches(
	entries: List<TEntry>,
	query: String,
	labelOf: (TEntry) -> String,
	idOf: (TEntry) -> String,
): List<TEntry> =
	entries
		.mapNotNull { entry ->
			commandMatchScore(labelOf(entry), idOf(entry), query)?.let { score -> entry to score }
		}
		.sortedWith(compareBy({ scored -> scored.second }, { scored -> labelOf(scored.first).lowercase() }))
		.map { scored -> scored.first }
