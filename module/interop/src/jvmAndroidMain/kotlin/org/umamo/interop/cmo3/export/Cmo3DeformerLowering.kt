package org.umamo.interop.cmo3.export

import org.umamo.format.cmo3.model.gen.ACDeformerSource
import org.umamo.format.cmo3.model.gen.ACParameterControllableSource
import org.umamo.format.cmo3.model.gen.CPartSource
import org.umamo.format.cmo3.model.gen.CRotationDeformerSource
import org.umamo.format.cmo3.model.gen.CWarpDeformerSource
import org.umamo.format.cmo3.type.CArrayList
import org.umamo.interop.DeformerField
import org.umamo.interop.EntityDiff
import org.umamo.interop.ExportEntityCategory
import org.umamo.interop.ExportNoticeReason
import org.umamo.interop.cmo3.mutableGraphListOf
import org.umamo.interop.cmo3.uuidOf
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.PartId

/**
 * The field pass for deformers: each changed deformer's name, lock, parent deformer, part, base
 * angle, and quad transform, with its geometry, channels, and blend shapes handed to the keyform
 * lowering.
 */
internal class Cmo3DeformerLowering(
	context: Cmo3ExportContext,
	private val index: Cmo3GraphIndex,
	private val notices: Cmo3ExportNotices,
	private val keyforms: Cmo3KeyformLowering,
) {
	private val editor = context.editor
	private val editedDeformerById = context.editedDeformerById
	private val baselineDeformerById = context.baselineDeformerById

	/**
	 * Lowers each changed deformer's fields onto its graph source.
	 *
	 * @param List<EntityDiff<DeformerId, DeformerField>> diffs The deformer diffs.
	 */
	fun lowerDeformers(diffs: List<EntityDiff<DeformerId, DeformerField>>) {
		for (diff in diffs) {
			when (diff) {
				// Structural synthesis already failed and reported why; see the field pass in Cmo3Export.
				is EntityDiff.Created -> {}

				// Deletions are consumed by the structural pass and stripped from the forwarded diff.
				is EntityDiff.Deleted -> {}

				is EntityDiff.Changed -> {
					val source = index.deformerByIdStr[diff.id.raw]
					val editedDeformer = editedDeformerById[diff.id]

					if (source == null || editedDeformer == null) {
						notices.unsupported(ExportEntityCategory.Deformer, diff.id.raw, ExportNoticeReason.NoMatchingSourceToReconcile)

						continue
					}

					for (field in diff.fields) {
						when (field) {
							DeformerField.KIND -> {
								notices.unsupported(ExportEntityCategory.Deformer, diff.id.raw, ExportNoticeReason.DeformerKindChangeNotLowered)
							}

							DeformerField.NAME -> {
								lowerLocalName(editor, source, editedDeformer.name)
							}

							DeformerField.VISIBLE -> {
								lowerIsVisible(editor, source, editedDeformer.isVisible)
							}

							DeformerField.SELECTABLE -> {
								lowerIsLocked(editor, source, editedDeformer.isSelectable)
							}

							DeformerField.PARENT -> {
								// CMO3: ACDeformerSource field targetDeformerGuid - the transform-tree parent,
								// or the editor's fixed ROOT sentinel at the tree root (official deformers
								// never write null here).  Reusing the parent's own guid instance keeps the
								// shared xs.ref identity the editor writes.
								source.targetDeformerGuid =
									editedDeformer.parent?.let { index.deformerByIdStr[it.raw]?.guid }
										?: rootDeformerSentinel()
								editor.ensureChildSlot(source, "ACDeformerSource", "targetDeformerGuid")
								// The forms are read in the new parent's space from now on, reused ones included.
								keyforms.formSpaces.retagFormSpaces(source, editedDeformer.parent != null)
							}

							DeformerField.PART -> {
								lowerDeformerPart(source, diff.id, editedDeformer.partId)
							}

							DeformerField.QUAD_TRANSFORM -> {
								val warpSource = source as? CWarpDeformerSource

								if (warpSource == null) {
									notices.unsupported(ExportEntityCategory.Deformer, diff.id.raw, ExportNoticeReason.DeformerEditNeedsWarpSource)
								} else {
									// CMO3: CWarpDeformerSource field isQuadTransform - the FFD interpolation mode.
									warpSource.isQuadTransform = (editedDeformer as Deformer.Warp).isQuadTransform
									editor.ensureChildSlot(warpSource, "CWarpDeformerSource", "isQuadTransform", "keyforms")
								}
							}

							DeformerField.BASE_ANGLE -> {
								val rotationSource = source as? CRotationDeformerSource

								if (rotationSource == null) {
									notices.unsupported(ExportEntityCategory.Deformer, diff.id.raw, ExportNoticeReason.DeformerEditNeedsRotationSource)
								} else {
									// CMO3: CRotationDeformerSource field baseAngle - the static editor reference angle.
									rotationSource.baseAngle = (editedDeformer as Deformer.Rotation).baseAngle
									editor.ensureChildSlot(rotationSource, "CRotationDeformerSource", "baseAngle")
								}
							}

							DeformerField.LATTICE -> {
								val warpSource = source as? CWarpDeformerSource

								if (warpSource != null && editedDeformer is Deformer.Warp) {
									// CMO3: CWarpDeformerSource fields col / row - the FFD lattice
									// dimensions; the resized control-point forms ride the grid rebuild.
									warpSource.col = editedDeformer.columns
									warpSource.row = editedDeformer.rows
									editor.ensureChildSlot(warpSource, "CWarpDeformerSource", "col", "row")
									editor.ensureChildSlot(warpSource, "CWarpDeformerSource", "row", "isQuadTransform")
								} else {
									notices.unsupported(ExportEntityCategory.Deformer, diff.id.raw, ExportNoticeReason.DeformerEditNeedsWarpSource)
								}
							}

							// Handled once per deformer by the keyform rebuild below.
							DeformerField.GEOMETRY,
							DeformerField.CHANNELS,
							DeformerField.STATICS,
							DeformerField.BLEND_SHAPES,
							-> {}
						}
					}

					val rebuildGrid =
						DeformerField.GEOMETRY in diff.fields ||
							DeformerField.CHANNELS in diff.fields ||
							DeformerField.STATICS in diff.fields ||
							DeformerField.LATTICE in diff.fields
					val rebuildMorphs = DeformerField.BLEND_SHAPES in diff.fields

					if (rebuildGrid || rebuildMorphs) {
						when (editedDeformer) {
							is Deformer.Warp -> {
								val warpSource = source as? CWarpDeformerSource

								if (warpSource == null) {
									notices.unsupported(ExportEntityCategory.Deformer, diff.id.raw, ExportNoticeReason.DeformerEditNeedsWarpSource)
								} else {
									keyforms.lowerWarp(warpSource, editedDeformer, rebuildGrid, rebuildMorphs)
								}
							}

							is Deformer.Rotation -> {
								val rotationSource = source as? CRotationDeformerSource

								if (rotationSource == null) {
									notices.unsupported(ExportEntityCategory.Deformer, diff.id.raw, ExportNoticeReason.DeformerEditNeedsRotationSource)
								} else {
									keyforms.lowerRotation(rotationSource, editedDeformer, rebuildGrid, rebuildMorphs)
								}
							}
						}
					}
				}
			}
		}
	}

	/**
	 * Moves a deformer to another part: rewrites its parentGuid and moves its guid between the
	 * parts' _childGuids lists (deformers live in the parts-panel child order even though the
	 * runtime routes them out of the org tree).  Runs before any CHILDREN rebuild, so the rebuild's
	 * anchor pass sees the deformer already in its new list.
	 *
	 * @param ACDeformerSource source     The deformer's graph source.
	 * @param DeformerId       deformerId The deformer's id.
	 * @param PartId?          newPartId  The new owning part, or null for the root.
	 */
	private fun lowerDeformerPart(source: ACDeformerSource, deformerId: DeformerId, newPartId: PartId?) {
		val deformerUuid = uuidOf(source.guid)
		val oldPartId = baselineDeformerById[deformerId]?.partId
		val oldPartSource = oldPartId?.let { index.partByIdStr[it.raw] } ?: index.rootPartSource
		val newPartSource = newPartId?.let { index.partByIdStr[it.raw] } ?: index.rootPartSource

		if (newPartSource == null) {
			notices.unsupported(ExportEntityCategory.Deformer, deformerId.raw, ExportNoticeReason.DeformerHasNoPartToMoveTo)

			return
		}

		// CMO3: ACParameterControllableSource field parentGuid - the org-tree owner; the synthetic
		// root part is the "no part" owner.
		source.parentGuid = newPartSource.guid
		editor.ensureChildSlot(source, "ACParameterControllableSource", "parentGuid", "keyformGridSource")

		if (deformerUuid == null) {
			return
		}

		mutableGraphListOf(oldPartSource?._childGuids)?.removeAll { entry -> uuidOf(entry) == deformerUuid }
		val newChildList = mutableGraphListOf(newPartSource._childGuids)

		if (newChildList != null) {
			if (newChildList.none { entry -> uuidOf(entry) == deformerUuid }) {
				newChildList.add(source.guid)
			}
		} else {
			editor.writeGraphList(
				owner = newPartSource,
				tag = "CPartSource",
				property = "_childGuids",
				beforeProperty = "useOffscreen",
				current = newPartSource._childGuids,
				newElements = listOf(source.guid),
				kind = GraphListKind.CArrayList,
				assign = { list -> newPartSource._childGuids = list },
			)
		}
	}
}