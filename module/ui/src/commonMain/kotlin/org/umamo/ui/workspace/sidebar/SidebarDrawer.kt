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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.focus.focusProperties
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

/** The gap between two cards in the panel stack. */
private val SIDEBAR_STACK_SPACING = 6.dp

/**
 * The margin between the floating sidebar and the area's top and bottom edges: the tab rail's own inset at its
 * ends and outer side, so the first card and the first tab start level.
 */
private val SIDEBAR_EDGE_MARGIN = 4.dp

/** The pull tab's width (its height is [SIDEBAR_PULL_TAB_HEIGHT]). */
private val SIDEBAR_PULL_TAB_WIDTH = 14.dp

/** The pull tab's height. */
private val SIDEBAR_PULL_TAB_HEIGHT = 48.dp

/** The width of the grip straddling the cards' inner edge that drags the stack's width. */
private val SIDEBAR_RESIZE_GRIP_WIDTH = 6.dp

/** How far the grip reaches past the cards' inner edge, into a transparent gutter the stack keeps for it. */
private val SIDEBAR_GRIP_OUTSET = SIDEBAR_RESIZE_GRIP_WIDTH / 2

/**
 * A work surface's sidebar (Blender's N panel) floating over the area's right edge, as Blender's overlapping
 * regions do: while open, a stack of collapsible panel cards beside a column of vertical tabs, with no
 * background of their own, and while closed, a pull tab at the top of the edge.  The tabs and panels come from
 * the window's [LocalSidebarRegistry] for this area's surface; whether it is open, how wide it is, which tab
 * shows, and which panels are folded is the area's own [state].
 *
 * The pull tab dispatches view.toggleSidebar (N), which toggles this area's sidebar, open by the
 * [LocalViewportChrome] default until the rigger chooses.  The grip on the cards' inner edge drags the stack's
 * width, fitted to what the area leaves, and dragging it far enough past the narrowest closes the sidebar.  An
 * area too narrow for even the narrowest stack shows only the pull tab, and the sidebar stays open for when the
 * area grows.  The cards and the tabs keep the pointer from reaching the surface beneath them, except while a
 * gesture of this area owns the pointer; everywhere around them the pointer is the surface's.
 *
 * @param String areaId The area the sidebar belongs to.
 * @param SpaceKind space The work surface the area shows, which decides the panels it gets.
 * @param SidebarState state The area's sidebar state.
 * @param Boolean passPointerThrough Whether the cards, the tabs, and the pull tab let the pointer through to the
 *   surface, true while a gesture of this area owns the pointer and has to follow it across them.
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
	// Nothing on this box handles the pointer while it blocks, so the space around the cards and the tabs stays
	// the surface's.  The shields sit inside it, and the hit test asks only the surface's direct sibling whether
	// it hands the pointer on, so the box hands it on as well while the shields do.
	val rootModifier = if (passPointerThrough) modifier.sharesPointerWithSiblings() else modifier
	BoxWithConstraints(modifier = rootModifier.fillMaxHeight()) {
		val stackWidth = if (open) sidebarStackWidth(state.width, maxWidth) else null
		val widestStack = sidebarStackWidth(SIDEBAR_MAX_WIDTH, maxWidth)

		if (stackWidth != null && widestStack != null) {
			SidebarPanels(
				areaId = areaId,
				space = space,
				state = state,
				stackWidth = stackWidth,
				widestStack = widestStack,
				passPointerThrough = passPointerThrough,
				modifier = Modifier.align(Alignment.TopEnd),
			)
		} else {
			// A closed sidebar, or one the area has no room for, shows no tab, so the tab commands have
			// nothing to step through.
			SideEffect { state.shownTabIds = emptyList() }
			PointerShield(
				passPointerThrough = passPointerThrough,
				modifier = Modifier.align(Alignment.TopEnd).padding(top = SIDEBAR_EDGE_MARGIN),
			) {
				SidebarPullTab()
			}
		}
	}
}

/**
 * The open sidebar: the active tab's panels as section cards in a scrolling stack, and a gap away the tab rail,
 * the selected tab in the cards' color.  Each is shielded on its own and only as tall as its content, so the
 * margins, the gap, and everything below them belong to the surface.
 *
 * @param String areaId The area the sidebar belongs to.
 * @param SpaceKind space The work surface the area shows.
 * @param SidebarState state The area's sidebar state.
 * @param Dp stackWidth The panel stack's width, fitted to the area.
 * @param Dp widestStack The widest the area lets the stack be dragged.
 * @param Boolean passPointerThrough Whether the cards and the tabs let the pointer through to the surface.
 * @param Modifier modifier Layout modifier (the caller aligns the sidebar to the area's top right).
 */
@Composable
private fun SidebarPanels(
	areaId: String,
	space: SpaceKind,
	state: SidebarState,
	stackWidth: Dp,
	widestStack: Dp,
	passPointerThrough: Boolean,
	modifier: Modifier = Modifier,
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
	// The resize cursor is held over the whole stack while the grip drags (see holdsHoverIcon).
	var resizing by remember { mutableStateOf(false) }
	Row(modifier = modifier) {
		PointerShield(
			passPointerThrough = passPointerThrough,
			// The margin comes before the shield's handler, so it stays the surface's.  The hover icon is a
			// pointer target of its own, so it sits on the stack and not around the whole sidebar.
			modifier =
				Modifier
					.padding(vertical = SIDEBAR_EDGE_MARGIN)
					.width(stackWidth)
					// Only while a drag is live, so the controls' own hover cursors win otherwise.
					.holdsHoverIcon(umamoPointerIcon(LocalUmamoCursors.ewScroll), held = resizing),
		) {
			Box(modifier = Modifier.fillMaxWidth()) {
				Column(
					modifier = Modifier.fillMaxWidth().verticalScroll(scrollState).padding(start = SIDEBAR_GRIP_OUTSET),
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
				// Matched to the stack after it is measured, so the grip spans the cards without stretching the
				// stack to the area's height.
				Box(modifier = Modifier.matchParentSize()) {
					SidebarResizeGrip(
						state = state,
						widestStack = widestStack,
						onResizingChange = { dragging -> resizing = dragging },
						modifier = Modifier.align(Alignment.CenterStart),
					)
				}
			}
		}
		Spacer(modifier = Modifier.width(SIDEBAR_TAB_GAP))
		PointerShield(passPointerThrough = passPointerThrough) {
			VerticalTabRail(
				tabs = tabs.map { resolved -> VerticalTabRailItem(resolved.tab.id, resolved.tab.label.resolve()) },
				selectedId = activeTabId,
				onSelect = { tabId -> state.activeTabId = tabId },
				contentSide = RailContentSide.Start,
				modifier = Modifier.stepsTabsOnPrimaryWheel(state),
				tabFill = colors.tabBackground,
				selectedFill = colors.panelThirdElevation,
				tabBorder = colors.panelBorder,
				selectedBorder = colors.panelThirdElevationBorder,
			)
		}
	}
}

/**
 * The grip straddling the cards' inner edge, half over the stack's gutter and half over the cards' border:
 * dragging it left widens the stack, between the narrowest it may be and the widest the area leaves.  Each step
 * starts from the width on screen, so a stored width the area clamped never leaves a dead zone.  Dragged right
 * far enough past the narrowest, it closes the sidebar and puts back the width the drag began from, so the
 * sidebar reopens as it was.
 *
 * @param SidebarState state The area's sidebar state, whose width and visibility the drag sets.
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
	// Read only by the drag's callbacks, never drawn.
	var widthAtDragStart by remember { mutableStateOf(state.width) }
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
					onDraggingChange = { dragging ->
						if (dragging) {
							widthAtDragStart = state.width
						}

						onResizingChange(dragging)
					},
					collapseBelow = SIDEBAR_COLLAPSE_WIDTH,
					onCollapse = {
						state.width = widthAtDragStart
						state.visibility.choice = false
					},
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
 * The closed sidebar's pull tab: a slim chip rounded on the viewport side, flush with the area's right edge,
 * whose chevron points the way the sidebar opens.  Dispatches view.toggleSidebar through the registry.
 */
@Composable
private fun SidebarPullTab() {
	val commands = LocalCommands.current
	val colors = LocalUmamoColors.current
	val shapes = LocalUmamoShapes.current
	val interaction = remember { MutableInteractionSource() }
	val hovered by interaction.collectIsHoveredAsState()
	val label = stringResource(Res.string.cmd_view_toggle_sidebar)
	// Rounded on the viewport side only; the other side sits flush against the area edge.
	val tabShape = shapes.small.copy(topEnd = ZeroCornerSize, bottomEnd = ZeroCornerSize)
	Tooltip(text = label) {
		Box(
			modifier =
				Modifier
					.size(width = SIDEBAR_PULL_TAB_WIDTH, height = SIDEBAR_PULL_TAB_HEIGHT)
					.clip(tabShape)
					.background(if (hovered) colors.buttonHover else colors.panelBackground)
					.border(width = 1.dp, color = if (hovered) colors.panelBorderHover else colors.panelBorder, shape = tabShape)
					// NOT focusable: the click opens the sidebar, which takes the tab away, and a focused node
					// leaving the composition leaves focus null - every shortcut then dies until something
					// focusable is clicked again.  The keyboard belongs to the shell root.
					.focusProperties { canFocus = false }
					.clickable(interactionSource = interaction, indication = null) { commands.invoke("view.toggleSidebar") }
					.semantics { contentDescription = label },
			contentAlignment = Alignment.Center,
		) {
			Canvas(modifier = Modifier.size(12.dp)) {
				drawIcon(LocalUmamoIcons.chevronLeft, colors.controlGlyph)
			}
		}
	}
}