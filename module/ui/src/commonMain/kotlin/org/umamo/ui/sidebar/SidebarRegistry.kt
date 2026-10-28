package org.umamo.ui.sidebar

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * A registered tab or panel with the order it was registered in, which breaks ties in its declared order.
 *
 * @property ItemT item The tab or panel.
 * @property Long sequence When it was registered, relative to every other registration in the registry.
 */
private class Registered<ItemT>(val item: ItemT, val sequence: Long)

/**
 * The tabs and panels every sidebar in a window draws from.  Umamo's built-ins and, later, plugins register
 * here at runtime and withdraw through the handle a registration returns; the sidebars read it in
 * composition and follow every change.
 *
 * Registration happens on the main thread, as the command registry's does; a contributor running elsewhere
 * hands its calls to the main thread first.
 */
class SidebarRegistry {
	/** The registered tabs by id. */
	private val tabsById = mutableStateMapOf<String, Registered<SidebarTab>>()

	/** The registered panels by id. */
	private val panelsById = mutableStateMapOf<String, Registered<SidebarPanel>>()

	/** The next registration's sequence. */
	private var nextSequence = 0L

	/**
	 * Registers [tab], replacing a tab registered under the same id.
	 *
	 * @param SidebarTab tab The tab.
	 * @return SidebarRegistration The handle that withdraws this tab, and only this one.
	 */
	fun register(tab: SidebarTab): SidebarRegistration {
		val registered = Registered(tab, nextSequence++)
		tabsById[tab.id] = registered

		return SidebarRegistration {
			if (tabsById[tab.id] === registered) {
				tabsById.remove(tab.id)
			}
		}
	}

	/**
	 * Registers [panel], replacing a panel registered under the same id.
	 *
	 * @param SidebarPanel panel The panel.
	 * @return SidebarRegistration The handle that withdraws this panel, and only this one.
	 */
	fun register(panel: SidebarPanel): SidebarRegistration {
		val registered = Registered(panel, nextSequence++)
		panelsById[panel.id] = registered

		return SidebarRegistration {
			if (panelsById[panel.id] === registered) {
				panelsById.remove(panel.id)
			}
		}
	}

	/**
	 * The tabs a sidebar shows for [context], in rail order, each with its visible panels in stack order.  A
	 * panel shows when its tab is registered, its surfaces include the context's, and it is visible for the
	 * context; a tab shows when at least one of its panels does.
	 *
	 * @param SidebarContext context The sidebar's context.
	 * @return List The tabs to show, top to bottom.
	 */
	fun tabsFor(context: SidebarContext): List<ResolvedSidebarTab> {
		val visiblePanelsByTab =
			panelsById.values
				.filter { registered ->
					val panel = registered.item
					panel.tabId in tabsById && context.space in panel.spaces && panel.isVisible(context)
				}
				.sortedWith(compareBy({ registered -> registered.item.order }, { registered -> registered.sequence }))
				.groupBy({ registered -> registered.item.tabId }, { registered -> registered.item })

		return tabsById.values
			.filter { registered -> registered.item.id in visiblePanelsByTab }
			.sortedWith(compareBy({ registered -> registered.item.order }, { registered -> registered.sequence }))
			.map { registered -> ResolvedSidebarTab(registered.item, visiblePanelsByTab.getValue(registered.item.id)) }
	}
}

/**
 * The window's sidebar registry, or null outside an editor shell (previews, tests), where a sidebar shows no tabs.
 */
val LocalSidebarRegistry = staticCompositionLocalOf<SidebarRegistry?> { null }

/**
 * A sidebar registry with Umamo's own tabs and panels registered once, held for the caller's lifetime.  The
 * app holds one per window, so swapping documents never registers the built-ins again.
 *
 * @return SidebarRegistry The registry.
 */
@Composable
fun rememberSidebarRegistry(): SidebarRegistry = remember { SidebarRegistry().also { registry -> registerBuiltInSidebar(registry) } }