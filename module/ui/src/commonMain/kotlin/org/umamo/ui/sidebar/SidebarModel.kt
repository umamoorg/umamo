package org.umamo.ui.sidebar

import androidx.compose.runtime.Composable
import org.umamo.edit.EditorMode
import org.umamo.edit.EditorSession
import org.umamo.edit.MeshSelection
import org.umamo.edit.Selection
import org.umamo.edit.SelectionTarget
import org.umamo.runtime.model.PuppetModel
import org.umamo.ui.l10n.UiText
import org.umamo.ui.workspace.SpaceKind

/*
 * What a work surface's sidebar is made of: tabs down its outer edge, each switching a stack of collapsible
 * panels.  Umamo's own tabs and panels and, later, a plugin's are registered the same way, so every id is
 * namespaced: Umamo's start with "umamo.", and a plugin's start with the plugin's own id.
 */

/**
 * One tab on a sidebar's rail.  A tab shows only while at least one of its panels is visible, so a tab whose
 * panels all bow out (or whose contributor is gone) never leaves an empty tab behind.
 *
 * @property String id The tab's namespaced identity ("umamo.item").
 * @property UiText label The tab's label.
 * @property Int order Where the tab sits on the rail, lowest first; registration order breaks a tie.
 */
class SidebarTab(
	val id: String,
	val label: UiText,
	val order: Int,
)

/**
 * One collapsible panel in a tab's stack.  It names its tab by id, so a contributor can add a panel to any
 * registered tab, Umamo's own included.
 *
 * @property String id The panel's namespaced identity ("umamo.item.transform"), which also keys its fold state.
 * @property String tabId The tab the panel shows under.
 * @property UiText title The panel's heading.
 * @property Int order Where the panel sits in its tab's stack, lowest first; registration order breaks a tie.
 * @property Set spaces The work surfaces whose sidebar shows the panel.
 * @property Boolean initiallyCollapsed Whether the panel starts folded.
 * @property Function isVisible Whether the panel shows for a context, such as only with something selected.
 * @property Function content The panel's rows, drawn from the context.
 */
class SidebarPanel(
	val id: String,
	val tabId: String,
	val title: UiText,
	val order: Int,
	val spaces: Set<SpaceKind>,
	val initiallyCollapsed: Boolean = false,
	val isVisible: (SidebarContext) -> Boolean = { true },
	val content: @Composable (SidebarContext) -> Unit,
)

/**
 * The snapshot a sidebar's panels read from, rebuilt each recomposition from the open document and session
 * so a panel reads the model, mode, and selections from it rather than resolving them itself.
 *
 * @property String areaId The area the sidebar belongs to.
 * @property SpaceKind space The work surface the area shows.
 * @property PuppetModel puppet The open document's model.
 * @property EditorMode mode The editor mode.
 * @property Selection selection The object selection.
 * @property SelectionTarget? shownTarget The item a one-item panel shows: the active item, else the one last
 *   made active, else any selected item, or null when there is none (see shownTargetOf).
 * @property MeshSelection meshSelection The Edit-mode element selection.
 * @property EditorSession? session The editing session an editable row writes through, or null with no session.
 */
class SidebarContext(
	val areaId: String,
	val space: SpaceKind,
	val puppet: PuppetModel,
	val mode: EditorMode,
	val selection: Selection,
	val shownTarget: SelectionTarget?,
	val meshSelection: MeshSelection,
	val session: EditorSession?,
)

/**
 * A tab as a sidebar shows it: the tab and its visible panels, in stack order.
 *
 * @property SidebarTab tab The tab.
 * @property List panels The tab's visible panels, top to bottom; never empty.
 */
class ResolvedSidebarTab(
	val tab: SidebarTab,
	val panels: List<SidebarPanel>,
)

/** A registered tab or panel, withdrawn by [dispose]. */
fun interface SidebarRegistration {
	/**
	 * Withdraws what was registered.  Withdrawing twice, or after a newer registration replaced it, does nothing.
	 */
	fun dispose()
}