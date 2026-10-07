package org.umamo.ui.viewport.viewport2d

import androidx.compose.ui.geometry.Offset
import org.umamo.edit.Selection
import org.umamo.edit.SelectionTarget
import org.umamo.edit.structure.toggleSelectable
import org.umamo.runtime.model.DrawableId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * Pins the Object overlay's region selection over drawable centroids ([circleStampSelection],
 * [boxSelection]) and the one anchor holder they read ([ObjectSelectionAnchors]).  The rig's quad centroid
 * sits at screen (200, 150) and the other drawable's at about (346.7, 136.7).
 */
class ObjectGizmoSelectionTest {
	private val quad = SelectionTarget.Drawable(RIG_QUAD)
	private val other = SelectionTarget.Drawable(RIG_OTHER)
	private val centroids = mapOf(RIG_QUAD to floatArrayOf(10f, -10f), RIG_OTHER to floatArrayOf(140f / 3f, -20f / 3f))
	private val model = gizmoRigModel()

	/**
	 * One stamp over the rig.
	 *
	 * @param Selection working The working selection.
	 * @param Boolean erasing Whether the stamp erases.
	 * @param Offset center The brush center on screen.
	 * @return Selection The painted selection.
	 */
	private fun stamp(working: Selection, erasing: Boolean, center: Offset): Selection =
		circleStampSelection(working, erasing, center, 20f, RIG_CAMERA, RIG_AREA_SIZE, centroids, model)

	/** A stamp adds what it encloses and makes the last of it active. */
	@Test
	fun aStampAddsWhatItEncloses() {
		val painted = stamp(Selection(setOf(other), other), erasing = false, center = rigScreenOf(10f, -10f))

		assertEquals(setOf(other, quad), painted.targets)
		assertEquals(quad, painted.active)
	}

	/** An erasing stamp removes; when it removes the active target, the last one left becomes active. */
	@Test
	fun anErasingStampRemovesAndMovesTheActive() {
		val painted = stamp(Selection(setOf(other, quad), quad), erasing = true, center = rigScreenOf(10f, -10f))

		assertEquals(setOf(other), painted.targets)
		assertEquals(other, painted.active)
	}

	/** A stamp that encloses nothing returns the working selection itself. */
	@Test
	fun anEmptyStampReturnsTheWorkingSelection() {
		val working = Selection(setOf(quad), quad)

		assertSame(working, stamp(working, erasing = false, center = Offset(20f, 20f)))
	}

	/** A drawable that cannot be selected is never painted. */
	@Test
	fun aStampSkipsAnUnselectableDrawable() {
		val session = gizmoObjectSession(emptyList())
		session.toggleSelectable(quad)

		val painted = circleStampSelection(Selection(), false, rigScreenOf(10f, -10f), 20f, RIG_CAMERA, RIG_AREA_SIZE, centroids, session.model.value)

		assertEquals(emptySet(), painted.targets)
	}

	/** A box dragged in any direction encloses the same centroids. */
	@Test
	fun aReversedBoxEnclosesTheSame() {
		val forward = boxSelection(Selection(), Offset(180f, 130f), Offset(220f, 170f), false, RIG_CAMERA, RIG_AREA_SIZE, centroids, model)
		val reversed = boxSelection(Selection(), Offset(220f, 170f), Offset(180f, 130f), false, RIG_CAMERA, RIG_AREA_SIZE, centroids, model)

		assertEquals(setOf(quad), forward.targets)
		assertEquals(forward.targets, reversed.targets)
		assertEquals(quad, reversed.active)
	}

	/** An additive box that encloses nothing keeps the selection and its active target. */
	@Test
	fun anEmptyAdditiveBoxKeepsTheActive() {
		val current = Selection(setOf(quad), quad)

		val boxed = boxSelection(current, Offset(20f, 20f), Offset(60f, 60f), true, RIG_CAMERA, RIG_AREA_SIZE, centroids, model)

		assertEquals(setOf(quad), boxed.targets)
		assertEquals(quad, boxed.active)
	}

	/** The holder reads its source only when refreshed, and serves that snapshot until the next refresh. */
	@Test
	fun theAnchorsServeTheirLastSnapshot() {
		var reads = 0
		var source = mapOf(RIG_QUAD to floatArrayOf(1f, 2f))
		val anchors =
			ObjectSelectionAnchors {
				reads++
				source
			}
		assertEquals(emptyMap(), anchors.centroids)

		anchors.refresh()
		source = mapOf(RIG_OTHER to floatArrayOf(3f, 4f))

		assertEquals(setOf(RIG_QUAD), anchors.centroids.keys)
		assertEquals(1, reads)
	}

	/** The tint preview's ids are the selection's drawables, whatever else it holds. */
	@Test
	fun theSelectedDrawableIdsSkipOtherTargets() {
		val selection = Selection(setOf(quad, SelectionTarget.Drawable(DrawableId("x"))), quad)

		assertEquals(setOf(RIG_QUAD, DrawableId("x")), selection.selectedDrawableIds())
	}
}