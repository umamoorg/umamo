package org.umamo.edit

import org.umamo.runtime.model.AlphaBlendMode
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.Part
import org.umamo.runtime.model.PartComposite
import org.umamo.runtime.model.PartGroupMode
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.PuppetModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins the scalar property edits behind the Properties panel: each PuppetModelEdits builder round-trips
 * its field and returns the SAME instance on a no-op (unchanged value or a missing id), the deformer
 * builders guard on kind (base angle only on Rotation, quad transform only on Warp), the part group-mode
 * builder carries a whole Isolated composite, and a session setter commits exactly one undo step (or
 * nothing when it is a no-op).
 */
class PropertyScalarEditsTest {
	private val drawableId = DrawableId("d")
	private val rotationId = DeformerId("rot")
	private val warpId = DeformerId("warp")
	private val partId = PartId("p")

	private val drawable =
		Drawable(
			id = drawableId,
			name = "d",
			parentDeformerId = null,
			blendMode = BlendMode.Normal,
			maskedBy = emptyList(),
			mesh = null,
			keyforms = null,
		)

	private val rotation =
		Deformer.Rotation(id = rotationId, name = "rot", parent = null, partId = null, baseAngle = 0f, keyforms = null)

	private val warp =
		Deformer.Warp(
			id = warpId,
			name = "warp",
			parent = null,
			partId = null,
			rows = 2,
			columns = 2,
			isQuadTransform = false,
			keyforms = null,
		)

	private val part = Part(partId, "p", children = emptyList())

	private fun model(): PuppetModel =
		PuppetModel(
			parameters = emptyList(),
			parts = listOf(part),
			deformers = listOf(rotation, warp),
			drawables = listOf(drawable),
			rootChildren = emptyList(),
			rootPartId = null,
			canvasWidth = 100f,
			canvasHeight = 200f,
			worldOriginX = 50f,
			worldOriginY = 100f,
		)

	@Test
	fun drawableScalarBuildersRoundTripAndNoOp() {
		val base = model()

		assertEquals(BlendMode.Multiply, base.withDrawableBlendMode(drawableId, BlendMode.Multiply).drawables.first().blendMode)
		assertEquals(AlphaBlendMode.Atop, base.withDrawableAlphaBlendMode(drawableId, AlphaBlendMode.Atop).drawables.first().alphaBlendMode)
		assertTrue(base.withDrawableCulling(drawableId, true).drawables.first().culling)
		assertTrue(base.withDrawableInvertMask(drawableId, true).drawables.first().invertMask)

		// Unchanged value and missing id are both no-ops (same instance).
		assertSame(base, base.withDrawableBlendMode(drawableId, BlendMode.Normal))
		assertSame(base, base.withDrawableCulling(drawableId, false))
		assertSame(base, base.withDrawableInvertMask(DrawableId("missing"), true))
	}

	@Test
	fun deformerBaseAngleOnlyAffectsRotation() {
		val base = model()

		val edited = base.withDeformerBaseAngle(rotationId, 45f)
		assertEquals(45f, (edited.deformers.first { it.id == rotationId } as Deformer.Rotation).baseAngle)

		// A warp has no base angle, and an unchanged angle is a no-op.
		assertSame(base, base.withDeformerBaseAngle(warpId, 45f))
		assertSame(base, base.withDeformerBaseAngle(rotationId, 0f))
	}

	@Test
	fun deformerQuadTransformOnlyAffectsWarp() {
		val base = model()

		val edited = base.withDeformerQuadTransform(warpId, true)
		assertTrue((edited.deformers.first { it.id == warpId } as Deformer.Warp).isQuadTransform)

		// A rotation has no lattice, and an unchanged flag is a no-op.
		assertSame(base, base.withDeformerQuadTransform(rotationId, true))
		assertSame(base, base.withDeformerQuadTransform(warpId, false))
	}

	@Test
	fun partScalarBuildersRoundTripAndNoOp() {
		val base = model()

		assertTrue(base.withPartSketch(partId, true).parts.first().isSketch)
		assertEquals(700, base.withPartDrawOrder(partId, 700).parts.first().drawOrder)

		assertSame(base, base.withPartSketch(partId, false))
		assertSame(base, base.withPartDrawOrder(partId, 500))
	}

	@Test
	fun partGroupModeCarriesIsolatedComposite() {
		val base = model()
		val edited = base.withPartGroupMode(partId, PartGroupMode.Isolated(PartComposite(opacity = 0.5f)))

		val mode = edited.parts.first().groupMode
		assertTrue(mode is PartGroupMode.Isolated)
		assertEquals(0.5f, (mode as PartGroupMode.Isolated).composite.opacity)

		assertSame(base, base.withPartGroupMode(partId, PartGroupMode.PassThrough))
	}

	@Test
	fun canvasAndOriginBuildersRoundTripAndNoOp() {
		val base = model()

		assertEquals(300f, base.withCanvasSize(300f, 400f).canvasWidth)
		assertEquals(400f, base.withCanvasSize(300f, 400f).canvasHeight)
		assertEquals(7f, base.withWorldOrigin(7f, 8f).worldOriginX)

		assertSame(base, base.withCanvasSize(100f, 200f))
		assertSame(base, base.withWorldOrigin(50f, 100f))
	}

	@Test
	fun sessionEditIsOneUndoStepThatDirtiesAndReverses() {
		val session = EditorSession(model())
		assertFalse(session.canUndo.value)

		session.setDrawableCulling(drawableId, true)

		assertTrue(session.canUndo.value)
		assertTrue(session.dirty.value)
		assertTrue(session.model.value.drawables.first().culling)

		session.undo()
		assertFalse(session.canUndo.value)
		assertFalse(session.model.value.drawables.first().culling)
	}

	@Test
	fun sessionNoOpEditRecordsNothing() {
		val session = EditorSession(model())

		// The drawable is already Normal, so this edit changes nothing.
		session.setDrawableBlendMode(drawableId, BlendMode.Normal)

		assertFalse(session.canUndo.value)
		assertFalse(session.dirty.value)
	}

	@Test
	fun sessionGroupModeAndCanvasEditsCommit() {
		val session = EditorSession(model())

		session.setPartGroupMode(partId, PartGroupMode.Grouped)
		assertEquals(PartGroupMode.Grouped, session.model.value.parts.first().groupMode)

		session.setCanvasSize(640f, 480f)
		assertEquals(640f, session.model.value.canvasWidth)

		// Two distinct edits, so two undo steps back to the original.
		session.undo()
		session.undo()
		assertEquals(PartGroupMode.PassThrough, session.model.value.parts.first().groupMode)
		assertEquals(100f, session.model.value.canvasWidth)
	}
}
