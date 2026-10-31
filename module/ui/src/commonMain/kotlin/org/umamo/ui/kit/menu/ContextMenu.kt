package org.umamo.ui.kit.menu

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import org.umamo.ui.kit.AtPointPositionProvider
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
 * While [suppressed], a request is still taken - it reaches no enclosing area's menu - but opens nothing: a surface
 * whose right press means something else while a gesture runs elsewhere says so here.
 *
 * @param List items The menu entries (built in composition, so labels can be localized).
 * @param Modifier modifier The layout modifier for the wrapping box.
 * @param Boolean suppressed Whether a request is swallowed without opening the menu.
 * @param Function content The content the context menu is attached to.
 */
@Composable
fun ContextMenuArea(
	items: List<MenuItem>,
	modifier: Modifier = Modifier,
	suppressed: Boolean = false,
	content: @Composable () -> Unit,
) {
	var open by remember { mutableStateOf(false) }
	var anchorOffset by remember { mutableStateOf(IntOffset.Zero) }
	// Read through updated state: the gesture detector keeps the handler it started with.
	val swallowRequests by rememberUpdatedState(suppressed)
	Box(
		modifier =
			modifier.contextMenuGesture { localOffset ->
				if (!swallowRequests) {
					anchorOffset = localOffset
					open = true
				}
			},
	) {
		CompositionLocalProvider(LocalContextMenuItems provides items) {
			content()
		}

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
 * The items of the innermost enclosing [ContextMenuArea], for content that has to build its OWN menu.
 *
 * A text field is the case this exists for: it cannot simply sit inside a [ContextMenuArea] and inherit the
 * behaviour, because it needs its own clipboard entries appended and its own gesture to beat the one the
 * text-field primitive installs.  Reading the enclosing items here rather than passing them down means a
 * field picks up whatever menu it happens to be nested in - the keyframe menu on a Properties row, the area
 * menu in a panel header - with no call site changed.
 *
 * Empty outside any area, which reads as "no items to prepend" rather than as an error.
 */
internal val LocalContextMenuItems = compositionLocalOf { emptyList<MenuItem>() }

/**
 * Reports a context-menu request with the pointer position in the modified element's local space.  The
 * trigger is platform-specific - a secondary (right) click on desktop, a long press on a touch screen -
 * because pointer-button state is a desktop-only (skiko) detail, so the detector is provided per platform.
 *
 * @param Function onContextMenu Called with the request point in local coordinates.
 * @return Modifier The modifier with the platform's gesture detector attached.
 */
internal expect fun Modifier.contextMenuGesture(onContextMenu: (IntOffset) -> Unit): Modifier

/**
 * [contextMenuGesture]'s variant for a TEXT FIELD, which has to beat the field primitive's own handler.
 *
 * Compose installs its built-in Cut / Copy / Paste menu on the text-field node itself.  That node is inside
 * ours, and the main pointer pass runs innermost-first, so an ordinary [contextMenuGesture] never sees the
 * press - the built-in menu has already consumed it.  The trigger therefore has to be watched a pass earlier.
 *
 * @param Function onContextMenu Called with the request point in local coordinates.
 * @return Modifier The modifier with the detector attached.
 */
internal expect fun Modifier.textEditContextMenuGesture(onContextMenu: (IntOffset) -> Unit): Modifier

/**
 * Rounds a pointer [Offset] to the integer pixel offset the popup position providers work in.
 *
 * @return IntOffset The rounded offset.
 */
internal fun Offset.toIntOffset(): IntOffset = IntOffset(x.roundToInt(), y.roundToInt())