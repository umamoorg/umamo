package org.umamo.ui.settings

import org.umamo.ui.action.KeyChord
import org.umamo.ui.kit.container.SectionVisibility
import org.umamo.ui.kit.container.sectionVisibility

/** What the keybindings page is searching for: typed text, or a chord the rigger pressed to look up. */
internal sealed interface KeybindingSearch {
	/**
	 * A typed query, matched against each command's name and its shown shortcut; blank when not searching.
	 *
	 * @property String query The text in the search field.
	 */
	data class Text(val query: String) : KeybindingSearch

	/**
	 * A recorded press: the page narrows to the command that press runs.
	 *
	 * @property KeyChord chord The chord the rigger pressed.
	 */
	data class Chord(val chord: KeyChord) : KeybindingSearch
}

/**
 * Whether [this] search narrows the page at all.
 *
 * @return Boolean False only for a blank text query.
 */
internal fun KeybindingSearch.isActive(): Boolean =
	when (this) {
		is KeybindingSearch.Text -> query.isNotBlank()
		is KeybindingSearch.Chord -> true
	}

/**
 * One command row as the search sees it.
 *
 * @property String commandId The command's id.
 * @property String title The command's localized name.
 * @property String chordLabel The text the row's shortcut chip shows, "Unbound" included, so a search for what the
 *   chip says finds the row.
 */
internal class KeybindingSearchRow(val commandId: String, val title: String, val chordLabel: String)

/**
 * Which rows of one category card a search leaves standing.  Text follows the section search rule (a category
 * title match shows the whole card); a recorded chord keeps only the command it runs, wherever it sits.
 *
 * @param String title The category's localized heading.
 * @param List rows The card's rows, in order.
 * @param KeybindingSearch search The current search.
 * @param String? firingCommandId The command a recorded chord runs (what dispatch resolves it to), or null when it
 *   runs none; read only for a chord search.
 * @return SectionVisibility Whether the card shows and which rows to draw.
 */
internal fun keybindingCardVisibility(title: String, rows: List<KeybindingSearchRow>, search: KeybindingSearch, firingCommandId: String?): SectionVisibility =
	when (search) {
		is KeybindingSearch.Text -> {
			sectionVisibility(title, rows.map { row -> listOf(row.title, row.chordLabel) }, search.query)
		}

		is KeybindingSearch.Chord -> {
			val firing = rows.indices.filter { rowIndex -> rows[rowIndex].commandId == firingCommandId }

			SectionVisibility(firing.isNotEmpty(), firing)
		}
	}