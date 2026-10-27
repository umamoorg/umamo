package org.umamo.ui.model

import org.umamo.edit.Selection
import org.umamo.edit.SelectionTarget
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.Part
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.PuppetModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Drawable a. */
private val DRAWABLE_A = SelectionTarget.Drawable(DrawableId("a"))

/** Drawable b. */
private val DRAWABLE_B = SelectionTarget.Drawable(DrawableId("b"))

/** Part p. */
private val PART_P = SelectionTarget.Part(PartId("p"))

/** A drawable the model does not hold, as a selection left naming a deleted object does. */
private val DELETED = SelectionTarget.Drawable(DrawableId("deleted"))

/**
 * The item the one-item panels show: the active one, else the one last made active, else any selected one,
 * and never one the model no longer holds.
 */
class ShownTargetTest {
	/**
	 * A model with part p and drawables a and b.
	 *
	 * @return PuppetModel The model.
	 */
	private fun model(): PuppetModel {
		/**
		 * A mesh-less drawable.
		 *
		 * @param String id The drawable's id.
		 * @return Drawable The drawable.
		 */
		fun drawable(id: String): Drawable =
			Drawable(
				id = DrawableId(id),
				name = id,
				parentDeformerId = null,
				blendMode = BlendMode.Normal,
				maskedBy = emptyList(),
				mesh = null,
				geometryGrid = null,
			)

		return PuppetModel(
			parameters = emptyList(),
			parts = listOf(Part(PartId("p"), "P", children = emptyList())),
			deformers = emptyList(),
			drawables = listOf(drawable("a"), drawable("b")),
			rootChildren = emptyList(),
			rootPartId = null,
		)
	}

	/** The active item wins, even among several selected and over the one remembered. */
	@Test
	fun theActiveItemWins() {
		val selection = Selection(setOf(DRAWABLE_A, DRAWABLE_B, PART_P), DRAWABLE_B)
		assertEquals(DRAWABLE_B, shownTargetOf(selection, lastActive = PART_P, model()))
	}

	/** After a deselect the item last made active stays shown. */
	@Test
	fun theLastActiveItemOutlastsADeselect() {
		assertEquals(PART_P, shownTargetOf(Selection(), lastActive = PART_P, model()))
	}

	/** With targets but no active item (as an invert leaves it), the remembered item is shown before any selected one. */
	@Test
	fun aSelectionWithNoActiveFallsToTheRememberedItem() {
		assertEquals(DRAWABLE_A, shownTargetOf(Selection(setOf(DRAWABLE_B)), lastActive = DRAWABLE_A, model()))
		assertEquals(DRAWABLE_B, shownTargetOf(Selection(setOf(DRAWABLE_B)), lastActive = null, model()))
	}

	/** An item the model no longer holds is skipped, whether it is active, remembered, or merely selected. */
	@Test
	fun aDeletedItemIsNeverShown() {
		assertEquals(DRAWABLE_A, shownTargetOf(Selection(setOf(DELETED, DRAWABLE_A), DELETED), lastActive = DELETED, model()))
		assertNull(shownTargetOf(Selection(setOf(DELETED), DELETED), lastActive = DELETED, model()))
	}

	/** With nothing selected and nothing remembered there is nothing to show. */
	@Test
	fun nothingAtAllShowsNothing() {
		assertNull(shownTargetOf(Selection(), lastActive = null, model()))
	}
}