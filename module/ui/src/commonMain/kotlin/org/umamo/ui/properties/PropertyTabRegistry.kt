package org.umamo.ui.properties

import androidx.compose.runtime.staticCompositionLocalOf
import org.umamo.ui.resources.Res
import org.umamo.ui.resources.properties_tab_data
import org.umamo.ui.resources.properties_tab_document
import org.umamo.ui.resources.properties_tab_object
import org.umamo.ui.theme.LocalUmamoIcons

/**
 * The resolver from a [PropertyContext] to the tabs the Properties panel shows - the small registry that
 * mirrors [org.umamo.ui.workspace.SpaceRegistry] one level down.  Iterating a list (rather than a hardcoded
 * switch) is what makes the tab set the extension point: the icon strip and the header search index both
 * fall out of [visibleTabs].
 *
 * @property List tabs The registered tabs, in strip order.
 */
class PropertyTabRegistry(private val tabs: List<PropertyTab>) {
	/**
	 * The tabs to show for [context], in strip order - those whose visibility predicate holds.
	 *
	 * @param PropertyContext context The current context.
	 * @return List The visible tabs, top to bottom.
	 */
	fun visibleTabs(context: PropertyContext): List<PropertyTab> = tabs.filter { tab -> tab.isVisible(context) }

	/**
	 * The tab with the given [id], or null when none is registered under it.
	 *
	 * @param PropertyTabId id The tab identity to resolve.
	 * @return PropertyTab? The matching tab, or null.
	 */
	fun tab(id: PropertyTabId): PropertyTab? = tabs.firstOrNull { tab -> tab.id == id }

	/**
	 * A new registry with [extra] tabs layered over this one: an entry with an existing id replaces it in
	 * place (keeping strip position), a new id appends.  The seam a vendor extension layers onto through
	 * [LocalPropertyTabRegistry] (the same shape [org.umamo.ui.workspace.SpaceRegistry.withOverrides] uses).
	 *
	 * @param List extra The overriding / additional tabs.
	 * @return PropertyTabRegistry The merged registry.
	 */
	fun withOverrides(extra: List<PropertyTab>): PropertyTabRegistry {
		val overrideById = extra.associateBy { tab -> tab.id }
		val replaced = tabs.map { tab -> overrideById[tab.id] ?: tab }
		val appended = extra.filter { tab -> tabs.none { existing -> existing.id == tab.id } }

		return PropertyTabRegistry(replaced + appended)
	}
}

/**
 * The base Properties tab set: the always-on Document tab plus the per-item Object and Data tabs.  Object
 * and Data occupy fixed slots; only the Data tab's icon and sections adapt to the active item's type (see
 * [dataTabIcon] / [dataTabSections]).
 *
 * @return PropertyTabRegistry The base registry every Properties space starts from.
 */
fun defaultPropertyTabRegistry(): PropertyTabRegistry =
	PropertyTabRegistry(
		listOf(
			PropertyTab(
				id = PropertyTabId.Document,
				title = Res.string.properties_tab_document,
				icon = { LocalUmamoIcons.puppetRoot },
				isVisible = { true },
				sections = { listOf(CanvasSection, DisplaySection, RuntimeSection) },
			),
			PropertyTab(
				id = PropertyTabId.Object,
				title = Res.string.properties_tab_object,
				icon = { LocalUmamoIcons.editorModeObject },
				isVisible = { context -> context.activeTarget != null },
				sections = { listOf(TransformSection, RelationsSection) },
			),
			PropertyTab(
				id = PropertyTabId.Data,
				title = Res.string.properties_tab_data,
				icon = { context -> dataTabIcon(context) },
				isVisible = { context -> context.activeTarget != null },
				sections = { context -> dataTabSections(context) },
			),
		),
	)

/**
 * The active [PropertyTabRegistry] for the composition.  `static` - the registry is stable for the shell's
 * lifetime; the Properties space reads `LocalPropertyTabRegistry.current` to build its tab strip and search
 * index.  The shell provides `defaultPropertyTabRegistry().withOverrides(...)`, so a vendor extension adds
 * or replaces tabs without touching core (mirrors [org.umamo.ui.workspace.LocalSpaceRegistry]).
 */
val LocalPropertyTabRegistry =
	staticCompositionLocalOf<PropertyTabRegistry> {
		error("LocalPropertyTabRegistry not provided — wrap the shell in a PropertyTabRegistry provider")
	}