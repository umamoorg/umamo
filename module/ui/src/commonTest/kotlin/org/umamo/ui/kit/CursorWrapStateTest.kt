package org.umamo.ui.kit

import androidx.compose.ui.geometry.Offset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The wrap-gesture bookkeeping rules (CursorWrapState) with the platform warp injected: the virtual pointer stays
 * continuous across a teleport, the ACTUAL landing is folded (not the requested target), stale pre-warp events
 * resolve to the landing, a warp-less platform folds nothing, and a landing that never held comes back out.
 */
class CursorWrapStateTest {
	private val origin = Offset(1000f, 500f)
	private val reach = 150f

	/** A warp that lands exactly where asked - the ideal platform. */
	private fun exactWarp(): (Float, Float) -> Offset? = { screenX, screenY -> Offset(screenX, screenY) }

	@Test
	fun virtualPointerIsRawBeforeAnyWrap() {
		val state = CursorWrapState(exactWarp())
		assertEquals(Offset(120f, 80f), state.virtualPointer(Offset(120f, 80f)))
	}

	@Test
	fun aWarpKeepsTheVirtualPointerContinuous() {
		val state = CursorWrapState(exactWarp())
		val position = Offset(399f, 150f)
		val landed = state.warpTo(position, Offset(4f, 150f), origin)
		assertNotNull(landed)
		assertEquals(Offset(4f, 150f), landed)
		// The virtual pointer at the landing equals the pre-wrap position: the gesture sees no jump.
		assertEquals(position, state.virtualPointer(landed))
		assertTrue(state.warpPending)
	}

	@Test
	fun foldUsesTheActualLandingNotTheRequestedTarget() {
		// A platform that rounds/rescales: it lands 3px right of every requested target.
		val state = CursorWrapState({ screenX, screenY -> Offset(screenX + 3f, screenY) })
		val position = Offset(399f, 150f)
		val landed = state.warpTo(position, Offset(4f, 150f), origin)
		assertNotNull(landed)
		assertEquals(7f, landed.x)
		// Continuity still holds against the ACTUAL landing, so the offset absorbed the platform skew.
		assertEquals(position, state.virtualPointer(landed))
	}

	@Test
	fun warplessPlatformFoldsNothing() {
		val state = CursorWrapState({ _, _ -> null })
		assertNull(state.warpTo(Offset(399f, 150f), Offset(4f, 150f), origin))
		assertEquals(Offset.Zero, state.wrapOffset)
		assertFalse(state.warpPending)
	}

	@Test
	fun missingOriginFoldsNothing() {
		val state = CursorWrapState(exactWarp())
		assertNull(state.warpTo(Offset(399f, 150f), Offset(4f, 150f), origin = null))
		assertEquals(Offset.Zero, state.wrapOffset)
	}

	@Test
	fun staleOldEventResolvesToTheLandingUntilAFreshOneArrives() {
		val state = CursorWrapState(exactWarp())
		val landed = state.warpTo(Offset(399f, 150f), Offset(4f, 150f), origin)
		assertNotNull(landed)
		// A queued pre-warp event still at the old place, farther than the reach from the landing, is stale.
		assertEquals(landed, state.resolveArrival(Offset(398f, 150f), reach))
		// The real post-warp event lands near the landing: fresh, and it clears the pending landing.
		assertNull(state.resolveArrival(landed + Offset(1f, 0f), reach))
		assertFalse(state.warpPending)
		// With the landing confirmed, even a far position is a legitimate move, not a stale one.
		assertNull(state.resolveArrival(Offset(398f, 150f), reach))
	}

	@Test
	fun theReachDecidesWhatIsStale() {
		val state = CursorWrapState(exactWarp())
		val landed = state.warpTo(Offset(399f, 150f), Offset(4f, 150f), origin)
		assertNotNull(landed)
		assertEquals(landed, state.resolveArrival(landed + Offset(60f, 0f), reach = 50f), "past the reach is stale")
		assertNull(state.resolveArrival(landed + Offset(60f, 0f), reach = 70f), "inside it is fresh")
	}

	@Test
	fun abandoningAPendingWarpTakesExactlyItsJumpBackOut() {
		val state = CursorWrapState(exactWarp())
		// A first warp that held.
		val firstLanding = state.warpTo(Offset(399f, 150f), Offset(4f, 150f), origin)
		assertNotNull(firstLanding)
		assertNull(state.resolveArrival(firstLanding, reach))
		val offsetAfterFirst = state.wrapOffset
		// A second that never holds: the events keep coming from where the cursor was.
		state.warpTo(Offset(390f, 150f), Offset(10f, 150f), origin)
		state.abandonPendingWarp()
		assertEquals(offsetAfterFirst, state.wrapOffset, "only the pending jump comes out")
		assertFalse(state.warpPending)
		assertNull(state.resolveArrival(Offset(390f, 150f), reach), "and nothing is stale after")
		// Abandoning with nothing pending changes nothing.
		state.abandonPendingWarp()
		assertEquals(offsetAfterFirst, state.wrapOffset)
	}

	@Test
	fun resetClearsTheGestureBookkeeping() {
		val state = CursorWrapState(exactWarp())
		state.warpTo(Offset(399f, 150f), Offset(4f, 150f), origin)
		state.reset()
		assertEquals(Offset.Zero, state.wrapOffset)
		// No pending landing survives the reset: nothing resolves as stale.
		assertNull(state.resolveArrival(Offset(398f, 150f), reach))
	}
}