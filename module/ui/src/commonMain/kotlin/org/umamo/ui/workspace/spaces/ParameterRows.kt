package org.umamo.ui.workspace.spaces

import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterGroupId
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.ParameterNode
import org.umamo.runtime.model.PuppetModel

/**
 * One row of the Parameters panel: a group header, a single 1D slider, or a 2D pad for a LINKED pair.
 * [depth] is the group nesting level, used to indent the control.  Compose-free on purpose - the whole
 * row model lives in this file so commonTest can pin the folding and link-candidate rules.
 */
internal sealed interface ParameterRow {
	val depth: Int

	/** A collapsible group header (Cubism's CParameterGroup). */
	data class GroupHeader(
		val groupId: ParameterGroupId,
		val name: String,
		override val depth: Int,
		val expanded: Boolean,
	) : ParameterRow

	/**
	 * A standalone parameter rendered as a horizontal slider.  [linkCandidateId] is the parameter this
	 * one may be linked with (the immediately-next parameter in the same run - CMO3's "next parameter
	 * in document order", never across a group boundary), or null when no valid partner exists: the
	 * next parameter is absent (last in its run), not animatable, or either side already belongs to a
	 * link.
	 */
	data class Single(val parameter: Parameter, override val depth: Int, val linkCandidateId: ParameterId? = null) : ParameterRow

	/** A linked pair rendered as one 2D pad ([horizontal] = X axis, [vertical] = Y axis). */
	data class Pair2D(val horizontal: Parameter, val vertical: Parameter, override val depth: Int) : ParameterRow
}

/**
 * A stable LazyColumn key for a row, unique across all row kinds (parameter and group ids are unique, and
 * a link's consumed vertical member is never also emitted as a Single).  Keeps each row's slot identity -
 * and its remembered state - bound to its data across group expand/collapse.
 *
 * @param ParameterRow row The row to key.
 * @return String A stable, unique key.
 */
internal fun rowKey(row: ParameterRow): String =
	when (row) {
		is ParameterRow.GroupHeader -> "group:${row.groupId.raw}"
		is ParameterRow.Single -> "param:${row.parameter.id.raw}"
		is ParameterRow.Pair2D -> "pair:${row.horizontal.id.raw}"
	}

/**
 * The animatable-parameter set plus the resolved LINKED-pair lookups, computed once per puppet.
 * [linkMemberIds] holds every member of every raw link, including pairs whose partner is not
 * animatable: a degenerate link still occupies its members, and offering a second link over one
 * would corrupt the model.
 */
internal data class LinkInfo(
	val animatableIds: Set<ParameterId>,
	val verticalByHorizontal: Map<ParameterId, ParameterId>,
	val verticalMembers: Set<ParameterId>,
	val linkMemberIds: Set<ParameterId>,
)

/**
 * Indexes the puppet's animatable parameters and its LINKED pairs (keeping only pairs whose both members
 * are animatable for the pad folding, but ALL link members for the occupied set) for the row builder to
 * consult.
 *
 * @param PuppetModel puppet The loaded rig.
 * @return LinkInfo The animatable id set and the link lookups.
 */
internal fun buildLinkInfo(puppet: PuppetModel): LinkInfo {
	val animatableIds = puppet.parameters.filter { parameter -> parameter.max > parameter.min }.map { it.id }.toSet()
	val verticalByHorizontal = HashMap<ParameterId, ParameterId>()
	val verticalMembers = HashSet<ParameterId>()
	val linkMemberIds = HashSet<ParameterId>()
	for (link in puppet.parameterLinks) {
		linkMemberIds.add(link.horizontal)
		linkMemberIds.add(link.vertical)
		if (link.horizontal in animatableIds && link.vertical in animatableIds) {
			verticalByHorizontal[link.horizontal] = link.vertical
			verticalMembers.add(link.vertical)
		}
	}
	return LinkInfo(animatableIds, verticalByHorizontal, verticalMembers, linkMemberIds)
}

/**
 * Flattens the parameter-panel group tree into an ordered list of render rows.  Each group emits a
 * [ParameterRow.GroupHeader] and, when expanded, recurses its children one level deeper; each run of
 * consecutive parameter siblings is paired into [ParameterRow.Single] / [ParameterRow.Pair2D].  Falls
 * back to a single flat run when the model carries no groups.
 *
 * @param PuppetModel puppet        The loaded rig.
 * @param LinkInfo    linkInfo      Animatable + link lookups from [buildLinkInfo].
 * @param Map         parameterById id -> Parameter, for resolving tree leaves and link partners.
 * @param Map         expanded      Live group expand state (group id -> open); absent => initiallyOpen.
 * @param Set?        visibleParamIds When non-null, only parameters in this set are emitted (the "affects
 *                                  the selected object" filter), and a group with no surviving rows is
 *                                  dropped; null means no filter (every parameter is shown).
 * @return List<ParameterRow> The rows in panel order.
 */
internal fun buildParameterRows(
	puppet: PuppetModel,
	linkInfo: LinkInfo,
	parameterById: Map<ParameterId, Parameter>,
	expanded: Map<ParameterGroupId, Boolean>,
	visibleParamIds: Set<ParameterId>? = null,
): List<ParameterRow> {
	val rows = ArrayList<ParameterRow>()
	if (puppet.parameterTree.isEmpty()) {
		rows += pairRun(puppet.parameters, depth = 0, linkInfo = linkInfo, parameterById = parameterById, visibleParamIds = visibleParamIds)
		return rows
	}
	walkParameterNodes(
		puppet.parameterTree,
		depth = 0,
		linkInfo = linkInfo,
		parameterById = parameterById,
		expanded = expanded,
		visibleParamIds = visibleParamIds,
		out = rows,
	)
	return rows
}

/**
 * Recursively appends rows for [nodes] at [depth]: parameter leaves accumulate into a run (flushed
 * through [pairRun] at each group boundary and at the end), and a group emits its header then, if
 * expanded, recurses.
 *
 * @param List         nodes         The sibling nodes to walk.
 * @param Int          depth         The current group nesting level.
 * @param LinkInfo     linkInfo      Animatable + link lookups.
 * @param Map          parameterById id -> Parameter.
 * @param Map          expanded      Live group expand state.
 * @param Set?         visibleParamIds The filter set, or null for no filter (see [buildParameterRows]).
 * @param MutableList  out           The accumulating row list.
 */
private fun walkParameterNodes(
	nodes: List<ParameterNode>,
	depth: Int,
	linkInfo: LinkInfo,
	parameterById: Map<ParameterId, Parameter>,
	expanded: Map<ParameterGroupId, Boolean>,
	visibleParamIds: Set<ParameterId>?,
	out: MutableList<ParameterRow>,
) {
	val run = ArrayList<Parameter>()
	for (node in nodes) {
		when (node) {
			is ParameterNode.Param -> {
				parameterById[node.id]?.let { run.add(it) }
			}

			is ParameterNode.Group -> {
				if (run.isNotEmpty()) {
					out += pairRun(run, depth, linkInfo, parameterById, visibleParamIds)
					run.clear()
				}
				// While filtering, drop a group whose subtree holds no visible parameter (no empty headers) -
				// checked independent of expansion, since a collapsed group must still vanish when it is empty.
				if (visibleParamIds != null && !groupHasVisibleParam(node, linkInfo, visibleParamIds)) {
					continue
				}
				val isExpanded = expanded[node.id] ?: node.initiallyOpen
				out += ParameterRow.GroupHeader(node.id, node.name, depth, isExpanded)
				if (isExpanded) {
					walkParameterNodes(node.children, depth + 1, linkInfo, parameterById, expanded, visibleParamIds, out)
				}
			}
		}
	}
	if (run.isNotEmpty()) {
		out += pairRun(run, depth, linkInfo, parameterById, visibleParamIds)
	}
}

/**
 * Whether [group]'s subtree holds at least one animatable parameter in [visible] - the test that keeps a
 * group header (and lets it survive the filter) only when it has something to show, recursing into nested
 * groups.
 *
 * @param ParameterNode.Group group   The group to test.
 * @param LinkInfo            linkInfo Animatable lookups.
 * @param Set                 visible  The filter set (parameter ids to keep).
 * @return Boolean True when any animatable leaf under the group is in [visible].
 */
private fun groupHasVisibleParam(group: ParameterNode.Group, linkInfo: LinkInfo, visible: Set<ParameterId>): Boolean {
	for (child in group.children) {
		when (child) {
			is ParameterNode.Param ->
				if (child.id in visible && child.id in linkInfo.animatableIds) {
					return true
				}

			is ParameterNode.Group ->
				if (groupHasVisibleParam(child, linkInfo, visible)) {
					return true
				}
		}
	}
	return false
}

/**
 * Converts a run of sibling parameters into slider / pad rows: a non-animatable parameter is skipped, a
 * LINKED pair's vertical member is folded into its horizontal's pad (and skipped on its own), and every
 * other animatable parameter becomes a slider.  All emitted rows carry [depth] for indentation.
 *
 * Each slider row also carries its link candidate - the parameter a link click would pair it with.
 * The candidate is the run's immediately-next parameter (CMO3's "next parameter in document order",
 * scoped to the run so a link never crosses a group boundary - [walkParameterNodes] flushes runs at
 * every group) when that next parameter is animatable and NEITHER side already belongs to any link;
 * otherwise null and the row offers no link affordance.
 * // CMO3: CParameterSource.combined - first member flags the pair, partner is the next parameter in
 * // document order (see Cmo3Import's link derivation).
 *
 * @param List     params        The parameter run, in panel order.
 * @param Int      depth         The group nesting level for the emitted rows.
 * @param LinkInfo linkInfo      Animatable + link lookups.
 * @param Map      parameterById id -> Parameter, for resolving a link's vertical partner.
 * @param Set?     visibleParamIds The filter set, or null for no filter. A slider survives when its id is
 *                               in the set; a pad survives when EITHER member is (it is one control).
 * @return List<ParameterRow> The slider / pad rows for the run.
 */
private fun pairRun(
	params: List<Parameter>,
	depth: Int,
	linkInfo: LinkInfo,
	parameterById: Map<ParameterId, Parameter>,
	visibleParamIds: Set<ParameterId>?,
): List<ParameterRow> =
	buildList {
		for ((runIndex, parameter) in params.withIndex()) {
			if (parameter.id !in linkInfo.animatableIds) {
				continue
			}
			if (parameter.id in linkInfo.verticalMembers) {
				continue
			}
			val verticalId = linkInfo.verticalByHorizontal[parameter.id]
			val vertical = verticalId?.let { parameterById[it] }
			if (vertical != null) {
				// A pad is one control over two axes, so it survives the filter if either axis is visible.
				if (visibleParamIds == null || parameter.id in visibleParamIds || vertical.id in visibleParamIds) {
					add(ParameterRow.Pair2D(parameter, vertical, depth))
				}
			} else {
				if (visibleParamIds == null || parameter.id in visibleParamIds) {
					val nextParameter = params.getOrNull(runIndex + 1)
					val linkCandidateId =
						if (
							nextParameter != null &&
							nextParameter.id in linkInfo.animatableIds &&
							parameter.id !in linkInfo.linkMemberIds &&
							nextParameter.id !in linkInfo.linkMemberIds
						) {
							nextParameter.id
						} else {
							null
						}
					add(ParameterRow.Single(parameter, depth, linkCandidateId))
				}
			}
		}
	}
