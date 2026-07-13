package org.umamo.ui.workspace

import androidx.compose.runtime.compositionLocalOf

/* The 2D viewport's floating chrome state: whether the left tool toolbar and the right sidebar drawer
 * are shown.  The state itself lives in settings (interface.viewport.showToolbar / showSidebar) and is
 * provided by the settings-backed shell wrapper; the plain EditorShell stays Settings-free (its
 * documented contract), so the default here is what a standalone shell renders. */

/** The settings key backing the left tool toolbar's visibility. */
const val SHOW_TOOLBAR_SETTINGS_KEY = "interface.viewport.showToolbar"

/** The settings key backing the right sidebar drawer's visibility. */
const val SHOW_SIDEBAR_SETTINGS_KEY = "interface.viewport.showSidebar"

/**
 * The viewport chrome visibility flags, resolved from settings by the persistent shell wrapper (or the
 * defaults below in a standalone shell).  The view.toggleToolbar / view.toggleSidebar commands and the
 * sidebar's pull tab flip the backing settings; this state follows reactively.
 *
 * ビューポートの浮遊クローム（左ツールバー・右サイドバー）の表示状態。設定に連動する。
 *
 * @property Boolean showToolbar Whether the left tool toolbar overlay is shown.
 * @property Boolean showSidebar Whether the right sidebar drawer is open.
 */
data class ViewportChromeState(
	val showToolbar: Boolean = true,
	val showSidebar: Boolean = false,
)

/**
 * The active [ViewportChromeState].  Non-static: the persistent shell swaps the value on every settings
 * change, and readers (the viewport body) must recompose with it.
 */
val LocalViewportChrome = compositionLocalOf { ViewportChromeState() }
