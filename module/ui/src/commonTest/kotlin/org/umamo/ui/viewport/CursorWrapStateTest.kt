package org.umamo.ui.viewport

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The wrap-gesture bookkeeping rules (CursorWrapState) with the platform warp injected: the virtual
 * pointer stays continuous across a teleport, the ACTUAL landing is folded (not the requested target),
 * stale pre-warp events resolve to the landing, and a warp-less platform folds nothing.
 */
class CursorWrapStateTest {
	private val size = IntSize(400, 300)
	private val origin = Offset(1000f, 500f)

	/** A warp that lands exactly where asked - the ideal platform. */
	private fun exactWarp(): (Float, Float) -> Offset? = { screenX, screenY -> Offset(screenX, screenY) }

	@Test
	fun virtualPointerIsRawBeforeAnyWrap() {
		val state = CursorWrapState(exactWarp())
		assertEquals(Offset(120f, 80f), state.virtualPointer(Offset(120f, 80f)))
	}

	@Test
	fun interiorPointerNeverWraps() {
		val state = CursorWrapState(exactWarp())
		assertNull(state.maybeWrap(Offset(200f, 150f), size, origin))
		assertEquals(Offset.Zero, state.wrapOffset)
	}

	@Test
	fun wrapKeepsTheVirtualPointerContinuous() {
		val state = CursorWrapState(exactWarp())
		// The pointer reaches the right edge; the cursor teleports to the left interior.
		val edgePosition = Offset(399f, 150f)
		val landed = state.maybeWrap(edgePosition, size, origin)
		assertNotNull(landed)
		assertEquals(WRAP_INSET_PX, landed.x)
		// The virtual pointer at the landing equals the pre-wrap position: the transform sees no jump.
		assertEquals(edgePosition, state.virtualPointer(landed))
	}

	@Test
	fun foldUsesTheActualLandingNotTheRequestedTarget() {
		// A platform that rounds/rescales: it lands 3px right of every requested target.
		val state = CursorWrapState({ screenX, screenY -> Offset(screenX + 3f, screenY) })
		val edgePosition = Offset(399f, 150f)
		val landed = state.maybeWrap(edgePosition, size, origin)
		assertNotNull(landed)
		assertEquals(WRAP_INSET_PX + 3f, landed.x)
		// Continuity still holds against the ACTUAL landing, so the offset absorbed the platform skew.
		assertEquals(edgePosition, state.virtualPointer(landed))
	}

	@Test
	fun warplessPlatformFoldsNothing() {
		val state = CursorWrapState({ _, _ -> null })
		assertNull(state.maybeWrap(Offset(399f, 150f), size, origin))
		assertEquals(Offset.Zero, state.wrapOffset)
	}

	@Test
	fun missingAreaOriginFoldsNothing() {
		val state = CursorWrapState(exactWarp())
		assertNull(state.maybeWrap(Offset(399f, 150f), size, areaScreenOrigin = null))
		assertEquals(Offset.Zero, state.wrapOffset)
	}

	@Test
	fun staleOldEdgeEventResolvesToTheLandingUntilAFreshOneArrives() {
		val state = CursorWrapState(exactWarp())
		val landed = state.maybeWrap(Offset(399f, 150f), size, origin)
		assertNotNull(landed)
		// A queued pre-warp event still at the old edge (half a viewport from the landing) is stale.
		assertEquals(landed, state.resolveArrival(Offset(398f, 150f), size))
		// The real post-warp event lands near the landing: fresh, and it clears the pending landing.
		assertNull(state.resolveArrival(landed + Offset(1f, 0f), size))
		// With the landing confirmed, even a far position is a legitimate move, not a stale one.
		assertNull(state.resolveArrival(Offset(398f, 150f), size))
	}

	@Test
	fun resetClearsTheGestureBookkeeping() {
		val state = CursorWrapState(exactWarp())
		state.maybeWrap(Offset(399f, 150f), size, origin)
		state.reset()
		assertEquals(Offset.Zero, state.wrapOffset)
		// No pending landing survives the reset: nothing resolves as stale.
		assertNull(state.resolveArrival(Offset(398f, 150f), size))
	}
}
