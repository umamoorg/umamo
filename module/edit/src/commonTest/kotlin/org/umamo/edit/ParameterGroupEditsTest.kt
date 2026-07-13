package org.umamo.edit

import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterGroupId
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.ParameterLink
import org.umamo.runtime.model.ParameterNode
import org.umamo.runtime.model.PuppetModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins the parameter-group document edits: create mints a fresh expanded group at the top, delete
 * unwraps (splicing children to the root, leaving parameters and links untouched), rename changes the
 * label, a blank rename is a no-op, the id mint avoids collisions, and each undoes.
 */
class ParameterGroupEditsTest {
	private val angleX = ParameterId("ParamAngleX")
	private val angleY = ParameterId("ParamAngleY")

	/** An animatable parameter over -1..1. */
	private fun parameter(id: ParameterId): Parameter = Parameter(id, id.raw, min = -1f, max = 1f, default = 0f)

	/** A drawable-less model holding [parameters], [links], and an optional group [tree]. */
	private fun model(
		parameters: List<Parameter>,
		links: List<ParameterLink> = emptyList(),
		tree: List<ParameterNode> = emptyList(),
	): PuppetModel =
		PuppetModel(
			parameters = parameters,
			parts = emptyList(),
			deformers = emptyList(),
			drawables = emptyList(),
			rootChildren = emptyList(),
			rootPartId = null,
			parameterLinks = links,
			parameterTree = tree,
		)

	/** A group node with the given children. */
	private fun group(id: ParameterGroupId, children: List<ParameterNode>): ParameterNode.Group =
		ParameterNode.Group(id, id.raw, initiallyOpen = true, children = children)

	/** Create prepends a fresh expanded group and materializes the flat tree beneath it. */
	@Test
	fun createPrependsFreshExpandedGroupAtTop() {
		val session = EditorSession(model(listOf(parameter(angleX), parameter(angleY))))
		val id = session.createParameterGroup("New Group")

		val tree = session.model.value.parameterTree
		val head = tree.first() as ParameterNode.Group
		assertEquals(id, head.id)
		assertEquals("New Group", head.name)
		assertTrue(head.initiallyOpen)
		assertTrue(head.children.isEmpty())
		// The former flat parameters materialized as root leaves beneath the new group.
		assertEquals(listOf(angleX, angleY), tree.filterIsInstance<ParameterNode.Param>().map { it.id })

		session.undo()
		assertTrue(session.model.value.parameterTree.isEmpty(), "undo restores the group-less tree")
	}

	/** The minted id skips ids already present. */
	@Test
	fun freshIdAvoidsCollision() {
		val existing = model(emptyList(), tree = listOf(group(ParameterGroupId("ParamGroup"), emptyList()), group(ParameterGroupId("ParamGroup2"), emptyList())))
		assertEquals(ParameterGroupId("ParamGroup3"), existing.freshParameterGroupId())
	}

	/** Delete unwraps: children splice to the root at the group's position; parameters and links untouched. */
	@Test
	fun deleteSplicesChildrenToRootAtPosition() {
		val links = listOf(ParameterLink(angleX, angleY))
		val tree =
			listOf(
				ParameterNode.Param(angleX),
				group(ParameterGroupId("ParamGroup"), listOf(ParameterNode.Param(angleY))),
			)
		val startModel = model(listOf(parameter(angleX), parameter(angleY)), links, tree)
		val session = EditorSession(startModel)
		session.deleteParameterGroup(ParameterGroupId("ParamGroup"))

		val newModel = session.model.value
		assertEquals(listOf(angleX, angleY), newModel.parameterTree.filterIsInstance<ParameterNode.Param>().map { it.id })
		assertTrue(newModel.parameterTree.none { it is ParameterNode.Group }, "the group node is gone")
		assertSame(startModel.parameters, newModel.parameters, "the axis list is untouched")
		assertSame(startModel.parameterLinks, newModel.parameterLinks, "the links are untouched")

		session.undo()
		assertTrue(session.model.value.parameterTree.any { it is ParameterNode.Group }, "undo restores the group")
	}

	/** Rename changes the name; a blank rename is a no-op. */
	@Test
	fun renameChangesNameAndBlankIsNoOp() {
		val groupId = ParameterGroupId("ParamGroup")
		val startModel = model(emptyList(), tree = listOf(group(groupId, emptyList())))
		val renamed = startModel.withParameterGroupRenamed(groupId, "  Eyes  ")
		assertEquals("Eyes", (renamed.parameterTree.single() as ParameterNode.Group).name)

		assertSame(startModel, startModel.withParameterGroupRenamed(groupId, "   "), "a blank rename is a no-op")
	}
}
