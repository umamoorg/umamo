package org.umamo.edit

import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.Part
import org.umamo.runtime.model.PartComposite
import org.umamo.runtime.model.PartGroupMode
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.PuppetModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

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
}