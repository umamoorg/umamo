package org.umamo.interop.cmo3.export

import org.umamo.format.cmo3.model.gen.CParameterGroup
import org.umamo.format.cmo3.model.gen.CPartSource
import org.umamo.format.cmo3.type.CArrayList
import org.umamo.interop.ExportEntityCategory
import org.umamo.interop.ExportNoticeReason
import org.umamo.interop.cmo3.elementsOf
import org.umamo.interop.cmo3.uuidOf
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterNode
import org.umamo.runtime.model.Part

/**
 * The two panel trees the field pass rebuilds: a part's children in the parts panel, and a parameter
 * group's children in the parameters panel, each from the edited model's ordered children.  The part,
 * parameter, and document lowerings all write those lists through this one class.
 */
internal class Cmo3PanelTreeLowering(
	context: Cmo3ExportContext,
	private val index: Cmo3GraphIndex,
	private val notices: Cmo3ExportNotices,
) {
	private val editor = context.editor

	/**
	 * Rebuilds a part's _childGuids from the edited org children, preserving non-org entries
	 * (deformers, unknown guids) by anchor: each keeps its position after the nearest preceding org
	 * child that survives, else moves to the end.  The org tree's single source of truth is the
	 * model's ordered children; the graph list is derived from it plus the preserved entries.
	 *
	 * @param CPartSource source         The part (or root part) whose list is rebuilt.
	 * @param List        editedChildren The edited model's ordered org children.
	 */
	fun rebuildOrgChildren(source: CPartSource, editedChildren: List<OrgChild>) {
		val currentEntries = elementsOf(source._childGuids)
		val nonOrgAfterAnchor = LinkedHashMap<String?, MutableList<Any?>>()
		var lastOrgUuid: String? = null

		for (entry in currentEntries) {
			val uuid = uuidOf(entry)
			val isOrgChild = uuid != null && (uuid in index.drawableUuids || uuid in index.userPartUuids)

			if (isOrgChild) {
				lastOrgUuid = uuid
			} else {
				nonOrgAfterAnchor.getOrPut(lastOrgUuid) { ArrayList() }.add(entry)
			}
		}

		val newEntries = ArrayList<Any?>()
		nonOrgAfterAnchor[null]?.let(newEntries::addAll)

		for (child in editedChildren) {
			val childGuid =
				when (child) {
					is OrgChild.Drawable -> index.drawableByIdStr[child.id.raw]?.guid
					is OrgChild.Part -> index.partByIdStr[child.id.raw]?.guid
				}

			if (childGuid == null) {
				val childId = if (child is OrgChild.Drawable) child.id.raw else (child as OrgChild.Part).id.raw
				notices.unsupported(ExportEntityCategory.Part, childId, ExportNoticeReason.CreatedEntityHasNoSourceYet)

				continue
			}

			newEntries.add(childGuid)
			nonOrgAfterAnchor.remove(uuidOf(childGuid))?.let(newEntries::addAll)
		}

		// Non-org entries whose anchor did not survive keep existing at the end of the list.
		for ((anchorUuid, trailing) in nonOrgAfterAnchor) {
			if (anchorUuid != null) {
				newEntries.addAll(trailing)
			}
		}

		editor.writeGraphList(
			owner = source,
			tag = "CPartSource",
			property = "_childGuids",
			beforeProperty = "useOffscreen",
			current = source._childGuids,
			newElements = newEntries,
			kind = GraphListKind.CArrayList,
			assign = { list -> source._childGuids = list },
		)
	}

	/**
	 * Rebuilds a parameter group's _childGuids from the edited tree nodes, and refreshes every
	 * child's redundant parentGroupGuid back-pointer to this group (the walk is authoritative, but
	 * the editor maintains the back-pointers, so the export does too).
	 *
	 * @param CParameterGroup group        The group (or the hidden root group) to rebuild.
	 * @param List            editedNodes  The edited model's ordered child nodes.
	 */
	fun rebuildGroupChildren(group: CParameterGroup, editedNodes: List<ParameterNode>) {
		val newEntries = ArrayList<Any?>()

		for (node in editedNodes) {
			when (node) {
				is ParameterNode.Param -> {
					val parameterSource = index.parameterByIdStr[node.id.raw]

					if (parameterSource == null) {
						notices.unsupported(ExportEntityCategory.Parameter, node.id.raw, ExportNoticeReason.CreatedEntityHasNoSourceYet)

						continue
					}

					newEntries.add(parameterSource.guid)
					// CMO3: CParameterSource field parentGroupGuid - the redundant membership back-pointer.
					parameterSource.parentGroupGuid = group.guid
					editor.ensureChildSlot(parameterSource, "CParameterSource", "parentGroupGuid")
				}

				is ParameterNode.Group -> {
					val childGroup = index.groupByIdStr[node.id.raw]

					if (childGroup == null) {
						notices.unsupported(ExportEntityCategory.ParameterGroup, node.id.raw, ExportNoticeReason.CreatedEntityHasNoSourceYet)

						continue
					}

					newEntries.add(childGroup.guid)
					// CMO3: CParameterGroup field parentGroupGuid - the enclosing group back-pointer.
					childGroup.parentGroupGuid = group.guid
					editor.ensureChildSlot(childGroup, "CParameterGroup", "parentGroupGuid", "_childGuids")
				}
			}
		}

		editor.writeGraphList(
			owner = group,
			tag = "CParameterGroup",
			property = "_childGuids",
			beforeProperty = "id",
			current = group._childGuids,
			newElements = newEntries,
			kind = GraphListKind.CArrayList,
			assign = { list -> group._childGuids = list },
		)
	}
}