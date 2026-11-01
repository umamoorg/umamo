package org.umamo.ui.kit.menu

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.umamo.ui.theme.UmamoIcon

/**
 * How tall a search menu's rows grow before they scroll, unless its [MenuItem.Search] says otherwise:
 * well under the window, so a picker over a whole layer inventory still fits below or above its chip and
 * opens anchored to it.
 */
val MENU_SEARCH_ROWS_MAX_HEIGHT: Dp = 320.dp

/**
 * One entry in a [Menu]: a clickable action, a nested submenu, a separator, a section heading, or a
 * search box.  This is a data model, not a Compose slot DSL, so one renderer draws every menu
 * identically.
 *
 * The label is a plain String rather than a StringResource because menus mix localized chrome ("Open")
 * with user data (a recent-file name, a space title) that has no StringResource; the call site resolves
 * the chrome and supplies the data.  An [Action] carries an onSelect lambda rather than a command id
 * because not every item is command-bound (the area selector emits an AreaCommand, a recent-file row
 * passes its path); command binding layers on top (the app menu's commandRow).
 */
sealed interface MenuItem {
	/**
	 * A selectable row: an optional leading [icon], the [label], an optional [shortcut] accelerator
	 * right-aligned, running [onSelect] on click.  A disabled row renders dimmed and ignores clicks.
	 *
	 * @property String label The display text (already-resolved chrome or user data).
	 * @property Function onSelect Invoked when the row is chosen.
	 * @property String? shortcut The right-aligned accelerator hint (e.g. "Ctrl+O"), or null for none.
	 * @property Boolean enabled Whether the row is interactive.
	 * @property UmamoIcon? icon The leading glyph, or null for a text-only row.
	 */
	data class Action(
		val label: String,
		val onSelect: () -> Unit,
		val shortcut: String? = null,
		val enabled: Boolean = true,
		val icon: UmamoIcon? = null,
	) : MenuItem

	/**
	 * A row that opens a nested flyout of [items], marked with a trailing arrow.  On desktop the flyout
	 * opens on hover; on a touch screen (no hover) it opens on tap.  A disabled submenu cannot open.
	 *
	 * @property String label The display text.
	 * @property List items The submenu entries.
	 * @property Boolean enabled Whether the submenu can be opened.
	 */
	data class Submenu(
		val label: String,
		val items: List<MenuItem>,
		val enabled: Boolean = true,
	) : MenuItem

	/** A horizontal rule separating groups of rows. */
	data object Separator : MenuItem

	/**
	 * A muted caption over the rows that follow it - a file name over its layers, a group's title over
	 * its entries.  Inert: it neither hovers nor clicks nor dismisses, and it ignores the icon column the
	 * action rows share.  One line, ellipsized, because a heading naming a document object (an artwork
	 * file) can be arbitrarily long and must not widen the menu the rows beneath it are sized for.
	 *
	 * @property String label The heading text.
	 */
	data class Heading(
		val label: String,
	) : MenuItem

	/**
	 * A search box pinned above the rows, taking focus when the menu opens so typing starts at once.  One
	 * per menu.  The menu does no filtering: the caller keeps the query and filters the rows it passes, so
	 * the ranking rule stays where the data is.
	 *
	 * The menu takes [width] as its own, fixed: a search menu that re-hugged its widest surviving row
	 * would resize under the user's hands on every keystroke.
	 *
	 * @property String    value         The current query.
	 * @property Function  onValueChange Takes the edited query; the clear affordance reports an empty string.
	 * @property Dp        width         The menu's width, which the box fills less the row inset.
	 * @property String?   placeholder   The dimmed hint while the query is empty, or null for the kit's default.
	 * @property Dp        maxRowsHeight How tall the rows grow before they scroll.
	 */
	data class Search(
		val value: String,
		val onValueChange: (String) -> Unit,
		val width: Dp,
		val placeholder: String? = null,
		val maxRowsHeight: Dp = MENU_SEARCH_ROWS_MAX_HEIGHT,
	) : MenuItem
}

/**
 * One top-level menu-bar entry: a [label] (File, Help, …) that opens [items] as a [Menu] dropped
 * beneath it.
 *
 * @property String label The menu-bar label.
 * @property List items The menu opened beneath the label.
 */
data class TopLevelMenu(
	val label: String,
	val items: List<MenuItem>,
)