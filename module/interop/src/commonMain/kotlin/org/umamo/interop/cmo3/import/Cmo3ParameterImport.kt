package org.umamo.interop.cmo3.import

import org.umamo.format.cmo3.model.gen.CParameterGroup
import org.umamo.format.cmo3.model.gen.CParameterGroupSet
import org.umamo.format.cmo3.model.gen.Type
import org.umamo.interop.cmo3.elementsOf
import org.umamo.interop.cmo3.idStrOf
import org.umamo.interop.cmo3.uuidOf
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterGroupId
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.ParameterKind
import org.umamo.runtime.model.ParameterLink
import org.umamo.runtime.model.ParameterNode

/*
 * The parameter producers of the CMO3 import: the parameters themselves, their combined pairs, and the
 * parameter panel's group tree.
 */

/**
 * Builds the runtime parameters, in document order.
 *
 * @param Cmo3ImportContext context The import's shared reads.
 * @return List<Parameter> The parameters; a source with no id keeps an empty one.
 */
internal fun importParameters(context: Cmo3ImportContext): List<Parameter> =
	context.parameterSources.map { source ->
		Parameter(
			id = ParameterId(idStrOf(source.id).orEmpty()),
			// CMO3: CParameterSource.name is the display label; an absent or empty one falls back to the id (ParamAngleX).
			name = displayNameOf(source.name, source.id),
			min = source.minValue,
			max = source.maxValue,
			default = source.defaultValue,
			// CMO3: CParameterSource field paramType - MORPH_TARGET marks a blend-shape axis.
			kind = if ((source.paramType as? Type) == Type.MORPH_TARGET) ParameterKind.BLEND_SHAPE else ParameterKind.NORMAL,
		)
	}

/**
 * Pairs each combined parameter with the one after it.
 *
 * @param Cmo3ImportContext context The import's shared reads.
 * @return List<ParameterLink> The linked pairs, horizontal member first.
 */
internal fun importParameterLinks(context: Cmo3ImportContext): List<ParameterLink> {
	val parameterSources = context.parameterSources

	// CMO3: CParameterSource.combined is true ONLY on the first (horizontal/X) member of a LINKED
	// ("combined") pair; the next parameter in document order is its vertical (Y) member.  Walk the
	// sources in order and, when a combined head has a following source, emit the (X, Y) link and skip
	// the consumed partner so an adjacent second pair is not mis-read as starting on that partner.  A
	// trailing combined head with no following member is ignored - there is no Y axis to pair.
	return buildList {
		var sourceIndex = 0

		while (sourceIndex < parameterSources.size) {
			val source = parameterSources[sourceIndex]
			val nextSource = parameterSources.getOrNull(sourceIndex + 1)

			if (source.combined && nextSource != null) {
				val horizontalId = idStrOf(source.id)
				val verticalId = idStrOf(nextSource.id)

				if (horizontalId != null && verticalId != null) {
					add(ParameterLink(ParameterId(horizontalId), ParameterId(verticalId)))
				}

				sourceIndex += 2
			} else {
				sourceIndex += 1
			}
		}
	}
}

/**
 * Builds the parameter panel's group tree.
 *
 * @param Cmo3ImportContext context The import's shared reads.
 * @return List<ParameterNode> The root group's children in panel order, empty for a group-less model.
 */
internal fun importParameterTree(context: Cmo3ImportContext): List<ParameterNode> {
	// CMO3: CParameterGroup is the parameter-panel group (the editor's collapsible groups).
	// CModelSource.rootParameterGroup is the hidden top group; its _childGuids interleaves parameter and
	// subgroup GUIDs in panel order, and each subgroup nests the same way - mirroring the parts tree's
	// _childGuids walk.  Each CParameterSource also carries a redundant parentGroupGuid, but the
	// rootParameterGroup walk is the authoritative panel order.  See docs/format/CMO3.md §3.
	val parameterGroupByUuid =
		buildMap {
			val groups = (context.modelSource.parameterGroupSet as? CParameterGroupSet)?._groups

			for (group in elementsOf(groups).filterIsInstance<CParameterGroup>()) {
				val uuid = uuidOf(group.guid) ?: continue
				put(uuid, group)
			}
		}

	return buildParameterTree(context.modelSource.rootParameterGroup as? CParameterGroup, parameterGroupByUuid, context.paramIdByUuid)
}

/**
 * Builds the parameter-panel group tree from the CMO3 root CParameterGroup, returning the root's
 * children in panel order, or an empty list for a group-less model so callers fall back to the flat
 * parameter list.
 *
 * @param CParameterGroup? rootGroup     The hidden top group (CModelSource.rootParameterGroup), or null.
 * @param Map              groupByUuid   uuid → CParameterGroup, for resolving nested-group references.
 * @param Map              paramIdByUuid uuid → ParameterId, for resolving leaf parameters.
 * @return List<ParameterNode> The root group's children in panel order (empty when no root group).
 */
private fun buildParameterTree(
	rootGroup: CParameterGroup?,
	groupByUuid: Map<String, CParameterGroup>,
	paramIdByUuid: Map<String, ParameterId>,
): List<ParameterNode> {
	if (rootGroup == null) {
		return emptyList()
	}

	val visited = HashSet<String>()

	/**
	 * Builds a parameter group's children, recursing into sub-groups and skipping a group already visited.
	 *
	 * @param CParameterGroup group The group's graph source.
	 * @return List<ParameterNode> The child nodes, in stored order.
	 */
	fun childrenOf(group: CParameterGroup): List<ParameterNode> =
		buildList {
			for (childGuid in elementsOf(group._childGuids)) {
				val uuid = uuidOf(childGuid) ?: continue
				val paramId = paramIdByUuid[uuid]

				if (paramId != null) {
					add(ParameterNode.Param(paramId))

					continue
				}

				val childGroup = groupByUuid[uuid] ?: continue

				// A malformed file can reference a group cyclically.
				if (!visited.add(uuid)) {
					continue
				}

				add(
					ParameterNode.Group(
						id = ParameterGroupId(idStrOf(childGroup.id).orEmpty()),
						// CMO3: CParameterGroup.name is the group label; an absent or empty one falls back to the id.  Names are
						// not unique (group names can repeat), so identity stays on the id.
						name = displayNameOf(childGroup.name, childGroup.id),
						// CMO3: CParameterGroup.folderIsOpened - the editor's saved expand/collapse state.
						initiallyOpen = childGroup.folderIsOpened,
						children = childrenOf(childGroup),
					),
				)
			}
		}

	return childrenOf(rootGroup)
}