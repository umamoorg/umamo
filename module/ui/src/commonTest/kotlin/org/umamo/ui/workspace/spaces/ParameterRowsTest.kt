package org.umamo.ui.workspace.spaces

import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterGroupId
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.ParameterLink
import org.umamo.runtime.model.ParameterNode
import org.umamo.runtime.model.PuppetModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the pure Parameters-panel row model: link pairs fold into 2D pads (the vertical member is
 * never emitted on its own), group boundaries flush runs, and the link-candidate rules - the
 * candidate is the run's immediately-next parameter, refused when it is absent, not animatable, or
 * when either side already belongs to any link (including a degenerate one).
 */
class ParameterRowsTest {
	private val angleX = ParameterId("ParamAngleX")
	private val angleY = ParameterId("ParamAngleY")
	private val angleZ = ParameterId("ParamAngleZ")
	private val breath = ParameterId("ParamBreath")

	/**
	 * An animatable parameter over -1..1 (or a fixed one when [animatable] is false).
	 *
	 * @param ParameterId id         The parameter id.
	 * @param Boolean     animatable Whether the range is non-degenerate.
	 * @return Parameter The fixture parameter.
	 */
	private fun parameter(id: ParameterId, animatable: Boolean = true): Parameter =
		Parameter(id, id.raw, min = if (animatable) -1f else 0f, max = if (animatable) 1f else 0f, default = 0f)

	/**
	 * A drawable-less model holding just [parameters], [links], and an optional group [tree].
	 *
	 * @param List parameters The parameters in document order.
	 * @param List links      The parameter links.
	 * @param List tree       The group tree (empty = the flat fallback).
	 * @return PuppetModel The fixture model.
	 */
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

	/**
	 * The rows the panel would render for [puppet] with every group expanded as saved.
	 *
	 * @param PuppetModel puppet The fixture model.
	 * @return List<ParameterRow> The built rows.
	 */
	private fun rowsOf(puppet: PuppetModel): List<ParameterRow> =
		buildParameterRows(puppet, buildLinkInfo(puppet), puppet.parameters.associateBy { it.id }, expanded = emptyMap())

	/**
	 * The rows the panel would render for [puppet] under the "affects selection" filter set [visible].
	 *
	 * @param PuppetModel puppet  The fixture model.
	 * @param Set         visible The visible-parameter filter set.
	 * @return List<ParameterRow> The filtered rows.
	 */
	private fun filteredRowsOf(puppet: PuppetModel, visible: Set<ParameterId>): List<ParameterRow> =
		buildParameterRows(puppet, buildLinkInfo(puppet), puppet.parameters.associateBy { it.id }, expanded = emptyMap(), visibleParamIds = visible)

	/** A linked pair folds into one Pair2D and its vertical member is never emitted as a Single. */
	@Test
	fun linkedPairFoldsIntoPad() {
		val puppet =
			model(
				parameters = listOf(parameter(angleX), parameter(angleY), parameter(breath)),
				links = listOf(ParameterLink(angleX, angleY)),
			)
		val rows = rowsOf(puppet)
		assertEquals(2, rows.size)
		val pad = rows[0] as ParameterRow.Pair2D
		assertEquals(angleX, pad.horizontal.id)
		assertEquals(angleY, pad.vertical.id)
		assertTrue(rows.none { row -> row is ParameterRow.Single && row.parameter.id == angleY })
	}

	/** In the flat fallback, adjacent unlinked animatable parameters are link candidates of each other. */
	@Test
	fun adjacentUnlinkedParametersAreCandidates() {
		val puppet = model(parameters = listOf(parameter(angleX), parameter(angleY)))
		val rows = rowsOf(puppet)
		assertEquals(angleY, (rows[0] as ParameterRow.Single).linkCandidateId)
		assertNull((rows[1] as ParameterRow.Single).linkCandidateId, "the last parameter has nothing below to link")
	}

	/** A non-animatable next parameter is not a candidate (it is not even rendered). */
	@Test
	fun nonAnimatableNextIsNoCandidate() {
		val puppet = model(parameters = listOf(parameter(angleX), parameter(angleY, animatable = false)))
		val rows = rowsOf(puppet)
		assertEquals(1, rows.size, "the fixed parameter renders no row")
		assertNull((rows[0] as ParameterRow.Single).linkCandidateId)
	}

	/** A parameter whose next neighbour already belongs to a link gets no candidate. */
	@Test
	fun nextAlreadyLinkedIsNoCandidate() {
		val puppet =
			model(
				parameters = listOf(parameter(breath), parameter(angleX), parameter(angleY)),
				links = listOf(ParameterLink(angleX, angleY)),
			)
		val rows = rowsOf(puppet)
		assertNull((rows[0] as ParameterRow.Single).linkCandidateId, "the neighbour is a link's horizontal member")
	}

	/**
	 * A degenerate link (its partner not animatable) still renders its animatable member as a Single,
	 * but that member is occupied - it must offer no second link, and its neighbour must not offer to
	 * link with it.
	 */
	@Test
	fun degenerateLinkMemberIsOccupied() {
		val puppet =
			model(
				parameters = listOf(parameter(breath), parameter(angleX), parameter(angleY, animatable = false)),
				links = listOf(ParameterLink(angleX, angleY)),
			)
		val rows = rowsOf(puppet)
		assertEquals(2, rows.size)
		assertNull((rows[0] as ParameterRow.Single).linkCandidateId, "the neighbour is occupied by the degenerate link")
		assertNull((rows[1] as ParameterRow.Single).linkCandidateId, "the occupied member offers no second link")
	}

	/** A group boundary flushes the run: candidacy never crosses groups, and the header row lands between. */
	@Test
	fun groupBoundaryBreaksCandidacy() {
		val groupId = ParameterGroupId("group1")
		val puppet =
			model(
				parameters = listOf(parameter(angleX), parameter(angleY)),
				tree =
					listOf(
						ParameterNode.Param(angleX),
						ParameterNode.Group(groupId, "Group", initiallyOpen = true, children = listOf(ParameterNode.Param(angleY))),
					),
			)
		val rows = rowsOf(puppet)
		assertEquals(3, rows.size)
		assertNull((rows[0] as ParameterRow.Single).linkCandidateId, "the next parameter sits across a group boundary")
		assertEquals("group:group1", rowKey(rows[1]))
		assertEquals(1, (rows[2] as ParameterRow.Single).depth)
	}

	/** Row keys are unique across a mixed list of groups, sliders, and pads. */
	@Test
	fun rowKeysAreUniqueAcrossKinds() {
		val groupId = ParameterGroupId("group1")
		val puppet =
			model(
				parameters = listOf(parameter(angleX), parameter(angleY), parameter(angleZ), parameter(breath)),
				links = listOf(ParameterLink(angleX, angleY)),
				tree =
					listOf(
						ParameterNode.Param(angleX),
						ParameterNode.Param(angleY),
						ParameterNode.Param(angleZ),
						ParameterNode.Group(groupId, "Group", initiallyOpen = true, children = listOf(ParameterNode.Param(breath))),
					),
			)
		val rows = rowsOf(puppet)
		val keys = rows.map { row -> rowKey(row) }
		assertEquals(keys.size, keys.toSet().size, "every row key must be unique")
	}

	/** The filter keeps only the sliders whose parameter is in the visible set. */
	@Test
	fun filterKeepsOnlyVisibleSingles() {
		val puppet = model(parameters = listOf(parameter(angleX), parameter(angleY), parameter(angleZ)))
		val rows = filteredRowsOf(puppet, setOf(angleY))
		assertEquals(1, rows.size)
		assertEquals(angleY, (rows[0] as ParameterRow.Single).parameter.id)
	}

	/** A pad survives the filter when EITHER of its axes is visible (it is one control). */
	@Test
	fun filterKeepsPadWhenEitherMemberVisible() {
		val puppet =
			model(
				parameters = listOf(parameter(angleX), parameter(angleY), parameter(breath)),
				links = listOf(ParameterLink(angleX, angleY)),
			)
		// Only the vertical member (angleY) is visible; the pad still shows, and the unrelated breath drops.
		val rows = filteredRowsOf(puppet, setOf(angleY))
		assertEquals(1, rows.size)
		assertEquals(angleX, (rows[0] as ParameterRow.Pair2D).horizontal.id)
	}

	/** A group whose every child is filtered out is dropped - no empty header. */
	@Test
	fun filterDropsEmptyGroup() {
		val groupId = ParameterGroupId("group1")
		val puppet =
			model(
				parameters = listOf(parameter(angleX), parameter(angleY)),
				tree =
					listOf(
						ParameterNode.Param(angleX),
						ParameterNode.Group(groupId, "Group", initiallyOpen = true, children = listOf(ParameterNode.Param(angleY))),
					),
			)
		// Only angleX is visible; the group holds only angleY, so it vanishes rather than showing empty.
		val rows = filteredRowsOf(puppet, setOf(angleX))
		assertEquals(1, rows.size)
		assertEquals(angleX, (rows[0] as ParameterRow.Single).parameter.id)
		assertTrue(rows.none { row -> row is ParameterRow.GroupHeader })
	}
}
