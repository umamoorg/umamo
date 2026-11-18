package org.umamo.edit

import org.umamo.edit.SessionTestModels.meshModel
import org.umamo.edit.transform.beginBoxSelect
import org.umamo.edit.transform.beginBrush
import org.umamo.edit.transform.beginMeshOperator
import org.umamo.runtime.model.DrawableId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The area a gesture is latched in. */
private const val LEFT_AREA = "area-left"

/** A bystander area. */
private const val RIGHT_AREA = "area-right"

/**
 * Tests for AreaPointerOwnership.kt: a gesture latched in an area owns that area's pointer and no other's, and a held
 * select drag, which names no area, owns every area's.  The chrome over an area answers to its own gate, which the
 * armed deform brush does not hold.
 */
class AreaPointerOwnershipTest {
	/** With nothing latched, no area's pointer is owned. */
	@Test
	fun anIdleSessionOwnsNoPointer() {
		val session = EditorSession(meshModel())

		assertFalse(session.gestureOwnsAreaPointer(LEFT_AREA))
		assertFalse(session.gestureOwnsAreaPointer(RIGHT_AREA))
	}

	/** A modal operator owns the pointer of the area it latched in, and releases it when it clears. */
	@Test
	fun aModalOperatorOwnsItsOwnArea() {
		val session = EditorSession(meshModel())
		session.setMode(EditorMode.Edit)
		session.setMeshSelection(MeshSelectionOps.replace(session.meshSelection.value, DrawableId("d"), MeshElement.Vertex(0)))
		session.beginMeshOperator(MeshOperatorKind.Grab, LEFT_AREA)
		assertEquals(LEFT_AREA, assertNotNull(session.activeMeshOperator.value, "precondition: the operator latched").areaId)

		assertTrue(session.gestureOwnsAreaPointer(LEFT_AREA))
		assertFalse(session.gestureOwnsAreaPointer(RIGHT_AREA), "a bystander area keeps its pointer")

		session.clearMeshOperator()
		assertFalse(session.gestureOwnsAreaPointer(LEFT_AREA))
	}

	/** An armed select tool owns the pointer of the area it was armed in. */
	@Test
	fun anArmedSelectToolOwnsItsOwnArea() {
		val session = EditorSession(meshModel())
		session.beginBoxSelect(LEFT_AREA)
		assertEquals(LEFT_AREA, assertNotNull(session.activeSelectTool.value, "precondition: the tool armed").areaId)

		assertTrue(session.gestureOwnsAreaPointer(LEFT_AREA))
		assertFalse(session.gestureOwnsAreaPointer(RIGHT_AREA), "a bystander area keeps its pointer")

		session.clearSelectTool()
		assertFalse(session.gestureOwnsAreaPointer(LEFT_AREA))
	}

	/** An armed deform brush owns the pointer of the area it was armed in. */
	@Test
	fun anArmedBrushOwnsItsOwnArea() {
		val session = EditorSession(meshModel())
		session.setMode(EditorMode.Edit)
		session.beginBrush(LEFT_AREA)
		assertEquals(LEFT_AREA, assertNotNull(session.activeBrush.value, "precondition: the brush armed").areaId)

		assertTrue(session.gestureOwnsAreaPointer(LEFT_AREA))
		assertFalse(session.gestureOwnsAreaPointer(RIGHT_AREA), "a bystander area keeps its pointer")

		session.clearBrush()
		assertFalse(session.gestureOwnsAreaPointer(LEFT_AREA))
	}

	/** An armed Zoom Region owns the pointer of the area it was armed for. */
	@Test
	fun anArmedZoomRegionOwnsItsOwnArea() {
		val session = EditorSession(meshModel())
		session.armZoomRegion(LEFT_AREA)

		assertTrue(session.gestureOwnsAreaPointer(LEFT_AREA))
		assertFalse(session.gestureOwnsAreaPointer(RIGHT_AREA), "a bystander area keeps its pointer")

		session.disarmZoomRegion()
		assertFalse(session.gestureOwnsAreaPointer(LEFT_AREA))
	}

	/** A held select drag names no area, so it owns every area's pointer until it is released. */
	@Test
	fun aHeldSelectDragOwnsEveryArea() {
		val session = EditorSession(meshModel())
		session.setViewportGestureActive(true)

		assertTrue(session.gestureOwnsAreaPointer(LEFT_AREA))
		assertTrue(session.gestureOwnsAreaPointer(RIGHT_AREA))

		session.setViewportGestureActive(false)
		assertFalse(session.gestureOwnsAreaPointer(LEFT_AREA))
	}

	/** The rule itself matches each latch against the asking area alone. */
	@Test
	fun theRuleMatchesEachLatchAgainstTheAskingArea() {
		assertTrue(gestureOwnsAreaPointer(LEFT_AREA, operatorAreaId = LEFT_AREA, selectToolAreaId = null, brushAreaId = null, viewportGestureActive = false, zoomRegionArmedAreaId = null))
		assertTrue(gestureOwnsAreaPointer(LEFT_AREA, operatorAreaId = null, selectToolAreaId = LEFT_AREA, brushAreaId = null, viewportGestureActive = false, zoomRegionArmedAreaId = null))
		assertTrue(gestureOwnsAreaPointer(LEFT_AREA, operatorAreaId = null, selectToolAreaId = null, brushAreaId = LEFT_AREA, viewportGestureActive = false, zoomRegionArmedAreaId = null))
		assertTrue(gestureOwnsAreaPointer(LEFT_AREA, operatorAreaId = null, selectToolAreaId = null, brushAreaId = null, viewportGestureActive = false, zoomRegionArmedAreaId = LEFT_AREA))
		assertFalse(
			gestureOwnsAreaPointer(LEFT_AREA, operatorAreaId = RIGHT_AREA, selectToolAreaId = RIGHT_AREA, brushAreaId = RIGHT_AREA, viewportGestureActive = false, zoomRegionArmedAreaId = RIGHT_AREA),
			"latches held by another area leave this one alone",
		)
	}

	/**
	 * The armed brush holds the area's pointer - the wheel resizes the brush rather than zooming the surface - but
	 * not the area's chrome: the toolbar has to switch tools and the sidebar has to reach the brush's own settings
	 * while it is armed.
	 */
	@Test
	fun anArmedBrushLeavesTheAreasChromeUsable() {
		val session = EditorSession(meshModel())
		session.setMode(EditorMode.Edit)
		session.beginBrush(LEFT_AREA)

		assertTrue(session.gestureOwnsAreaPointer(LEFT_AREA), "the brush still holds the area's navigation")
		assertFalse(session.chromeStepsAsideForArea(LEFT_AREA), "the toolbar and the sidebar stay usable")
	}

	/** A drag in flight holds the chrome, so the drag does not press the buttons under it. */
	@Test
	fun aDragInFlightHoldsTheAreasChrome() {
		val session = EditorSession(meshModel())
		session.setViewportGestureActive(true)

		assertTrue(session.chromeStepsAsideForArea(LEFT_AREA))

		session.setViewportGestureActive(false)
		assertFalse(session.chromeStepsAsideForArea(LEFT_AREA))
	}

	/** An armed select tool holds the chrome, as it did before the brush's own rule. */
	@Test
	fun anArmedSelectToolHoldsTheAreasChrome() {
		val session = EditorSession(meshModel())
		session.beginBoxSelect(LEFT_AREA)

		assertTrue(session.chromeStepsAsideForArea(LEFT_AREA))
	}

	/** The chrome's rule matches each latch against the asking area alone. */
	@Test
	fun theChromeRuleMatchesEachLatchAgainstTheAskingArea() {
		assertTrue(chromeStepsAsideForArea(LEFT_AREA, operatorAreaId = LEFT_AREA, selectToolAreaId = null, viewportGestureActive = false, zoomRegionArmedAreaId = null))
		assertTrue(chromeStepsAsideForArea(LEFT_AREA, operatorAreaId = null, selectToolAreaId = LEFT_AREA, viewportGestureActive = false, zoomRegionArmedAreaId = null))
		assertTrue(chromeStepsAsideForArea(LEFT_AREA, operatorAreaId = null, selectToolAreaId = null, viewportGestureActive = true, zoomRegionArmedAreaId = null))
		assertTrue(chromeStepsAsideForArea(LEFT_AREA, operatorAreaId = null, selectToolAreaId = null, viewportGestureActive = false, zoomRegionArmedAreaId = LEFT_AREA))
		assertFalse(
			chromeStepsAsideForArea(LEFT_AREA, operatorAreaId = RIGHT_AREA, selectToolAreaId = RIGHT_AREA, viewportGestureActive = false, zoomRegionArmedAreaId = RIGHT_AREA),
			"latches held by another area leave this one alone",
		)
	}
}