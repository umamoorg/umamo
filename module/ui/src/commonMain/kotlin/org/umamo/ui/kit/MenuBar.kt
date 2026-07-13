package org.umamo.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.LocalUmamoShapes
import org.umamo.ui.theme.LocalUmamoTypography

/**
 * A tiny coordination seam between a [MenuBar] and a host that runs its own keyboard dispatch.  While a
 * menu-bar menu is open the bar parks a close callback here; a host whose key handler would otherwise
 * claim Escape first (the editor shell's root onPreviewKeyEvent, where Escape is bound to a command)
 * pulls this and dismisses the menu before its own shortcut for that key fires.  Holds null whenever no
 * menu-bar menu is open.  Only a [MenuBar] should write it.
 *
 * MenuBar とホスト側のキー処理をつなぐ小さな仲介。メニューが開いている間だけ閉じる関数を預ける。
 *
 * @property Function closeOpenMenu Closes the currently open menu, or null when none is open.
 */
class MenuBarController {
	var closeOpenMenu: (() -> Unit)? by mutableStateOf(null)
}

/**
 * Supplies the [MenuBarController] a host shares with its [MenuBar].  Defaults to a standalone instance
 * so a bar used without a coordinating host still works: it falls back to its own focus-based Escape
 * handling and to pointer dismissal (outside click / selection).
 */
val LocalMenuBarController = staticCompositionLocalOf { MenuBarController() }

/**
 * A horizontal strip of top-level menu labels (File, Help, …), each opening its [TopLevelMenu] as a
 * [Menu] dropped beneath it.  Clicking a label toggles its menu; while any menu is open, hovering a
 * different label switches to it (the standard menu-bar behavior), so a user can sweep across the bar
 * without re-clicking.  Built from kit [Menu]s, so it shares the menu look and works on desktop and
 * tablet alike - an in-window bar rather than a host-OS menu strip.
 *
 * The dropdowns open non-focusable so the host window keeps focus while a menu is open - a focusable
 * popup steals focus and the labels (which live in that now-unfocused window) stop receiving hover, so
 * the sweep-to-switch would silently never fire.  That trade-off moves the keyboard dismiss onto the bar
 * itself: opening a menu pulls focus to the bar and Escape closes it (a host with its own key dispatch
 * coordinates through [LocalMenuBarController] instead); pointer dismissal (outside click / selection)
 * works regardless.
 *
 * 最上位メニューラベルの横帯。クリックで開閉、開いている間は他ラベルへのホバーで切り替わる。
 * ドロップダウンは非フォーカスで開き（フォーカスを奪うとラベルのホバーが効かなくなるため）、Esc は
 * バー自身が処理する。
 *
 * @param List menus The top-level menus in bar order.
 * @param Modifier modifier The layout modifier.
 */
@Composable
fun MenuBar(menus: List<TopLevelMenu>, modifier: Modifier = Modifier) {
	var openIndex by remember { mutableStateOf<Int?>(null) }
	val focusRequester = remember { FocusRequester() }
	val closeMenu = remember { { openIndex = null } }
	val controller = LocalMenuBarController.current

	// Opening a menu pulls keyboard focus to the bar so Escape can dismiss it: the dropdowns are
	// non-focusable (keeping the window focused so the labels keep receiving hover for the sweep below),
	// which leaves the bar itself to own the keyboard dismiss.  Keyed on the open/closed transition, not
	// the index, so sweeping between menus does not re-request focus on every label change.
	LaunchedEffect(openIndex != null) {
		if (openIndex != null) {
			focusRequester.requestFocus()
		}
	}
	// While open, park the close callback on the shared controller so a host that dispatches keys ahead
	// of this bar (the editor shell claims Escape at its root before it ever reaches here) can dismiss
	// the menu first.  Cleared on close and on disposal so a stale closer never lingers.
	DisposableEffect(openIndex, controller) {
		controller.closeOpenMenu = if (openIndex == null) null else closeMenu
		onDispose { controller.closeOpenMenu = null }
	}

	Row(
		modifier =
			modifier
				.focusRequester(focusRequester)
				.focusable()
				.onPreviewKeyEvent { keyEvent ->
					if (openIndex != null && keyEvent.type == KeyEventType.KeyDown && keyEvent.key == Key.Escape) {
						closeMenu()
						true
					} else {
						false
					}
				},
		verticalAlignment = Alignment.CenterVertically,
	) {
		menus.forEachIndexed { index, menu ->
			MenuBarLabel(
				menu = menu,
				menuOpen = openIndex == index,
				anyMenuOpen = openIndex != null,
				onToggle = { openIndex = if (openIndex == index) null else index },
				onHoverSwitch = { openIndex = index },
				onDismiss = closeMenu,
			)
		}
	}
}

/**
 * One menu-bar label and the [Menu] it anchors.  The popup is a child of this label's [Box], so Compose
 * hands the label's bounds to the below-anchor position provider and the menu drops under the label with
 * no manual coordinate capture.  While a menu is already open elsewhere on the bar, hovering this label
 * switches the open menu to it.
 *
 * メニューバーの 1 ラベルとそれが開くメニュー。ポップアップはラベルの子なので真下に開く。
 *
 * @param TopLevelMenu menu The menu this label opens.
 * @param Boolean menuOpen Whether this label's menu is currently open.
 * @param Boolean anyMenuOpen Whether some label on the bar is open (enables hover-to-switch).
 * @param Function onToggle Toggles this label's menu open/closed (on click).
 * @param Function onHoverSwitch Switches the open menu to this label (on hover while another is open).
 * @param Function onDismiss Closes the open menu (outside click / Esc / selection).
 */
@Composable
private fun MenuBarLabel(
	menu: TopLevelMenu,
	menuOpen: Boolean,
	anyMenuOpen: Boolean,
	onToggle: () -> Unit,
	onHoverSwitch: () -> Unit,
	onDismiss: () -> Unit,
) {
	val colors = LocalUmamoColors.current
	val interaction = remember { MutableInteractionSource() }
	val hovered by interaction.collectIsHoveredAsState()
	// Sweeping the bar: once one menu is open, moving the pointer over another label opens that one.
	LaunchedEffect(hovered) {
		if (hovered && anyMenuOpen && !menuOpen) {
			onHoverSwitch()
		}
	}
	Box {
		Row(
			modifier =
				Modifier
					.clickable(interactionSource = interaction, indication = null, onClick = onToggle)
					.background(
						color = if (menuOpen || hovered) colors.accent else Color.Transparent,
						shape = LocalUmamoShapes.current.medium,
					)
					.padding(horizontal = 10.dp, vertical = 4.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			Text(
				text = menu.label,
				style = LocalUmamoTypography.current.labelMedium,
				color = if (menuOpen || hovered) colors.accentText else colors.text,
			)
		}
		if (menuOpen) {
			// Non-focusable so the host window keeps focus and the bar keeps receiving hover (the
			// sweep-to-switch above): a focusable popup would starve the labels of hover events.  The
			// keyboard dismiss is owned by the bar / its host instead; outside-click dismissal still
			// routes through onDismissRequest regardless of focusability.
			Menu(
				items = menu.items,
				onDismissRequest = onDismiss,
				positionProvider = BelowAnchorPositionProvider,
				focusable = false,
			)
		}
	}
}
