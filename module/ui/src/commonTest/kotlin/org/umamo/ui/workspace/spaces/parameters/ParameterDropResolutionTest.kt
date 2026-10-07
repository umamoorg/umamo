package org.umamo.ui.workspace.spaces.parameters

import org.umamo.edit.ParameterMoveSubject
import org.umamo.edit.parameter.ParameterNodeRef
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterGroupId
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.ParameterNode
import org.umamo.runtime.model.PuppetModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pins a release resolved whole: from the row the pointer was over and how far down it, to the move the
 * document takes and the group that opens for it.
 */
class ParameterDropResolutionTest {
	private val bodyX = ParameterId("ParamBodyX")
	private val breath = ParameterId("ParamBreath")
	private val eyeOpen = ParameterId("ParamEyeOpen")
	private val face = ParameterGroupId("GroupFace")
	private val body = ParameterGroupId("GroupBody")

	/**
	 * An animatable parameter named by its id.
	 *
	 * @param ParameterId id The parameter id.
	 * @return Parameter The fixture parameter.
	 */
	private fun parameter(id: ParameterId): Parameter = Parameter(id, id.raw, min = -1f, max = 1f, default = 0f)

	private val puppet =
		PuppetModel(
			parameters = listOf(parameter(eyeOpen), parameter(bodyX), parameter(breath)),
			parts = emptyList(),
			deformers = emptyList(),
			drawables = emptyList(),
			rootChildren = emptyList(),
			rootPartId = null,
			parameterTree =
				listOf(
					ParameterNode.Group(face, "Face", initiallyOpen = true, children = listOf(ParameterNode.Param(eyeOpen))),
					ParameterNode.Param(bodyX),
					ParameterNode.Param(breath),
					ParameterNode.Group(body, "Body", initiallyOpen = false, children = emptyList()),
				),
		)

	private val faceRow = ParameterRow.GroupHeader(face, "Face", depth = 0, expanded = true)
	private val eyeOpenRow = ParameterRow.Single(parameter(eyeOpen), depth = 1)
	private val bodyXRow = ParameterRow.Single(parameter(bodyX), depth = 0)
	private val breathRow = ParameterRow.Single(parameter(breath), depth = 0)
	private val rows: List<ParameterRow> = listOf(faceRow, eyeOpenRow, bodyXRow, breathRow)

	private val draggedBreath = ParameterMoveSubject.Leaves(listOf(breath))
	private val draggedBody = ParameterMoveSubject.Group(body)

	/** A release on a row's upper half puts the dragged row ahead of it, and opens nothing. */
	@Test
	fun aReleaseOnTheUpperHalfLandsBefore() {
		val drop = resolveParameterDrop(puppet, rows, draggedBreath, rowKey(bodyXRow), fraction = 0.25f)

		assertEquals(ParameterDrop(newParentGroupId = null, before = ParameterNodeRef.Leaf(bodyX), expandsGroupId = null), drop)
	}

	/** A release on a row's lower half puts the dragged row ahead of whatever follows it. */
	@Test
	fun aReleaseOnTheLowerHalfLandsAfter() {
		val drop = resolveParameterDrop(puppet, rows, draggedBreath, rowKey(eyeOpenRow), fraction = 0.75f)

		// Eye Open is the last of its group, so "after it" is the end of that group.
		assertEquals(ParameterDrop(newParentGroupId = face, before = null, expandsGroupId = null), drop)
	}

	/** A release on the middle of a group header nests, and asks for the group to be opened. */
	@Test
	fun aReleaseOnAGroupsMiddleNestsAndOpensIt() {
		val drop = resolveParameterDrop(puppet, rows, draggedBreath, rowKey(faceRow), fraction = 0.5f)

		assertEquals(ParameterDrop(newParentGroupId = face, before = null, expandsGroupId = face), drop)
	}

	/** A release on a group header's edge reorders beside the group and opens nothing. */
	@Test
	fun aReleaseOnAGroupsEdgeReorders() {
		val drop = resolveParameterDrop(puppet, rows, draggedBreath, rowKey(faceRow), fraction = 0.1f)

		assertEquals(ParameterDrop(newParentGroupId = null, before = ParameterNodeRef.Group(face), expandsGroupId = null), drop)
	}

	/** A group dragged over a row inside another group has nowhere to land. */
	@Test
	fun aGroupOverANestedRowDropsNothing() {
		assertNull(resolveParameterDrop(puppet, rows, draggedBody, rowKey(eyeOpenRow), fraction = 0.5f))
	}

	/** A group dragged over a group header's middle reorders, since a group never nests. */
	@Test
	fun aGroupOverAGroupsMiddleReorders() {
		val drop = resolveParameterDrop(puppet, rows, draggedBody, rowKey(faceRow), fraction = 0.4f)

		assertEquals(ParameterDrop(newParentGroupId = null, before = ParameterNodeRef.Group(face), expandsGroupId = null), drop)
	}

	/** A release over a row that has left the list drops nothing. */
	@Test
	fun aReleaseOverARowThatIsGoneDropsNothing() {
		assertNull(resolveParameterDrop(puppet, rows, draggedBreath, "param:ParamGone", fraction = 0.5f))
	}
}