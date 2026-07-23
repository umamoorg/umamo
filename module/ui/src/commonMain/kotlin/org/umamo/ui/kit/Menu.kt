package org.umamo.ui.kit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.delay
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.LocalUmamoShapes
import org.umamo.ui.theme.LocalUmamoTypography
import org.umamo.ui.theme.drawIcon
import kotlin.math.max

/** How long the pointer may be off both a submenu row and its flyout before the flyout closes. */
private const val SUBMENU_CLOSE_GRACE_MS = 180L

/** The guaranteed gap between a row's label and its trailing shortcut or submenu arrow. */
private val ROW_TRAILING_GAP = 24.dp

/** Edge length of a row's leading icon, matching the kit's other 16.dp Canvas icons. */
private val MENU_ICON_SIZE = 16.dp

/**
 * The breathing room kept between a menu and the window edge.  A menu grows to the window minus this and
 * only then scrolls, so a long-but-fitting list (the blend modes) shows in full rather than being cut off.
 */
private val MENU_WINDOW_MARGIN = 16.dp

/** The shortest a menu is ever capped to, so a tiny window still shows a usable strip of rows. */
private val MENU_MIN_HEIGHT = 96.dp

/** The cap used when a menu is measured unbounded (no window height to subtract from). */
private val MENU_FALLBACK_MAX_HEIGHT = 360.dp

/** The gap between a row's leading icon (or its reserved slot) and the label. */
private val MENU_ICON_LABEL_GAP = 8.dp

/**
 * A popup menu rendered from a [MenuItem] list - the one menu surface used across the menu bar, context
 * menus, and the area type selector.  Styled to the kit's menu look: medium rounded corners, the dark
 * panel fill, no border, and no drop shadow.  Hosted in a [Popup] so it escapes its parent's bounds,
 * positioned by [positionProvider]; a focusable root dismisses on an outside click or Esc via
 * [onDismissRequest].  Renders [MenuItem.Submenu] entries as nested flyouts that open on hover (desktop)
 * or tap (touch).
 *
 * MenuItem のリストから描く共通ポップアップメニュー。メニューバー・コンテキストメニュー・エリア種別で共用。
 *
 * @param List items The entries to render.
 * @param Function onDismissRequest Called to close the whole menu tree (outside click / Esc / selection).
 * @param PopupPositionProvider positionProvider Where the popup is placed relative to its anchor.
 * @param Modifier modifier Modifier for the menu's content column.
 * @param Boolean focusable Whether this popup owns focus and the dismiss - true for a root menu, false
 *   for a nested submenu flyout (only one popup in the tree may be focusable, or they fight over focus).
 */
@Composable
fun Menu(
	items: List<MenuItem>,
	onDismissRequest: () -> Unit,
	positionProvider: PopupPositionProvider,
	modifier: Modifier = Modifier,
	focusable: Boolean = true,
) {
	Popup(
		popupPositionProvider = positionProvider,
		onDismissRequest = onDismissRequest,
		properties = PopupProperties(focusable = focusable),
	) {
		MenuPanel(items = items, dismissRoot = onDismissRequest, modifier = modifier)
	}
}

/**
 * The styled column of rows inside a [Menu]'s popup.  Tracks which submenu (if any) is currently open so
 * exactly one flyout shows per level; [dismissRoot] is threaded down so selecting a leaf anywhere in the
 * tree tears the whole menu down.  When any action row in this panel carries an icon, every row reserves
 * the icon slot so all labels share one column (Blender-style); each flyout is its own panel, so the
 * decision never leaks across nesting levels.
 *
 * Menu のポップアップ内の行の並び。開いているサブメニューを 1 つに保ち、葉の選択で全体を閉じる。
 * いずれかの行にアイコンがあれば全行がアイコン枠を確保し、ラベル列を揃える。
 *
 * @param List items The entries to render.
 * @param Function dismissRoot Closes the entire menu tree.
 * @param Modifier modifier Modifier for the column.
 */
@Composable
private fun MenuPanel(items: List<MenuItem>, dismissRoot: () -> Unit, modifier: Modifier = Modifier) {
	val colors = LocalUmamoColors.current
	var openSubmenuIndex by remember { mutableStateOf<Int?>(null) }
	val reserveIconSlot = items.any { menuEntry -> menuEntry is MenuItem.Action && menuEntry.icon != null }
	val scrollState = rememberScrollState()
	Surface(color = colors.menuBackground, shape = LocalUmamoShapes.current.medium) {
		// A popup is measured against the window, so its incoming max height IS the window height: cap the
		// rows to that minus a margin and only then scroll.  A long-but-fitting menu (the blend modes) shows
		// in full instead of being cut off at an arbitrary constant.
		BoxWithConstraints {
			val available = this@BoxWithConstraints.maxHeight
			val rowCap =
				if (available == Dp.Infinity) {
					MENU_FALLBACK_MAX_HEIGHT
				} else {
					(available - MENU_WINDOW_MARGIN).coerceAtLeast(MENU_MIN_HEIGHT)
				}
			Box {
				// The cap and the scroll ride on an OUTER column so the row column below keeps measuring its
				// IntrinsicSize.Max width exactly as before - intrinsics are asked of that node, never
				// through the scrolling one.
				Column(modifier = Modifier.heightIn(max = rowCap).verticalScroll(scrollState)) {
					Column(modifier = modifier.width(IntrinsicSize.Max).padding(vertical = 2.dp)) {
						items.forEachIndexed { index, item ->
							when (item) {
								is MenuItem.Action ->
									MenuActionRow(
										item = item,
										reserveIconSlot = reserveIconSlot,
										onClick = {
											item.onSelect()
											dismissRoot()
										},
									)

								is MenuItem.Separator -> MenuSeparatorRow()
								is MenuItem.Submenu ->
									MenuSubmenuRow(
										item = item,
										reserveIconSlot = reserveIconSlot,
										expanded = openSubmenuIndex == index,
										onRequestOpen = { openSubmenuIndex = index },
										onRequestClose = {
											if (openSubmenuIndex == index) {
												openSubmenuIndex = null
											}
										},
										dismissRoot = dismissRoot,
									)
							}
						}
					}
				}
				// matchParentSize keeps the scrollbar a pure overlay: it fills its own box rather than the
				// popup's window-sized constraints, so it can no longer inflate the menu to full height.
				Box(modifier = Modifier.matchParentSize()) {
					VerticalScrollbarOverlay(scrollState)
				}
			}
		}
	}
}

/**
 * One selectable menu row: an optional leading icon, the label, an optional shortcut hint right-aligned.
 * A disabled row is dimmed (icon included) and neither hovers nor clicks.  An icon-less row in a panel
 * that reserves the slot indents its label to the shared column.
 *
 * 選択可能な 1 行。任意の先頭アイコン、ラベル、右に任意のショートカット。無効行は淡色で操作不可。
 *
 * @param MenuItem.Action item The row to render.
 * @param Boolean reserveIconSlot Whether icon-less rows indent to the panel's shared label column.
 * @param Function onClick Invoked when an enabled row is chosen.
 */
@Composable
private fun MenuActionRow(item: MenuItem.Action, reserveIconSlot: Boolean, onClick: () -> Unit) {
	val colors = LocalUmamoColors.current
	val typography = LocalUmamoTypography.current
	val interaction = remember { MutableInteractionSource() }
	val hovered by interaction.collectIsHoveredAsState()
	val rowModifier =
		if (item.enabled) {
			Modifier.clickable(interactionSource = interaction, indication = null, onClick = onClick)
		} else {
			Modifier
		}
	Row(
		modifier =
			Modifier
				.fillMaxWidth()
				.padding(horizontal = 4.dp, vertical = 1.dp)
				.then(rowModifier)
				.background(
					when {
						hovered && item.enabled -> colors.rowHover
						else -> Color.Transparent
					},
					shape = LocalUmamoShapes.current.small,
				)
				.padding(horizontal = 4.dp, vertical = 2.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		if (item.icon != null) {
			val iconTint = if (item.enabled) colors.text else colors.textMuted
			Canvas(modifier = Modifier.size(MENU_ICON_SIZE)) {
				drawIcon(item.icon, iconTint)
			}
			Spacer(modifier = Modifier.width(MENU_ICON_LABEL_GAP))
		} else if (reserveIconSlot) {
			Spacer(modifier = Modifier.width(MENU_ICON_SIZE + MENU_ICON_LABEL_GAP))
		}
		Text(
			text = item.label,
			style = typography.labelMedium,
			color = if (item.enabled) colors.text else colors.textMuted,
		)
		Spacer(modifier = Modifier.weight(1f))
		if (item.shortcut != null) {
			Spacer(modifier = Modifier.width(ROW_TRAILING_GAP))
			Text(text = item.shortcut, style = typography.labelSmall, color = colors.textMuted)
		}
	}
}

/** A separator rule between groups of rows, inset so it does not touch the menu's rounded corners. */
@Composable
private fun MenuSeparatorRow() {
	val colors = LocalUmamoColors.current
	Divider(modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp), color = colors.guideLine)
}

/**
 * A submenu row: label, a trailing arrow, and a nested flyout that opens to its right.  On desktop the
 * flyout opens on hover and closes after a short grace once the pointer leaves both the row and the
 * flyout (so a diagonal move across the gap does not snap it shut); on a touch screen, with no hover,
 * tapping the row toggles it.  The flyout popup is non-focusable so the focusable root menu stays the
 * sole owner of the outside-click / Esc dismiss for the whole tree.
 *
 * サブメニュー行。右に入れ子フライアウトを開く。デスクトップはホバー（猶予付き）、タッチはタップで開閉。
 *
 * @param MenuItem.Submenu item The submenu row.
 * @param Boolean reserveIconSlot Whether this row indents its label to the panel's shared label column.
 * @param Boolean expanded Whether this row's flyout is currently shown.
 * @param Function onRequestOpen Asks the panel to open this row's flyout (closing any sibling's).
 * @param Function onRequestClose Asks the panel to close this row's flyout.
 * @param Function dismissRoot Closes the entire menu tree (forwarded to the flyout).
 */
@Composable
private fun MenuSubmenuRow(
	item: MenuItem.Submenu,
	reserveIconSlot: Boolean,
	expanded: Boolean,
	onRequestOpen: () -> Unit,
	onRequestClose: () -> Unit,
	dismissRoot: () -> Unit,
) {
	val colors = LocalUmamoColors.current
	val typography = LocalUmamoTypography.current
	val rowInteraction = remember { MutableInteractionSource() }
	val rowHovered by rowInteraction.collectIsHoveredAsState()
	val flyoutInteraction = remember { MutableInteractionSource() }
	val flyoutHovered by flyoutInteraction.collectIsHoveredAsState()

	// Open on hover (desktop only - touch never reports hover, so the row's clickable carries that there).
	LaunchedEffect(rowHovered) {
		if (rowHovered && item.enabled) {
			onRequestOpen()
		}
	}
	// Close after a grace once neither the row nor the flyout is hovered, re-checking after the delay so a
	// diagonal move from the row across the gap onto the flyout keeps it open.
	LaunchedEffect(rowHovered, flyoutHovered) {
		if (!rowHovered && !flyoutHovered) {
			delay(SUBMENU_CLOSE_GRACE_MS)
			if (!rowHovered && !flyoutHovered) {
				onRequestClose()
			}
		}
	}

	// clickable feeds hover into rowInteraction (so rowHovered tracks the desktop pointer) and toggles the
	// flyout on tap (the touch path, where hover never fires).
	val rowModifier =
		if (item.enabled) {
			Modifier.clickable(indication = null, interactionSource = rowInteraction) {
				if (expanded) {
					onRequestClose()
				} else {
					onRequestOpen()
				}
			}
		} else {
			Modifier
		}
	Box {
		Row(
			modifier =
				Modifier
					.fillMaxWidth()
					.padding(horizontal = 4.dp, vertical = 1.dp)
					.then(rowModifier)
					.background(
						when {
							((rowHovered || expanded) && item.enabled) -> colors.rowHover
							else -> Color.Transparent
						},
						shape = LocalUmamoShapes.current.small,
					)
					.padding(horizontal = 4.dp, vertical = 2.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			if (reserveIconSlot) {
				Spacer(modifier = Modifier.width(MENU_ICON_SIZE + MENU_ICON_LABEL_GAP))
			}
			Text(
				text = item.label,
				style = typography.labelMedium,
				color = if (item.enabled) colors.text else colors.textMuted,
			)
			Spacer(modifier = Modifier.weight(1f))
			Spacer(modifier = Modifier.width(ROW_TRAILING_GAP))
			Text(text = "▸", style = typography.labelMedium, color = colors.textMuted)
		}
		if (expanded) {
			Popup(
				popupPositionProvider = SubmenuPositionProvider,
				onDismissRequest = dismissRoot,
				properties = PopupProperties(focusable = false),
			) {
				Box(modifier = Modifier.hoverable(flyoutInteraction)) {
					MenuPanel(items = item.items, dismissRoot = dismissRoot)
				}
			}
		}
	}
}

/**
 * Positions a popup at its anchor's bottom-left - a menu dropping straight down from its trigger (the
 * menu-bar label or the area type selector) - kept on screen: the left edge is clamped into the window,
 * and a menu that would overflow the bottom flips ABOVE its anchor instead, falling back to a clamped
 * position when it fits neither way (a menu taller than the window then starts at the top edge and
 * scrolls).  Without this a long dropdown simply ran off the bottom and its tail was unreachable.
 */
internal object BelowAnchorPositionProvider : PopupPositionProvider {
	override fun calculatePosition(
		anchorBounds: IntRect,
		windowSize: IntSize,
		layoutDirection: LayoutDirection,
		popupContentSize: IntSize,
	): IntOffset {
		val left = anchorBounds.left.coerceIn(0, max(0, windowSize.width - popupContentSize.width))
		val fitsBelow = anchorBounds.bottom + popupContentSize.height <= windowSize.height
		val above = anchorBounds.top - popupContentSize.height
		val top =
			when {
				fitsBelow -> anchorBounds.bottom
				above >= 0 -> above
				else -> windowSize.height - popupContentSize.height
			}
		return IntOffset(left, top.coerceIn(0, max(0, windowSize.height - popupContentSize.height)))
	}
}

/**
 * Positions a submenu flyout flush to the right of its parent row, aligned with the row's top, flipping
 * to the row's left when a right-side flyout would overflow the window, and clamping the top so a tall
 * flyout near the window bottom stays on screen.
 *
 * サブメニューを親行の右に隣接配置。右がはみ出す場合は左へ反転し、上端を画面内に収める。
 */
private object SubmenuPositionProvider : PopupPositionProvider {
	override fun calculatePosition(
		anchorBounds: IntRect,
		windowSize: IntSize,
		layoutDirection: LayoutDirection,
		popupContentSize: IntSize,
	): IntOffset {
		val fitsRight = anchorBounds.right + popupContentSize.width <= windowSize.width
		val left =
			if (fitsRight) {
				anchorBounds.right
			} else {
				max(0, anchorBounds.left - popupContentSize.width)
			}
		val top = anchorBounds.top.coerceIn(0, max(0, windowSize.height - popupContentSize.height))
		return IntOffset(left, top)
	}
}

/**
 * Positions a popup at a point [localOffset] inside its anchor (the context-menu host), so the menu opens
 * at the cursor.  The point is offset by the anchor's window origin and then clamped to keep the whole
 * menu on screen.
 *
 * アンカー内の指定点（カーソル位置）にポップアップを配置する。画面外に出ないよう収める。
 *
 * @property IntOffset localOffset The cursor point relative to the anchor's top-left.
 */
internal class AtPointPositionProvider(private val localOffset: IntOffset) : PopupPositionProvider {
	override fun calculatePosition(
		anchorBounds: IntRect,
		windowSize: IntSize,
		layoutDirection: LayoutDirection,
		popupContentSize: IntSize,
	): IntOffset {
		val x = (anchorBounds.left + localOffset.x).coerceIn(0, max(0, windowSize.width - popupContentSize.width))
		val y = (anchorBounds.top + localOffset.y).coerceIn(0, max(0, windowSize.height - popupContentSize.height))
		return IntOffset(x, y)
	}
}
