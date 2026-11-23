package org.umamo.ui.kit

import androidx.compose.ui.geometry.Offset

/**
 * The cursor-wrap bookkeeping for one gesture that warps the OS cursor as it goes: the accumulated wrap offset that
 * keeps the virtual pointer continuous across teleports, the pending-landing guard that discards stale pre-warp
 * events, and the fold rule that uses the ACTUAL landed position.  Where to warp, and when, is the caller's.  The
 * platform warp call is injected so every rule is unit-testable without Compose input or a real OS cursor.
 *
 * @param Function warp Warps the OS cursor to an absolute screen position and returns where it actually landed, or
 *   null when the platform cannot warp; defaults to [warpCursor].
 */
internal class CursorWrapState(
	private val warp: (Float, Float) -> Offset? = ::warpCursor,
) {
	/**
	 * The sum of every cursor-wrap teleport in the current gesture.  The gesture is driven by a "virtual" pointer
	 * (raw position + this offset) that keeps moving as if the cursor had never wrapped.
	 */
	var wrapOffset: Offset = Offset.Zero
		private set

	// Where the last warp landed (gesture-local), or null when no warp is pending confirmation.
	private var pendingWarpLanding: Offset? = null

	// What the pending warp added to the wrap offset, so a landing that never holds can be taken back out.
	private var pendingWarpJump: Offset = Offset.Zero

	/** Whether a warp has landed and no fresh event has confirmed it yet. */
	val warpPending: Boolean
		get() = pendingWarpLanding != null

	/**
	 * The wrap-continuous ("virtual") pointer for [rawPosition], which travels past the edge freely.
	 *
	 * @param Offset rawPosition The physical pointer position in gesture-local pixels.
	 * @return Offset The virtual pointer position.
	 */
	fun virtualPointer(rawPosition: Offset): Offset = rawPosition + wrapOffset

	/**
	 * Filters one arriving position against a pending warp.  Events generated before a warp but delivered after it
	 * still carry old positions, and reading them against the post-warp [wrapOffset] would spike the gesture and
	 * re-trigger the wrap; one farther than [reach] from the warp landing is stale.
	 *
	 * @param Offset position The arriving pointer position in gesture-local pixels.
	 * @param Float reach How far from the landing a fresh event may arrive, in pixels.
	 * @return Offset? The landing to freeze the pointer at when [position] is stale, or null when the event is fresh
	 *   and processing should continue.
	 */
	fun resolveArrival(position: Offset, reach: Float): Offset? {
		val expectedLanding = pendingWarpLanding

		if (expectedLanding != null && (position - expectedLanding).getDistance() > reach) {
			return expectedLanding
		}

		pendingWarpLanding = null
		pendingWarpJump = Offset.Zero

		return null
	}

	/**
	 * Teleports the cursor to [target], folding the jump into [wrapOffset] so the virtual pointer stays continuous.
	 *
	 * @param Offset position The current pointer position in gesture-local pixels.
	 * @param Offset target Where the cursor should land, in the same pixels.
	 * @param Offset? origin The gesture surface's top-left in absolute screen pixels, or null before first layout (no
	 *   warp can happen without it).
	 * @return Offset? The landed gesture-local position (the caller's new pointer), or null when no warp happened.
	 */
	fun warpTo(position: Offset, target: Offset, origin: Offset?): Offset? {
		val screenOrigin = origin ?: return null
		val landedScreen = warp(screenOrigin.x + target.x, screenOrigin.y + target.y) ?: return null
		val landed = Offset(landedScreen.x - screenOrigin.x, landedScreen.y - screenOrigin.y)
		// Fold where the OS says the cursor landed, not the requested target: the warp rounds, clamps, and can rescale,
		// and a request-vs-landing difference would accumulate as drift across wraps.
		val jump = position - landed
		wrapOffset += jump
		pendingWarpLanding = landed
		pendingWarpJump = jump

		return landed
	}

	/**
	 * Takes the pending warp back out of [wrapOffset], for a landing that never held: the next events kept coming from
	 * where the cursor was before it, so the virtual pointer goes back to reading them as it did then.
	 */
	fun abandonPendingWarp() {
		if (pendingWarpLanding == null) {
			return
		}

		wrapOffset -= pendingWarpJump
		pendingWarpLanding = null
		pendingWarpJump = Offset.Zero
	}

	/** Resets the gesture bookkeeping; called when a gesture starts and when it tears down. */
	fun reset() {
		wrapOffset = Offset.Zero
		pendingWarpLanding = null
		pendingWarpJump = Offset.Zero
	}
}