package org.umamo.ui.workspace

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.dp
import org.umamo.ui.viewport.ViewportSettings

/* The application's defaults for the 2D viewport's floating chrome: whether the left tool toolbar and the
 * right sidebar drawer show in an area that has no choice of its own.  Each area keeps its own choice once
 * the rigger toggles it there (ChromeVisibility), and a saved document carries it.  The defaults live in
 * settings (ViewportSettings' SHOW_TOOLBAR_KEY / SHOW_SIDEBAR_KEY), set in Preferences, and are provided by
 * the settings-backed shell wrapper; the plain EditorShell reads no settings, so it renders ViewportSettings'
 * fallbacks.
 *
 * The operation strip's inset sits here too.  The strip host provides it and the viewport's zoom badge
 * reads it, so it is a seam between the two packages and belongs to neither. */

/**
 * The viewport chrome's application defaults, resolved from settings by the persistent shell wrapper (or
 * [ViewportSettings]' fallbacks in a standalone shell).  An area that has a choice of its own ignores them;
 * the view.toggleToolbar / view.toggleSidebar commands make such a choice for the hovered area.
 *
 * @property Boolean showToolbar Whether the left tool toolbar shows in an area with no choice of its own.
 * @property Boolean showSidebar Whether the right sidebar shows in an area with no choice of its own.
 */
data class ViewportChromeState(
	val showToolbar: Boolean = ViewportSettings.SHOW_TOOLBAR_DEFAULT,
	val showSidebar: Boolean = ViewportSettings.SHOW_SIDEBAR_DEFAULT,
)

/**
 * The active [ViewportChromeState] defaults.  Non-static: the persistent shell swaps the value on every
 * settings change, and readers (the viewport body, the sidebar) must recompose with it.
 */
val LocalViewportChrome = compositionLocalOf { ViewportChromeState() }

/**
 * How far an area's own bottom-left chrome must lift to clear the strip: the strip's height plus its
 * margin while one shows in that area, else zero.  Provided by the strip host around the space
 * body; the viewport's zoom badge reads it, so the two never overlap.
 */
val LocalOperationStripInset = compositionLocalOf { 0.dp }