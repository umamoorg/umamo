package org.umamo.ui.sidebar

import org.umamo.edit.EditorMode
import org.umamo.edit.MeshSelection
import org.umamo.edit.Selection
import org.umamo.runtime.model.PuppetModel
import org.umamo.ui.l10n.UiText
import org.umamo.ui.workspace.SpaceKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The sidebar registry: which tabs and panels a sidebar shows, in which order, and what a registration's
 * handle withdraws.
 */
class SidebarRegistryTest {
	/**
	 * A context for a 2D viewport sidebar over an empty document.
	 *
	 * @param EditorMode mode The editor mode.
	 * @param SpaceKind space The work surface.
	 * @return SidebarContext The context.
	 */
	private fun context(mode: EditorMode = EditorMode.Object, space: SpaceKind = SpaceKind.Viewport2D): SidebarContext =
		SidebarContext(
			areaId = "area-1",
			space = space,
			puppet =
				PuppetModel(
					parameters = emptyList(),
					parts = emptyList(),
					deformers = emptyList(),
					drawables = emptyList(),
					rootChildren = emptyList(),
					rootPartId = null,
				),
			mode = mode,
			selection = Selection(),
			shownTarget = null,
			meshSelection = MeshSelection(),
			session = null,
		)

	/**
	 * A tab with a literal label.
	 *
	 * @param String id The tab's id.
	 * @param Int order Its order.
	 * @return SidebarTab The tab.
	 */
	private fun tab(id: String, order: Int = 100): SidebarTab = SidebarTab(id, UiText.Literal(id), order)

	/**
	 * A 2D viewport panel with a literal title and no rows.
	 *
	 * @param String id The panel's id.
	 * @param String tabId The tab it shows under.
	 * @param Int order Its order.
	 * @param Set spaces The surfaces it appears in.
	 * @param Function isVisible When it shows.
	 * @return SidebarPanel The panel.
	 */
	private fun panel(
		id: String,
		tabId: String,
		order: Int = 100,
		spaces: Set<SpaceKind> = setOf(SpaceKind.Viewport2D),
		isVisible: (SidebarContext) -> Boolean = { true },
	): SidebarPanel = SidebarPanel(id, tabId, UiText.Literal(id), order, spaces, isVisible = isVisible, content = {})

	/**
	 * The ids of the tabs a sidebar shows for [context], in rail order.
	 *
	 * @param SidebarRegistry registry The registry.
	 * @param SidebarContext context The context.
	 * @return List The tab ids.
	 */
	private fun shownTabIds(registry: SidebarRegistry, context: SidebarContext = context()): List<String> =
		registry.tabsFor(context).map { resolved -> resolved.tab.id }

	/** Tabs sit by their order, and registration order breaks a tie. */
	@Test
	fun tabsSortByOrderThenRegistration() {
		val registry = SidebarRegistry()

		for ((id, order) in listOf("late" to 300, "tieFirst" to 200, "early" to 100, "tieSecond" to 200)) {
			registry.register(tab(id, order))
			registry.register(panel("$id.panel", id))
		}

		assertEquals(listOf("early", "tieFirst", "tieSecond", "late"), shownTabIds(registry))
	}

	/** Panels sit in their tab's stack by their order, and registration order breaks a tie. */
	@Test
	fun panelsSortByOrderThenRegistration() {
		val registry = SidebarRegistry()
		registry.register(tab("item"))
		registry.register(panel("bottom", "item", order = 300))
		registry.register(panel("tieFirst", "item", order = 200))
		registry.register(panel("top", "item", order = 100))
		registry.register(panel("tieSecond", "item", order = 200))

		val panels = registry.tabsFor(context()).single().panels.map { shown -> shown.id }

		assertEquals(listOf("top", "tieFirst", "tieSecond", "bottom"), panels)
	}

	/** A tab shows only while one of its panels does, so a tab whose panels all bow out leaves no empty tab. */
	@Test
	fun aTabWithNoVisiblePanelIsHidden() {
		val registry = SidebarRegistry()
		registry.register(tab("item"))
		registry.register(tab("empty"))
		registry.register(panel("editOnly", "item", isVisible = { context -> context.mode == EditorMode.Edit }))

		assertEquals(emptyList(), shownTabIds(registry, context(EditorMode.Object)), "neither tab has a visible panel in Object mode")
		assertEquals(listOf("item"), shownTabIds(registry, context(EditorMode.Edit)), "the panel shows in Edit mode, and its tab with it")
	}

	/** A panel shows only in the surfaces it names, and only under a registered tab. */
	@Test
	fun aPanelNeedsItsSurfaceAndItsTab() {
		val registry = SidebarRegistry()
		registry.register(tab("item"))
		registry.register(panel("uvOnly", "item", spaces = setOf(SpaceKind.UvEditor)))
		registry.register(panel("orphan", "missingTab"))

		assertEquals(emptyList(), shownTabIds(registry), "the UV panel is not the 2D viewport's, and the orphan has no tab")
		assertEquals(listOf("item"), shownTabIds(registry, context(space = SpaceKind.UvEditor)), "a UV editor's sidebar shows the UV panel")
	}

	/** A handle withdraws what it registered, and withdrawing twice is harmless. */
	@Test
	fun aHandleWithdrawsItsRegistration() {
		val registry = SidebarRegistry()
		val tabHandle = registry.register(tab("item"))
		val panelHandle = registry.register(panel("transform", "item"))
		assertEquals(listOf("item"), shownTabIds(registry))

		panelHandle.dispose()
		panelHandle.dispose()

		assertTrue(shownTabIds(registry).isEmpty(), "the tab has no panel left")

		registry.register(panel("transform", "item"))
		tabHandle.dispose()

		assertTrue(shownTabIds(registry).isEmpty(), "the panel has no tab left")
	}

	/** A registration under an id already taken replaces the earlier one, whose handle no longer withdraws it. */
	@Test
	fun aReplacementSurvivesItsPredecessorsHandle() {
		val registry = SidebarRegistry()
		val firstTabHandle = registry.register(tab("item"))
		registry.register(SidebarTab("item", UiText.Literal("Replaced"), 100))
		registry.register(panel("transform", "item"))

		firstTabHandle.dispose()

		val shown = registry.tabsFor(context()).single()
		assertEquals(UiText.Literal("Replaced"), shown.tab.label, "the replacement stays registered")
	}

	/** Umamo's own tabs sit Item, Tool, View, each with its one panel, and their cleanup withdraws them all. */
	@Test
	fun theBuiltInsRegisterAndWithdraw() {
		val registry = SidebarRegistry()
		val cleanup = registerBuiltInSidebar(registry)

		assertEquals(listOf(ITEM_SIDEBAR_TAB_ID, TOOL_SIDEBAR_TAB_ID, VIEW_SIDEBAR_TAB_ID), shownTabIds(registry))
		assertTrue(shownTabIds(registry, context(space = SpaceKind.UvEditor)).isEmpty(), "the built-in panels are the 2D viewport's")

		cleanup()

		assertTrue(shownTabIds(registry).isEmpty())
	}
}