package org.umamo.ui.workspace.spaces

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.ZeroCornerSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import org.umamo.ui.action.LocalCommands
import org.umamo.ui.kit.Text
import org.umamo.ui.kit.Tooltip
import org.umamo.ui.resources.*
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.LocalUmamoIcons
import org.umamo.ui.theme.LocalUmamoShapes
import org.umamo.ui.theme.LocalUmamoTypography
import org.umamo.ui.theme.drawIcon
import org.umamo.ui.workspace.LocalViewportChrome

/** The open drawer panel's width. */
private val SIDEBAR_PANEL_WIDTH = 200.dp

/** The pull tab's width (its height is [SIDEBAR_TAB_HEIGHT]). */
private val SIDEBAR_TAB_WIDTH = 14.dp

/** The pull tab's height. */
private val SIDEBAR_TAB_HEIGHT = 48.dp

/**
 * The viewport's right sidebar drawer (Blender's N panel, as chrome): a collapsible panel hugging the
 * viewport's right edge with a pull tab riding its outer border.  The tab's chevron points into the
 * viewport ("<") while closed and back out (">") while open; clicking it dispatches view.toggleSidebar
 * (bound to N), the same command the keymap and palette use, which flips the settings-backed
 * [LocalViewportChrome] flag.  The panel body is currently an empty shell; it does not yet host the
 * tool/item tabs.
 *
 * ビューポート右側のサイドバー（Blender の N パネル）。プルタブで開閉し、view.toggleSidebar コマンドを
 * 発行する。中身は今のところ骨組みのみ。
 *
 * @param Modifier modifier The layout modifier (the caller aligns it to the viewport's right edge).
 */
@Composable
internal fun ViewportSidebarDrawer(modifier: Modifier = Modifier) {
	val chrome = LocalViewportChrome.current
	val colors = LocalUmamoColors.current
	Row(modifier = modifier.fillMaxHeight()) {
		Box(modifier = Modifier.fillMaxHeight(), contentAlignment = Alignment.Center) {
			SidebarPullTab(open = chrome.showSidebar)
		}
		if (chrome.showSidebar) {
			Column(
				modifier =
					Modifier
						.fillMaxHeight()
						.width(SIDEBAR_PANEL_WIDTH)
						.background(colors.panelBackground)
						.border(width = 1.dp, color = colors.panelBorder)
						.padding(8.dp),
			) {
				Text(
					text = stringResource(Res.string.sidebar_title),
					style = LocalUmamoTypography.current.labelMedium,
					color = colors.text,
				)
				Text(
					text = stringResource(Res.string.sidebar_empty_hint),
					style = LocalUmamoTypography.current.labelSmall,
					color = colors.textMuted,
					modifier = Modifier.padding(top = 6.dp),
				)
			}
		}
	}
}

/**
 * The drawer's pull tab: a slim rounded-start chip on the panel's outer edge whose chevron points the
 * way the drawer will move.  Dispatches view.toggleSidebar through the registry.
 *
 * @param Boolean open Whether the drawer is currently open (flips the chevron).
 */
@Composable
private fun SidebarPullTab(open: Boolean) {
	val commands = LocalCommands.current
	val colors = LocalUmamoColors.current
	val shapes = LocalUmamoShapes.current
	val interaction = remember { MutableInteractionSource() }
	val hovered by interaction.collectIsHoveredAsState()
	val label = stringResource(Res.string.cmd_view_toggle_sidebar)
	// Rounded on the viewport side only; the panel side sits flush against the drawer (or the area edge).
	val tabShape = shapes.small.copy(topEnd = ZeroCornerSize, bottomEnd = ZeroCornerSize)
	Tooltip(text = label) {
		Box(
			modifier =
				Modifier
					.size(width = SIDEBAR_TAB_WIDTH, height = SIDEBAR_TAB_HEIGHT)
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
