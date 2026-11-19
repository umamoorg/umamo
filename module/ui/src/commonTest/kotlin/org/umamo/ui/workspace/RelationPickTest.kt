package org.umamo.ui.workspace

import org.umamo.edit.SelectionTarget
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.Part
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.PuppetModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the relation-pick slot's resolve contract: only an accepted kind the field admits resolves (and does
 * so exactly once, clearing the request first), a click on anything else leaves the pick armed rather than
 * silently ending it, and cancelling clears without firing.  A drawable stands for its part or its parent
 * deformer in a pick that does not take drawables, read from the model as it is when the pointer reaches it.
 */
class RelationPickTest {
	private val drawable = SelectionTarget.Drawable(DrawableId("d1"))
	private val part = SelectionTarget.Part(PartId("p1"))
	private val deformer = SelectionTarget.Deformer(DeformerId("w1"))

	/**
	 * A one-drawable rig: [drawable] in [part], deformed by [deformer].
	 *
	 * @return PuppetModel The rig.
	 */
	private fun rig(): PuppetModel =
		PuppetModel(
			parameters = emptyList(),
			parts = listOf(Part(part.id, "P", listOf(OrgChild.Drawable(drawable.id)))),
			deformers = emptyList(),
			drawables =
				listOf(
					Drawable(
						id = drawable.id,
						name = "D",
						parentDeformerId = deformer.id,
						blendMode = BlendMode.Normal,
						maskedBy = emptyList(),
						mesh = null,
						geometryGrid = null,
					),
				),
			rootChildren = listOf(OrgChild.Part(part.id)),
			rootPartId = null,
		)

	@Test
	fun aDrawableStandsForItsParentDeformerInADeformerPick() {
		val controller = RelationPickController()
		val model = rig()
		val picked = mutableListOf<SelectionTarget>()
		controller.arm(setOf(PickKind.Deformer), model = { model }) { target -> picked.add(target) }

		controller.hover(drawable)
		assertEquals(deformer, controller.hoveredTarget, "the badge names the deformer a click binds")

		// The hover was reported as the drawable, so the surface withdraws it as the drawable.
		controller.unhover(drawable)
		assertNull(controller.hoveredTarget)

		assertEquals(PickClickOutcome.Resolved, controller.click(drawable))
		assertEquals(listOf<SelectionTarget>(deformer), picked)
	}

	@Test
	fun aDrawableStandsForItsOwningPartInAPartPick() {
		val controller = RelationPickController()
		val model = rig()
		val picked = mutableListOf<SelectionTarget>()
		controller.arm(setOf(PickKind.Part), model = { model }) { target -> picked.add(target) }

		assertTrue(controller.resolve(drawable))
		assertEquals(listOf<SelectionTarget>(part), picked)
	}

	@Test
	fun aRefusedDrawableNeverBindsItsPartInstead() {
		val controller = RelationPickController()
		val model = rig()
		controller.arm(setOf(PickKind.Drawable, PickKind.Part), admits = { target -> target != drawable }, model = { model }) { }

		controller.hover(drawable)
		assertNull(controller.hoveredTarget, "a pick that takes drawables reads a drawable as itself")
		assertEquals(PickClickOutcome.Swallowed, controller.click(drawable))
	}

	@Test
	fun aStandInTheFieldDoesNotAdmitIsAMiss() {
		val controller = RelationPickController()
		val model = rig()
		controller.arm(setOf(PickKind.Deformer), admits = { target -> target != deformer }, model = { model }) { }

		controller.hover(drawable)
		assertNull(controller.hoveredTarget)
		assertEquals(PickClickOutcome.Swallowed, controller.click(drawable))
	}

	@Test
	fun withoutAModelADrawableStandsOnlyForItself() {
		val controller = RelationPickController()
		controller.arm(setOf(PickKind.Deformer)) { }

		assertEquals(PickClickOutcome.Swallowed, controller.click(drawable))
	}

	@Test
	fun theStandInFollowsTheModel() {
		val controller = RelationPickController()
		var model = rig()
		controller.arm(setOf(PickKind.Deformer), model = { model }) { }

		controller.hover(drawable)
		assertEquals(deformer, controller.hoveredTarget)

		val other = SelectionTarget.Deformer(DeformerId("w2"))
		model = model.copy(drawables = model.drawables.map { held -> held.copy(parentDeformerId = other.id) })
		controller.hover(drawable)
		assertEquals(other, controller.hoveredTarget, "an edit made while armed is read, not the model the pick was armed over")
	}

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
	fun aTargetTheFieldDoesNotAdmitReadsAsNothingUnderThePointer() {
		val controller = RelationPickController()
		val own = SelectionTarget.Deformer(DeformerId("own"))
		controller.arm(setOf(PickKind.Deformer), admits = { target -> target != own }) { }

		controller.hover(own)
		assertNull(controller.hoveredTarget, "the badge never names a target the click would refuse")

		controller.hover(deformer)
		assertEquals(deformer, controller.hoveredTarget)
	}

	@Test
	fun aTargetTheFieldDoesNotAdmitLeavesThePickArmed() {
		val controller = RelationPickController()
		val picked = mutableListOf<SelectionTarget>()
		val own = SelectionTarget.Deformer(DeformerId("own"))
		controller.arm(setOf(PickKind.Deformer), admits = { target -> target != own }) { target -> picked.add(target) }

		assertFalse(controller.resolve(own), "a deformer cannot be bound as its own parent")
		assertTrue(picked.isEmpty())
		assertNotNull(controller.request, "the refused click does not end the pick")

		assertTrue(controller.resolve(deformer))
		assertEquals(listOf<SelectionTarget>(deformer), picked)
	}

	@Test
	fun anArmedPickSwallowsAClickOnATargetItDoesNotAdmit() {
		val controller = RelationPickController()
		val own = SelectionTarget.Part(PartId("own"))
		controller.arm(setOf(PickKind.Part), admits = { target -> target != own }) { }

		// Swallowed, not Ignored: falling through would select the part and swap the arming field away.
		assertEquals(PickClickOutcome.Swallowed, controller.click(own))
		assertNotNull(controller.request)
		assertEquals(PickClickOutcome.Resolved, controller.click(part))
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

	/**
	 * cancelFor is what the arming field's teardown calls, so it MUST be scoped: an armed pick parks a
	 * callback and a full-window hidden cursor in shell state, and a blanket cancel from an unrelated field
	 * disposing would silently kill a pick the user is still in the middle of.
	 */
	@Test
	fun cancelForOnlyCancelsThePickItsOwnerArmed() {
		val controller = RelationPickController()
		controller.arm(setOf(PickKind.Drawable), owner = "field.a") {}

		// Another field going away must not touch this pick.
		controller.cancelFor("field.b")
		assertNotNull(controller.request, "a different owner cannot cancel it")
		assertTrue(controller.isPickingFor("field.a"))

		// A null owner never matches, so an unowned teardown cannot cancel either.
		controller.cancelFor(null)
		assertNotNull(controller.request)

		controller.cancelFor("field.a")
		assertNull(controller.request, "the arming field's teardown does cancel it")
	}
}