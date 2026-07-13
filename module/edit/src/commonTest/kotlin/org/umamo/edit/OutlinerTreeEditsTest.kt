package org.umamo.edit

import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.Part
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.PuppetModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OutlinerTreeEditsTest {
	private val partA = PartId("A")
	private val partB = PartId("B")
	private val drawable1 = DrawableId("d1")
	private val drawable2 = DrawableId("d2")
	private val drawable3 = DrawableId("d3")
	private val warpRoot = DeformerId("w1")
	private val warpSibling = DeformerId("w2")
	private val warpNested = DeformerId("w3")

	/**
	 * A minimal tree model: part A holds drawables d1 + d2, part B is empty, d3 sits at the root; the
	 * armature has roots w1 + w2 with w3 nested under w1.  Only the fields the drop resolution walks
	 * (parts, rootChildren, deformers) are populated.
	 *
	 * @return PuppetModel The fixture model.
	 */
	private fun model(): PuppetModel =
		PuppetModel(
			parameters = emptyList(),
			parts =
				listOf(
					Part(partA, "A", children = listOf(OrgChild.Drawable(drawable1), OrgChild.Drawable(drawable2))),
					Part(partB, "B", children = emptyList()),
				),
			deformers =
				listOf(
					rotation(warpRoot, parent = null),
					rotation(warpSibling, parent = null),
					rotation(warpNested, parent = warpRoot),
				),
			drawables = emptyList(),
			rootChildren = listOf(OrgChild.Part(partA), OrgChild.Part(partB), OrgChild.Drawable(drawable3)),
			rootPartId = null,
		)

	/**
	 * A minimal rotation deformer for armature-shape tests (the kind is irrelevant to drop routing).
	 *
	 * @param DeformerId id The deformer's id.
	 * @param DeformerId? parent Its nesting parent, or null at the armature root.
	 * @return Deformer The deformer.
	 */
	private fun rotation(id: DeformerId, parent: DeformerId?): Deformer =
		Deformer.Rotation(id, id.raw, parent = parent, partId = null, baseAngle = 0f, keyforms = null)

	@Test
	fun bandRefusesCrossDomainDrops() {
		assertNull(outlinerDropBandFor(SelectionTarget.Deformer(warpRoot), SelectionTarget.Drawable(drawable1), 0.5f))
		assertNull(outlinerDropBandFor(SelectionTarget.Drawable(drawable1), SelectionTarget.Deformer(warpRoot), 0.5f))
	}

	@Test
	fun drawableTargetSplitsAtMidlineAndNeverNests() {
		val dragged = SelectionTarget.Drawable(drawable3)
		val target = SelectionTarget.Drawable(drawable1)
		assertEquals(RowDropBand.Before, outlinerDropBandFor(dragged, target, 0.4f))
		assertEquals(RowDropBand.After, outlinerDropBandFor(dragged, target, 0.6f))
	}

	@Test
	fun partTargetHasWideIntoBand() {
		val dragged = SelectionTarget.Drawable(drawable3)
		val target = SelectionTarget.Part(partB)
		assertEquals(RowDropBand.Before, outlinerDropBandFor(dragged, target, 0.2f))
		assertEquals(RowDropBand.Into, outlinerDropBandFor(dragged, target, 0.5f))
		assertEquals(RowDropBand.After, outlinerDropBandFor(dragged, target, 0.8f))
	}

	@Test
	fun beforeDropAnchorsOnTargetInsideItsParent() {
		val drop =
			model().resolveOutlinerDrop(SelectionTarget.Drawable(drawable3), SelectionTarget.Drawable(drawable1), 0.1f)
		assertEquals(
			OutlinerDrop.MoveOrgChild(OrgChild.Drawable(drawable3), partA, before = OrgChild.Drawable(drawable1), expandTarget = false),
			drop,
		)
	}

	@Test
	fun afterDropAnchorsOnNextSibling() {
		val drop =
			model().resolveOutlinerDrop(SelectionTarget.Drawable(drawable3), SelectionTarget.Drawable(drawable1), 0.9f)
		assertEquals(
			OutlinerDrop.MoveOrgChild(OrgChild.Drawable(drawable3), partA, before = OrgChild.Drawable(drawable2), expandTarget = false),
			drop,
		)
	}

	@Test
	fun afterDropOnLastSiblingAppends() {
		val drop =
			model().resolveOutlinerDrop(SelectionTarget.Drawable(drawable3), SelectionTarget.Drawable(drawable2), 0.9f)
		assertEquals(
			OutlinerDrop.MoveOrgChild(OrgChild.Drawable(drawable3), partA, before = null, expandTarget = false),
			drop,
		)
	}

	@Test
	fun intoPartNestsAndExpands() {
		val drop =
			model().resolveOutlinerDrop(SelectionTarget.Drawable(drawable3), SelectionTarget.Part(partB), 0.5f)
		assertEquals(
			OutlinerDrop.MoveOrgChild(OrgChild.Drawable(drawable3), partB, before = null, expandTarget = true),
			drop,
		)
	}

	@Test
	fun rootLevelTargetResolvesRootSiblings() {
		// Dropping d1 after part B at the root: the next root sibling is d3.
		val drop =
			model().resolveOutlinerDrop(SelectionTarget.Drawable(drawable1), SelectionTarget.Part(partB), 0.9f)
		assertEquals(
			OutlinerDrop.MoveOrgChild(OrgChild.Drawable(drawable1), newParentId = null, before = OrgChild.Drawable(drawable3), expandTarget = false),
			drop,
		)
	}

	@Test
	fun deformerBeforeAndAfterAnchorWithinParent() {
		val model = model()
		// w2 dropped before w3 (nested under w1): parent w1, anchored on w3.
		assertEquals(
			OutlinerDrop.MoveDeformer(warpSibling, warpRoot, beforeId = warpNested, expandTarget = false),
			model.resolveOutlinerDrop(SelectionTarget.Deformer(warpSibling), SelectionTarget.Deformer(warpNested), 0.1f),
		)
		// w2 dropped after w3, the last of w1's children: append (null anchor).
		assertEquals(
			OutlinerDrop.MoveDeformer(warpSibling, warpRoot, beforeId = null, expandTarget = false),
			model.resolveOutlinerDrop(SelectionTarget.Deformer(warpSibling), SelectionTarget.Deformer(warpNested), 0.9f),
		)
	}

	@Test
	fun deformerIntoNestsAndExpands() {
		assertEquals(
			OutlinerDrop.MoveDeformer(warpSibling, warpRoot, beforeId = null, expandTarget = true),
			model().resolveOutlinerDrop(SelectionTarget.Deformer(warpSibling), SelectionTarget.Deformer(warpRoot), 0.5f),
		)
	}
}
