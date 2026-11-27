package org.umamo.interop.cmo3.export

import org.umamo.interop.ExportEntityCategory
import org.umamo.interop.ExportNoticeReason
import org.umamo.interop.GlueDiff
import org.umamo.interop.GlueField
import org.umamo.interop.cmo3.uuidOf

/**
 * The field pass for glues: each changed glue's vertex pairs, re-bound by the topology
 * lowering, and its keyed channels, handed to the keyform lowering.
 */
internal class Cmo3GlueLowering(
	context: Cmo3ExportContext,
	private val index: Cmo3GraphIndex,
	private val notices: Cmo3ExportNotices,
	private val keyforms: Cmo3KeyformLowering,
	private val topology: Cmo3MeshTopologyLowering,
) {
	private val edited = context.edited

	/**
	 * Lowers each changed glue onto its graph source, resolved by mesh pair and ordinal.
	 *
	 * @param List<GlueDiff> diffs The glue diffs.
	 */
	fun lowerGlues(diffs: List<GlueDiff>) {
		if (diffs.isEmpty()) {
			return
		}

		// A glue has no id: resolve the graph source and the edited glue by ordered mesh pair plus
		// ordinal, the same keying the diff used.
		val sourcesByPair =
			index.glueSources.groupBy { glueSource ->
				val meshA = index.drawableIdStrByUuid[uuidOf(glueSource.targetArtMeshA_guid)]
				val meshB = index.drawableIdStrByUuid[uuidOf(glueSource.targetArtMeshB_guid)]
				meshA to meshB
			}
		val editedByPair = edited.glues.groupBy { glue -> glue.meshA.raw to glue.meshB.raw }

		for (diff in diffs) {
			val subject = "${diff.meshA.raw}+${diff.meshB.raw}"

			when (diff) {
				// Structural synthesis already failed and reported why; see the field pass in Cmo3Export.
				is GlueDiff.Created -> {}

				// Deletions are consumed by the structural pass and stripped from the forwarded diff.
				is GlueDiff.Deleted -> {}

				is GlueDiff.Changed -> {
					val pairKey = diff.meshA.raw to diff.meshB.raw
					val glueSource = sourcesByPair[pairKey as Pair<String?, String?>]?.getOrNull(diff.ordinal)
					val editedGlue = editedByPair[pairKey]?.getOrNull(diff.ordinal)

					if (glueSource == null || editedGlue == null) {
						notices.unsupported(ExportEntityCategory.Glue, subject, ExportNoticeReason.NoMatchingSourceToReconcile)

						continue
					}

					if (GlueField.PAIRS in diff.fields) {
						topology.lowerGluePairsFor(editedGlue)
					}

					if (GlueField.CHANNELS in diff.fields || GlueField.INTENSITY in diff.fields) {
						keyforms.lowerGlue(glueSource, editedGlue)
					}
				}
			}
		}
	}
}