package org.umamo.edit.structure

import org.umamo.edit.EditorSession
import org.umamo.edit.SessionTestModels.drawable
import org.umamo.edit.SessionTestModels.model
import org.umamo.edit.SessionTestModels.partA
import org.umamo.edit.SessionTestModels.partB
import org.umamo.edit.SessionTestModels.warp
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.Part
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.RenderDrawable
import org.umamo.runtime.model.partByDrawable
import org.umamo.runtime.model.withDerivedRenderRoot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Session-level tests for the org-tree moves in StructureEdits.kt: re-homing and reordering drawables and
 * parts, re-parenting deformers, and the cycle refusals.
 */
class StructureEditsTest {
	/**
	 * Moving a drawable re-homes it under another part; undo restores its old owner.
	 */
	@Test
	fun moveOrgChildRehomesDrawableToAnotherPart() {
		val session = EditorSession(model())

		session.moveOrgChild(OrgChild.Drawable(DrawableId("d")), newParentId = PartId("b"), before = null)
		assertEquals(PartId("b"), session.model.value.partByDrawable()[DrawableId("d")])

		session.undo()
		assertEquals(PartId("a"), session.model.value.partByDrawable()[DrawableId("d")])
	}

	/**
	 * Moving a drawable among its siblings reorders the org tree (the outliner) and the derived render
	 * order (the viewport, panel reversed back-to-front) consistently; undo restores both.
	 */
	@Test
	fun moveOrgChildReordersTreeAndDerivesRenderOrder() {
		val drawableE =
			Drawable(
				id = DrawableId("e"),
				name = "e",
				parentDeformerId = null,
				blendMode = BlendMode.Normal,
				maskedBy = emptyList(),
				mesh = null,
				geometryGrid = null,
			)
		val layered =
			PuppetModel(
				parameters = emptyList(),
				parts = listOf(partA.copy(children = listOf(OrgChild.Drawable(DrawableId("d")), OrgChild.Drawable(DrawableId("e"))))),
				deformers = emptyList(),
				drawables = listOf(drawable, drawableE),
				rootChildren = listOf(OrgChild.Part(PartId("a"))),
				rootPartId = null,
			).withDerivedRenderRoot()
		val session = EditorSession(layered)

		// d is above e in the panel; move d to after e (append). The panel order flips, and the derived
		// render order (panel reversed) follows.
		session.moveOrgChild(OrgChild.Drawable(DrawableId("d")), newParentId = PartId("a"), before = null)
		assertEquals(
			listOf<OrgChild>(OrgChild.Drawable(DrawableId("e")), OrgChild.Drawable(DrawableId("d"))),
			session.model.value.parts.first { it.id == PartId("a") }.children,
		)
		assertEquals(
			listOf(DrawableId("d"), DrawableId("e")),
			session.model.value.renderRoot.children.filterIsInstance<RenderDrawable>().map { it.id },
		)

		session.undo()
		assertEquals(
			listOf<OrgChild>(OrgChild.Drawable(DrawableId("d")), OrgChild.Drawable(DrawableId("e"))),
			session.model.value.parts.first { it.id == PartId("a") }.children,
		)
	}

	/**
	 * A drawable can be interleaved between two parts at the root (Cubism's cross-kind reorder), so a loose
	 * mesh sits between folders rather than only being parented into one.
	 */
	@Test
	fun moveOrgChildInterleavesADrawableBetweenParts() {
		val session = EditorSession(model())

		// model()'s top level is [a, b] with d under a; move d to the root, between a and b.
		session.moveOrgChild(OrgChild.Drawable(DrawableId("d")), newParentId = null, before = OrgChild.Part(PartId("b")))
		assertEquals(
			listOf<OrgChild>(OrgChild.Part(PartId("a")), OrgChild.Drawable(DrawableId("d")), OrgChild.Part(PartId("b"))),
			session.model.value.rootChildren,
		)
		assertNull(session.model.value.partByDrawable()[DrawableId("d")], "now a root-level drawable, owned by no part")
	}

	/**
	 * Moving a part nests it under the new parent; a move that would form a cycle is refused.
	 */
	@Test
	fun moveOrgChildReparentsPartAndRefusesCycles() {
		val session = EditorSession(model())

		session.moveOrgChild(OrgChild.Part(PartId("b")), newParentId = PartId("a"), before = null)
		assertTrue(OrgChild.Part(PartId("b")) in session.model.value.parts.first { it.id == PartId("a") }.children)

		// a is now an ancestor of b, so moving a under b is a cycle and must be refused (no new step).
		val before = session.model.value
		session.moveOrgChild(OrgChild.Part(PartId("a")), newParentId = PartId("b"), before = null)
		assertSame(before, session.model.value)

		session.undo()
		assertFalse(OrgChild.Part(PartId("b")) in session.model.value.parts.first { it.id == PartId("a") }.children)
	}

	/**
	 * Moving a deformer re-nests it under the new parent; a move that would form a cycle is refused.
	 */
	@Test
	fun moveDeformerReparentsAndRefusesCycles() {
		val warp2 =
			Deformer.Warp(
				id = DeformerId("w2"),
				name = "Warp2",
				parent = null,
				partId = null,
				rows = 2,
				columns = 2,
				isQuadTransform = true,
				geometryGrid = null,
			)
		val nested =
			PuppetModel(
				parameters = emptyList(),
				parts = listOf(partA, partB),
				deformers = listOf(warp, warp2),
				drawables = listOf(drawable),
				rootChildren = listOf(OrgChild.Part(PartId("a")), OrgChild.Part(PartId("b")), OrgChild.Drawable(DrawableId("d"))),
				rootPartId = null,
			)
		val session = EditorSession(nested)

		session.moveDeformer(DeformerId("w2"), newParentId = DeformerId("w"), beforeId = null)
		assertEquals(DeformerId("w"), session.model.value.deformers.first { it.id == DeformerId("w2") }.parent)

		// w is now an ancestor of w2, so nesting w under w2 is a cycle and must be refused.
		val before = session.model.value
		session.moveDeformer(DeformerId("w"), newParentId = DeformerId("w2"), beforeId = null)
		assertSame(before, session.model.value)

		session.undo()
		assertNull(session.model.value.deformers.first { it.id == DeformerId("w2") }.parent)
	}
}