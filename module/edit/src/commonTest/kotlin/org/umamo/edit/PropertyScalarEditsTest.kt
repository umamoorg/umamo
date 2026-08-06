package org.umamo.edit

import org.umamo.runtime.model.AlphaBlendMode
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.ColorRgb
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.Part
import org.umamo.runtime.model.PartComposite
import org.umamo.runtime.model.PartGroupMode
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.RenderGroup
import org.umamo.runtime.model.RenderNode
import org.umamo.runtime.model.RuntimeTarget
import org.umamo.runtime.model.multiplyColor
import org.umamo.runtime.model.opacity
import org.umamo.runtime.model.screenColor
import org.umamo.runtime.model.withDerivedRenderRoot
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
	fun partCompositeIsStoredAndSurvivesModeRoundTrip() {
		val base = model()
		val custom = PartComposite(opacity = 0.5f)

		// The composite is stored on the part independent of the mode.
		val withComposite = base.withPartComposite(partId, custom)
		assertEquals(custom, withComposite.parts.first().composite)
		// Only applied while Isolated: activeComposite is null until the mode is Isolated.
		assertEquals(null, withComposite.parts.first().activeComposite)

		val isolated = withComposite.withPartGroupMode(partId, PartGroupMode.Isolated)
		assertEquals(custom, isolated.parts.first().activeComposite)

		// Leaving and re-entering Isolated does NOT reset the composite (the whole point).
		val roundTripped =
			isolated
				.withPartGroupMode(partId, PartGroupMode.PassThrough)
				.withPartGroupMode(partId, PartGroupMode.Isolated)
		assertEquals(custom, roundTripped.parts.first().composite)
		assertEquals(custom, roundTripped.parts.first().activeComposite)

		// No-op composite / mode edits return the same instance.
		assertSame(withComposite, withComposite.withPartComposite(partId, custom))
		assertSame(base, base.withPartGroupMode(partId, PartGroupMode.PassThrough))
	}

	/**
	 * The part in the org tree (so deriveRenderRoot emits a RenderGroup for it), Isolated by default so
	 * its composite resolves onto the group, with a freshly derived renderRoot.
	 */
	private fun modelWithPartInTree(groupMode: PartGroupMode = PartGroupMode.Isolated): PuppetModel =
		model()
			.copy(parts = listOf(part.copy(groupMode = groupMode)), rootChildren = listOf(OrgChild.Part(partId)))
			.withDerivedRenderRoot()

	/** The derived render group owning [id], or null when the part is not a render-tree boundary. */
	private fun groupFor(model: PuppetModel, id: PartId): RenderGroup? {
		fun search(node: RenderNode): RenderGroup? {
			if (node !is RenderGroup) {
				return null
			}
			if (node.partId == id) {
				return node
			}
			for (child in node.children) {
				search(child)?.let { return it }
			}
			return null
		}
		return search(model.renderRoot)
	}

	@Test
	fun partCompositeEditsReDeriveTheRenderRoot() {
		// The renderer reads composite/draw-order/group structure from renderRoot, not from parts, so a
		// composite property edit that does not re-derive renderRoot never reaches the viewport (the bug
		// this pins): resolvedComposite bakes the composite into RenderGroup.composite at derive time.
		val base = modelWithPartInTree()
		assertEquals(PartComposite(), groupFor(base, partId)?.composite)

		val edited = base.withPartComposite(partId, PartComposite(opacity = 0.5f))
		assertEquals(0.5f, groupFor(edited, partId)?.composite?.opacity)

		// A part draw-order edit updates the group's sort key in the render tree.
		assertEquals(700, groupFor(base.withPartDrawOrder(partId, 700), partId)?.drawOrder)

		// PassThrough removes the group boundary entirely (its children hoist into the enclosing group).
		assertEquals(null, groupFor(base.withPartGroupMode(partId, PartGroupMode.PassThrough), partId))
	}

	/**
	 * The 5.3 per-art-mesh tint is now a STATIC channel value, so the builders write one field instead of
	 * rewriting every keyform cell.
	 *
	 * That is the point of the split: the old builder flattened any authored per-keyform color animation
	 * and, by replacing the grid, tripped the renderer's identity check into re-uploading geometry for a
	 * color change.  Neither is possible now - the color never touches the geometry grid.
	 */
	@Test
	fun drawableColorBuildersWriteTheStaticAndNoOp() {
		val base = model().copy(drawables = listOf(drawable))
		val red = ColorRgb(1f, 0f, 0f)

		val tinted = base.withDrawableMultiplyColor(drawableId, red)
		assertEquals(red, tinted.drawables.first().multiplyColor, "the static multiply color is set")
		assertSame(
			base.drawables.first().geometryGrid,
			tinted.drawables.first().geometryGrid,
			"a color edit leaves the geometry grid untouched by identity, so no re-upload is triggered",
		)

		// Already-set and missing-id are no-ops (same instance).
		assertSame(tinted, tinted.withDrawableMultiplyColor(drawableId, red))
		assertSame(base, base.withDrawableMultiplyColor(drawableId, ColorRgb.MultiplyIdentity))
		assertSame(base, base.withDrawableScreenColor(DrawableId("missing"), ColorRgb.ScreenIdentity))
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

	@Test
	fun sessionRuntimeTargetEditCommitsUndoesAndNoOps() {
		val session = EditorSession(model())

		session.setRuntimeTarget(RuntimeTarget.Cubism50)
		assertEquals(RuntimeTarget.Cubism50, session.model.value.runtimeTarget)
		assertTrue(session.dirty.value, "a target change is document content, so it dirties")
		assertEquals("change.document.runtimeTarget", DocumentChange.SetRuntimeTarget(RuntimeTarget.Cubism50).labelKey)

		session.undo()
		assertEquals(RuntimeTarget.NoTarget, session.model.value.runtimeTarget)
		assertFalse(session.dirty.value, "undo restores the saved model instance")

		// The target is already NoTarget, so this edit changes nothing.
		session.setRuntimeTarget(RuntimeTarget.NoTarget)
		assertFalse(session.canUndo.value)
		assertFalse(session.dirty.value)
	}

	@Test
	fun sessionSetPartCompositeCommitsOneStep() {
		val session = EditorSession(model())
		val custom = PartComposite(opacity = 0.25f)

		session.setPartComposite(partId, custom)
		assertEquals(custom, session.model.value.parts.first().composite)

		session.undo()
		assertEquals(PartComposite(), session.model.value.parts.first().composite)
	}

	/** The drawable's new scalar statics round-trip, and refuse a no-op the same way the others do. */
	@Test
	fun drawableScalarChannelBuildersWriteTheStaticAndNoOp() {
		val base = model()
		val faded = base.withDrawableOpacity(drawableId, 0.25f)
		assertEquals(0.25f, faded.drawables.first().opacity)
		val reordered = base.withDrawableDrawOrder(drawableId, 720f)
		assertEquals(720f, reordered.drawables.first().drawOrder)

		assertSame(base, base.withDrawableOpacity(drawableId, 1f), "the default opacity is already 1")
		assertSame(base, base.withDrawableDrawOrder(DrawableId("missing"), 720f))
		assertSame(
			base.drawables.first().geometryGrid,
			faded.drawables.first().geometryGrid,
			"a scalar-static edit leaves the geometry grid untouched by identity, so no re-upload is triggered",
		)
	}

	/**
	 * A drawable's draw order does NOT re-derive the render root, unlike a part's.
	 *
	 * A part's draw order is baked into the derived group tree; a drawable's is resolved per pose at render
	 * time as its channel's static, so re-deriving would be work with no output.
	 */
	@Test
	fun drawableDrawOrderLeavesTheRenderRootAlone() {
		val base = model()
		val reordered = base.withDrawableDrawOrder(drawableId, 720f)
		assertSame(base.renderRoot, reordered.renderRoot)
	}

	/** The deformer render statics round-trip on BOTH subtypes and refuse a no-op. */
	@Test
	fun deformerRenderStaticBuildersCoverBothSubtypes() {
		val base = model()
		for (id in listOf(rotationId, warpId)) {
			val faded = base.withDeformerOpacity(id, 0.5f)
			assertEquals(0.5f, faded.deformerNamed(id).opacity, "opacity on $id")
			val tinted = base.withDeformerMultiplyColor(id, ColorRgb(0.5f, 0.5f, 0.5f))
			assertEquals(ColorRgb(0.5f, 0.5f, 0.5f), tinted.deformerNamed(id).multiplyColor, "multiply on $id")
			val screened = base.withDeformerScreenColor(id, ColorRgb(0.25f, 0f, 0f))
			assertEquals(ColorRgb(0.25f, 0f, 0f), screened.deformerNamed(id).screenColor, "screen on $id")

			assertSame(base, base.withDeformerOpacity(id, 1f), "the default opacity is already 1")
		}
		assertSame(base, base.withDeformerOpacity(DeformerId("missing"), 0.5f))
	}

	/** The flip flags exist only on a rotation deformer; a warp refuses them. */
	@Test
	fun deformerFlipsOnlyAffectRotation() {
		val base = model()
		val flipped = base.withDeformerFlipX(rotationId, true)
		assertEquals(true, (flipped.deformers.first { it.id == rotationId } as Deformer.Rotation).flipX)
		val flippedY = base.withDeformerFlipY(rotationId, true)
		assertEquals(true, (flippedY.deformers.first { it.id == rotationId } as Deformer.Rotation).flipY)

		assertSame(base, base.withDeformerFlipX(warpId, true), "a warp has no reflection")
		assertSame(base, base.withDeformerFlipY(warpId, true), "a warp has no reflection")
		assertSame(base, base.withDeformerFlipX(rotationId, false), "already false")
	}

	/** Each new session setter is one undo step that reverses cleanly. */
	@Test
	fun newSessionSettersAreOneUndoStepEach() {
		val session = EditorSession(model())
		session.setDrawableOpacity(drawableId, 0.25f)
		session.setDeformerOpacity(rotationId, 0.5f)
		session.setDeformerFlipX(rotationId, true)
		assertEquals(0.25f, session.model.value.drawables.first().opacity)
		assertEquals(0.5f, session.model.value.deformerNamed(rotationId).opacity)

		session.undo()
		session.undo()
		session.undo()
		assertEquals(1f, session.model.value.drawables.first().opacity)
		assertEquals(1f, session.model.value.deformerNamed(rotationId).opacity)
	}

	/** A no-op setter records nothing, so an unchanged value cannot pad the history. */
	@Test
	fun newSessionNoOpSettersRecordNothing() {
		val session = EditorSession(model())
		session.setDrawableOpacity(drawableId, 1f)
		session.setDeformerFlipX(warpId, true)
		assertEquals(false, session.canUndo.value)
	}

	/** The deformer with [id], for reading a static through the sealed-type accessors. */
	private fun PuppetModel.deformerNamed(id: DeformerId): Deformer = deformers.first { candidate -> candidate.id == id }
}
