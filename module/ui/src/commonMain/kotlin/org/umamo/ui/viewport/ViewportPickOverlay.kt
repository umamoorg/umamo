package org.umamo.ui.viewport

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import org.umamo.edit.SelectionTarget
import org.umamo.runtime.model.partByDrawable
import org.umamo.ui.model.LocalPuppet
import org.umamo.ui.workspace.LocalRelationPick
import org.umamo.ui.workspace.PickKind

/**
 * The relation-pick overlay: while a Properties field's eyedropper is armed, this layer covers the whole
 * viewport, shows the eyedropper cursor, and turns the next click into the picked entity.  Mounted ABOVE
 * the Object gizmo - that overlay owns the primary button in Object mode and would otherwise change the
 * selection before the pick ever saw the click.
 *
 * Self-gated on the shell's [org.umamo.ui.workspace.RelationPickController]: with no pick armed it composes
 * nothing and installs no pointer input, so every other frame passes straight through to the gizmo and
 * navigation layers beneath.  Unlike the zoom-region and select-tool latches this is NOT scoped to one
 * area - a pick may be resolved from any viewport or from the outliner, whichever the user clicks first.
 *
 * [PuppetViewportService.pickAt] hit-tests drawables only, so a Part request resolves through the picked
 * drawable's owning part; a Deformer-only request has nothing to offer here and stays outliner-only.
 * The selectability filter the Object gizmo applies is deliberately skipped - an eyedropper may bind an
 * unselectable object, matching Blender.
 *
 * It also publishes what the pointer is over as it moves, which the shell's overlay names in a badge.  The
 * eyedropper cursor itself is drawn at the SHELL level, not here, so it is visible the instant a pick is
 * armed no matter which panel the pointer happens to be over.
 *
 * @param String areaId The viewport area this overlay covers.
 * @param PuppetViewportService service The render service (for the hit test).
 * @param Modifier modifier The layout modifier.
 */
@Composable
fun ViewportPickOverlay(areaId: String, service: PuppetViewportService, modifier: Modifier = Modifier) {
	val picker = LocalRelationPick.current
	val request = picker.request
	val puppet = LocalPuppet.current
	if (request == null || puppet == null) {
		return
	}
	// Whether a click HERE can bind anything.  A deformer-only pick cannot - the hit test is drawable-only -
	// but the overlay still mounts, because the alternative is worse: with no overlay the click falls through
	// to the object gizmo, changes the selection, and swaps the arming field out from under the live pick.
	// Same rule the outliner follows via PickClickOutcome.Swallowed - while ANY pick is armed, the viewport
	// must not select.
	val resolvableHere = PickKind.Drawable in request.accepts || PickKind.Part in request.accepts

	// Built once per model, not per pointer event: a Part pick calls this on every Move, and partByDrawable
	// walks the whole org tree allocating a fresh map.
	val partOfDrawable = remember(puppet) { puppet.partByDrawable() }

	// Resolves the entity a click at this position would bind, or null for a miss.  The selectability filter
	// the Object gizmo applies is deliberately skipped - an eyedropper may bind an unselectable object.
	fun targetAt(x: Float, y: Float): SelectionTarget? {
		if (!resolvableHere) {
			return null
		}
		val hit = service.pickAt(areaId, x, y) ?: return null
		return if (PickKind.Drawable in request.accepts) {
			SelectionTarget.Drawable(hit)
		} else {
			partOfDrawable[hit]?.let { partId -> SelectionTarget.Part(partId) }
		}
	}

	Box(
		modifier =
			modifier
				.fillMaxSize()
				.clipToBounds()
				.pointerInput(areaId, request) {
					awaitPointerEventScope {
						while (true) {
							val event = awaitPointerEvent()
							val change = event.changes.firstOrNull() ?: continue
							// Name what is under the pointer so the shell's badge can show it before the click.
							// The hit test runs per move, which is affordable because this overlay only exists
							// while a pick is armed - a deliberate, short-lived interaction.
							when (event.type) {
								PointerEventType.Move, PointerEventType.Enter -> picker.hover(targetAt(change.position.x, change.position.y))
								PointerEventType.Exit -> picker.hover(null)
								else -> {}
							}
							if (event.type != PointerEventType.Press) {
								continue
							}
							if (event.buttons.isSecondaryPressed) {
								picker.cancel()
								change.consume()
								continue
							}
							if (!event.buttons.isPrimaryPressed) {
								continue
							}
							// Consume every primary press, hit or miss - and also when nothing here is resolvable at
							// all: the gizmo beneath must never see it, or a missed (or unresolvable) pick would
							// change the selection.  A miss leaves the pick armed to try again.
							change.consume()
							targetAt(change.position.x, change.position.y)?.let { target -> picker.resolve(target) }
						}
					}
				},
	)
}
