package org.umamo.ui.workspace

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Whether one area shows a piece of its chrome, such as the 2D viewport's toolbar or sidebar: the area's own
 * choice once the rigger toggled it there, else the application's default for areas that have none.  The
 * choice stays the area's once made, even when it matches the default, so changing the default later never
 * flips an area the rigger set.
 */
class ChromeVisibility {
	/** The area's own choice, or null while the area follows the application's default. */
	var choice: Boolean? by mutableStateOf(null)

	/**
	 * Whether the chrome shows: the area's choice, else [default].
	 *
	 * @param Boolean default The application's default for areas with no choice of their own.
	 * @return Boolean True when the chrome shows.
	 */
	fun isShown(default: Boolean): Boolean = choice ?: default

	/**
	 * Hides the chrome if it shows and shows it if it is hidden, making that the area's own choice.
	 *
	 * @param Boolean default The application's default for areas with no choice of their own.
	 */
	fun toggle(default: Boolean) {
		choice = !isShown(default)
	}
}

/**
 * The registry from a 2D viewport area id to its toolbar's [ChromeVisibility]: each viewport registers its
 * area's for the area's lifetime, and view.toggleToolbar resolves the hovered area here at dispatch time.
 */
internal typealias AreaToolbarHub = AreaHub<ChromeVisibility>

/**
 * The shell's area toolbar hub, or null outside an editor shell (previews, tests).  Viewports register their
 * areas; the toolbar toggle resolves the hovered area at dispatch.
 */
internal val LocalAreaToolbarHub = staticCompositionLocalOf<AreaToolbarHub?> { null }