package org.umamo.ui.viewport.viewport2d

import org.umamo.edit.EditorMode
import org.umamo.edit.Selection
import org.umamo.edit.SelectionTarget
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.DrawableId
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins which drawables the viewport tints in each mode: Object mode tints the selection's drawables, its active
 * one apart, and Edit and Pose mode tint nothing, whatever the selection holds.
 */
class SelectionTintTest {
	private val first = DrawableId("first")
	private val second = DrawableId("second")
	private val selection =
		Selection(
			setOf(SelectionTarget.Drawable(first), SelectionTarget.Drawable(second), SelectionTarget.Deformer(DeformerId("w"))),
			SelectionTarget.Drawable(second),
		)

	/** Object mode tints the selected drawables, the active one apart, and never the selected deformer. */
	@Test
	fun objectModeTintsTheSelectedDrawables() {
		assertEquals(setOf(first, second) to second, selectionTintOf(selection, EditorMode.Object, previewSelection = null))
	}

	/** An in-flight circle stroke shows what it has painted, with no active drawable yet. */
	@Test
	fun aCircleStrokeTintsWhatItPainted() {
		assertEquals(setOf(first) to null, selectionTintOf(selection, EditorMode.Object, previewSelection = setOf(first)))
	}

	/** Edit and Pose mode tint nothing, so a selected drawable never reads as something they act on. */
	@Test
	fun editAndPoseModeTintNothing() {
		for (mode in listOf(EditorMode.Edit, EditorMode.Pose)) {
			assertEquals(emptySet<DrawableId>() to null, selectionTintOf(selection, mode, previewSelection = setOf(first)), "$mode")
		}
	}
}