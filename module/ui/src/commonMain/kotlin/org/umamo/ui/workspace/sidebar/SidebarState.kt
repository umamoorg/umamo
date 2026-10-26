package org.umamo.ui.workspace.sidebar

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import org.umamo.ui.sidebar.SidebarPanel
import org.umamo.ui.workspace.AreaHub

/**
 * One area's sidebar: which tab it shows and which panels the rigger folded.  The work surface hosting the
 * sidebar keeps one per area, so two viewports each remember their own.
 */
class SidebarState {
	/**
	 * The tab the rigger last chose, or null before any choice.  Kept as chosen even while that tab is not
	 * shown, so a tab that comes back (a contributor reloaded, a selection made again) is shown again.
	 */
	var activeTabId: String? by mutableStateOf(null)

	/**
	 * The panels the rigger opened or folded against their default, by panel id.  An absent entry means the
	 * panel's own default.
	 */
	private val expandedByPanel = mutableStateMapOf<String, Boolean>()

	/**
	 * The ids of the tabs on the rail right now, top to bottom, or empty while the sidebar is closed.  Written
	 * by the drawer after each composition and read only when a command runs, so it is plain rather than
	 * snapshot state: nothing draws from it.
	 */
	var shownTabIds: List<String> = emptyList()

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
	 * Opens [panel] if it is folded and folds it if it is open.
	 *
	 * @param SidebarPanel panel The panel.
	 */
	fun toggleExpanded(panel: SidebarPanel) {
		expandedByPanel[panel.id] = !isExpanded(panel)
	}
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