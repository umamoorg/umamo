package org.umamo.ui.viewport.viewport2d

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isAltPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import org.umamo.edit.SelectionTarget
import org.umamo.runtime.model.DrawableId
import org.umamo.ui.model.LocalPuppet
import org.umamo.ui.viewport.ObjectPickCandidate
import org.umamo.ui.viewport.PuppetViewportService
import org.umamo.ui.viewport.gizmo.AltPickResolution
import org.umamo.ui.viewport.gizmo.resolveAltOverlapPick
import org.umamo.ui.workspace.LocalRelationPick
import org.umamo.ui.workspace.PickKind

/**
 * The relation-pick overlay: while a Properties field's eyedropper is armed, this layer covers the whole
 * viewport and turns the next click into the picked entity, publishing what the pointer is over as it moves.
 * Mounted ABOVE the Object gizmo - that overlay owns the primary button in Object mode and would otherwise
 * change the selection before the pick ever saw the click.
 *
 * Where no lattice is drawn, a deformer is reached through its art alone: Edit mode draws no lattice layer, Pose
 * mode leaves the warps being posed to the session's cage, and rotation deformers are not drawn at all.
 *
 * @param String areaId The viewport area this overlay covers.
 * @param PuppetViewportService service The render service (the lattice and art hit tests, and the hover).
 * @param Function onOverlapRequest Opens the overlap picker over an Alt click's stack of two or more targets.
 * @param Modifier modifier The layout modifier.
 */
@Composable
fun ViewportPickOverlay(
	areaId: String,
	service: PuppetViewportService,
	onOverlapRequest: (Offset, List<ObjectPickCandidate>) -> Unit,
	modifier: Modifier = Modifier,
) {
	val picker = LocalRelationPick.current
	val request = picker.request
	val puppet = LocalPuppet.current
	val requestOverlap by rememberUpdatedState(onOverlapRequest)

	if (request == null || puppet == null) {
		return
	}

	// Below the early return on purpose: the pick's end takes this effect out of the composition, and with it the
	// lattice hover this overlay lit, so no highlight outlives the pick.  The model is only track()'s, unused here.
	val latticeHover = remember(areaId, service) { LatticeHover(areaId, service) { puppet } }
	DisposableEffect(latticeHover, request) {
		onDispose { latticeHover.clear() }
	}

	val takesDeformers = PickKind.Deformer in request.accepts

	/**
	 * What a click on [drawableId]'s art binds: the first accepted kind it stands for, as the outliner's row for it
	 * binds (RelationPickRequest.targetFor), or null when it stands for none the pick takes.
	 *
	 * @param DrawableId drawableId The drawable whose art is under the pointer.
	 * @return SelectionTarget? The target, or null for a miss.
	 */
	fun artTarget(drawableId: DrawableId): SelectionTarget? = request.targetFor(SelectionTarget.Drawable(drawableId))

	/**
	 * The first drawn lattice under [position] the pick takes, so a refused one (the owner's own) gives way to the
	 * one beneath it; null when the pick takes no deformer or none is there.  The selectability filter the Object
	 * gizmo applies is deliberately skipped - an eyedropper may bind an unselectable object.
	 *
	 * @param Offset position The pointer in the area, in pixels.
	 * @return SelectionTarget.Deformer? The lattice's warp, or null.
	 */
	fun latticeAt(position: Offset): SelectionTarget.Deformer? {
		if (!takesDeformers) {
			return null
		}

		return latticesUnder(service, areaId, position).firstNotNullOfOrNull { deformerId ->
			SelectionTarget.Deformer(deformerId).takeIf { candidate -> request.takes(candidate) }
		}
	}

	/**
	 * What the front-most art under [position] binds ([artTarget]), or null for a miss.
	 *
	 * @param Offset position The pointer in the area, in pixels.
	 * @return SelectionTarget? The target, or null.
	 */
	fun artAt(position: Offset): SelectionTarget? = service.pickAt(areaId, position.x, position.y)?.let { drawableId -> artTarget(drawableId) }

	/**
	 * Everything an Alt click at [position] could bind, in the Object pick's stack order (lattices, then art front to
	 * back), each entry as the target the pick takes for it and each target once.
	 *
	 * @param Offset position The pointer in the area, in pixels.
	 * @return List<ObjectPickCandidate> The candidates.
	 */
	fun stackAt(position: Offset): List<ObjectPickCandidate> =
		viewportPickStack(service, areaId, position)
			.mapNotNull { candidate ->
				val target =
					when (val hit = candidate.target) {
						is SelectionTarget.Deformer -> hit.takeIf { lattice -> takesDeformers && request.takes(lattice) }
						is SelectionTarget.Drawable -> artTarget(hit.id)
						is SelectionTarget.Part -> null
					}
				target?.let { taken -> ObjectPickCandidate(taken, candidate.centrality) }
			}.distinctBy { candidate -> candidate.target }

	/**
	 * Publishes what the pointer at [position] is over, to the shell's badge and, for a lattice, as this area's
	 * lattice hover.  Only a lattice the pointer is on lights up; a deformer reached through its art keeps its
	 * lattice plain.
	 *
	 * @param Offset? position The pointer in the area, or null once it has left.
	 */
	fun hoverAt(position: Offset?) {
		val lattice = position?.let { pointer -> latticeAt(pointer) }
		picker.hover(lattice ?: position?.let { pointer -> artAt(pointer) })
		latticeHover.show(lattice?.id)
	}

	Box(
		modifier =
			modifier
				.fillMaxSize()
				.clipToBounds()
				.pointerInput(areaId, request, puppet) {
					awaitPointerEventScope {
						while (true) {
							val event = awaitPointerEvent()
							val change = event.changes.firstOrNull() ?: continue

							// Name what is under the pointer so the shell's badge can show it before the click.
							// The hit test runs per move, which is affordable because this overlay only exists
							// while a pick is armed - a deliberate, short-lived interaction.
							when (event.type) {
								PointerEventType.Move, PointerEventType.Enter -> {
									hoverAt(change.position)
								}

								PointerEventType.Exit -> {
									hoverAt(null)
								}

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

							// Consume every primary press, hit or miss: the gizmo beneath must never see it, or a missed
							// pick would change the selection.  A miss leaves the pick armed to try again.
							change.consume()

							if (event.keyboardModifiers.isAltPressed) {
								when (val resolution = resolveAltOverlapPick(stackAt(change.position))) {
									is AltPickResolution.ShowOverlap -> {
										requestOverlap(change.position, resolution.candidates)
									}

									is AltPickResolution.SelectSingle -> {
										picker.resolve(resolution.target)
									}

									AltPickResolution.None -> {}
								}

								continue
							}

							(latticeAt(change.position) ?: artAt(change.position))?.let { target -> picker.resolve(target) }
						}
					}
				},
	)
}