package org.umamo.ui.kit

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.unit.IntSize

/*
 * How chrome floating over a work surface decides who gets the pointer.  Compose offers a pointer event to
 * overlapping siblings from the top down and stops at the first one whose subtree has a pointer handler
 * under the pointer, unless a handler on that sibling's own root says it shares the pointer with its
 * siblings.  Whether a handler consumes the event plays no part in that choice.
 */

/**
 * Keeps the pointer from falling through this element to a sibling drawn beneath it, such as the viewport
 * under a panel floating over it.
 *
 * A background or padding has no pointer handler, so without one a click on a panel's empty body goes to
 * the sibling underneath.  The handler added here only listens and consumes nothing: the element's
 * ancestors still see every event (an area's hover tracking and its context menu among them), and the
 * controls inside the element keep handling their own.
 *
 * @return Modifier This modifier with the blocking handler appended.
 */
fun Modifier.blocksPointerFallThrough(): Modifier =
	pointerInput(Unit) {
		awaitPointerEventScope {
			while (true) {
				awaitPointerEvent()
			}
		}
	}

/**
 * Hands the pointer on to the siblings drawn beneath this element as well, even where the element's own
 * subtree has a handler under the pointer.  Both the element and the siblings then receive each event.
 *
 * @return Modifier This modifier with the sharing handler appended.
 */
fun Modifier.sharesPointerWithSiblings(): Modifier = this then SharesPointerWithSiblingsElement

/**
 * The element that installs [SharesPointerWithSiblingsNode]; it carries no state, so one instance serves
 * every use.
 */
private data object SharesPointerWithSiblingsElement : ModifierNodeElement<SharesPointerWithSiblingsNode>() {
	/**
	 * Creates the node.
	 *
	 * @return SharesPointerWithSiblingsNode The new node.
	 */
	override fun create(): SharesPointerWithSiblingsNode = SharesPointerWithSiblingsNode()

	/**
	 * Nothing to update: the node has no state.
	 *
	 * @param SharesPointerWithSiblingsNode node The node in place.
	 */
	override fun update(node: SharesPointerWithSiblingsNode) {}
}

/**
 * A pointer handler that does nothing but tell the hit test to go on to the siblings beneath its element.
 */
private class SharesPointerWithSiblingsNode :
	Modifier.Node(),
	PointerInputModifierNode {
	/**
	 * Ignores the event: this node only steers the hit test.
	 *
	 * @param PointerEvent pointerEvent The event.
	 * @param PointerEventPass pass The dispatch pass.
	 * @param IntSize bounds The element's size.
	 */
	override fun onPointerEvent(pointerEvent: PointerEvent, pass: PointerEventPass, bounds: IntSize) {}

	/**
	 * Nothing to cancel: this node holds no gesture.
	 */
	override fun onCancelPointerInput() {}

	/**
	 * Lets the hit test continue to the siblings beneath the element.
	 *
	 * @return Boolean Always true.
	 */
	override fun sharePointerInputWithSiblings(): Boolean = true
}

/**
 * Chrome that floats over a work surface and normally keeps the pointer from reaching it, but steps aside
 * while a gesture of the surface owns the pointer: a modal transform keeps following the pointer across the
 * chrome and is confirmed or cancelled over it, as a Blender modal operator is.
 *
 * Blocking, the chrome's own controls handle the pointer and the empty space between them blocks it.
 * Passing through, the surface beneath receives every event and a cover over the chrome keeps its controls
 * inert, so a click that confirms a transform never also presses the button under it.
 *
 * The hit test asks only the surface's direct sibling whether it hands the pointer on.  A shield nested in a
 * container laid over the surface needs that container to hand it on too while passing through, with
 * [sharesPointerWithSiblings].
 *
 * @param Boolean passPointerThrough Whether the surface beneath gets the pointer, true while a gesture owns it.
 * @param Modifier modifier Layout modifier for the chrome's box (its alignment, margin, and clip).
 * @param Function content The chrome.
 */
@Composable
fun PointerShield(passPointerThrough: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
	val rootModifier = if (passPointerThrough) modifier.sharesPointerWithSiblings() else modifier.blocksPointerFallThrough()
	Box(modifier = rootModifier) {
		content()

		if (passPointerThrough) {
			Box(modifier = Modifier.matchParentSize().blocksPointerFallThrough())
		}
	}
}