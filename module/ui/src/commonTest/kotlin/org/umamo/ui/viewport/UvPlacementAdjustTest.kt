package org.umamo.ui.viewport

import org.umamo.edit.EditorSession
import org.umamo.edit.IndividualOriginScope
import org.umamo.edit.MeshOperatorKind
import org.umamo.edit.ModalCaptureSource
import org.umamo.edit.OperatorParameter
import org.umamo.edit.Selection
import org.umamo.edit.SelectionTarget
import org.umamo.edit.TransformPivotMode
import org.umamo.edit.buildModalTransformCapture
import org.umamo.edit.intValue
import org.umamo.edit.setAtlasPlacements
import org.umamo.edit.withParameter
import org.umamo.format.art.LayerBounds
import org.umamo.runtime.model.AtlasPage
import org.umamo.runtime.model.AtlasPlacement
import org.umamo.runtime.model.AtlasTile
import org.umamo.runtime.model.AtlasTileId
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.PuppetAtlas
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.atlasPixelOf
import org.umamo.runtime.model.layerPixelOf
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the placement gesture as an operation settings strip client: a confirmed Grab / Rotate /
 * Scale registers its numbers as rows, an adjustment re-evaluates the SAME frozen gesture from the
 * base snapshot (the pivot it turns about is the one frozen at latch) and rewrites the gesture's own
 * step, the landing callback sees each adjustment, one undo returns to the base, and any other push
 * ends the record.
 */
class UvPlacementAdjustTest {
	private val tileId = AtlasTileId("a")
	private val drawableId = DrawableId("dA")
	private val pageSide = 64
	private val original = AtlasPlacement(pageIndex = 0, positionX = 4f, positionY = 4f, scaleX = 1f, scaleY = 1f, rotationDegrees = 0f)
	private val trim = LayerBounds(0, 0, 10, 10)

	/** The island's display positions: the tile's 10x10 art at page (4, 4), display y up. */
	private val positions = floatArrayOf(4f, 60f, 14f, 60f, 4f, 50f, 14f, 50f)
	private val indices = intArrayOf(0, 1, 2, 1, 3, 2)

	private fun model(): PuppetModel =
		PuppetModel(
			parameters = emptyList(),
			parts = emptyList(),
			deformers = emptyList(),
			drawables =
				listOf(
					Drawable(
						id = drawableId,
						name = "dA",
						parentDeformerId = null,
						blendMode = BlendMode.Normal,
						maskedBy = emptyList(),
						mesh =
							DrawableMesh(
								floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f),
								floatArrayOf(4f / 64, 4f / 64, 14f / 64, 4f / 64, 4f / 64, 14f / 64, 14f / 64, 14f / 64),
								indices,
							),
						geometryGrid = null,
						atlasTileId = tileId,
					),
				),
			rootChildren = emptyList(),
			rootPartId = null,
			canvasWidth = 100f,
			canvasHeight = 100f,
			worldOriginX = 50f,
			worldOriginY = 50f,
			atlas = PuppetAtlas(pages = listOf(AtlasPage(pageSide, pageSide)), tiles = listOf(AtlasTile(tileId, "a", 10, 10, original))),
		)

	/**
	 * A frozen gesture over the one island, the way the overlay builds it at latch: the shared
	 * capture's median anchor is the tile's pivot.
	 *
	 * @param MeshOperatorKind kind The latched operator.
	 * @return PlacementGesture The gesture.
	 */
	private fun gesture(kind: MeshOperatorKind): PlacementGesture {
		val transform =
			assertNotNull(
				buildModalTransformCapture(
					sources = listOf(ModalCaptureSource(drawableId, positions.copyOf(), indices, setOf(0, 1, 2, 3))),
					pivotMode = TransformPivotMode.MedianPoint,
					individualOriginScope = IndividualOriginScope.WholeMesh,
					operatorKind = kind,
					activeAnchor = null,
					cursorAnchor = null,
				),
			)
		val mover = PlacementMover(tileId, original, trim, null, null, null, emptyList(), transform.anchor.first, transform.anchor.second, crop = null)
		return PlacementGesture(
			transform = transform,
			movers = listOf(mover),
			bystanders = emptyList(),
			occupancy = null,
			tileByDrawable = mapOf(drawableId to tileId),
			frozenPositionsByDrawable = mapOf(drawableId to positions.copyOf()),
			pageWidth = pageSide,
			pageHeight = pageSide,
			extrude = 2,
		)
	}

	private fun evaluate(gesture: PlacementGesture, parameters: PlacementGestureParameters): PlacementDragResult =
		evaluatePlacementDrag(gesture.transform.operatorKind, parameters, gesture.movers, gesture.bystanders, gesture.occupancy, gesture.pageWidth, gesture.pageHeight, gesture.extrude)

	/**
	 * Runs a gesture to its confirm: evaluates, commits the placements under the operator's label, and
	 * registers the record.
	 *
	 * @param EditorSession session The session.
	 * @param PlacementGesture gesture The frozen gesture.
	 * @param PlacementGestureParameters parameters The drag's parameters at confirm.
	 * @param Function onLanded Receives each adjustment's evaluation.
	 * @return PlacementDragResult The drag's evaluation.
	 */
	private fun confirm(session: EditorSession, gesture: PlacementGesture, parameters: PlacementGestureParameters, onLanded: (PlacementDragResult) -> Unit): PlacementDragResult {
		val dragged = evaluate(gesture, parameters)
		session.setAtlasPlacements(changedPlacements(gesture.movers, dragged), gesture.transform.operatorKind)
		assertNotNull(registerPlacementAdjustment(session, "uv-1", gesture, dragged, onLanded), "the gesture registers")
		return dragged
	}

	private fun placementOf(session: EditorSession): AtlasPlacement = assertNotNull(session.model.value.atlas.tileById.getValue(tileId).placement)

	private fun assertClose(expected: Float, actual: Float, message: String) {
		assertTrue(abs(expected - actual) < 1e-3f, "$message: expected $expected, was $actual")
	}

	@Test
	fun aGrabRegistersItsMoveAndAnAdjustmentRewritesTheStepFromTheBase() {
		val session = EditorSession(model())
		val gesture = gesture(MeshOperatorKind.Grab)
		val landed = ArrayList<PlacementDragResult>()

		confirm(session, gesture, PlacementGestureParameters(7f, -5f, 1f, 1f, 0f)) { result -> landed.add(result) }

		val record = assertNotNull(session.adjustableOperation.value)
		assertEquals("uv-1", record.areaId)
		assertEquals("change.document.atlasPlacement", record.change.labelKey)
		assertEquals(7, record.parameters.intValue(PlacementParameterKeys.DELTA_X, -1))
		assertEquals(5, record.parameters.intValue(PlacementParameterKeys.DELTA_Y, -1), "display y up 5 is page y down 5")
		assertEquals(original.copy(positionX = 11f, positionY = 9f), placementOf(session))
		val steps = session.historyView.value.steps.size

		session.adjustLastOperation(
			record.parameters.withParameter(
				PlacementParameterKeys.DELTA_X,
				OperatorParameter.IntParameter(PlacementParameterKeys.DELTA_X, PlacementParameterKeys.DELTA_X, 20, -pageSide, pageSide),
			),
		)

		assertEquals(original.copy(positionX = 24f, positionY = 9f), placementOf(session), "the rerun moved the tile from the BASE by the new delta")
		assertEquals(steps, session.historyView.value.steps.size, "the step was rewritten, not added")
		assertEquals(20, landed.single().status.deltaX, "the landing callback saw the adjustment")
		session.undo()
		assertEquals(original, placementOf(session), "one undo returns to the base")
		assertNull(session.adjustableOperation.value, "undo ends the record")
	}

	@Test
	fun aRotateAdjustsAboutTheFrozenPivotAndAScaleItsFactors() {
		val session = EditorSession(model())
		val rotate = gesture(MeshOperatorKind.Rotate)
		confirm(session, rotate, PlacementGestureParameters(0f, 0f, 1f, 1f, 0.5f)) {}
		val rotateRecord = assertNotNull(session.adjustableOperation.value)
		assertEquals("change.document.atlasPlacement.rotate", rotateRecord.change.labelKey)

		session.adjustLastOperation(
			rotateRecord.parameters.withParameter(
				PlacementParameterKeys.ANGLE,
				OperatorParameter.FloatParameter(PlacementParameterKeys.ANGLE, PlacementParameterKeys.ANGLE, 90f, -360f, 360f),
			),
		)

		val turned = placementOf(session)
		assertClose(90f, turned.rotationDegrees, "the page-space angle is the row's")
		// The pivot frozen at latch - the island's center, page (9, 9) - is a fixed point of the turn.
		val pivotInTile = assertNotNull(layerPixelOf(original, 9f, 9f))
		val pivotAfter = atlasPixelOf(turned, pivotInTile[0], pivotInTile[1])
		assertClose(9f, pivotAfter[0], "pivot x fixed")
		assertClose(9f, pivotAfter[1], "pivot y fixed")

		session.undo()
		val scale = gesture(MeshOperatorKind.Scale)
		confirm(session, scale, PlacementGestureParameters(0f, 0f, 1.5f, 1.5f, 0f)) {}
		val scaleRecord = assertNotNull(session.adjustableOperation.value)
		assertEquals("change.document.atlasPlacement.scale", scaleRecord.change.labelKey)

		session.adjustLastOperation(
			scaleRecord.parameters.withParameter(
				PlacementParameterKeys.SCALE_X,
				OperatorParameter.FloatParameter(PlacementParameterKeys.SCALE_X, PlacementParameterKeys.SCALE_X, 2f, 0.01f, 100f),
			),
		)

		val scaled = placementOf(session)
		assertClose(2f, scaled.scaleX, "one factor edited alone scales that tile axis")
		assertClose(1.5f, scaled.scaleY, "the other keeps the drag's factor")
		assertClose(0f, scaled.rotationDegrees, "no shear")
	}

	@Test
	fun anyOtherPushEndsTheRecord() {
		val session = EditorSession(model())
		confirm(session, gesture(MeshOperatorKind.Grab), PlacementGestureParameters(3f, 0f, 1f, 1f, 0f)) {}
		assertNotNull(session.adjustableOperation.value)

		session.setSelection(Selection(setOf(SelectionTarget.Drawable(drawableId)), SelectionTarget.Drawable(drawableId)))

		assertNull(session.adjustableOperation.value, "a selection push is a push")
	}
}