package org.umamo.ui.workspace.sidebar

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.ZeroCornerSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import org.umamo.edit.EditorMode
import org.umamo.edit.MeshSelection
import org.umamo.edit.Selection
import org.umamo.ui.action.LocalCommands
import org.umamo.ui.kit.PointerShield
import org.umamo.ui.kit.Tooltip
import org.umamo.ui.kit.VerticalScrollbarOverlay
import org.umamo.ui.kit.container.RailContentSide
import org.umamo.ui.kit.container.SectionCard
import org.umamo.ui.kit.container.VerticalTabRail
import org.umamo.ui.kit.container.VerticalTabRailItem
import org.umamo.ui.kit.sharesPointerWithSiblings
import org.umamo.ui.l10n.resolve
import org.umamo.ui.model.LocalEditorMode
import org.umamo.ui.model.LocalEditorSession
import org.umamo.ui.model.LocalPuppet
import org.umamo.ui.model.LocalSelection
import org.umamo.ui.resources.*
import org.umamo.ui.sidebar.LocalSidebarRegistry
import org.umamo.ui.sidebar.SidebarContext
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.LocalUmamoIcons
import org.umamo.ui.theme.LocalUmamoShapes
import org.umamo.ui.theme.drawIcon
import org.umamo.ui.workspace.LocalViewportChrome
import org.umamo.ui.workspace.SpaceKind

/** The width of the open sidebar's panel stack; the tab rail adds its own beside it. */
private val SIDEBAR_STACK_WIDTH = 200.dp

/** The panel stack's inset around its cards and the gap between two cards. */
private val SIDEBAR_STACK_SPACING = 6.dp

/** The pull tab's width (its height is [SIDEBAR_PULL_TAB_HEIGHT]). */
private val SIDEBAR_PULL_TAB_WIDTH = 14.dp

/** The pull tab's height. */
private val SIDEBAR_PULL_TAB_HEIGHT = 48.dp

/**
 * A work surface's sidebar (Blender's N panel) as chrome hugging the area's right edge: a pull tab riding
 * its outer border and, while open, a stack of collapsible panels beside a rail of vertical tabs.  The tabs
 * and panels come from the window's [LocalSidebarRegistry] for this area's surface; which tab shows and
 * which panels are folded is the area's own [state].
 *
 * The pull tab's chevron points into the viewport ("<") while closed and back out (">") while open; it
 * dispatches view.toggleSidebar (N), which flips the settings-backed [LocalViewportChrome] flag.  The open
 * sidebar keeps the pointer from reaching the surface beneath it, except while a gesture of this area owns
 * the pointer; the column around the pull tab stays transparent, as it looks.
 *
 * @param String areaId The area the sidebar belongs to.
 * @param SpaceKind space The work surface the area shows, which decides the panels it gets.
 * @param SidebarState state The area's sidebar state.
 * @param Boolean passPointerThrough Whether the open sidebar lets the pointer through to the surface, true
 *   while a gesture of this area owns the pointer and has to follow it across the sidebar.
 * @param Modifier modifier The layout modifier (the caller aligns it to the area's right edge).
 */
@Composable
internal fun SidebarDrawer(
	areaId: String,
	space: SpaceKind,
	state: SidebarState,
	passPointerThrough: Boolean,
	modifier: Modifier = Modifier,
) {
	val chrome = LocalViewportChrome.current
	// The shield sits inside this row, and the hit test asks only the surface's direct sibling whether it hands
	// the pointer on, so the row hands it on as well while the shield does.
	val rowModifier = if (passPointerThrough) modifier.sharesPointerWithSiblings() else modifier
	Row(modifier = rowModifier.fillMaxHeight()) {
		Box(modifier = Modifier.fillMaxHeight(), contentAlignment = Alignment.Center) {
			SidebarPullTab(open = chrome.showSidebar)
		}

		if (chrome.showSidebar) {
			PointerShield(passPointerThrough = passPointerThrough, modifier = Modifier.fillMaxHeight()) {
				SidebarPanels(areaId = areaId, space = space, state = state)
			}
		} else {
			// A closed sidebar shows no tab, so the tab commands have nothing to step through.
			SideEffect { state.shownTabIds = emptyList() }
		}
	}
}

/**
 * The open sidebar: the active tab's panels as section cards in a scrolling stack, beside the tab rail.  The
 * selected tab takes the stack's color and meets it, as the Properties panel's tab strip meets its body.
 *
 * @param String areaId The area the sidebar belongs to.
 * @param SpaceKind space The work surface the area shows.
 * @param SidebarState state The area's sidebar state.
 */
@Composable
private fun SidebarPanels(areaId: String, space: SpaceKind, state: SidebarState) {
	val colors = LocalUmamoColors.current
	val registry = LocalSidebarRegistry.current
	val context = currentSidebarContext(areaId, space)
	val tabs = if (registry != null && context != null) registry.tabsFor(context) else emptyList()
	val shownTabIds = tabs.map { resolved -> resolved.tab.id }
	SideEffect { state.shownTabIds = shownTabIds }
	val activeTabId = state.resolvedActiveTab(shownTabIds)
	val activeTab = tabs.firstOrNull { resolved -> resolved.tab.id == activeTabId }
	val scrollState = rememberScrollState()
	Row(modifier = Modifier.fillMaxHeight().border(width = 1.dp, color = colors.panelBorder)) {
		Box(modifier = Modifier.fillMaxHeight().width(SIDEBAR_STACK_WIDTH).background(colors.headerBackground)) {
			Column(
				modifier = Modifier.fillMaxSize().verticalScroll(scrollState).padding(SIDEBAR_STACK_SPACING),
				verticalArrangement = Arrangement.spacedBy(SIDEBAR_STACK_SPACING),
			) {
				if (activeTab != null && context != null) {
					for (panel in activeTab.panels) {
						key(panel.id) {
							SectionCard(
								title = panel.title.resolve(),
								expanded = state.isExpanded(panel),
								onToggle = { state.toggleExpanded(panel) },
							) {
								panel.content(context)
							}
						}
					}
				}
			}
			VerticalScrollbarOverlay(scrollState)
		}
		VerticalTabRail(
			tabs = tabs.map { resolved -> VerticalTabRailItem(resolved.tab.id, resolved.tab.label.resolve()) },
			selectedId = activeTabId,
			onSelect = { tabId -> state.activeTabId = tabId },
			contentSide = RailContentSide.Start,
			modifier =
				Modifier
					.fillMaxHeight()
					.background(colors.tabBackground)
					.stepsTabsOnPrimaryWheel(state),
		)
	}
}

/**
 * The context this area's panels read, or null with no document open (the sidebar then shows no tabs).
 *
 * @param String areaId The area the sidebar belongs to.
 * @param SpaceKind space The work surface the area shows.
 * @return SidebarContext? The context, or null with no document.
 */
@Composable
private fun currentSidebarContext(areaId: String, space: SpaceKind): SidebarContext? {
	val puppet = LocalPuppet.current ?: return null
	val session = LocalEditorSession.current
	val meshSelection = session?.meshSelection?.collectAsState()?.value ?: MeshSelection()

	return SidebarContext(
		areaId = areaId,
		space = space,
		puppet = puppet,
		mode = LocalEditorMode.current?.mode ?: EditorMode.Object,
		selection = LocalSelection.current?.selection ?: Selection(),
		meshSelection = meshSelection,
		session = session,
	)
}

/**
 * Steps through the sidebar's tabs on a wheel turn with the primary modifier held (Ctrl, or Cmd on macOS),
 * as Blender's Ctrl+wheel over its tabs does; a plain wheel turn still scrolls the rail.  Read on the
 * initial pass, ahead of the rail's own scrolling, and consumed so the turn does not scroll as well.
 *
 * @param SidebarState state The area's sidebar state.
 * @return Modifier This modifier with the wheel handler appended.
 */
private fun Modifier.stepsTabsOnPrimaryWheel(state: SidebarState): Modifier =
	pointerInput(state) {
		awaitPointerEventScope {
			while (true) {
				val event = awaitPointerEvent(PointerEventPass.Initial)

				if (event.type != PointerEventType.Scroll) {
					continue
				}

				if (!event.keyboardModifiers.isCtrlPressed && !event.keyboardModifiers.isMetaPressed) {
					continue
				}

				val change = event.changes.firstOrNull() ?: continue
				val wheelStep = change.scrollDelta.y

				if (wheelStep == 0f) {
					continue
				}

				state.selectAdjacentTab(if (wheelStep > 0f) 1 else -1)
				change.consume()
			}
		}
	}

/**
 * The drawer's pull tab: a slim rounded-start chip on the sidebar's outer edge whose chevron points the way
 * the sidebar will move.  Dispatches view.toggleSidebar through the registry.
 *
 * @param Boolean open Whether the sidebar is currently open (flips the chevron).
 */
@Composable
private fun SidebarPullTab(open: Boolean) {
	val commands = LocalCommands.current
	val colors = LocalUmamoColors.current
	val shapes = LocalUmamoShapes.current
	val interaction = remember { MutableInteractionSource() }
	val hovered by interaction.collectIsHoveredAsState()
	val label = stringResource(Res.string.cmd_view_toggle_sidebar)
	// Rounded on the viewport side only; the panel side sits flush against the sidebar (or the area edge).
	val tabShape = shapes.small.copy(topEnd = ZeroCornerSize, bottomEnd = ZeroCornerSize)
	Tooltip(text = label) {
		Box(
			modifier =
				Modifier
					.size(width = SIDEBAR_PULL_TAB_WIDTH, height = SIDEBAR_PULL_TAB_HEIGHT)
					.clip(tabShape)
					.background(if (hovered) colors.buttonHover else colors.panelBackground)
					.border(width = 1.dp, color = if (hovered) colors.panelBorderHover else colors.panelBorder, shape = tabShape)
					.clickable(interactionSource = interaction, indication = null) { commands.invoke("view.toggleSidebar") }
					.semantics { contentDescription = label },
			contentAlignment = Alignment.Center,
		) {
			val chevron = if (open) LocalUmamoIcons.chevronRight else LocalUmamoIcons.chevronLeft
			Canvas(modifier = Modifier.size(12.dp)) {
				drawIcon(chevron, colors.controlGlyph)
			}
		}
	}
}