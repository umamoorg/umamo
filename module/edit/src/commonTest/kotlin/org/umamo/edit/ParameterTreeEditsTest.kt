package org.umamo.edit

import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterGroupId
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.ParameterNode
import org.umamo.runtime.model.PuppetModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins the pure parameter-tree move surgery: reorder within the root, move a parameter into / out of a
 * group, reorder a group among root nodes, the atomic linked-pair move, materialize-on-empty, and the
 * one-level and no-op refusals (which record no undo step).
 */
class ParameterTreeEditsTest {
	private val angleX = ParameterId("ParamAngleX")
	private val angleY = ParameterId("ParamAngleY")
	private val angleZ = ParameterId("ParamAngleZ")
	private val breath = ParameterId("ParamBreath")
	private val groupA = ParameterGroupId("ParamGroup")
	private val groupB = ParameterGroupId("ParamGroup2")

	/** An animatable parameter over -1..1. */
	private fun parameter(id: ParameterId): Parameter = Parameter(id, id.raw, min = -1f, max = 1f, default = 0f)

	/**
	 * A drawable-less model holding [parameters] and an optional group [tree].
	 *
	 * @param List parameters The parameters (the id-keyed axis list).
	 * @param List tree       The group tree (empty = the flat fallback).
	 * @return PuppetModel The fixture model.
	 */
	private fun model(parameters: List<Parameter>, tree: List<ParameterNode> = emptyList()): PuppetModel =
		PuppetModel(
			parameters = parameters,
			parts = emptyList(),
			deformers = emptyList(),
			drawables = emptyList(),
			rootChildren = emptyList(),
			rootPartId = null,
			parameterTree = tree,
		)

	/** The flat leaf-id sequence of a tree's root level (groups are not leaves). */
	private fun rootLeafIds(tree: List<ParameterNode>): List<ParameterId> =
		tree.filterIsInstance<ParameterNode.Param>().map { leaf -> leaf.id }

	/** A group node with the given children. */
	private fun group(id: ParameterGroupId, children: List<ParameterNode>): ParameterNode.Group =
		ParameterNode.Group(id, id.raw, initiallyOpen = true, children = children)

	/** Reordering within the root moves the leaf and leaves the others in place. */
	@Test
	fun reorderWithinRoot() {
		val tree = listOf(ParameterNode.Param(angleX), ParameterNode.Param(angleY), ParameterNode.Param(angleZ))
		val session = EditorSession(model(listOf(parameter(angleX), parameter(angleY), parameter(angleZ)), tree))
		session.moveParameterRow(
			ParameterMoveSubject.Leaves(listOf(angleX)),
			newParentGroupId = null,
			before = ParameterNodeRef.Leaf(angleZ),
		)
		assertEquals(listOf(angleY, angleX, angleZ), rootLeafIds(session.model.value.parameterTree))
		session.undo()
		assertEquals(listOf(angleX, angleY, angleZ), rootLeafIds(session.model.value.parameterTree))
	}

	/** Moving a root leaf into a group re-homes it as that group's child and removes it from the root. */
	@Test
	fun moveLeafIntoGroup() {
		val tree = listOf(ParameterNode.Param(angleX), group(groupA, listOf(ParameterNode.Param(angleY))))
		val session = EditorSession(model(listOf(parameter(angleX), parameter(angleY)), tree))
		session.moveParameterRow(ParameterMoveSubject.Leaves(listOf(angleX)), newParentGroupId = groupA, before = null)
		val newTree = session.model.value.parameterTree
		assertTrue(rootLeafIds(newTree).isEmpty(), "the leaf left the root")
		val destination = newTree.filterIsInstance<ParameterNode.Group>().single()
		assertEquals(listOf(angleY, angleX), destination.children.filterIsInstance<ParameterNode.Param>().map { it.id })
	}

	/** Moving a group child out to the root inserts it at the chosen root position. */
	@Test
	fun moveLeafOutToRoot() {
		val tree = listOf(group(groupA, listOf(ParameterNode.Param(angleX), ParameterNode.Param(angleY))))
		val session = EditorSession(model(listOf(parameter(angleX), parameter(angleY)), tree))
		session.moveParameterRow(
			ParameterMoveSubject.Leaves(listOf(angleX)),
			newParentGroupId = null,
			before = ParameterNodeRef.Group(groupA),
		)
		val newTree = session.model.value.parameterTree
		assertEquals(listOf(angleX), rootLeafIds(newTree), "the leaf is now a root sibling before the group")
		val remaining = newTree.filterIsInstance<ParameterNode.Group>().single()
		assertEquals(listOf(angleY), remaining.children.filterIsInstance<ParameterNode.Param>().map { it.id })
	}

	/** A group reorders among root nodes, carrying its children. */
	@Test
	fun reorderGroupAmongRootNodes() {
		val tree =
			listOf(
				ParameterNode.Param(angleX),
				group(groupA, listOf(ParameterNode.Param(angleY))),
				ParameterNode.Param(angleZ),
			)
		val session = EditorSession(model(listOf(parameter(angleX), parameter(angleY), parameter(angleZ)), tree))
		session.moveParameterRow(
			ParameterMoveSubject.Group(groupA),
			newParentGroupId = null,
			before = ParameterNodeRef.Leaf(angleX),
		)
		val newTree = session.model.value.parameterTree
		assertTrue(newTree.first() is ParameterNode.Group, "the group leads the root now")
		assertEquals(groupA, (newTree.first() as ParameterNode.Group).id)
		assertEquals(listOf(angleX, angleZ), rootLeafIds(newTree))
	}

	/** A linked pair moves as one contiguous run, keeping horizontal-then-vertical order. */
	@Test
	fun pairMoveKeepsLeavesAdjacent() {
		val tree =
			listOf(
				ParameterNode.Param(angleX),
				ParameterNode.Param(angleY),
				ParameterNode.Param(angleZ),
				ParameterNode.Param(breath),
			)
		val session = EditorSession(model(listOf(parameter(angleX), parameter(angleY), parameter(angleZ), parameter(breath)), tree))
		// angleY (horizontal) + angleZ (vertical) are a linked pad; move them to the front.
		session.moveParameterRow(
			ParameterMoveSubject.Leaves(listOf(angleY, angleZ)),
			newParentGroupId = null,
			before = ParameterNodeRef.Leaf(angleX),
		)
		assertEquals(listOf(angleY, angleZ, angleX, breath), rootLeafIds(session.model.value.parameterTree))
	}

	/** A group-less model materializes a flat tree on the first real move; a no-op leaves the tree empty. */
	@Test
	fun materializeOnEmptyTreeThenMove() {
		val base = model(listOf(parameter(angleX), parameter(angleY), parameter(angleZ)))
		val moved =
			base.withParameterRowMoved(
				ParameterMoveSubject.Leaves(listOf(angleX)),
				newParentGroupId = null,
				before = ParameterNodeRef.Leaf(angleZ),
			)
		assertEquals(listOf(angleY, angleX, angleZ), rootLeafIds(moved.parameterTree), "the flat tree materialized and the move applied")

		// A move that changes nothing must not materialize a tree - the model stays group-less.
		val noOp =
			base.withParameterRowMoved(
				ParameterMoveSubject.Leaves(listOf(angleX)),
				newParentGroupId = null,
				before = ParameterNodeRef.Leaf(angleY),
			)
		assertSame(base, noOp)
		assertTrue(noOp.parameterTree.isEmpty())
	}

	/** A group cannot be dropped inside another group (one level only) - same instance, no step. */
	@Test
	fun refusesGroupUnderGroup() {
		val tree = listOf(group(groupA, emptyList()), group(groupB, emptyList()))
		val session = EditorSession(model(emptyList(), tree))
		session.moveParameterRow(ParameterMoveSubject.Group(groupA), newParentGroupId = groupB, before = null)
		assertFalse(session.canUndo.value, "a refused move records no undo step")
		assertEquals(tree, session.model.value.parameterTree)
	}

	/** A move that changes nothing returns the same instance. */
	@Test
	fun noOpReturnsSameInstance() {
		val tree = listOf(ParameterNode.Param(angleX), ParameterNode.Param(angleY))
		val base = model(listOf(parameter(angleX), parameter(angleY)), tree)
		val result =
			base.withParameterRowMoved(
				ParameterMoveSubject.Leaves(listOf(angleX)),
				newParentGroupId = null,
				before = ParameterNodeRef.Leaf(angleY),
			)
		assertSame(base, result)
	}
}
