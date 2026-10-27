package org.umamo.ui.workspace.sidebar

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.unit.Dp
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
import org.umamo.ui.kit.dragResizes
import org.umamo.ui.kit.holdsHoverIcon
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
import org.umamo.ui.theme.LocalUmamoCursors
import org.umamo.ui.theme.LocalUmamoIcons
import org.umamo.ui.theme.LocalUmamoShapes
import org.umamo.ui.theme.drawIcon
import org.umamo.ui.theme.umamoPointerIcon
import org.umamo.ui.workspace.LocalViewportChrome
import org.umamo.ui.workspace.SpaceKind

/** The panel stack's inset around its cards and the gap between two cards. */
private val SIDEBAR_STACK_SPACING = 6.dp

/** The pull tab's width (its height is [SIDEBAR_PULL_TAB_HEIGHT]). */
private val SIDEBAR_PULL_TAB_WIDTH = 14.dp

/** The pull tab's height. */
private val SIDEBAR_PULL_TAB_HEIGHT = 48.dp

/** The width of the grip along the open sidebar's inner edge that drags its width. */
private val SIDEBAR_RESIZE_GRIP_WIDTH = 5.dp

/**
 * A work surface's sidebar (Blender's N panel) as chrome hugging the area's right edge: a pull tab riding
 * its outer border and, while open, a stack of collapsible panels beside a rail of vertical tabs.  The tabs
 * and panels come from the window's [LocalSidebarRegistry] for this area's surface; whether it is open, how
 * wide it is, which tab shows, and which panels are folded is the area's own [state].
 *
 * The pull tab's chevron points into the viewport ("<") while closed and back out (">") while open; it
 * dispatches view.toggleSidebar (N), which toggles this area's sidebar, open by the [LocalViewportChrome]
 * default until the rigger chooses.  The grip along the sidebar's inner edge drags its width, fitted to what
 * the area leaves; an area too narrow for even the narrowest sidebar shows only the pull tab, and the
 * sidebar stays open for when the area grows.  The open sidebar keeps the pointer from reaching the surface
 * beneath it, except while a gesture of this area owns the pointer; the column around the pull tab stays
 * transparent, as it looks.
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
	val open = state.visibility.isShown(LocalViewportChrome.current.showSidebar)
	// The shield sits inside this box, and the hit test asks only the surface's direct sibling whether it hands
	// the pointer on, so the box hands it on as well while the shield does.
	val rootModifier = if (passPointerThrough) modifier.sharesPointerWithSiblings() else modifier
	BoxWithConstraints(modifier = rootModifier.fillMaxHeight()) {
		val stackWidth = if (open) sidebarStackWidth(state.width, maxWidth) else null
		val widestStack = sidebarStackWidth(SIDEBAR_MAX_WIDTH, maxWidth)
		Row(modifier = Modifier.fillMaxHeight()) {
			Box(modifier = Modifier.fillMaxHeight(), contentAlignment = Alignment.Center) {
				SidebarPullTab(open = stackWidth != null)
			}

			if (stackWidth != null && widestStack != null) {
				PointerShield(passPointerThrough = passPointerThrough, modifier = Modifier.fillMaxHeight()) {
					SidebarPanels(areaId = areaId, space = space, state = state, stackWidth = stackWidth, widestStack = widestStack)
				}
			} else {
				// A closed sidebar, or one the area has no room for, shows no tab, so the tab commands have
				// nothing to step through.
				SideEffect { state.shownTabIds = emptyList() }
			}
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
 * @param Dp stackWidth The panel stack's width, fitted to the area.
 * @param Dp widestStack The widest the area lets the stack be dragged.
 */
@Composable
private fun SidebarPanels(
	areaId: String,
	space: SpaceKind,
	state: SidebarState,
	stackWidth: Dp,
	widestStack: Dp,
) {
	val colors = LocalUmamoColors.current
	val registry = LocalSidebarRegistry.current
	val context = currentSidebarContext(areaId, space)
	val tabs = if (registry != null && context != null) registry.tabsFor(context) else emptyList()
	val shownTabIds = tabs.map { resolved -> resolved.tab.id }
	SideEffect { state.shownTabIds = shownTabIds }
	val activeTabId = state.resolvedActiveTab(shownTabIds)
	val activeTab = tabs.firstOrNull { resolved -> resolved.tab.id == activeTabId }
	val scrollState = rememberScrollState()
	// The resize cursor is held over the whole sidebar while the grip drags (see holdsHoverIcon).
	var resizing by remember { mutableStateOf(false) }
	Row(
		modifier =
			Modifier
				.fillMaxHeight()
				.border(width = 1.dp, color = colors.panelBorder)
				// Only while a drag is live, so the controls' own hover cursors win otherwise.
				.holdsHoverIcon(umamoPointerIcon(LocalUmamoCursors.ewScroll), held = resizing),
	) {
		Box(modifier = Modifier.fillMaxHeight().width(stackWidth).background(colors.headerBackground)) {
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
			SidebarResizeGrip(
				state = state,
				widestStack = widestStack,
				onResizingChange = { dragging -> resizing = dragging },
				modifier = Modifier.align(Alignment.CenterStart),
			)
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
 * The grip along the open sidebar's inner edge, over the stack's border and padding and clear of its cards:
 * dragging it left widens the stack, between the narrowest it may be and the widest the area leaves.  Each
 * step starts from the width on screen, so a stored width the area clamped never leaves a dead zone.
 *
 * @param SidebarState state The area's sidebar state, whose width the drag sets.
 * @param Dp widestStack The widest the area lets the stack be.
 * @param Function onResizingChange Told when a drag starts and ends, to hold the resize cursor meanwhile.
 * @param Modifier modifier Layout modifier (the caller aligns the grip to the stack's inner edge).
 */
@Composable
private fun SidebarResizeGrip(
	state: SidebarState,
	widestStack: Dp,
	onResizingChange: (Boolean) -> Unit,
	modifier: Modifier = Modifier,
) {
	Box(
		modifier =
			modifier
				.width(SIDEBAR_RESIZE_GRIP_WIDTH)
				.fillMaxHeight()
				.dragResizes(
					size = { state.width },
					min = SIDEBAR_MIN_WIDTH,
					max = widestStack,
					orientation = Orientation.Horizontal,
					hoverIcon = umamoPointerIcon(LocalUmamoCursors.ewScroll),
					onResize = { width -> state.width = width },
					// The grip is on the stack's left edge and the sidebar hugs the right, so dragging left widens it.
					growsBackward = true,
					onDraggingChange = onResizingChange,
				),
	)
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
	val selectionHandle = LocalSelection.current

	return SidebarContext(
		areaId = areaId,
		space = space,
		puppet = puppet,
		mode = LocalEditorMode.current?.mode ?: EditorMode.Object,
		selection = selectionHandle?.selection ?: Selection(),
		shownTarget = selectionHandle?.shownTarget(puppet),
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