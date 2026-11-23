package org.umamo.ui.kit.textentry

import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerHoverIcon
import org.umamo.ui.theme.LocalRestingPointerIcon

/**
 * Holds a control drawn inside or over a text editor, a search field's clear or record button, and keeps the plain
 * pointer on it whether the editor is being typed in or not.
 *
 * @param InlineEditController controller The text-entry seam the host reads.
 * @param Function content The control.
 */
@Composable
fun TextEntryControl(controller: InlineEditController = LocalInlineEditController.current, content: @Composable () -> Unit) {
	val interaction = remember { MutableInteractionSource() }
	// A plain value, not the state: onDispose must see what the effect began with, and the state already reads the
	// exit by the time the effect is disposed for it.
	val hovered = interaction.collectIsHoveredAsState().value
	// Cleared on dispose as well as on exit: a clear button leaves with the text it clears, under the pointer.
	DisposableEffect(hovered, controller) {
		if (hovered) {
			controller.pointerOnEditorControl = true
		}

		onDispose {
			if (hovered) {
				controller.pointerOnEditorControl = false
			}
		}
	}

	Box(modifier = Modifier.hoverable(interaction).pointerHoverIcon(LocalRestingPointerIcon.current), contentAlignment = Alignment.Center) {
		content()
	}
}