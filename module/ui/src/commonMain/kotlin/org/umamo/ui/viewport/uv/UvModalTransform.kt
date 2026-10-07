package org.umamo.ui.viewport.uv

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.umamo.edit.ActiveOperator
import org.umamo.edit.EditorSession
import org.umamo.edit.MeshOperatorKind
import org.umamo.render.ViewportCamera
import org.umamo.ui.viewport.gizmo.ModalGestureState
import org.umamo.ui.viewport.gizmo.ModalTransformTarget

/**
 * The commit side both UV gizmo overlays share for a modal G / S / R latched on the session's UV operator:
 * the gesture state, the rule that only the area the latch names drives it, the cancel, and the teardowns.
 * What a gesture captures, drives, and commits differs by mode - texture coordinates in Edit mode, atlas
 * placements in Object mode - and is the subclass's.  The pointer-side mechanics (stale discard, virtual
 * pointer, wrap, button semantics) live in [org.umamo.ui.viewport.gizmo.ModalTransformController], which is
 * handed this as its target.
 *
 * One instance per area, created with `remember(areaId)`: the pointer loop and the session collectors are
 * long-running and keep the instance they started with, so a subclass holds nothing that may change under
 * them except through a State holder it reads when used.
 *
 * @param String areaId The UV editor area the overlay covers; only an operator latched here drives.
 * @param EditorSession session The session owning the UV operator latch.
 */
internal abstract class UvModalTransform<TCapture>(
	protected val areaId: String,
	protected val session: EditorSession,
) : ModalTransformTarget {
	/**
	 * The per-area modal-gesture bookkeeping (last pointer, capture + preview, gesture origin, area origin,
	 * cursor wrap, pointer controller).
	 */
	val gesture = ModalGestureState<TCapture, FloatArray>()

	/**
	 * The UV operator latch, while it is this area's.
	 *
	 * @return ActiveOperator? The latched operator, or null when none is latched or another area holds it.
	 */
	protected fun ownedOperator(): ActiveOperator? = session.activeUvOperator.value?.takeIf { operator -> operator.areaId == areaId }

	/**
	 * Submits a drive for one virtual-pointer position, when the latched operator is this area's.
	 *
	 * @param Offset virtualPointer The wrap-continuous pointer.
	 * @param ViewportCamera camera The area camera.
	 * @param IntSize size The area size in pixels.
	 * @return Boolean True when a drive was submitted.
	 */
	final override fun drivePreview(virtualPointer: Offset, camera: ViewportCamera, size: IntSize): Boolean {
		// Defensive ownership check (the pointer loop already gates): only the initiating area drives.
		val operator = ownedOperator() ?: return false

		return submitDrive(operator.kind, virtualPointer, camera, size)
	}

	/** Cancels the in-flight gesture; the latch effect's teardown follows as the operator clears. */
	final override fun cancel() {
		session.clearUvOperator()
	}

	/**
	 * Tears the gesture down as the operator clears.
	 *
	 * @return Boolean True when this area owned a gesture, so the caller restores whatever the gesture
	 *   previewed; a bystander teardown (at mount, or another area's latch) must not, or it would stomp the
	 *   initiating area's live preview.
	 */
	open fun end(): Boolean = gesture.end()

	/**
	 * Ends the gesture because the overlay is leaving composition mid-gesture: the mode changed, the area
	 * closed, the area lost its camera, or the shown surface stopped holding anything to edit.  The latch
	 * effect is cancelled with the overlay and never runs its teardown, so this does it instead.  The latch is
	 * cleared while it is still this area's - a mode switch has cleared it already, and a latch another area
	 * holds is not this one's to clear - so no gesture is left latched to an overlay that cannot drive it,
	 * and none restarts from a fresh gesture state when the overlay comes back.
	 *
	 * @return Boolean True when a gesture was in flight, so the caller restores whatever it previewed.
	 */
	fun abandon(): Boolean {
		if (ownedOperator() != null) {
			session.clearUvOperator()
		}
		return end()
	}

	/**
	 * Resolves the drive for one virtual-pointer position under [operator] on the UI thread and submits it
	 * to the subclass's drive worker, which publishes the preview.
	 *
	 * @param MeshOperatorKind operator The latched operator.
	 * @param Offset virtualPointer The wrap-continuous pointer.
	 * @param ViewportCamera camera The area camera.
	 * @param IntSize size The area size in pixels.
	 * @return Boolean False when the capture has not landed yet.
	 */
	protected abstract fun submitDrive(operator: MeshOperatorKind, virtualPointer: Offset, camera: ViewportCamera, size: IntSize): Boolean
}