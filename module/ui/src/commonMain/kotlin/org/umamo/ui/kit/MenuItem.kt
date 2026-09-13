package org.umamo.ui.kit

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.umamo.ui.theme.UmamoIcon

/**
 * How tall a search menu's rows grow before they scroll, unless its [MenuItem.Search] says otherwise.
 * A picker over a document's whole layer inventory would otherwise take the general rule - rows up to
 * the window's height - and a menu that tall fits neither below nor above its chip, so the popup is
 * clamped to the window's bottom edge and opens far from the chip until a search shrinks it.
 */
val MENU_SEARCH_ROWS_MAX_HEIGHT: Dp = 320.dp

/**
 * One entry in a [Menu]: a clickable action, a nested submenu, a separator, a section heading, or a
 * search box.  This is a data model, not a Compose slot DSL - a caller builds a List<MenuItem>
 * (resolving localized chrome via stringResource at the call site and passing user data such as file
 * names verbatim) and one renderer draws every menu identically across the menu bar, context menus,
 * the area type selector, and the searchable pickers.
 *
 * The label is a plain String rather than a StringResource because menus mix localized chrome ("Open")
 * with user data (a recent-file name, a space title) that has no StringResource; the call site resolves
 * the chrome and supplies the data.  An [Action] carries an onSelect lambda rather than a command id
 * because not every item is command-bound (the area selector emits an AreaCommand, a recent-file row
 * calls open(path), Exit calls onExit); command binding layers on top via commandMenuItem(...).
 *
 * メニュー 1 項目。アクション・入れ子サブメニュー・区切りのいずれか。スロット DSL ではなくデータ。
 */
sealed interface MenuItem {
	/**
	 * A selectable row: an optional leading [icon], the [label], an optional [shortcut] accelerator
	 * right-aligned, running [onSelect] on click.  A disabled row renders dimmed and neither hovers nor
	 * responds to a click.  When any sibling row in the same panel carries an icon, icon-less rows indent
	 * their label to the same column so labels stay aligned.
	 *
	 * 選択可能な行。任意の先頭アイコン、ラベル、右に任意のショートカット、クリックで onSelect を実行する。
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
	 * ホバー（デスクトップ）またはタップ（タッチ）で入れ子フライアウトを開く行。矢印付き。
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
	 * A search box pinned above the rows, taking focus when the menu opens so typing starts at once.
	 * The menu does no filtering: the caller keeps the query, filters the rows it passes, and hands the
	 * query back here - the model stays a plain list, and the ranking rule (a name match, a whole file
	 * when the file name matches, the palette's ranker) stays where the data is.  One per menu, listed
	 * first; the panel pins every Search item above the rows whatever its position.
	 *
	 * The menu takes [width] as its own, fixed: a search menu that re-hugged its widest surviving row
	 * would resize under the user's hands on every keystroke.  Its rows scroll past [maxRowsHeight], a
	 * cap well under the window so the menu stays anchored under its chip however long the unfiltered
	 * list is (see [MENU_SEARCH_ROWS_MAX_HEIGHT]).
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
 * メニューバーの最上位項目。ラベルをクリックすると items を真下にメニューとして開く。
 *
 * @property String label The menu-bar label.
 * @property List items The menu opened beneath the label.
 */
data class TopLevelMenu(
	val label: String,
	val items: List<MenuItem>,
)