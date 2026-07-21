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
 * Reports whether any string in [haystack] contains [query] (case-insensitive); a blank query matches
 * everything.  Pure so the header-search filter is unit-testable without Compose - the composable caller
 * resolves each section's localized labels into the haystack, this decides the match.
 *
 * @param List haystack The already-resolved localized strings to test.
 * @param String query The user's search text.
 * @return Boolean True when the query is blank or any haystack entry contains it.
 */
fun matchesQuery(haystack: List<String>, query: String): Boolean {
	val trimmed = query.trim()
	if (trimmed.isEmpty()) {
		return true
	}
	return haystack.any { candidate -> candidate.contains(trimmed, ignoreCase = true) }
}

/**
 * Whether a section is shown for a query, and which of its rows to draw.  A blank query shows the whole
 * section; a query that matches the section [title] also shows every row (the whole section is relevant);
 * otherwise only the rows whose terms match are shown, and the section is hidden entirely when none do.
 *
 * @property Boolean shown Whether the section appears at all.
 * @property List visibleRowIndices The indices (into the section's row list) to draw, in order.
 */
class SectionVisibility(val shown: Boolean, val visibleRowIndices: List<Int>)

/**
 * Resolves per-row search visibility for one section from its already-localized [title] and [rowTerms]
 * (one term list per row).  Pure so the header-search filter is unit-testable without Compose - the
 * composable caller resolves the strings, this decides which rows survive.
 *
 * @param String title The section's localized heading.
 * @param List rowTerms Each row's localized search terms, in row order.
 * @param String query The user's search text.
 * @return SectionVisibility Whether the section shows and which rows to draw.
 */
fun sectionVisibility(title: String, rowTerms: List<List<String>>, query: String): SectionVisibility {
	val trimmed = query.trim()
	val allRows = rowTerms.indices.toList()
	if (trimmed.isEmpty() || matchesQuery(listOf(title), trimmed)) {
		return SectionVisibility(true, allRows)
	}
	val matching = allRows.filter { index -> matchesQuery(rowTerms[index], trimmed) }
	return SectionVisibility(matching.isNotEmpty(), matching)
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
				sections = { listOf(CanvasSection, RuntimeSection) },
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
