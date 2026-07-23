package org.umamo.ui.properties

import org.umamo.edit.EditorSession
import org.umamo.edit.meshBounds
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.MeshForm
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the Properties Transform panel to the space the user actually sees.
 *
 * The bug this guards against: Drawable.mesh.positions is the BASE array, and a keyformed drawable's base
 * is not its displayed shape (`displayed = base + Σ wᵢ·Δᵢ`).  Reading or writing base directly showed
 * numbers unrelated to the drawable - on a corpus model, a base array 1.9 units wide for a drawable 183.8
 * wide - so a small typed nudge became an enormous transform.  These tests assert the rows measure and
 * write the DISPLAYED geometry instead, and that the write is refused off the neutral pose (where the
 * deformer-chain inverse is not exact).
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

	private fun model(): PuppetModel =
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
						mesh = DrawableMesh(basePositions.copyOf(), FloatArray(basePositions.size), intArrayOf(0, 1, 2)),
						keyforms =
							KeyformGrid(
								axes = listOf(KeyformAxis(parameterId, floatArrayOf(-1f, 0f, 1f))),
								cells =
									listOf(
										KeyformCell(intArrayOf(0), MeshForm(neutralDeltas.copyOf())),
										KeyformCell(intArrayOf(1), MeshForm(neutralDeltas.copyOf())),
										KeyformCell(intArrayOf(2), MeshForm(neutralDeltas.copyOf())),
									),
							),
					),
				),
			rootChildren = emptyList(),
			rootPartId = null,
		)

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
	fun aNoOpEditRecordsNothing() {
		val session = EditorSession(model())
		val current = drawableWorldTransform(session.model.value, session.pose.value, drawableId)!!

		session.setDrawableWorldCenter(drawableId, current.bounds.centerX, current.bounds.centerY)
		session.setDrawableWorldSize(drawableId, current.bounds.width, current.bounds.height)

		assertFalse(session.canUndo.value)
		assertFalse(session.dirty.value)
	}
}
