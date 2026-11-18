package org.umamo.interop.cmo3.import

import org.umamo.format.cmo3.model.gen.CAffecterSourceSet
import org.umamo.format.cmo3.model.gen.CGlueForm
import org.umamo.format.cmo3.model.gen.CGlueSource
import org.umamo.interop.cmo3.editableMeshOf
import org.umamo.interop.cmo3.elementsOf
import org.umamo.interop.cmo3.idStrOf
import org.umamo.interop.cmo3.uuidOf
import org.umamo.runtime.keyform.asChannelTrack
import org.umamo.runtime.keyform.channelGridsOf
import org.umamo.runtime.model.ChannelValue
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.FormChannel
import org.umamo.runtime.model.Glue
import org.umamo.runtime.model.GlueForm
import org.umamo.runtime.model.GluePair
import org.umamo.runtime.model.ParameterId

/*
 * The glue producer of the CMO3 import: seam welds between two art meshes.
 */

/**
 * Builds the runtime glues, in the affecter set's order.
 *
 * @param Cmo3ImportContext context The import's shared reads.
 * @return List<Glue> The glues; one whose target meshes or bind data do not resolve is left out.
 */
internal fun importGlues(context: Cmo3ImportContext): List<Glue> {
	// Glue affecters: seam-weld vertex pairs after deformation.  CMO3 references vertices by stable UID;
	// build uid→index per drawable from its editable mesh (positions[i] == editableMesh.point[i]).
	val uidToIndexByDrawableId =
		buildMap {
			for (source in context.drawableSources) {
				val id = idStrOf(source.id)?.let(::DrawableId) ?: continue
				val pointUid = editableMeshOf(source)?.pointUid as? IntArray ?: continue
				put(id, buildMap { pointUid.forEachIndexed { vertexIndex, uid -> put(uid, vertexIndex) } })
			}
		}

	return elementsOf((context.modelSource.affecterSourceSet as? CAffecterSourceSet)?._sources)
		.filterIsInstance<CGlueSource>()
		.mapNotNull { glue -> glueOf(glue, context.drawableIdByUuid, uidToIndexByDrawableId, context.paramIdByUuid) }
}

/**
 * Maps a CMO3 `CGlueSource` to a runtime [Glue], resolving its target meshes and converting each
 * UID-keyed vertex pair to mesh-local indices.  CMO3: `weights`/`bindVertexUids` are `[A,B]`-interleaved
 * per pair; `keyforms` (CGlueForm.intensity) drive the weld strength.
 *
 * @param CGlueSource glue                  The glue source.
 * @param Map         drawableIdByUuid      uuid → DrawableId.
 * @param Map         uidToIndexByDrawableId DrawableId → (vertex uid → index).
 * @param Map         paramIdByUuid         uuid → ParameterId (for the intensity grid).
 * @return Glue? The runtime glue, or null when a target/extension is missing.
 */
private fun glueOf(
	glue: CGlueSource,
	drawableIdByUuid: Map<String, DrawableId>,
	uidToIndexByDrawableId: Map<DrawableId, Map<Int, Int>>,
	paramIdByUuid: Map<String, ParameterId>,
): Glue? {
	val meshA = drawableIdByUuid[uuidOf(glue.targetArtMeshA_guid)] ?: return null
	val meshB = drawableIdByUuid[uuidOf(glue.targetArtMeshB_guid)] ?: return null
	val weights = glue.weights as? FloatArray ?: return null
	val uids = glue.bindVertexUids as? LongArray ?: return null
	val uidToIndexA = uidToIndexByDrawableId[meshA] ?: return null
	val uidToIndexB = uidToIndexByDrawableId[meshB] ?: return null
	val pairCount = minOf(weights.size, uids.size) / 2
	val pairs = ArrayList<GluePair>(pairCount)

	for (pairIndex in 0 until pairCount) {
		val indexA = uidToIndexA[uids[pairIndex * 2].toInt()] ?: continue
		val indexB = uidToIndexB[uids[pairIndex * 2 + 1].toInt()] ?: continue
		pairs.add(GluePair(indexA, indexB, weights[pairIndex * 2], weights[pairIndex * 2 + 1]))
	}

	val intensityTrack =
		buildGrid(glue.keyformGridSource, glue.keyforms, paramIdByUuid, ::glueForm)
			?.asChannelTrack { form -> ChannelValue.Scalar(form.intensity) }

	// A glue with no keyed intensity welds fully, which is the runtime's long-standing fallback.
	return Glue(meshA, meshB, pairs, channelGridsOf(FormChannel.GLUE_INTENSITY to intensityTrack), intensity = 1f)
}

/**
 * Glue cell payload: the weld intensity captured at one grid cell.
 *
 * @param Any form The form object (expected CGlueForm).
 * @return GlueForm? The intensity form, or null if absent.
 */
private fun glueForm(form: Any): GlueForm? = (form as? CGlueForm)?.let { GlueForm(it.intensity) }