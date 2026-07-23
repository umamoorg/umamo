package org.umamo.ui.workspace

import org.umamo.edit.SelectionTarget
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.PartId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the relation-pick slot's resolve contract: only an accepted kind resolves (and does so exactly
 * once, clearing the request first), a click on the wrong kind leaves the pick armed rather than silently
 * ending it, and cancelling clears without firing.
 */
class RelationPickTest {
	private val drawable = SelectionTarget.Drawable(DrawableId("d1"))
	private val part = SelectionTarget.Part(PartId("p1"))
	private val deformer = SelectionTarget.Deformer(DeformerId("w1"))

	@Test
	fun kindsMapFromTargets() {
		assertEquals(PickKind.Drawable, drawable.pickKind())
		assertEquals(PickKind.Part, part.pickKind())
		assertEquals(PickKind.Deformer, deformer.pickKind())
	}

	@Test
	fun resolvesAnAcceptedKindOnceAndClears() {
		val controller = RelationPickController()
		val picked = mutableListOf<SelectionTarget>()
		controller.arm(setOf(PickKind.Drawable)) { target -> picked.add(target) }

		assertTrue(controller.resolve(drawable))
		assertEquals(listOf<SelectionTarget>(drawable), picked)
		assertNull(controller.request, "the request clears as it fires")

		// A second click has nothing to resolve and must not re-fire the callback.
		assertFalse(controller.resolve(drawable))
		assertEquals(listOf<SelectionTarget>(drawable), picked)
	}

	@Test
	fun anUnacceptedKindLeavesThePickArmed() {
		val controller = RelationPickController()
		val picked = mutableListOf<SelectionTarget>()
		controller.arm(setOf(PickKind.Part)) { target -> picked.add(target) }

		assertFalse(controller.resolve(drawable), "a drawable is not accepted")
		assertTrue(picked.isEmpty())
		assertTrue(controller.request != null, "a stray click on the wrong kind does not end the pick")

		assertTrue(controller.resolve(part))
		assertEquals(listOf<SelectionTarget>(part), picked)
	}

	@Test
	fun hoverReportsOnlyAcceptedKindsAndClearsWithThePick() {
		val controller = RelationPickController()
		controller.arm(setOf(PickKind.Drawable)) { }

		controller.hover(drawable)
		assertEquals(drawable, controller.hoveredTarget)

		// A surface may report freely; an unaccepted kind simply reads as nothing to pick.
		controller.hover(part)
		assertNull(controller.hoveredTarget)

		// Resolving clears the hover along with the request, so no stale label survives the pick.
		controller.hover(drawable)
		controller.resolve(drawable)
		assertNull(controller.hoveredTarget)

		// With nothing armed a report is ignored outright.
		controller.hover(drawable)
		assertNull(controller.hoveredTarget)
	}

	@Test
	fun unhoverOnlyClearsTheSurfaceStillReported() {
		val controller = RelationPickController()
		controller.arm(setOf(PickKind.Drawable, PickKind.Part)) { }

		controller.hover(drawable)
		// A surface the pointer already left must not clear a hover another surface has since published.
		controller.unhover(part)
		assertEquals(drawable, controller.hoveredTarget)

		controller.unhover(drawable)
		assertNull(controller.hoveredTarget)
	}

	@Test
	fun anArmedPickSwallowsClicksItWillNotAccept() {
		val controller = RelationPickController()
		val picked = mutableListOf<SelectionTarget>()

		// With nothing armed a surface handles its own clicks as usual.
		assertEquals(PickClickOutcome.Ignored, controller.click(part))

		controller.arm(setOf(PickKind.Drawable)) { target -> picked.add(target) }

		// The regression: a part clicked during a drawable-only pick must NOT fall through to selection -
		// selecting would swap the Properties panel, and the arming field with it, out from under the pick.
		assertEquals(PickClickOutcome.Swallowed, controller.click(part))
		assertTrue(picked.isEmpty())
		assertTrue(controller.request != null, "the pick survives so the user can click a valid target")

		assertEquals(PickClickOutcome.Resolved, controller.click(drawable))
		assertEquals(listOf<SelectionTarget>(drawable), picked)
		// The pick is over, so the surface owns its clicks again.
		assertEquals(PickClickOutcome.Ignored, controller.click(part))
	}

	@Test
	fun cancelClearsWithoutFiring() {
		val controller = RelationPickController()
		var fired = false
		controller.arm(setOf(PickKind.Drawable, PickKind.Part)) { fired = true }

		controller.hover(drawable)
		controller.cancel()

		assertNull(controller.request)
		assertNull(controller.hoveredTarget)
		assertFalse(fired)
		assertFalse(controller.resolve(drawable))
	}
}
