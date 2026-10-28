package org.umamo.edit

import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.PartId
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Whether a selection target names an object the model still has, for each kind of target. */
class HasTargetTest {
	/** A part, a drawable, and a deformer the model holds are all found. */
	@Test
	fun everyKindTheModelHoldsIsFound() {
		val model = SessionTestModels.model()
		assertTrue(model.hasTarget(SelectionTarget.Part(PartId("a"))))
		assertTrue(model.hasTarget(SelectionTarget.Drawable(DrawableId("d"))))
		assertTrue(model.hasTarget(SelectionTarget.Deformer(DeformerId("w"))))
	}

	/** An id the model lacks is not found, whatever its kind, and a kind is not mistaken for another. */
	@Test
	fun aMissingIdIsNotFound() {
		val model = SessionTestModels.model()
		assertFalse(model.hasTarget(SelectionTarget.Part(PartId("gone"))))
		assertFalse(model.hasTarget(SelectionTarget.Drawable(DrawableId("gone"))))
		assertFalse(model.hasTarget(SelectionTarget.Deformer(DeformerId("gone"))))
		assertFalse(model.hasTarget(SelectionTarget.Drawable(DrawableId("a"))), "part a is not a drawable")
	}
}