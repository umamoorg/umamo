package org.umamo.edit.property

import org.umamo.edit.EditorSession
import org.umamo.edit.structure.withDeformerDeleted
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.Part
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.PuppetModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins the relation edits the Properties panel's pickers write through: each binds a flat cross-link field
 * (no tree surgery), returns the SAME instance on a no-op (unchanged value or a missing id), works on both
 * deformer subtypes, and commits exactly one undo step through its session op.
 */
class RelationEditsTest {
	private val drawableId = DrawableId("d")
	private val otherDrawableId = DrawableId("d2")
	private val rotationId = DeformerId("rot")
	private val warpId = DeformerId("warp")
	private val partId = PartId("p")

	private fun drawable(id: DrawableId): Drawable =
		Drawable(
			id = id,
			name = id.raw,
			parentDeformerId = null,
			blendMode = BlendMode.Normal,
			maskedBy = emptyList(),
			mesh = null,
			geometryGrid = null,
		)

	private val rotation =
		Deformer.Rotation(id = rotationId, name = "rot", parent = null, partId = null, baseAngle = 0f, geometryGrid = null)

	private val warp =
		Deformer.Warp(
			id = warpId,
			name = "warp",
			parent = null,
			partId = null,
			rows = 2,
			columns = 2,
			isQuadTransform = false,
			geometryGrid = null,
		)

	private fun model(): PuppetModel =
		PuppetModel(
			parameters = emptyList(),
			parts = listOf(Part(partId, "p", children = emptyList())),
			deformers = listOf(rotation, warp),
			drawables = listOf(drawable(drawableId), drawable(otherDrawableId)),
			rootChildren = emptyList(),
			rootPartId = null,
		)

	@Test
	fun drawableParentDeformerRoundTripsAndNoOps() {
		val base = model()

		val bound = base.withDrawableParentDeformer(drawableId, warpId, localPositions = null)
		assertEquals(warpId, bound.drawables.first { it.id == drawableId }.parentDeformerId)
		// Unbinding is a real edit back to null.
		assertNull(bound.withDrawableParentDeformer(drawableId, null, localPositions = null).drawables.first { it.id == drawableId }.parentDeformerId)

		// Unchanged value and missing id are both no-ops (same instance).
		assertSame(base, base.withDrawableParentDeformer(drawableId, null, localPositions = null))
		assertSame(bound, bound.withDrawableParentDeformer(drawableId, warpId, localPositions = null))
		assertSame(base, base.withDrawableParentDeformer(DrawableId("missing"), warpId, localPositions = null))
	}

	/**
	 * A rebinding that keeps the art in place takes a base in the new parent's space and keeps the canvas mesh;
	 * a base of the wrong length is ignored for the flat write.  Deleting a deformer applies each re-homed
	 * drawable's base the same way.
	 */
	@Test
	fun aRebindingTakesTheBaseItIsGiven() {
		val canvas = floatArrayOf(120f, 120f, 160f, 120f, 160f, 160f)
		val meshed = model().copy(drawables = listOf(drawable(drawableId).copy(mesh = DrawableMesh.withLocalEqualToCanvas(canvas, FloatArray(6), intArrayOf(0, 1, 2))), drawable(otherDrawableId)))
		val local = floatArrayOf(0.1f, 0.1f, 0.3f, 0.1f, 0.3f, 0.3f)

		val bound = meshed.withDrawableParentDeformer(drawableId, warpId, local).drawables.first { it.id == drawableId }
		assertSame(local, bound.mesh!!.localPositions, "the base is the one given")
		assertSame(canvas, bound.mesh!!.positions, "the canvas mesh is kept")
		val flat = meshed.withDrawableParentDeformer(drawableId, warpId, FloatArray(4)).drawables.first { it.id == drawableId }
		assertEquals(warpId, flat.parentDeformerId)
		assertSame(canvas, flat.mesh!!.localPositions, "a base of the wrong length is ignored")

		val underWarp = meshed.withDrawableParentDeformer(drawableId, warpId, local)
		val rehomed = underWarp.withDeformerDeleted(warpId, mapOf(drawableId to canvas.copyOf())).drawables.first { it.id == drawableId }
		assertNull(rehomed.parentDeformerId)
		assertEquals(canvas.toList(), rehomed.mesh!!.localPositions.toList(), "the re-homed drawable takes its base")
	}

	@Test
	fun drawableMaskedByRoundTripsAndNoOps() {
		val base = model()
		val masks = listOf(otherDrawableId)

		val masked = base.withDrawableMaskedBy(drawableId, masks)
		assertEquals(masks, masked.drawables.first { it.id == drawableId }.maskedBy)
		// Clearing back to empty is a real edit.
		assertTrue(masked.withDrawableMaskedBy(drawableId, emptyList()).drawables.first { it.id == drawableId }.maskedBy.isEmpty())

		assertSame(base, base.withDrawableMaskedBy(drawableId, emptyList()))
		assertSame(masked, masked.withDrawableMaskedBy(drawableId, masks))
		assertSame(base, base.withDrawableMaskedBy(DrawableId("missing"), masks))
	}

	@Test
	fun deformerPartRoundTripsOnBothSubtypes() {
		val base = model()

		// The copy is per-subtype, so both a rotation and a warp must rebind.
		val boundRotation = base.withDeformerPart(rotationId, partId)
		assertEquals(partId, boundRotation.deformers.first { it.id == rotationId }.partId)
		assertTrue(boundRotation.deformers.first { it.id == rotationId } is Deformer.Rotation, "subtype is preserved")

		val boundWarp = base.withDeformerPart(warpId, partId)
		assertEquals(partId, boundWarp.deformers.first { it.id == warpId }.partId)
		assertTrue(boundWarp.deformers.first { it.id == warpId } is Deformer.Warp, "subtype is preserved")

		assertSame(base, base.withDeformerPart(rotationId, null))
		assertSame(boundWarp, boundWarp.withDeformerPart(warpId, partId))
		assertSame(base, base.withDeformerPart(DeformerId("missing"), partId))
	}

	@Test
	fun sessionRelationEditsAreOneUndoStepEach() {
		val session = EditorSession(model())

		session.setDrawableParentDeformer(drawableId, warpId, localPositions = null)
		assertEquals(warpId, session.model.value.drawables.first { it.id == drawableId }.parentDeformerId)

		session.setDrawableMaskedBy(drawableId, listOf(otherDrawableId))
		assertEquals(listOf(otherDrawableId), session.model.value.drawables.first { it.id == drawableId }.maskedBy)

		session.setDeformerPart(rotationId, partId)
		assertEquals(partId, session.model.value.deformers.first { it.id == rotationId }.partId)

		// Three distinct edits, so three undo steps back to the original.
		session.undo()
		session.undo()
		session.undo()
		assertNull(session.model.value.drawables.first { it.id == drawableId }.parentDeformerId)
		assertTrue(session.model.value.drawables.first { it.id == drawableId }.maskedBy.isEmpty())
		assertNull(session.model.value.deformers.first { it.id == rotationId }.partId)
	}
}