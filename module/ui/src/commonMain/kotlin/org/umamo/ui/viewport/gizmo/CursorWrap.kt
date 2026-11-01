package org.umamo.ui.viewport.gizmo

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
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
 * The cursor-wrap bookkeeping for one modal gesture, shared by the gizmo overlays: the accumulated
 * wrap offset that keeps the virtual pointer continuous across teleports, the pending-landing guard
 * that discards stale pre-warp events, and the fold rule that uses the ACTUAL landed position.  The
 * platform warp call is injected so every rule is unit-testable without Compose input or a real OS
 * cursor.
 *
 * @param Function warpCursor Warps the OS cursor to an absolute screen position and returns where it
 *   actually landed, or null when the platform cannot warp; defaults to [warpViewportCursor].
 */
internal class CursorWrapState(
	private val warpCursor: (Float, Float) -> Offset? = { screenX, screenY -> warpViewportCursor(screenX, screenY) },
) {
	/**
	 * The sum of every cursor-wrap teleport in the current gesture.  The transform is driven by a
	 * "virtual" pointer (raw position + this offset) that keeps moving as if the cursor had never
	 * wrapped, so every operator - Grab, Scale, Rotate - stays continuous across a wrap.
	 */
	var wrapOffset: Offset = Offset.Zero
		private set

	// Where the last warp landed (viewport-local), or null when no warp is pending confirmation.
	private var pendingWarpLanding: Offset? = null

	/**
	 * The wrap-continuous ("virtual") pointer for [rawPosition], which travels past the viewport edge
	 * freely so Scale and Rotate stay unbounded.
	 *
	 * @param Offset rawPosition The physical pointer position in area-local pixels.
	 * @return Offset The virtual pointer position.
	 */
	fun virtualPointer(rawPosition: Offset): Offset = rawPosition + wrapOffset

	/**
	 * Filters one arriving Move / Exit position against a pending warp.  Events generated before a warp
	 * but delivered after it still carry old-edge positions, and reading them against the post-warp
	 * [wrapOffset] would spike the transform and re-trigger the wrap; one more than half a viewport from
	 * the warp landing is stale.
	 *
	 * @param Offset position The arriving pointer position in area-local pixels.
	 * @param IntSize size The area size in pixels.
	 * @return Offset? The landing to freeze the pointer at when [position] is stale, or null when the
	 *   event is fresh and processing should continue.
	 */
	fun resolveArrival(position: Offset, size: IntSize): Offset? {
		val expectedLanding = pendingWarpLanding

		if (expectedLanding != null && (position - expectedLanding).getDistance() > min(size.width, size.height) / 2f) {
			return expectedLanding
		}

		pendingWarpLanding = null

		return null
	}

	/**
	 * Teleports the cursor to the opposite edge when [position] reaches a viewport edge (Blender-style),
	 * folding the jump into [wrapOffset] so the virtual pointer stays continuous.  Opposite-edge rather
	 * than toward-pivot is what lets a shrink fire wraps and reach the pivot again.
	 *
	 * @param Offset position The current pointer position in area-local pixels.
	 * @param IntSize size The area size in pixels.
	 * @param Offset? areaScreenOrigin The area's top-left in absolute screen pixels, or null before
	 *   first layout (no wrap can happen without it).
	 * @return Offset? The landed area-local position (the caller's new pointer), or null when no wrap
	 *   happened.
	 */
	fun maybeWrap(position: Offset, size: IntSize, areaScreenOrigin: Offset?): Offset? {
		val wrapTarget = computeCursorWrap(position, size, WRAP_MARGIN_PX, WRAP_INSET_PX) ?: return null
		val origin = areaScreenOrigin ?: return null
		val landedScreen = warpCursor(origin.x + wrapTarget.x, origin.y + wrapTarget.y) ?: return null
		val landed = Offset(landedScreen.x - origin.x, landedScreen.y - origin.y)
		// Fold where the OS says the cursor landed, not the requested target: the warp rounds, clamps, and
		// can rescale, and a request-vs-landing difference would accumulate as drift across wraps.
		wrapOffset += position - landed
		pendingWarpLanding = landed

		return landed
	}

	/** Resets the gesture bookkeeping; called when a gesture starts and when it tears down. */
	fun reset() {
		wrapOffset = Offset.Zero
		pendingWarpLanding = null
	}
}