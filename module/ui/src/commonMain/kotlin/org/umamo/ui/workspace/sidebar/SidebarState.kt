package org.umamo.ui.workspace.sidebar

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.umamo.ui.sidebar.SidebarPanel
import org.umamo.ui.workspace.AreaHub
import org.umamo.ui.workspace.ChromeVisibility

/** The narrowest the sidebar's panel stack may be dragged. */
internal val SIDEBAR_MIN_WIDTH = 160.dp

/** The panel stack's width until the rigger drags it. */
internal val SIDEBAR_DEFAULT_WIDTH = 200.dp

/** The widest the sidebar's panel stack may be dragged. */
internal val SIDEBAR_MAX_WIDTH = 480.dp

/**
 * What the rest of an area keeps beside the open sidebar's panel stack: room for the toolbar on the left
 * edge (its 6 dp margin, its 34 dp strip, and a gap), the pull tab, and the tab rail.
 */
internal val SIDEBAR_AREA_RESERVE = 48.dp + 14.dp + 28.dp

/**
 * One area's sidebar: whether it is open, how wide its panel stack is, which tab it shows, and which panels
 * the rigger folded.  The work surface hosting the sidebar keeps one per area, so two viewports each
 * remember their own, and a saved document carries it.
 */
class SidebarState {
	/** Whether the sidebar is open: the area's own choice, else the application's default. */
	val visibility = ChromeVisibility()

	/** The panel stack's width as the rigger dragged it, before it is fitted to the area. */
	var width: Dp by mutableStateOf(SIDEBAR_DEFAULT_WIDTH)

	/**
	 * The tab the rigger last chose, or null before any choice.  Kept as chosen even while that tab is not
	 * shown, so a tab that comes back (a contributor reloaded, a selection made again) is shown again.
	 */
	var activeTabId: String? by mutableStateOf(null)

	/**
	 * The panels the rigger opened or folded against their default, by panel id.  An absent entry means the
	 * panel's own default, so the map holds only deviations; an entry for a panel no contributor registers
	 * right now is kept as it was read.
	 */
	internal val expandedByPanel = mutableStateMapOf<String, Boolean>()

	/**
	 * The ids of the tabs on the rail right now, top to bottom, or empty while the sidebar is closed.  Written
	 * by the drawer after each composition and read only when a command runs, so it is plain rather than
	 * snapshot state: nothing draws from it.
	 */
	var shownTabIds: List<String> = emptyList()

	/** Whether everything sits at its default: no open choice, the default width, no tab chosen, and no panel folded. */
	val isAtDefaults: Boolean
		get() = visibility.choice == null && width == SIDEBAR_DEFAULT_WIDTH && activeTabId == null && expandedByPanel.isEmpty()

	/**
	 * Puts everything back to its default, as an area that never had a sidebar choice made in it.
	 */
	fun reset() {
		visibility.choice = null
		width = SIDEBAR_DEFAULT_WIDTH
		activeTabId = null
		expandedByPanel.clear()
	}

	/**
	 * The tab to show among [shown]: the rigger's choice when it is shown, else the first.  The choice itself
	 * is left as it was.
	 *
	 * @param List shown The ids of the tabs on the rail, top to bottom.
	 * @return String? The tab to show, or null with no tab shown.
	 */
	fun resolvedActiveTab(shown: List<String>): String? {
		val chosen = activeTabId

		return if (chosen != null && chosen in shown) chosen else shown.firstOrNull()
	}

	/**
	 * Chooses the tab [step] places from the one shown, wrapping past either end of the rail.  Does nothing
	 * while no tab is shown.
	 *
	 * @param Int step How many tabs to move; positive moves down the rail.
	 */
	fun selectAdjacentTab(step: Int) {
		val shown = shownTabIds
		val current = resolvedActiveTab(shown) ?: return
		val currentIndex = shown.indexOf(current)
		activeTabId = shown[(currentIndex + step).mod(shown.size)]
	}

	/**
	 * Whether [panel] is open: the rigger's choice for it, else its own default.
	 *
	 * @param SidebarPanel panel The panel.
	 * @return Boolean True when its rows show.
	 */
	fun isExpanded(panel: SidebarPanel): Boolean = expandedByPanel[panel.id] ?: !panel.initiallyCollapsed

	/**
	 * Opens [panel] if it is folded and folds it if it is open.  Back at its own default, the panel keeps no
	 * entry, so only real deviations are saved.
	 *
	 * @param SidebarPanel panel The panel.
	 */
	fun toggleExpanded(panel: SidebarPanel) {
		val expanded = !isExpanded(panel)

		if (expanded == !panel.initiallyCollapsed) {
			expandedByPanel.remove(panel.id)
		} else {
			expandedByPanel[panel.id] = expanded
		}
	}
}

/**
 * The panel stack's width in an area [areaWidth] wide: the rigger's [chosen] width, held between the
 * narrowest the stack may be and the widest the area leaves it, or null when the area cannot fit even the
 * narrowest stack (the sidebar then shows only its pull tab, staying open for when the area grows).
 *
 * @param Dp chosen The width the rigger dragged the stack to.
 * @param Dp areaWidth The width of the area the sidebar sits in.
 * @return Dp? The width to lay the stack out at, or null when it does not fit.
 */
internal fun sidebarStackWidth(chosen: Dp, areaWidth: Dp): Dp? {
	val room = minOf(SIDEBAR_MAX_WIDTH, areaWidth - SIDEBAR_AREA_RESERVE)

	if (room < SIDEBAR_MIN_WIDTH) {
		return null
	}

	return chosen.coerceIn(SIDEBAR_MIN_WIDTH, room)
}

/**
 * The registry from an area id to its live [SidebarState]: each work surface registers its area's state for
 * the area's lifetime, and the sidebar commands resolve the hovered area here when they run.
 */
internal typealias AreaSidebarHub = AreaHub<SidebarState>

/**
 * The shell's area sidebar hub, or null outside an editor shell (previews, tests).  Work surfaces register
 * their areas; the sidebar commands resolve the hovered area when they run.
 */
internal val LocalAreaSidebarHub = staticCompositionLocalOf<AreaSidebarHub?> { null }