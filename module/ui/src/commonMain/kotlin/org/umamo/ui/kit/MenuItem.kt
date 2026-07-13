package org.umamo.ui.kit

import org.umamo.ui.theme.UmamoIcon

/**
 * One entry in a [Menu]: a clickable action, a nested submenu, or a separator.  This is a data model,
 * not a Compose slot DSL - a caller builds a List<MenuItem> (resolving localized chrome via
 * stringResource at the call site and passing user data such as file names verbatim) and one renderer
 * draws every menu identically across the menu bar, context menus, and the area type selector.
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
