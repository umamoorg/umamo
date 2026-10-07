package org.umamo.ui.transform

import org.umamo.edit.EditorMode
import org.umamo.edit.EditorSession
import org.umamo.edit.Selection
import org.umamo.edit.SelectionTarget
import org.umamo.edit.transform.meshBounds
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.MeshDeltaForm
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.WarpLatticeForm
import org.umamo.runtime.model.originRelativeX
import org.umamo.runtime.model.originRelativeZ
import org.umamo.runtime.model.worldXFromOriginRelative
import org.umamo.runtime.model.worldZFromOriginRelative
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins the Properties Transform panel to the space the user actually sees.
 *
 * The bug this guards against: a keyformed drawable's base is not its displayed shape
 * (`displayed = localPositions + Σ wᵢ·Δᵢ`).  Reading or writing the base directly showed numbers unrelated to
 * the drawable - on a corpus model, a base 1.9 units wide for a drawable 183.8 wide - so a small typed nudge
 * became an enormous transform.  These tests assert the rows measure and write the DISPLAYED geometry
 * instead, that the write moves the canvas mesh and the base each in its own space, and that the write is
 * refused off the neutral pose (where the deformer-chain inverse is not exact).  Edit mode shows the rig at
 * rest, so there the rows measure and write the rest shape, whatever pose Object mode left.
 */
class DrawableWorldTransformTest {
	private val drawableId = DrawableId("d")
	private val parameterId = ParameterId("Param")

	/** A 10 x 10 base quad that the keyform grid then displaces and inflates to 100 x 100 at the default. */
	private val basePositions = floatArrayOf(0f, 0f, 10f, 0f, 10f, 10f, 0f, 10f)

	/** Deltas taking the 10 x 10 base to a 100 x 100 square at local (500, 500) - the "base is not what you see" case. */
	private val neutralDeltas =
		floatArrayOf(
			450f,
			450f,
			540f,
			450f,
			540f,
			540f,
			450f,
			540f,
		)

	/**
	 * The one-drawable model every test measures.
	 *
	 * @param Float canvasSize The square canvas's side, with the world origin at its center; 0 leaves both at
	 * their (0, 0) defaults, where world and origin-relative readings coincide.
	 * @param Float posedShiftX How far right of rest the square sits at the parameter's maximum, so a posed rig
	 * can show a shape the rest pose does not.
	 * @return PuppetModel The model.
	 */
	private fun model(canvasSize: Float = 0f, posedShiftX: Float = 0f): PuppetModel =
		PuppetModel(
			parameters = listOf(Parameter(parameterId, "Param", min = -1f, max = 1f, default = 0f)),
			parts = emptyList(),
			deformers = emptyList(),
			drawables =
				listOf(
					Drawable(
						id = drawableId,
						name = "d",
						parentDeformerId = null,
						blendMode = BlendMode.Normal,
						maskedBy = emptyList(),
						mesh = DrawableMesh.withLocalEqualToCanvas(basePositions.copyOf(), FloatArray(basePositions.size), intArrayOf(0, 1, 2)),
						geometryGrid =
							KeyformGrid(
								axes = listOf(KeyformAxis(parameterId, floatArrayOf(-1f, 0f, 1f))),
								cells =
									listOf(
										KeyformCell(intArrayOf(0), MeshDeltaForm(neutralDeltas.copyOf())),
										KeyformCell(intArrayOf(1), MeshDeltaForm(neutralDeltas.copyOf())),
										KeyformCell(intArrayOf(2), MeshDeltaForm(shiftedX(neutralDeltas, posedShiftX))),
									),
							),
					),
				),
			rootChildren = emptyList(),
			rootPartId = null,
			canvasWidth = canvasSize,
			canvasHeight = canvasSize,
			worldOriginX = canvasSize / 2f,
			worldOriginZ = -(canvasSize / 2f),
		)

	/**
	 * A copy of [deltas] with every x component moved right by [shiftX].
	 *
	 * @param FloatArray deltas The interleaved (x, y) deltas.
	 * @param Float shiftX How far right to move them.
	 * @return FloatArray The shifted copy.
	 */
	private fun shiftedX(deltas: FloatArray, shiftX: Float): FloatArray =
		FloatArray(deltas.size) { componentIndex -> if (componentIndex % 2 == 0) deltas[componentIndex] + shiftX else deltas[componentIndex] }

	/**
	 * Puts [session] in Edit mode with the drawable selected, which entering it requires.
	 *
	 * @param EditorSession session The session.
	 */
	private fun enterEditMode(session: EditorSession) {
		val target = SelectionTarget.Drawable(drawableId)
		session.setSelection(Selection(setOf(target), target))
		session.setMode(EditorMode.Edit)
		assertEquals(EditorMode.Edit, session.mode.value, "the fixture must really be in Edit mode")
	}

	/**
	 * Asserts [actual]'s drawable carries exactly [expected]'s rest arrays.
	 *
	 * @param PuppetModel expected The model whose mesh is expected.
	 * @param PuppetModel actual The model to check.
	 */
	private fun assertSameRest(expected: PuppetModel, actual: PuppetModel) {
		val expectedMesh = expected.drawables.single().mesh!!
		val actualMesh = actual.drawables.single().mesh!!
		assertContentEquals(expectedMesh.positions, actualMesh.positions, "canvas mesh")
		assertContentEquals(expectedMesh.localPositions, actualMesh.localPositions, "base")
	}

	/**
	 * One unkeyed drawable under a one-cell warp whose lattice spreads its unit square over canvas pixels
	 * 100..300 on both axes, so a canvas pixel is 1/200 of a lattice unit.
	 *
	 * @param FloatArray canvas The drawable's canvas mesh.
	 * @param FloatArray local  The drawable's base in the lattice.
	 * @return PuppetModel The model.
	 */
	private fun warpChildModel(canvas: FloatArray, local: FloatArray): PuppetModel {
		val warpId = DeformerId("warp")
		val lattice = floatArrayOf(100f, 100f, 300f, 100f, 100f, 300f, 300f, 300f)
		return PuppetModel(
			parameters = listOf(Parameter(parameterId, "Param", min = -1f, max = 1f, default = 0f)),
			parts = emptyList(),
			deformers =
				listOf(
					Deformer.Warp(
						id = warpId,
						name = "warp",
						parent = null,
						partId = null,
						rows = 1,
						columns = 1,
						isQuadTransform = true,
						geometryGrid = KeyformGrid(listOf(KeyformAxis(parameterId, floatArrayOf(0f))), listOf(KeyformCell(intArrayOf(0), WarpLatticeForm(lattice)))),
					),
				),
			drawables =
				listOf(
					Drawable(
						id = drawableId,
						name = "d",
						parentDeformerId = warpId,
						blendMode = BlendMode.Normal,
						maskedBy = emptyList(),
						mesh = DrawableMesh(positions = canvas, localPositions = local, uvs = FloatArray(canvas.size), indices = intArrayOf(0, 1, 2)),
						geometryGrid = null,
					),
				),
			rootChildren = emptyList(),
			rootPartId = null,
		)
	}

	/**
	 * Under a deformer the two rest arrays move apart: a world move shifts the canvas mesh by the same canvas
	 * pixels, and the base by the lattice's own measure of them - 20 pixels across is a tenth of a unit.
	 */
	@Test
	fun aWarpChildMovesItsCanvasMeshInPixelsAndItsBaseInTheLattice() {
		val canvas = floatArrayOf(120f, 120f, 160f, 120f, 160f, 160f, 120f, 160f)
		val local = floatArrayOf(0.1f, 0.1f, 0.3f, 0.1f, 0.3f, 0.3f, 0.1f, 0.3f)
		val session = EditorSession(warpChildModel(canvas, local))
		val before = drawableWorldTransform(session.model.value, session.pose.value, drawableId)!!.bounds
		assertEquals(140f, before.centerX, 1e-3f, "precondition: the lattice puts the base where the canvas mesh is")

		// 20 right and 10 up in world, which is 10 DOWN the canvas's y.
		session.setDrawableWorldCenter(drawableId, before.centerX + 20f, before.centerY + 10f)

		val mesh = session.model.value.drawables.single().mesh!!
		for (vertexIndex in 0 until 4) {
			assertEquals(canvas[vertexIndex * 2] + 20f, mesh.positions[vertexIndex * 2], 1e-3f, "vertex $vertexIndex canvas x")
			assertEquals(canvas[vertexIndex * 2 + 1] - 10f, mesh.positions[vertexIndex * 2 + 1], 1e-3f, "vertex $vertexIndex canvas y")
			assertEquals(local[vertexIndex * 2] + 0.1f, mesh.localPositions[vertexIndex * 2], 1e-5f, "vertex $vertexIndex base u")
			assertEquals(local[vertexIndex * 2 + 1] - 0.05f, mesh.localPositions[vertexIndex * 2 + 1], 1e-5f, "vertex $vertexIndex base v")
		}
	}

	/** A drawable whose base is its canvas mesh keeps the one shared array through a move. */
	@Test
	fun aRootDrawableKeepsItsSharedArray() {
		val session = EditorSession(model())
		session.setDrawableWorldCenter(drawableId, 0f, 0f)
		val mesh = session.model.value.drawables.single().mesh!!
		assertSame(mesh.positions, mesh.localPositions)
	}

	@Test
	fun boundsReportTheDisplayedGeometryNotTheBaseArray() {
		val puppet = model()
		val neutral = puppet.parameters.associate { it.id to it.default }

		// The base array is a 10 x 10 quad at the origin; reading it would be the bug.
		val baseBounds = meshBounds(puppet.drawables.first().mesh!!.positions)
		assertEquals(10f, baseBounds.width, "precondition: the base array is small")

		val transform = drawableWorldTransform(puppet, neutral, drawableId)!!
		assertEquals(100f, transform.bounds.width, "the row shows the displayed size, not the base size")
		assertEquals(100f, transform.bounds.height)
		assertTrue(transform.editable, "a neutral pose is editable")
	}

	@Test
	fun editingIsRefusedWhileTheRigIsPosed() {
		val puppet = model()
		val posed = mapOf(parameterId to 1f)

		val transform = drawableWorldTransform(puppet, posed, drawableId)!!
		assertFalse(transform.editable, "the deformer-chain inverse is only exact at the neutral pose")

		// And the write path refuses too, so a stale enabled flag still cannot corrupt the mesh.
		val session = EditorSession(puppet, initialPose = posed)
		session.setDrawableWorldCenter(drawableId, 0f, 0f)
		session.setDrawableWorldSize(drawableId, 1f, 1f)
		assertFalse(session.canUndo.value, "a posed edit records nothing")
		assertFalse(session.dirty.value)
	}

	@Test
	fun movingLandsTheDisplayedCenterOnTheRequestedPoint() {
		val session = EditorSession(model())

		session.setDrawableWorldCenter(drawableId, 0f, 0f)

		val moved = drawableWorldTransform(session.model.value, session.pose.value, drawableId)!!
		assertEquals(0f, moved.bounds.centerX, "what you type is what the readout becomes")
		assertEquals(0f, moved.bounds.centerY)
		assertEquals(100f, moved.bounds.width, "a move does not resize")
		assertTrue(session.canUndo.value, "one undo step")

		session.undo()
		val restored = drawableWorldTransform(session.model.value, session.pose.value, drawableId)!!
		assertEquals(500f, restored.bounds.centerX)
	}

	@Test
	fun resizingLandsTheDisplayedExtentsAndHoldsThePositionStill() {
		val session = EditorSession(model())

		session.setDrawableWorldSize(drawableId, 50f, 25f)

		val resized = drawableWorldTransform(session.model.value, session.pose.value, drawableId)!!
		assertEquals(50f, resized.bounds.width)
		assertEquals(25f, resized.bounds.height)
		// The bounds center is the pivot, so the Position row does not move when Size is edited.  Z is
		// negated against the art's local y because world y grows UPWARD - the Z+ up convention the panel
		// labels its rows with - so art sitting at local y 500 reads as Z -500.
		assertEquals(500f, resized.bounds.centerX)
		assertEquals(-500f, resized.bounds.centerY)
	}

	@Test
	fun positionReadsFromTheWorldAxes() {
		// A 1000 canvas puts the origin at world (500, -500) - exactly where the displayed square is centered.
		val session = EditorSession(model(canvasSize = 1000f))
		val puppet = session.model.value
		val centered = drawableWorldTransform(puppet, session.pose.value, drawableId)!!
		assertEquals(0f, puppet.originRelativeX(centered.bounds.centerX), "a mesh centered on the axes reads X 0")
		assertEquals(0f, puppet.originRelativeZ(centered.bounds.centerY), "and Z 0, not the -500 world y")

		// Typing (10, 20) goes through the same conversions the Position rows use, one axis at a time.
		session.setDrawableWorldCenter(drawableId, puppet.worldXFromOriginRelative(10f), puppet.worldZFromOriginRelative(20f))

		val moved = drawableWorldTransform(session.model.value, session.pose.value, drawableId)!!
		assertEquals(10f, session.model.value.originRelativeX(moved.bounds.centerX), "what you type is what the readout becomes")
		assertEquals(20f, session.model.value.originRelativeZ(moved.bounds.centerY), "Z counts up from the red line")
		assertEquals(510f, moved.bounds.centerX, "the world position is the typed value plus the origin")
		assertEquals(-480f, moved.bounds.centerY)
		assertTrue(session.canUndo.value, "one undo step")
	}

	@Test
	fun aNoOpEditRecordsNothing() {
		val session = EditorSession(model())
		val current = drawableWorldTransform(session.model.value, session.pose.value, drawableId)!!

		session.setDrawableWorldCenter(drawableId, current.bounds.centerX, current.bounds.centerY)
		session.setDrawableWorldSize(drawableId, current.bounds.width, current.bounds.height)

		assertFalse(session.canUndo.value)
		assertFalse(session.dirty.value)
	}

	/** In Edit mode the rows measure the rig at rest - what the viewport draws there - and stay editable. */
	@Test
	fun inEditModeTheRowsMeasureAndEditTheRestShape() {
		val posed = mapOf(parameterId to 1f)
		val session = EditorSession(model(posedShiftX = 100f), initialPose = posed)
		val objectShown = drawableWorldTransform(session.model.value, session.shownPose, drawableId)!!
		assertEquals(600f, objectShown.bounds.centerX, "precondition: posed, the square sits 100 right of rest")
		assertFalse(objectShown.editable, "precondition: posed, the inverse is not exact")

		enterEditMode(session)
		val editShown = drawableWorldTransform(session.model.value, session.shownPose, drawableId)!!
		assertEquals(500f, editShown.bounds.centerX, "Edit mode shows the rest shape")
		assertTrue(editShown.editable, "and the rest shape is where the inverse is exact")

		val modelBefore = session.model.value
		session.setDrawableWorldCenter(drawableId, 0f, 0f)

		assertTrue(session.model.value !== modelBefore, "the edit lands rather than being refused for the held pose")
		val moved = drawableWorldTransform(session.model.value, session.shownPose, drawableId)!!
		assertEquals(0f, moved.bounds.centerX, 1e-3f, "the edit lands where the rigger is looking")
		assertEquals(0f, moved.bounds.centerY, 1e-3f)
		assertEquals(posed, session.pose.value, "the pinned pose is held as it is")
	}

	/** A scrub frame's preview is the model its release commits, and building one records nothing. */
	@Test
	fun aPreviewIsTheModelItsCommitRecords() {
		val session = EditorSession(model())
		val before = session.model.value

		val movePreview = session.previewDrawableWorldCenter(drawableId, 0f, 0f)!!
		val sizePreview = session.previewDrawableWorldSize(drawableId, 50f, 25f)!!

		assertSame(before, session.model.value, "a preview never reaches the session")
		assertFalse(session.canUndo.value, "a preview records nothing")
		session.setDrawableWorldCenter(drawableId, 0f, 0f)
		assertSameRest(movePreview, session.model.value)
		session.undo()
		session.setDrawableWorldSize(drawableId, 50f, 25f)
		assertSameRest(sizePreview, session.model.value)
	}

	/** Where the commit would record nothing - a posed rig, or the value the drawable already has - nothing is previewed. */
	@Test
	fun aPreviewIsNullWhereTheCommitRecordsNothing() {
		val posedSession = EditorSession(model(), initialPose = mapOf(parameterId to 1f))
		assertNull(posedSession.previewDrawableWorldCenter(drawableId, 0f, 0f))
		assertNull(posedSession.previewDrawableWorldSize(drawableId, 1f, 1f))

		val session = EditorSession(model())
		val current = drawableWorldTransform(session.model.value, session.pose.value, drawableId)!!.bounds
		assertNull(session.previewDrawableWorldCenter(drawableId, current.centerX, current.centerY))
		assertNull(session.previewDrawableWorldSize(drawableId, current.width, current.height))
	}
}