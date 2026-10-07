package org.umamo.edit

import org.umamo.edit.SessionTestModels.deleteModel
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.Part
import org.umamo.runtime.model.PartComposite
import org.umamo.runtime.model.PartGroupMode
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.RenderDrawable
import org.umamo.runtime.model.partByDrawable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins that deleting from the org tree leaves no part composite naming what it removed: a deleted drawable
 * leaves every part's drawable masks, a deleted part subtree leaves every part's part masks, and an ungroup
 * hands its masking role to the folder's own children so the coverage it gave survives.  A copy keeps its
 * `textureSourceId` when its source goes, since that id keys the atlas page mapping rather than a drawable.
 */
class DeleteEditsTest {
	private val maskFolder = PartId("maskFolder")
	private val innerFolder = PartId("innerFolder")
	private val masked = PartId("masked")
	private val maskMesh = DrawableId("maskMesh")
	private val innerMesh = DrawableId("innerMesh")
	private val maskedMesh = DrawableId("maskedMesh")

	/**
	 * A drawable at the root of nothing, just enough for the org tree to name.
	 *
	 * @param DrawableId id The drawable's id.
	 * @param DrawableId? textureSourceId The drawable whose atlas binding it shares, or null.
	 * @return Drawable The drawable.
	 */
	private fun drawable(id: DrawableId, textureSourceId: DrawableId? = null): Drawable =
		Drawable(
			id = id,
			name = id.raw,
			parentDeformerId = null,
			blendMode = BlendMode.Normal,
			maskedBy = emptyList(),
			mesh = null,
			geometryGrid = null,
			textureSourceId = textureSourceId,
		)

	/**
	 * The fixture: `maskFolder` holds `maskMesh` and `innerFolder` (which holds `innerMesh`); an isolated part
	 * `masked` holding `maskedMesh` is masked by `maskMesh` and by the folder `maskFolder`.
	 *
	 * @return PuppetModel The fixture model.
	 */
	private fun model(): PuppetModel =
		PuppetModel(
			parameters = emptyList(),
			parts =
				listOf(
					Part(maskFolder, "maskFolder", children = listOf(OrgChild.Drawable(maskMesh), OrgChild.Part(innerFolder))),
					Part(innerFolder, "innerFolder", children = listOf(OrgChild.Drawable(innerMesh))),
					Part(
						masked,
						"masked",
						children = listOf(OrgChild.Drawable(maskedMesh)),
						groupMode = PartGroupMode.Isolated,
						composite = PartComposite(maskedBy = listOf(maskMesh), maskedByParts = listOf(maskFolder)),
					),
				),
			deformers = emptyList(),
			drawables = listOf(drawable(maskMesh), drawable(innerMesh), drawable(maskedMesh, textureSourceId = maskMesh)),
			rootChildren = listOf(OrgChild.Part(maskFolder), OrgChild.Part(masked)),
			rootPartId = null,
		)

	/** The composite of the part [id] in [model]. */
	private fun compositeOf(model: PuppetModel, id: PartId): PartComposite = model.parts.single { part -> part.id == id }.composite

	/** A deleted drawable leaves every part's drawable masks. */
	@Test
	fun aDeletedDrawableLeavesPartMasks() {
		val after = model().withDrawableDeleted(maskMesh)
		assertEquals(emptyList(), compositeOf(after, masked).maskedBy, "the part no longer names the deleted drawable")
		assertEquals(listOf(maskFolder), compositeOf(after, masked).maskedByParts, "its part masks are untouched")
	}

	/** A copy keeps its texture source when the source drawable is deleted, since the id keys the page mapping. */
	@Test
	fun aCopyKeepsItsTextureSourceWhenTheSourceGoes() {
		val after = model().withDrawableDeleted(maskMesh)
		assertEquals(maskMesh, after.drawables.single { drawable -> drawable.id == maskedMesh }.textureSourceId)
	}

	/** Deleting a part with its subtree takes the subtree's parts out of every part mask and its drawables out of every drawable mask. */
	@Test
	fun aCascadeDeleteLeavesPartMasks() {
		val after = model().withPartDeleted(maskFolder, cascade = true)
		assertEquals(emptyList(), compositeOf(after, masked).maskedByParts, "the deleted folder is no longer a mask")
		assertEquals(emptyList(), compositeOf(after, masked).maskedBy, "nor is the drawable that went with it")
	}

	/** An ungroup hands the dissolved folder's masking to its children: sub-parts as part masks, drawables as drawable masks. */
	@Test
	fun anUngroupHandsItsMaskingToItsChildren() {
		val after = model().withPartDeleted(maskFolder, cascade = false)
		assertEquals(listOf(innerFolder), compositeOf(after, masked).maskedByParts, "the sub-folder takes the dissolved folder's place")
		assertEquals(listOf(maskMesh), compositeOf(after, masked).maskedBy, "the folder's drawable joins the drawable masks once")
	}

	/** An ungroup of a folder no composite masks by leaves every part's composite as it was. */
	@Test
	fun anUngroupOfAnUnmaskingFolderLeavesCompositesAlone() {
		val start = model()
		val after = start.withPartDeleted(innerFolder, cascade = false)
		assertSame(compositeOf(start, masked), compositeOf(after, masked))
	}

	/**
	 * Deleting a drawable scrubs every reference to it: the drawables list, the render-order tree, another
	 * drawable's clip mask, and any glue it was half of. Undo restores them all.
	 */
	@Test
	fun deleteDrawableScrubsAllReferences() {
		val session = EditorSession(deleteModel())

		session.deleteDrawable(DrawableId("d1"))
		val after = session.model.value
		assertFalse(after.drawables.any { it.id == DrawableId("d1") })
		assertFalse(after.renderRoot.children.filterIsInstance<RenderDrawable>().any { it.id == DrawableId("d1") })
		assertTrue(after.drawables.first { it.id == DrawableId("d3") }.maskedBy.isEmpty(), "the mask reference to d1 is dropped")
		assertTrue(after.glues.isEmpty(), "the glue that paired d1 with d2 is dropped")

		session.undo()
		assertTrue(session.model.value.drawables.any { it.id == DrawableId("d1") })
		assertEquals(1, session.model.value.glues.size)
		assertEquals(listOf(DrawableId("d1")), session.model.value.drawables.first { it.id == DrawableId("d3") }.maskedBy)
	}

	/**
	 * Deleting a deformer unwraps it: its child deformers and the drawables it deformed re-home to its
	 * parent, so removing a transform wrapper never deletes art.
	 */
	@Test
	fun deleteDeformerUnwrapsToParent() {
		val session = EditorSession(deleteModel())

		// w is a root; w2's parent is w, and d2 is deformed by w. Deleting w re-homes both to w's parent (null).
		session.deleteDeformer(DeformerId("w")) { emptyMap() }
		val after = session.model.value
		assertFalse(after.deformers.any { it.id == DeformerId("w") })
		assertNull(after.deformers.first { it.id == DeformerId("w2") }.parent)
		assertNull(after.drawables.first { it.id == DrawableId("d2") }.parentDeformerId)
		// d1 was bound to w2 (not w), so it is untouched.
		assertEquals(DeformerId("w2"), after.drawables.first { it.id == DrawableId("d1") }.parentDeformerId)

		session.undo()
		assertEquals(DeformerId("w"), session.model.value.deformers.first { it.id == DeformerId("w2") }.parent)
	}

	/**
	 * A cascade part delete removes the whole subtree - the part, its descendant parts, and every drawable
	 * under them (with references scrubbed) - while leaving unrelated parts and drawables intact.
	 */
	@Test
	fun deletePartCascadeRemovesSubtree() {
		val session = EditorSession(deleteModel())

		// A holds B; d1 is under A and d2 under B. A cascade delete of A removes A, B, d1, and d2.
		session.deletePart(PartId("A"), cascade = true)
		val after = session.model.value
		assertEquals(setOf(PartId("R"), PartId("T")), after.parts.map { it.id }.toSet())
		assertEquals(listOf(DrawableId("d3")), after.drawables.map { it.id })
		assertTrue(after.parts.first { it.id == PartId("R") }.children.isEmpty(), "A is detached from its parent R")
		assertEquals(listOf(DrawableId("d3")), after.renderRoot.children.filterIsInstance<RenderDrawable>().map { it.id })
		assertTrue(after.glues.isEmpty())

		session.undo()
		assertEquals(4, session.model.value.parts.size)
		assertEquals(3, session.model.value.drawables.size)
	}

	/**
	 * An ungroup part delete dissolves the folder only: its child parts and drawables rise one level to the
	 * deleted part's parent, and nothing is destroyed.
	 */
	@Test
	fun deletePartUngroupKeepsContents() {
		val session = EditorSession(deleteModel())

		// Ungroup A (whose parent is R): B rises into R's children, and d1 (under A) re-homes to R.
		session.deletePart(PartId("A"), cascade = false)
		val after = session.model.value
		assertFalse(after.parts.any { it.id == PartId("A") })
		assertEquals(3, after.drawables.size, "no drawable is deleted on an ungroup")
		// A's own children (sub-part B and mesh d1) splice into A's parent R, in place.
		assertEquals(
			listOf<OrgChild>(OrgChild.Part(PartId("B")), OrgChild.Drawable(DrawableId("d1"))),
			after.parts.first { it.id == PartId("R") }.children,
		)
		assertEquals(PartId("R"), after.partByDrawable()[DrawableId("d1")])

		session.undo()
		assertTrue(session.model.value.parts.any { it.id == PartId("A") })
		assertEquals(PartId("A"), session.model.value.partByDrawable()[DrawableId("d1")])
	}
}