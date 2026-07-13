package org.umamo.ui.workspace.spaces

import org.umamo.edit.Selection
import org.umamo.edit.SelectionTarget
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.Part
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.PuppetModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit-tests [buildOutlinerTree]: the unified Blender-style tree (root → Armature deformer hierarchy +
 * the org tree's interleaved parts / drawables). Corpus-free - a hand-built rig exercises deformer
 * nesting, the interleaved child order read straight off the org tree, the dim rules, and the synthetic
 * rows' null selection targets.
 */
class OutlinerTreeTest {
	private fun part(id: String, name: String, children: List<OrgChild> = emptyList(), visible: Boolean = true, sketch: Boolean = false) =
		Part(PartId(id), name, children, isVisible = visible, isSketch = sketch)

	private fun drawable(id: String, name: String, visible: Boolean = true) =
		Drawable(DrawableId(id), name, null, BlendMode.Normal, emptyList(), null, null, isVisible = visible)

	private fun warp(id: String, name: String, parent: String?) =
		Deformer.Warp(DeformerId(id), name, parent?.let(::DeformerId), null, 1, 1, true, null)

	private fun rotation(id: String, name: String, parent: String?) =
		Deformer.Rotation(DeformerId(id), name, parent?.let(::DeformerId), null, 0f, null)

	private fun model(): PuppetModel =
		PuppetModel(
			parameters = emptyList(),
			parts =
				listOf(
					part("front_hair", "Front Hair", listOf(OrgChild.Drawable(DrawableId("mesh_fh")))),
					part("head", "Head", listOf(OrgChild.Drawable(DrawableId("mesh_head")), OrgChild.Part(PartId("hair_inner")))),
					part("hair_inner", "Hair Inner", visible = false), // child of head, hidden -> dimmed
					part("guide", "[Guide Image]", sketch = true), // root, sketch -> dimmed
				),
			deformers =
				listOf(
					warp("w_root", "Root Warp", parent = null),
					rotation("r_child", "Child Rotation", parent = "w_root"),
				),
			drawables =
				listOf(
					drawable("mesh_fh", "Front Hair Mesh"),
					drawable("mesh_head", "Head Mesh", visible = false), // own eyeball off -> dimmed
				),
			rootChildren = listOf(OrgChild.Part(PartId("front_hair")), OrgChild.Part(PartId("head")), OrgChild.Part(PartId("guide"))),
			rootPartId = null,
		)

	@Test
	fun rootHoldsArmatureThenTopLevelPartsInOrder() {
		val root = buildOutlinerTree(model(), rootLabel = "Puppet", armatureLabel = "Armature")

		assertEquals("Puppet", root.label)
		assertEquals(OutlinerIcon.PuppetRoot, root.icon)
		assertNull(root.target, "the puppet root only expands, it does not select")

		// First child is the Armature node; the rest are the root org children in their tree order.
		assertEquals(
			listOf("armature", "part:front_hair", "part:head", "part:guide"),
			root.children.map { it.id },
		)
	}

	@Test
	fun rootReadsInterleavedPartsAndDrawablesFromTree() {
		// Cubism panel order: part a, a loose root mesh, part b - read straight off rootChildren.
		val puppet =
			PuppetModel(
				parameters = emptyList(),
				parts = listOf(part("a", "A"), part("b", "B")),
				deformers = emptyList(),
				drawables = listOf(drawable("loose", "Loose")),
				rootChildren = listOf(OrgChild.Part(PartId("a")), OrgChild.Drawable(DrawableId("loose")), OrgChild.Part(PartId("b"))),
				rootPartId = null,
			)
		val root = buildOutlinerTree(puppet, rootLabel = "Puppet", armatureLabel = "Armature")

		assertEquals(listOf("armature", "part:a", "drawable:loose", "part:b"), root.children.map { it.id })
	}

	@Test
	fun partReadsInterleavedChildOrderFromTree() {
		// Inside "Head": drawable d1, sub-part Inner, drawable d2 - the sub-part sits between the meshes.
		val puppet =
			PuppetModel(
				parameters = emptyList(),
				parts =
					listOf(
						part(
							"head",
							"Head",
							listOf(OrgChild.Drawable(DrawableId("d1")), OrgChild.Part(PartId("inner")), OrgChild.Drawable(DrawableId("d2"))),
						),
						part("inner", "Inner"),
					),
				deformers = emptyList(),
				drawables = listOf(drawable("d1", "D1"), drawable("d2", "D2")),
				rootChildren = listOf(OrgChild.Part(PartId("head"))),
				rootPartId = null,
			)
		val head = buildOutlinerTree(puppet).children.first { it.id == "part:head" }

		assertEquals(listOf("drawable:d1", "part:inner", "drawable:d2"), head.children.map { it.id })
	}

	@Test
	fun armatureNestsDeformersByParent() {
		val armature = buildOutlinerTree(model()).children.first { it.id == "armature" }

		assertEquals(OutlinerIcon.Armature, armature.icon)
		assertNull(armature.target)
		assertEquals(listOf("deformer:w_root"), armature.children.map { it.id })

		val warpNode = armature.children.single()
		assertEquals("Root Warp", warpNode.label)
		assertEquals(OutlinerIcon.WarpDeformer, warpNode.icon)

		val rotationNode = warpNode.children.single()
		assertEquals(OutlinerIcon.RotationDeformer, rotationNode.icon)
		assertEquals(SelectionTarget.Deformer(DeformerId("r_child")), rotationNode.target)
	}

	@Test
	fun partListsItsChildrenInTreeOrderAndUsesNames() {
		val head = buildOutlinerTree(model()).children.first { it.id == "part:head" }

		assertEquals(SelectionTarget.Part(PartId("head")), head.target)
		// The org tree puts the drawable before the sub-part here.
		assertEquals(listOf("drawable:mesh_head", "part:hair_inner"), head.children.map { it.id })
		assertEquals("Head Mesh", head.children.first().label)
	}

	@Test
	fun dimsHiddenAndSketchRows() {
		val root = buildOutlinerTree(model())
		val guide = root.children.first { it.id == "part:guide" }
		val head = root.children.first { it.id == "part:head" }
		val hiddenDrawable = head.children.first { it.id == "drawable:mesh_head" }
		val hiddenChildPart = head.children.first { it.id == "part:hair_inner" }
		val visibleDrawable = root.children.first { it.id == "part:front_hair" }.children.single()

		assertTrue(guide.dimmed, "sketch part is dimmed")
		assertTrue(hiddenDrawable.dimmed, "hidden drawable is dimmed")
		assertTrue(hiddenChildPart.dimmed, "hidden part is dimmed")
		assertTrue(!visibleDrawable.dimmed, "a visible drawable is not dimmed")
	}

	@Test
	fun filterHidesArmatureWhenDeformersToggledOff() {
		val filtered = filterOutliner(buildOutlinerTree(model()), query = "", showParts = true, showDrawables = true, showDeformers = false)

		assertTrue(filtered.children.none { it.id == "armature" }, "the Armature subtree is removed")
		assertTrue(filtered.children.any { it.id == "part:front_hair" }, "parts remain")
	}

	@Test
	fun filterHidesDrawableRowsWhenDrawablesToggledOff() {
		val filtered = filterOutliner(buildOutlinerTree(model()), query = "", showParts = true, showDrawables = false, showDeformers = true)

		val frontHair = filtered.children.first { it.id == "part:front_hair" }
		assertTrue(frontHair.children.isEmpty(), "drawables under the part are removed")
		assertTrue(filtered.children.any { it.id == "armature" }, "the Armature subtree remains")
	}

	@Test
	fun filterHoistsDrawablesWhenPartsToggledOff() {
		val filtered = filterOutliner(buildOutlinerTree(model()), query = "", showParts = false, showDrawables = true, showDeformers = true)

		// No part / folder rows survive; their drawables flatten up under the root next to the Armature.
		assertTrue(filtered.children.none { it.id.startsWith("part:") }, "part rows are removed")
		assertEquals(listOf("armature", "drawable:mesh_fh", "drawable:mesh_head"), filtered.children.map { it.id })
	}

	@Test
	fun searchKeepsOnlyThePathToMatchesAndDropsUnrelatedChildren() {
		val filtered = filterOutliner(buildOutlinerTree(model()), query = "head", showParts = true, showDrawables = true, showDeformers = true)

		// Only the matching part survives at the top level; Armature / Front Hair / Guide are dropped.
		assertEquals(listOf("part:head"), filtered.children.map { it.id })
		// A matched part keeps only its matching descendant ("Head Mesh"); the unrelated sub-part is dropped.
		assertEquals(listOf("drawable:mesh_head"), filtered.children.single().children.map { it.id })
	}

	@Test
	fun searchKeepsNonMatchingAncestorsAsThePathToADeepMatch() {
		// "Child Rotation" is nested under "Root Warp" under Armature; only that path survives.
		val filtered = filterOutliner(buildOutlinerTree(model()), query = "child", showParts = true, showDrawables = true, showDeformers = true)

		assertEquals(listOf("armature"), filtered.children.map { it.id })
		val warp = filtered.children.single().children.single()
		assertEquals("deformer:w_root", warp.id) // kept as an ancestor though "Root Warp" does not match
		assertEquals(listOf("deformer:r_child"), warp.children.map { it.id })
	}

	@Test
	fun armatureNodePresentEvenWithNoDeformers() {
		val empty = PuppetModel(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), null)
		val root = buildOutlinerTree(empty)

		val armature = root.children.single()
		assertEquals("armature", armature.id)
		assertTrue(armature.children.isEmpty())
	}

	private val partA = SelectionTarget.Part(PartId("a"))
	private val meshB = SelectionTarget.Drawable(DrawableId("b"))
	private val meshC = SelectionTarget.Drawable(DrawableId("c"))
	private val partD = SelectionTarget.Part(PartId("d"))

	@Test
	fun rangeSelectAddsTheRunFromAnchorToClickInEitherDirection() {
		// index:        0     1      2      3      4
		val ordered = listOf<SelectionTarget?>(null, partA, meshB, meshC, partD)

		val forward = outlinerRangeSelection(ordered, Selection(setOf(partA), partA), clickedIndex = 3, clickedTarget = meshC)
		assertEquals(setOf(partA, meshB, meshC), forward.targets)
		assertEquals(meshC, forward.active, "the clicked row becomes active")

		// Anchored at the clicked-from row, clicking upward selects the same run.
		val backward = outlinerRangeSelection(ordered, Selection(setOf(meshC), meshC), clickedIndex = 1, clickedTarget = partA)
		assertEquals(setOf(partA, meshB, meshC), backward.targets)
		assertEquals(partA, backward.active)
	}

	@Test
	fun rangeSelectUnionsWithExistingSelectionAndSkipsNullRows() {
		// A prior pick (partD) stays; the new range adds partA..meshB, skipping the synthetic null row.
		val ordered = listOf<SelectionTarget?>(partA, null, meshB, partD)
		val result = outlinerRangeSelection(ordered, Selection(setOf(partD, partA), partA), clickedIndex = 2, clickedTarget = meshB)

		assertEquals(setOf(partA, meshB, partD), result.targets)
		assertEquals(meshB, result.active)
	}

	@Test
	fun rangeSelectWithNoAnchorSelectsOnlyTheClickedRow() {
		val ordered = listOf<SelectionTarget?>(partA, meshB, meshC)
		val result = outlinerRangeSelection(ordered, Selection(), clickedIndex = 1, clickedTarget = meshB)

		assertEquals(setOf(meshB), result.targets)
		assertEquals(meshB, result.active)
	}
}
