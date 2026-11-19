package org.umamo.edit.relation

import org.umamo.edit.Selection
import org.umamo.edit.SelectionTarget
import org.umamo.runtime.model.BlendMode
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
import org.umamo.runtime.model.withDerivedRenderRoot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the relation slots' reads and their one eligibility rule: what each slot binds, that nothing nests in its own
 * subtree or masks itself, that an applied mask is not offered again, that only an Isolated part takes masks, and that
 * Copy to Selected's peers are the selected objects of the owner's kind.
 */
class RelationSlotTest {
	private val outer = PartId("outer")
	private val inner = PartId("inner")
	private val other = PartId("other")
	private val root = DeformerId("root")
	private val child = DeformerId("child")
	private val turn = DeformerId("turn")
	private val face = DrawableId("face")
	private val eye = DrawableId("eye")
	private val brow = DrawableId("brow")

	/**
	 * The fixture: outer holds inner, which holds face and eye; other is Isolated, masked by brow; the warp child
	 * nests under root, face is deformed by child, and eye is masked by brow.
	 *
	 * @return PuppetModel The model.
	 */
	private fun model(): PuppetModel {
		/**
		 * A mesh-less drawable.
		 *
		 * @param DrawableId id The drawable.
		 * @param DeformerId? parent Its parent deformer.
		 * @param List maskedBy Its masks.
		 * @return Drawable The drawable.
		 */
		fun drawable(id: DrawableId, parent: DeformerId?, maskedBy: List<DrawableId> = emptyList()): Drawable =
			Drawable(id = id, name = id.raw, parentDeformerId = parent, blendMode = BlendMode.Normal, maskedBy = maskedBy, mesh = null, geometryGrid = null)

		return PuppetModel(
			parameters = emptyList(),
			parts =
				listOf(
					Part(outer, "Outer", listOf(OrgChild.Part(inner))),
					Part(inner, "Inner", listOf(OrgChild.Drawable(face), OrgChild.Drawable(eye))),
					Part(other, "Other", listOf(OrgChild.Drawable(brow)), groupMode = PartGroupMode.Isolated, composite = PartComposite(maskedBy = listOf(brow))),
				),
			deformers =
				listOf(
					Deformer.Warp(id = root, name = "root", parent = null, partId = outer, rows = 1, columns = 1, isQuadTransform = true, geometryGrid = null),
					Deformer.Warp(id = child, name = "child", parent = root, partId = outer, rows = 1, columns = 1, isQuadTransform = true, geometryGrid = null),
					Deformer.Rotation(id = turn, name = "turn", parent = null, partId = null, baseAngle = 0f, geometryGrid = null),
				),
			drawables = listOf(drawable(face, child), drawable(eye, null, listOf(brow)), drawable(brow, null)),
			rootChildren = listOf(OrgChild.Part(outer), OrgChild.Part(other)),
			rootPartId = null,
		).withDerivedRenderRoot()
	}

	@Test
	fun eachSlotReadsWhatItBinds() {
		val model = model()

		assertEquals(SelectionTarget.Part(inner), model.relationBound(RelationSlot.OwningPart(SelectionTarget.Drawable(face))))
		assertEquals(SelectionTarget.Part(outer), model.relationBound(RelationSlot.OwningPart(SelectionTarget.Part(inner))))
		assertNull(model.relationBound(RelationSlot.OwningPart(SelectionTarget.Part(outer))), "a top-level part binds no owner")
		assertEquals(SelectionTarget.Part(outer), model.relationBound(RelationSlot.OwningPart(SelectionTarget.Deformer(child))))
		assertEquals(SelectionTarget.Deformer(child), model.relationBound(RelationSlot.ParentDeformer(SelectionTarget.Drawable(face))))
		assertEquals(SelectionTarget.Deformer(root), model.relationBound(RelationSlot.ParentDeformer(SelectionTarget.Deformer(child))))
		assertEquals(SelectionTarget.Drawable(brow), model.relationBound(RelationSlot.Mask(SelectionTarget.Drawable(eye), SelectionTarget.Drawable(brow))))
		assertNull(model.relationBound(RelationSlot.Mask(SelectionTarget.Drawable(face), SelectionTarget.Drawable(brow))), "a row whose entry is gone binds nothing")
		assertNull(model.relationBound(RelationSlot.Masks(SelectionTarget.Drawable(eye))), "a whole list binds many")
	}

	@Test
	fun nothingNestsInsideItsOwnSubtree() {
		val model = model()
		val partParent = RelationSlot.OwningPart(SelectionTarget.Part(outer))
		val deformerParent = RelationSlot.ParentDeformer(SelectionTarget.Deformer(root))

		assertFalse(model.relationAdmits(partParent, SelectionTarget.Part(outer)), "a part in itself")
		assertFalse(model.relationAdmits(partParent, SelectionTarget.Part(inner)), "a part in its own child")
		assertTrue(model.relationAdmits(partParent, SelectionTarget.Part(other)))
		assertFalse(model.relationAdmits(deformerParent, SelectionTarget.Deformer(root)), "a deformer under itself")
		assertFalse(model.relationAdmits(deformerParent, SelectionTarget.Deformer(child)), "a deformer under its own child")
		assertTrue(model.relationAdmits(deformerParent, SelectionTarget.Deformer(turn)))
		assertTrue(model.relationAdmits(RelationSlot.ParentDeformer(SelectionTarget.Drawable(face)), SelectionTarget.Deformer(root)), "a drawable may take any deformer")
		assertFalse(model.relationAdmits(RelationSlot.ParentDeformer(SelectionTarget.Drawable(face)), SelectionTarget.Part(outer)), "only the kind the slot holds")
		assertFalse(model.relationAdmits(RelationSlot.OwningPart(SelectionTarget.Drawable(face)), SelectionTarget.Part(PartId("gone"))), "nor an object the model lacks")
	}

	@Test
	fun masksAreNeverSelfOrRepeatedAndOnlyAnIsolatedPartTakesThem() {
		val model = model()
		val eyeMasks = RelationSlot.Masks(SelectionTarget.Drawable(eye))
		val otherMasks = RelationSlot.Masks(SelectionTarget.Part(other))

		assertFalse(model.relationAdmits(eyeMasks, SelectionTarget.Drawable(eye)), "a drawable masking itself")
		assertFalse(model.relationAdmits(eyeMasks, SelectionTarget.Drawable(brow)), "a mask already applied")
		assertTrue(model.relationAdmits(eyeMasks, SelectionTarget.Drawable(face)))
		assertFalse(model.relationAdmits(eyeMasks, SelectionTarget.Part(inner)), "a drawable is clipped by drawables alone")
		assertTrue(model.relationAdmits(otherMasks, SelectionTarget.Part(inner)), "an Isolated part takes a part")
		assertFalse(model.relationAdmits(otherMasks, SelectionTarget.Part(other)), "but never itself")
		assertFalse(model.relationAdmits(otherMasks, SelectionTarget.Drawable(brow)))
		assertFalse(model.relationAdmits(RelationSlot.Masks(SelectionTarget.Part(inner)), SelectionTarget.Drawable(face)), "a part that is not Isolated takes no mask")
		assertTrue(model.relationAdmits(RelationSlot.Mask(SelectionTarget.Drawable(face), SelectionTarget.Drawable(brow)), SelectionTarget.Drawable(brow)), "a row tests its entry against another owner")
	}

	@Test
	fun peersAreTheSelectedObjectsOfTheOwnersKind() {
		val slot = RelationSlot.ParentDeformer(SelectionTarget.Drawable(face))
		val selection =
			Selection(
				setOf(SelectionTarget.Drawable(face), SelectionTarget.Drawable(eye), SelectionTarget.Deformer(root), SelectionTarget.Drawable(brow)),
				SelectionTarget.Drawable(face),
			)

		assertEquals(listOf<SelectionTarget>(SelectionTarget.Drawable(eye), SelectionTarget.Drawable(brow)), relationPeers(slot, selection))
		assertEquals(RelationSlot.ParentDeformer(SelectionTarget.Drawable(eye)), slot.withOwner(SelectionTarget.Drawable(eye)))
	}
}