package org.umamo.ui.viewport.gizmo

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.umamo.ui.kit.CursorWrapState
import kotlin.math.min

/** How close (px) to a viewport edge a modal-gesture pointer must be to trigger a Blender-style wrap. */
internal const val WRAP_MARGIN_PX = 2f

/**
 * Where (px from the opposite edge) the wrapped cursor reappears.  Must be greater than [WRAP_MARGIN_PX]
 * so the teleport lands clear of the trigger zone and cannot immediately re-wrap.
 */
internal const val WRAP_INSET_PX = 4f

/**
 * The area-local point the cursor should teleport to when a modal gesture reaches a viewport edge, or null
 * when [local] is not within [margin] px of (or beyond) any edge.  Pure, so the wrap geometry is
 * unit-testable without Compose input.
 *
 * @param Offset local The current pointer position in area-local pixels.
 * @param IntSize size The area size in pixels.
 * @param Float margin The edge proximity (px) that triggers a wrap.
 * @param Float inset The distance (px) from the opposite edge the wrapped cursor reappears at.
 * @return Offset? The wrapped local position, or null when no edge was reached.
 */
internal fun computeCursorWrap(local: Offset, size: IntSize, margin: Float, inset: Float): Offset? {
	val width = size.width.toFloat()
	val height = size.height.toFloat()
	var wrappedX = local.x.coerceIn(0f, width)
	var wrappedY = local.y.coerceIn(0f, height)
	var wrapped = false

	// Only wrap an axis when the viewport is wide enough that both the near trigger zone and the far
	// teleport target fit without overlap - otherwise a sliver of an area would ping-pong the cursor.
	if (width > inset + margin) {
		if (local.x <= margin) {
			wrappedX = width - inset
			wrapped = true
		} else if (local.x >= width - margin) {
			wrappedX = inset
			wrapped = true
		}
	}

	if (height > inset + margin) {
		if (local.y <= margin) {
			wrappedY = height - inset
			wrapped = true
		} else if (local.y >= height - margin) {
			wrappedY = inset
			wrapped = true
		}
	}

	return if (wrapped) {
		Offset(wrappedX, wrappedY)
	} else {
		null
	}
}

/**
 * Filters one arriving Move / Exit position of a modal gesture against a pending warp, as
 * [CursorWrapState.resolveArrival] does, with the reach the viewport uses: an event more than half the area's
 * smaller side from the landing is stale.
 *
 * @param Offset position The arriving pointer position in area-local pixels.
 * @param IntSize size The area size in pixels.
 * @return Offset? The landing to freeze the pointer at when [position] is stale, or null when the event is fresh
 *   and processing should continue.
 */
internal fun CursorWrapState.resolveArrival(position: Offset, size: IntSize): Offset? = resolveArrival(position, min(size.width, size.height) / 2f)

/**
 * Teleports the cursor to the opposite edge when [position] reaches a viewport edge (Blender-style), folding the
 * jump into the wrap offset so the virtual pointer stays continuous.  Opposite-edge rather than toward-pivot is what
 * lets a shrink fire wraps and reach the pivot again.
 *
 * @param Offset position The current pointer position in area-local pixels.
 * @param IntSize size The area size in pixels.
 * @param Offset? areaScreenOrigin The area's top-left in absolute screen pixels, or null before first layout (no
 *   wrap can happen without it).
 * @return Offset? The landed area-local position (the caller's new pointer), or null when no wrap happened.
 */
internal fun CursorWrapState.maybeWrap(position: Offset, size: IntSize, areaScreenOrigin: Offset?): Offset? {
	val wrapTarget = computeCursorWrap(position, size, WRAP_MARGIN_PX, WRAP_INSET_PX) ?: return null

	return warpTo(position, wrapTarget, areaScreenOrigin)
}