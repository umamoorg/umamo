package org.umamo.ui.kit

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt

/**
 * Wraps [content] so a context-menu request - a secondary (right) click on desktop, or a long press on a
 * touch screen - opens [items] as a [Menu] at the pointer.  The menu is positioned at the click point
 * rather than anchored to an element, which is what distinguishes a context menu from a dropdown.
 *
 * The items are a pre-built value, not a lambda, because their labels are localized via stringResource -
 * a @Composable call that can only run during composition, not on the pointer event that opens the menu.
 * The list is cheap to rebuild each recomposition and is therefore always current.
 *
 * 二次クリック（デスクトップ）または長押し（タッチ）で、ポインタ位置に items をメニューとして開く。
 *
 * @param List items The menu entries (built in composition, so labels can be localized).
 * @param Modifier modifier The layout modifier for the wrapping box.
 * @param Function content The content the context menu is attached to.
 */
@Composable
fun ContextMenuArea(items: List<MenuItem>, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
	var open by remember { mutableStateOf(false) }
	var anchorOffset by remember { mutableStateOf(IntOffset.Zero) }
	Box(
		modifier =
			modifier.contextMenuGesture { localOffset ->
				anchorOffset = localOffset
				open = true
			},
	) {
		content()
		if (open) {
			Menu(
				items = items,
				onDismissRequest = { open = false },
				positionProvider = AtPointPositionProvider(anchorOffset),
				focusable = true,
			)
		}
	}
}

/**
 * Reports a context-menu request with the pointer position in the modified element's local space.  The
 * trigger is platform-specific - a secondary (right) click on desktop, a long press on a touch screen -
 * because pointer-button state is a desktop-only (skiko) detail, so the detector is provided per platform.
 *
 * コンテキストメニュー要求を局所座標で通知する。トリガーはプラットフォーム依存（右クリック / 長押し）。
 *
 * @param Function onContextMenu Called with the request point in local coordinates.
 * @return Modifier The modifier with the platform's gesture detector attached.
 */
internal expect fun Modifier.contextMenuGesture(onContextMenu: (IntOffset) -> Unit): Modifier

/**
 * Rounds a pointer [Offset] to the integer pixel offset the popup position providers work in.
 *
 * @return IntOffset The rounded offset.
 */
internal fun Offset.toIntOffset(): IntOffset = IntOffset(x.roundToInt(), y.roundToInt())
