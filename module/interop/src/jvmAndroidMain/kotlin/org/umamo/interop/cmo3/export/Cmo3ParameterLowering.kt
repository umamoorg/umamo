package org.umamo.interop.cmo3.export

import org.umamo.format.cmo3.model.gen.CParameterGroup
import org.umamo.interop.EntityDiff
import org.umamo.interop.ExportEntityCategory
import org.umamo.interop.ExportNoticeReason
import org.umamo.interop.ParameterField
import org.umamo.interop.ParameterGroupField
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterGroupId
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.findParameterGroup

/**
 * The field pass for parameters: each changed parameter's name, range, kind, repeat flag, and note, and each
 * changed parameter group's name, folder state, and children.
 */
internal class Cmo3ParameterLowering(
	context: Cmo3ExportContext,
	private val index: Cmo3GraphIndex,
	private val notices: Cmo3ExportNotices,
	private val panelTree: Cmo3PanelTreeLowering,
) {
	private val editor = context.editor
	private val edited = context.edited
	private val editedParameterById = context.editedParameterById

	/**
	 * Lowers each changed parameter's fields onto its graph source.
	 *
	 * @param List<EntityDiff<ParameterId, ParameterField>> diffs The parameter diffs.
	 */
	fun lowerParameters(diffs: List<EntityDiff<ParameterId, ParameterField>>) {
		for (diff in diffs) {
			when (diff) {
				// Structural synthesis already failed and reported why; see the field pass in Cmo3Export.
				is EntityDiff.Created -> {}

				// Deletions are consumed by the structural pass and stripped from the forwarded diff.
				is EntityDiff.Deleted -> {}

				is EntityDiff.Changed -> {
					val source = index.parameterByIdStr[diff.id.raw]
					val editedParameter = editedParameterById[diff.id]

					if (source == null || editedParameter == null) {
						notices.unsupported(ExportEntityCategory.Parameter, diff.id.raw, ExportNoticeReason.NoMatchingSourceToReconcile)

						continue
					}

					for (field in diff.fields) {
						when (field) {
							ParameterField.NAME -> {
								// CMO3: CParameterSource field name - the localizable display label.
								source.name = editedParameter.name
								editor.ensureChildSlot(source, "CParameterSource", "name", "description")
							}

							ParameterField.RANGE -> {
								// CMO3: CParameterSource fields minValue / maxValue / defaultValue.
								source.minValue = editedParameter.min
								source.maxValue = editedParameter.max
								source.defaultValue = editedParameter.default
								editor.ensureChildSlot(source, "CParameterSource", "minValue", "maxValue")
								editor.ensureChildSlot(source, "CParameterSource", "maxValue", "defaultValue")
								editor.ensureChildSlot(source, "CParameterSource", "defaultValue", "isRepeat")
							}

							ParameterField.KIND -> {
								notices.unsupported(ExportEntityCategory.Parameter, diff.id.raw, ExportNoticeReason.ParameterKindChangeNotLowered)
							}

							ParameterField.REPEAT -> {
								// CMO3: CParameterSource field isRepeat.
								source.isRepeat = editedParameter.repeat
								editor.ensureChildSlot(source, "CParameterSource", "isRepeat", "id")
							}

							ParameterField.NOTE -> {
								// CMO3: CParameterSource field description - the note, "" when there is none.
								source.description = editedParameter.note
								editor.ensureChildSlot(source, "CParameterSource", "description", "combined")
							}
						}
					}
				}
			}
		}
	}

	/**
	 * Lowers each changed parameter group's fields onto its graph source.
	 *
	 * @param List<EntityDiff<ParameterGroupId, ParameterGroupField>> diffs The parameter group diffs.
	 */
	fun lowerParameterGroups(diffs: List<EntityDiff<ParameterGroupId, ParameterGroupField>>) {
		for (diff in diffs) {
			when (diff) {
				// Structural synthesis already failed and reported why; see the field pass in Cmo3Export.
				is EntityDiff.Created -> {}

				// Deletions are consumed by the structural pass and stripped from the forwarded diff.
				is EntityDiff.Deleted -> {}

				is EntityDiff.Changed -> {
					val group = index.groupByIdStr[diff.id.raw]
					val editedGroup = findParameterGroup(edited.parameterTree, diff.id)

					if (group == null || editedGroup == null) {
						notices.unsupported(ExportEntityCategory.ParameterGroup, diff.id.raw, ExportNoticeReason.NoMatchingSourceToReconcile)

						continue
					}

					for (field in diff.fields) {
						when (field) {
							ParameterGroupField.NAME -> {
								// CMO3: CParameterGroup field name - the folder label (not unique; identity is the id).
								group.name = editedGroup.name
								editor.ensureChildSlot(group, "CParameterGroup", "name", "description")
							}

							ParameterGroupField.INITIALLY_OPEN -> {
								// CMO3: CParameterGroup field folderIsOpened - the saved expand/collapse state.
								group.folderIsOpened = editedGroup.initiallyOpen
								editor.ensureChildSlot(group, "CParameterGroup", "folderIsOpened", "guid")
							}

							ParameterGroupField.CHILDREN -> {
								panelTree.rebuildGroupChildren(group, editedGroup.children)
							}
						}
					}
				}
			}
		}
	}
}