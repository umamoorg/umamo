package org.umamo.ui.action

/**
 * Scores how well one command matches a palette query, case-insensitively; lower is better, null is no
 * match.
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

/**
 * Scores how well one shortcut row matches a configurator query, case-insensitively; lower is better, null is
 * no match.
 *
 * The tiers run outwards from the command's own name: a label prefix, a word start, a label substring, the
 * dotted id, the group the command is listed under, and finally the accelerator the command is bound to - so a
 * search for "ctrl" finds the commands that answer to a Control chord.
 *
 * @param String label       The command's resolved, user-visible label.
 * @param String commandId   The command's dotted id.
 * @param String groupLabel  The label of the group the command is listed under.
 * @param String shortcutText The command's bound accelerators, as display text.
 * @param String query       The typed query (non-blank).
 * @return Int? The match tier, or null when the command matches nowhere.
 */
internal fun shortcutMatchScore(
	label: String,
	commandId: String,
	groupLabel: String,
	shortcutText: String,
	query: String,
): Int? =
	when {
		label.startsWith(query, ignoreCase = true) -> 0
		label.split(' ').any { word -> word.startsWith(query, ignoreCase = true) } -> 1
		label.contains(query, ignoreCase = true) -> 2
		commandId.contains(query, ignoreCase = true) -> 3
		groupLabel.contains(query, ignoreCase = true) -> 4
		shortcutText.contains(query, ignoreCase = true) -> 5
		else -> null
	}

/**
 * Filters and ranks shortcut rows for a query: non-matches drop, the rest sort by match tier
 * (see [shortcutMatchScore]) with alphabetical label order breaking ties.
 *
 * @param List     entries The candidate rows.
 * @param String   query   The typed query (non-blank).
 * @param Function labelOf Extracts a row's user-visible label.
 * @param Function idOf    Extracts a row's dotted command id.
 * @param Function groupOf Extracts a row's group label.
 * @param Function shortcutOf Extracts a row's bound accelerators, as display text.
 * @return List The matching rows, best first.
 */
internal fun <TEntry> rankShortcutMatches(
	entries: List<TEntry>,
	query: String,
	labelOf: (TEntry) -> String,
	idOf: (TEntry) -> String,
	groupOf: (TEntry) -> String,
	shortcutOf: (TEntry) -> String,
): List<TEntry> =
	entries
		.mapNotNull { entry ->
			shortcutMatchScore(labelOf(entry), idOf(entry), groupOf(entry), shortcutOf(entry), query)?.let { score -> entry to score }
		}
		.sortedWith(compareBy({ scored -> scored.second }, { scored -> labelOf(scored.first).lowercase() }))
		.map { scored -> scored.first }