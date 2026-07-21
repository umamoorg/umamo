package org.umamo.ui.workspace.spaces

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import org.umamo.edit.Selection
import org.umamo.ui.kit.SectionHeader
import org.umamo.ui.kit.VerticalScrollbarOverlay
import org.umamo.ui.kit.button.IconButton
import org.umamo.ui.model.LocalEditorSession
import org.umamo.ui.model.LocalPuppet
import org.umamo.ui.model.LocalSelection
import org.umamo.ui.properties.LocalPropertyTabRegistry
import org.umamo.ui.properties.PROPERTIES_VIEW_STATE_KEY
import org.umamo.ui.properties.PropertiesViewState
import org.umamo.ui.properties.PropertyContext
import org.umamo.ui.properties.PropertyRow
import org.umamo.ui.properties.PropertySection
import org.umamo.ui.properties.PropertyTab
import org.umamo.ui.properties.PropertyTabId
import org.umamo.ui.properties.sectionVisibility
import org.umamo.ui.resources.*
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.LocalUmamoShapes
import org.umamo.ui.workspace.AreaScope

/** The width of the left icon tab strip (Blender's property-tab rail): the 28.dp button plus a 2.dp far-edge margin. */
private val TAB_STRIP_WIDTH = 32.dp

/**
 * The Properties space: a Blender-style tabbed property editor.  A left icon strip switches between the
 * always-on Document tab and the per-item Object / Data tabs (visible only with one item active); the body
 * renders the active tab's collapsible sections.  The header's search box (mounted separately via
 * [PropertiesHeaderControls]) filters sections and auto-switches to a tab that has matches.
 *
 * Reads [LocalPuppet] for the document + object graph and [LocalSelection] for the active item; read-only
 * in this first cut.  Its search / active-tab / expanded state lives on the area's [PropertiesViewState],
 * shared with the header controls, so it survives switching the space away and back.
 *
 * @param AreaScope scope The hosting area's scope carrying the shared view state.
 * @param Modifier modifier The layout modifier.
 */
@Composable
fun PropertiesSpace(scope: AreaScope, modifier: Modifier = Modifier) {
	val puppet = LocalPuppet.current
	if (puppet == null) {
		return
	}
	val selection = LocalSelection.current?.selection ?: Selection()
	// Per-item tabs need one focused item; a multi-selection or empty selection leaves only Document.
	val activeTarget =
		if (selection.size == 1) {
			selection.active ?: selection.targets.firstOrNull()
		} else {
			null
		}
	val context = PropertyContext(puppet, selection, activeTarget, LocalEditorSession.current)
	val viewState = scope.spaceState(PROPERTIES_VIEW_STATE_KEY) { PropertiesViewState() }
	val registry = LocalPropertyTabRegistry.current
	val colors = LocalUmamoColors.current
	val scrollState = rememberScrollState()

	val query = viewState.query.trim()
	val searching = query.isNotEmpty()
	val visibleTabs = registry.visibleTabs(context)
	// Resolve per-row search visibility once for every visible tab; the strip filter and the body both read
	// it.  Each section's rows are built once here and reused when rendering, so a section's row-building
	// (and its label lookups) runs a single time per recomposition.
	val visibilityByTab = buildVisibilityIndex(visibleTabs, context, query)
	// While searching, the strip shows only tabs that still have a visible section (the "auto-switch tabs" cue).
	val stripTabs =
		if (searching) {
			visibleTabs.filter { tab -> visibilityByTab[tab.id]?.isNotEmpty() == true }
		} else {
			visibleTabs
		}
	val activeTabId = resolveActiveTab(searching, stripTabs, visibleTabs, viewState.activeTab)
	val bodySections = visibilityByTab[activeTabId].orEmpty()

	Row(modifier = modifier.fillMaxSize()) {
		Column(
			modifier =
				Modifier.fillMaxHeight().width(TAB_STRIP_WIDTH).background(colors.tabBackground)
					.padding(start = 4.dp, top = 4.dp, bottom = 4.dp),
			horizontalAlignment = Alignment.CenterHorizontally,
			verticalArrangement = Arrangement.spacedBy(2.dp),
		) {
			for (tab in stripTabs) {
				key(tab.id) {
					PropertyTabButton(
						tab = tab,
						context = context,
						active = tab.id == activeTabId,
						onClick = { viewState.activeTab = tab.id },
					)
				}
			}
		}
		Box(modifier = Modifier.weight(1f).fillMaxHeight().background(colors.headerBackground)) {
			if (bodySections.isEmpty()) {
				if (searching) {
					PlaceholderSpace(stringResource(Res.string.properties_no_matches))
				}
			} else {
				Column(
					modifier =
						Modifier.fillMaxSize().verticalScroll(scrollState)
							.padding(horizontal = 6.dp, vertical = 6.dp),
					verticalArrangement = Arrangement.spacedBy(6.dp),
				) {
					for (visible in bodySections) {
						val section = visible.section
						key(section.id) {
							// A search forces its matching sections open; otherwise the user's fold state wins,
							// defaulting to open for an untouched section.
							val expanded = if (searching) true else viewState.expandedSections[section.id] ?: true
							PropertySectionIsland {
								SectionHeader(
									label = stringResource(section.title),
									expanded = expanded,
									onToggle = {
										viewState.expandedSections[section.id] =
											!(viewState.expandedSections[section.id] ?: true)
									},
								)
								if (expanded) {
									Column(
										modifier =
											Modifier.fillMaxWidth()
												.padding(start = 12.dp, end = 8.dp, top = 2.dp, bottom = 8.dp),
										// Breathing room between separate rows so filled controls do not touch; a
										// FieldStack's own 1.dp seam is internal, so stacked groups stay tight.
										verticalArrangement = Arrangement.spacedBy(4.dp),
									) {
										// Only the rows that survived the search (every row when not searching).
										for (rowIndex in visible.visibleRowIndices) {
											key(rowIndex) {
												visible.rows[rowIndex].content(context)
											}
										}
									}
								}
							}
						}
					}
				}
				VerticalScrollbarOverlay(scrollState)
			}
		}
	}
}

/**
 * One section that survived the header-search filter: the [section] itself, its already-resolved [rows]
 * (built once so rendering reuses them), and the [visibleRowIndices] to draw - every row when not
 * searching or when the section title matched, else just the matching rows.
 *
 * @property PropertySection section The surviving section.
 * @property List rows The section's rows for the current context, resolved once.
 * @property List visibleRowIndices The indices into [rows] to draw, in order.
 */
private class VisibleSection(
	val section: PropertySection,
	val rows: List<PropertyRow>,
	val visibleRowIndices: List<Int>,
)

/**
 * Resolves, for every visible tab, which of its sections and rows survive the current [query].  Resolving
 * the localized section titles and row terms needs Compose, so this runs in the composition and the pure
 * [sectionVisibility] decides the match.  A tab maps to its surviving sections in order (empty when none
 * match), which both the strip filter and the body read.
 *
 * @param List visibleTabs The tabs visible for the context, in strip order.
 * @param PropertyContext context The current context the sections read from.
 * @param String query The trimmed search text.
 * @return Map The surviving sections per tab id.
 */
@Composable
private fun buildVisibilityIndex(
	visibleTabs: List<PropertyTab>,
	context: PropertyContext,
	query: String,
): Map<PropertyTabId, List<VisibleSection>> {
	val index = LinkedHashMap<PropertyTabId, List<VisibleSection>>(visibleTabs.size)
	for (tab in visibleTabs) {
		val survivors = ArrayList<VisibleSection>()
		for (section in tab.sections(context)) {
			val rows = section.rows(context)
			val rowTerms = rows.map { row -> row.terms.map { term -> stringResource(term) } }
			val visibility = sectionVisibility(stringResource(section.title), rowTerms, query)
			if (visibility.shown) {
				survivors.add(VisibleSection(section, rows, visibility.visibleRowIndices))
			}
		}
		index[tab.id] = survivors
	}
	return index
}

/**
 * A raised section card: one elevation step above the panel body (headerBackground over panelBackground,
 * the kit's standard two-tone step), rounded with the medium shape and outlined, mirroring the Parameters
 * panel's ParameterIsland.  It holds the section's disclosure header and, while open, its rows.  The header
 * spans the card (its hover fill is clipped to the rounded corners), so the island adds no inner padding of
 * its own - the header and body manage their own insets.
 *
 * @param Function content The card's contents (the SectionHeader and the open section body).
 */
@Composable
private fun PropertySectionIsland(content: @Composable ColumnScope.() -> Unit) {
	val colors = LocalUmamoColors.current
	val shapes = LocalUmamoShapes.current
	Column(
		modifier =
			Modifier.fillMaxWidth()
				.clip(shapes.medium)
				.background(colors.panelThirdElevation, shape = shapes.medium)
				.border(width = 1.dp, color = colors.panelThirdElevationBorder, shape = shapes.medium),
		content = content,
	)
}

/**
 * One icon-strip tab button.  The active tab's cell is filled to the area-header color and rounded on its
 * outer (left) edge, square on the body-facing (right) edge, so it reads as a tab pulled out of the rail
 * toward its content - the fill is the active cue, in place of an accent glyph.
 *
 * @param PropertyTab tab The tab this button switches to.
 * @param PropertyContext context The current context (resolves the adaptive Data-tab glyph).
 * @param Boolean active Whether this is the active tab.
 * @param Function onClick Selects this tab.
 */
@Composable
private fun PropertyTabButton(tab: PropertyTab, context: PropertyContext, active: Boolean, onClick: () -> Unit) {
	val colors = LocalUmamoColors.current
	val shapes = LocalUmamoShapes.current
	val cellModifier =
		Modifier.fillMaxWidth().background(
			color = if (active) colors.headerBackground else colors.panelBackground,
			shape =
				RoundedCornerShape(
					topStart = shapes.small.topStart,
					topEnd = CornerSize(0.dp),
					bottomEnd = CornerSize(0.dp),
					bottomStart = shapes.small.bottomStart,
				),
		)
	Box(modifier = cellModifier, contentAlignment = Alignment.Center) {
		IconButton(
			icon = tab.icon(context),
			onClick = onClick,
			contentDescription = stringResource(tab.title),
			size = DpSize(28.dp, 28.dp),
			glyphSize = 18.dp,
		)
	}
}

/**
 * The tab to render, clamping the stored choice to a visible tab.  While searching this follows the search:
 * it keeps the stored tab only if it still has matches, else falls to the first matching tab.  With no
 * candidate it lands on Document, which is always visible.
 *
 * @param Boolean searching Whether a search is active.
 * @param List stripTabs The tabs shown in the strip (the matching ones while searching).
 * @param List visibleTabs Every visible tab for the context.
 * @param PropertyTabId stored The tab the user last selected.
 * @return PropertyTabId The tab to show.
 */
private fun resolveActiveTab(
	searching: Boolean,
	stripTabs: List<PropertyTab>,
	visibleTabs: List<PropertyTab>,
	stored: PropertyTabId,
): PropertyTabId {
	if (searching) {
		val keepStored = stripTabs.firstOrNull { tab -> tab.id == stored }
		return (keepStored ?: stripTabs.firstOrNull())?.id ?: PropertyTabId.Document
	}
	return if (visibleTabs.any { tab -> tab.id == stored }) stored else PropertyTabId.Document
}
