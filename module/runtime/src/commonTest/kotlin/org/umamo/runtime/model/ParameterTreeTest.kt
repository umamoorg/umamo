package org.umamo.runtime.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pins the one group lookup over a parameter-panel tree: the CMO3 export's lowerings and the editor's
 * tree moves all find a group by id through it.
 */
class ParameterTreeTest {
	private fun group(id: String, vararg children: ParameterNode, name: String = id): ParameterNode.Group =
		ParameterNode.Group(ParameterGroupId(id), name, initiallyOpen = true, children = children.toList())

	private fun leaf(id: String): ParameterNode = ParameterNode.Param(ParameterId(id))

	/** Face > Eyes > Lashes, then Body, with leaves between them. */
	private fun tree(): List<ParameterNode> =
		listOf(
			leaf("ParamAngleX"),
			group("Face", leaf("ParamCheek"), group("Eyes", leaf("ParamEyeLOpen"), group("Lashes"))),
			group("Body", leaf("ParamBodyAngleX")),
		)

	@Test
	fun aGroupIsFoundAtAnyDepth() {
		assertEquals("Face", findParameterGroup(tree(), ParameterGroupId("Face"))?.id?.raw, "top level")
		assertEquals("Lashes", findParameterGroup(tree(), ParameterGroupId("Lashes"))?.id?.raw, "nested twice")
		assertEquals("Body", findParameterGroup(tree(), ParameterGroupId("Body"))?.id?.raw, "after a sibling's subtree")
	}

	@Test
	fun theGroupComesBackWithItsChildren() {
		val eyes = findParameterGroup(tree(), ParameterGroupId("Eyes"))

		assertEquals(listOf(leaf("ParamEyeLOpen"), group("Lashes")), eyes?.children)
	}

	@Test
	fun anAbsentIdAndALeafIdFindNothing() {
		assertNull(findParameterGroup(tree(), ParameterGroupId("Hair")), "no group by that id")
		// A leaf is not a group, even when a group id spells the same string.
		assertNull(findParameterGroup(tree(), ParameterGroupId("ParamAngleX")), "a parameter's id")
		assertNull(findParameterGroup(emptyList(), ParameterGroupId("Face")), "an empty tree")
	}

	@Test
	fun aRepeatedIdResolvesToTheFirstInPanelOrder() {
		val repeated =
			listOf(
				group("Outer", group("Twin", name = "nested first")),
				group("Twin", name = "top level second"),
			)

		assertEquals("nested first", findParameterGroup(repeated, ParameterGroupId("Twin"))?.name)
	}
}