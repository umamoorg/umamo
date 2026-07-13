package org.umamo.ui.workspace.spaces

import org.umamo.edit.ParameterMoveSubject
import org.umamo.edit.ParameterNodeRef
import org.umamo.edit.RowDropBand
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterGroupId
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.ParameterNode
import org.umamo.runtime.model.PuppetModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pins the pure parameter drop rules under the one-level constraint: which (dragged, target, band)
 * combinations are legal, and how a legal band resolves to a destination parent and insert-before anchor
 * (a pad's "after" skips its vertical partner).
 */
class ParametersDropRulesTest {
	private val angleX = ParameterId("ParamAngleX")
	private val angleY = ParameterId("ParamAngleY")
	private val angleZ = ParameterId("ParamAngleZ")
	private val breath = ParameterId("ParamBreath")
	private val groupA = ParameterGroupId("ParamGroup")

	private fun parameter(id: ParameterId): Parameter = Parameter(id, id.raw, min = -1f, max = 1f, default = 0f)

	private fun single(id: ParameterId, depth: Int = 0): ParameterRow.Single = ParameterRow.Single(parameter(id), depth)

	private fun pad(depth: Int = 0): ParameterRow.Pair2D = ParameterRow.Pair2D(parameter(angleY), parameter(angleZ), depth)

	private fun header(depth: Int = 0): ParameterRow.GroupHeader = ParameterRow.GroupHeader(groupA, "Group", depth, expanded = true)

	private val draggedLeaf = ParameterMoveSubject.Leaves(listOf(breath))
	private val draggedGroup = ParameterMoveSubject.Group(ParameterGroupId("ParamGroupOther"))

	/** A leaf target takes Before above the midline, After below; a dragged group only if the target is at root. */
	@Test
	fun leafTargetBands() {
		assertEquals(RowDropBand.Before, parameterDropBandFor(draggedLeaf, single(angleX), targetAtRoot = true, fraction = 0.2f))
		assertEquals(RowDropBand.After, parameterDropBandFor(draggedLeaf, single(angleX), targetAtRoot = true, fraction = 0.8f))
		// A dragged group onto a root leaf reorders among root nodes.
		assertEquals(RowDropBand.After, parameterDropBandFor(draggedGroup, single(angleX), targetAtRoot = true, fraction = 0.8f))
		// A dragged group onto an in-group leaf is illegal (a group can't live inside a group).
		assertNull(parameterDropBandFor(draggedGroup, single(angleX, depth = 1), targetAtRoot = false, fraction = 0.8f))
	}

	/** A group header nests a dragged leaf in its middle band and reorders at its edges; a dragged group never nests. */
	@Test
	fun groupHeaderBands() {
		assertEquals(RowDropBand.Before, parameterDropBandFor(draggedLeaf, header(), targetAtRoot = true, fraction = 0.1f))
		assertEquals(RowDropBand.Into, parameterDropBandFor(draggedLeaf, header(), targetAtRoot = true, fraction = 0.5f))
		assertEquals(RowDropBand.After, parameterDropBandFor(draggedLeaf, header(), targetAtRoot = true, fraction = 0.9f))
		// A dragged group onto a group header only reorders - never Into (even in the middle band).
		assertEquals(RowDropBand.Before, parameterDropBandFor(draggedGroup, header(), targetAtRoot = true, fraction = 0.4f))
		assertEquals(RowDropBand.After, parameterDropBandFor(draggedGroup, header(), targetAtRoot = true, fraction = 0.6f))
	}

	private fun model(parameters: List<Parameter>, tree: List<ParameterNode>): PuppetModel =
		PuppetModel(
			parameters = parameters,
			parts = emptyList(),
			deformers = emptyList(),
			drawables = emptyList(),
			rootChildren = emptyList(),
			rootPartId = null,
			parameterTree = tree,
		)

	/** Before anchors on the target's leading node; Into a group appends inside it. */
	@Test
	fun anchorBeforeAndInto() {
		val tree = listOf(ParameterNode.Param(angleX), header().let { ParameterNode.Group(groupA, "Group", true, emptyList()) })
		val puppet = model(listOf(parameter(angleX)), tree)
		assertEquals(null to ParameterNodeRef.Leaf(angleX), parameterDropAnchor(puppet, single(angleX), RowDropBand.Before))
		assertEquals(groupA to null, parameterDropAnchor(puppet, header(), RowDropBand.Into))
	}

	/** After a root leaf anchors on the next root node; after the last node appends (null). */
	@Test
	fun anchorAfterRootLeaf() {
		val tree = listOf(ParameterNode.Param(angleX), ParameterNode.Param(breath))
		val puppet = model(listOf(parameter(angleX), parameter(breath)), tree)
		assertEquals(null to ParameterNodeRef.Leaf(breath), parameterDropAnchor(puppet, single(angleX), RowDropBand.After))
		assertEquals(null to null, parameterDropAnchor(puppet, single(breath), RowDropBand.After))
	}

	/** After a pad skips its vertical partner and anchors on the node past both leaves. */
	@Test
	fun anchorAfterPadSkipsVertical() {
		// Tree: [padHorizontal(angleY), padVertical(angleZ), breath]; after the pad should anchor on breath.
		val tree = listOf(ParameterNode.Param(angleY), ParameterNode.Param(angleZ), ParameterNode.Param(breath))
		val puppet = model(listOf(parameter(angleY), parameter(angleZ), parameter(breath)), tree)
		assertEquals(null to ParameterNodeRef.Leaf(breath), parameterDropAnchor(puppet, pad(), RowDropBand.After))
	}
}
