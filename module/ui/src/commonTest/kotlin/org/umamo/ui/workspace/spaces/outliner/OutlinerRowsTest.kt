package org.umamo.ui.workspace.spaces.outliner

import org.umamo.edit.Selection
import org.umamo.edit.SelectionOps
import org.umamo.edit.SelectionTarget
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.PartId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pins the rows the outliner lists: which nodes a fold state flattens to and at what depth, the id path
 * to a node that a reveal opens, and what a click does to the selection under each modifier.
 *
 * Over a hand-built tree, so the cases read off the structure they assert:
 *
 *   root
 *     armature
 *       deformer:w
 *         deformer:r
 *     part:head
 *       drawable:eye
 *       part:hair
 *         drawable:bang
 *     drawable:loose
 */
class OutlinerRowsTest {
	private val bangTarget = SelectionTarget.Drawable(DrawableId("bang"))
	private val eyeTarget = SelectionTarget.Drawable(DrawableId("eye"))
	private val looseTarget = SelectionTarget.Drawable(DrawableId("loose"))
	private val headTarget = SelectionTarget.Part(PartId("head"))
	private val hairTarget = SelectionTarget.Part(PartId("hair"))
	private val rotationTarget = SelectionTarget.Deformer(DeformerId("r"))

	private val bang = node("drawable:bang", "Bang", OutlinerIcon.Drawable, bangTarget)
	private val hair = node("part:hair", "Hair", OutlinerIcon.Part, hairTarget, listOf(bang))
	private val eye = node("drawable:eye", "Eye", OutlinerIcon.Drawable, eyeTarget)
	private val head = node("part:head", "Head", OutlinerIcon.Part, headTarget, listOf(eye, hair))
	private val loose = node("drawable:loose", "Loose", OutlinerIcon.Drawable, looseTarget)
	private val rotation = node("deformer:r", "R", OutlinerIcon.RotationDeformer, rotationTarget)
	private val warp = node("deformer:w", "W", OutlinerIcon.WarpDeformer, SelectionTarget.Deformer(DeformerId("w")), listOf(rotation))
	private val armature = node("armature", "Armature", OutlinerIcon.Armature, null, listOf(warp))
	private val root = node(OUTLINER_ROOT_ID, "Puppet", OutlinerIcon.PuppetRoot, null, listOf(armature, head, loose))

	private fun node(id: String, label: String, icon: OutlinerIcon, target: SelectionTarget?, children: List<OutlinerNode> = emptyList()) =
		OutlinerNode(id, label, icon, dimmed = false, target, children)

	/** The rows list only what open branches hold, each at its depth, in tree order. */
	@Test
	fun flattenListsOnlyOpenBranchesWithTheirDepths() {
		val rootOnly = flattenOutliner(root) { id -> id == OUTLINER_ROOT_ID }
		assertEquals(listOf(OUTLINER_ROOT_ID, "armature", "part:head", "drawable:loose"), rootOnly.map { row -> row.node.id })
		assertEquals(listOf(0, 1, 1, 1), rootOnly.map { row -> row.depth })

		val headOpen = flattenOutliner(root) { id -> id == OUTLINER_ROOT_ID || id == "part:head" }
		assertEquals(
			listOf(OUTLINER_ROOT_ID, "armature", "part:head", "drawable:eye", "part:hair", "drawable:loose"),
			headOpen.map { row -> row.node.id },
		)
		assertEquals(listOf(0, 1, 1, 2, 2, 1), headOpen.map { row -> row.depth })
	}

	/** The root's own fold is the predicate's to answer: closed, it is the only row. */
	@Test
	fun flattenAsksThePredicateForTheRootToo() {
		val rows = flattenOutliner(root) { false }

		assertEquals(listOf(OutlinerRow(root, 0)), rows)
	}

	/** The path runs from the root to the node, inclusive, however deep it sits. */
	@Test
	fun pathToFindsTheIdsFromTheRootToTheTarget() {
		assertEquals(listOf(OUTLINER_ROOT_ID, "part:head", "part:hair", "drawable:bang"), pathTo(root, bangTarget))
		assertEquals(listOf(OUTLINER_ROOT_ID, "drawable:loose"), pathTo(root, looseTarget))
		assertEquals(listOf(OUTLINER_ROOT_ID, "armature", "deformer:w", "deformer:r"), pathTo(root, rotationTarget))
	}

	/** A target the tree does not hold has no path. */
	@Test
	fun pathToIsNullForAnAbsentTarget() {
		assertNull(pathTo(root, SelectionTarget.Drawable(DrawableId("nope"))))
	}

	/** Searched from a subtree, the path starts at that subtree's root, and a match there is the whole path. */
	@Test
	fun pathToMatchesTheSubtreeRootItself() {
		assertEquals(listOf("part:head"), pathTo(head, headTarget))
		assertEquals(listOf("part:head", "part:hair"), pathTo(head, hairTarget))
	}

	/** A plain click replaces the selection with the clicked row. */
	@Test
	fun aPlainClickReplacesTheSelection() {
		val rows = flattenOutliner(root) { true }
		val current = Selection(setOf(headTarget), headTarget)

		val after = selectionAfterClick(rows, current, rows.indexOf(OutlinerRow(loose, 1)), looseTarget, toggle = false, extend = false)

		assertEquals(SelectionOps.replace(looseTarget), after)
	}

	/** A Ctrl / Cmd click toggles the clicked row alone. */
	@Test
	fun aToggleClickTogglesTheRow() {
		val rows = flattenOutliner(root) { true }
		val current = Selection(setOf(headTarget), headTarget)

		val added = selectionAfterClick(rows, current, rows.indexOf(OutlinerRow(loose, 1)), looseTarget, toggle = true, extend = false)
		assertEquals(SelectionOps.toggle(current, looseTarget), added)

		val removed = selectionAfterClick(rows, added, rows.indexOf(OutlinerRow(head, 1)), headTarget, toggle = true, extend = false)
		assertEquals(SelectionOps.toggle(added, headTarget), removed)
	}

	/** A Shift click adds the run of visible rows from the active row to the clicked one. */
	@Test
	fun anExtendClickRangesOverTheVisibleRows() {
		val rows = flattenOutliner(root) { id -> id == OUTLINER_ROOT_ID || id == "part:head" }
		val current = Selection(setOf(headTarget), headTarget)
		val clickedIndex = rows.indexOf(OutlinerRow(loose, 1))

		val after = selectionAfterClick(rows, current, clickedIndex, looseTarget, toggle = false, extend = true)

		assertEquals(outlinerRangeSelection(rows.map { row -> row.node.target }, current, clickedIndex, looseTarget), after)
		assertEquals(setOf(headTarget, eyeTarget, hairTarget, looseTarget), after.targets, "the folded Bang is not among the visible rows")
		assertEquals(looseTarget, after.active)
	}

	/** With both modifiers held, the range wins over the toggle. */
	@Test
	fun extendWinsOverToggle() {
		val rows = flattenOutliner(root) { true }
		val current = Selection(setOf(headTarget), headTarget)
		val clickedIndex = rows.indexOf(OutlinerRow(loose, 1))

		val after = selectionAfterClick(rows, current, clickedIndex, looseTarget, toggle = true, extend = true)

		assertEquals(selectionAfterClick(rows, current, clickedIndex, looseTarget, toggle = false, extend = true), after)
	}
}