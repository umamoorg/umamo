package org.umamo.ui.model.mesh

import org.umamo.edit.AdjustableOperation
import org.umamo.edit.OperatorParameter
import org.umamo.edit.ParameterUnit
import org.umamo.edit.intValue
import org.umamo.edit.mesh.MeshGenerationKind
import org.umamo.interop.art.SourceArtImport
import org.umamo.ui.model.artwork.IMPORT_MAX_MARGIN

/** The parameter keys the Generate Quad Mesh rows carry beyond the shared Alpha Threshold (the strip maps them to strings). */
internal object QuadMeshParameterKeys {
	const val MARGIN = "meshQuad.margin"
}

/**
 * What one Generate Quad Mesh run uses: how far the quad reaches past the art and the alpha that counts
 * as art.  The threshold row is Generate Mesh from Art's own key, with the same floor - the atlas's
 * threshold, since art the atlas would not draw is never quadded as art - so the default of 1 shows as
 * that floor.
 *
 * @property Int margin         How far the quad reaches past the art and any glued vertex, in art pixels.
 * @property Int alphaThreshold The least alpha byte counted as art.
 */
internal data class QuadMeshOptions(val margin: Int = SourceArtImport.DEFAULT_BIRTH_MESH_MARGIN, val alphaThreshold: Int = 1) : MeshRecipe {
	override val kind: MeshGenerationKind
		get() = MeshGenerationKind.Quad

	/**
	 * These options clamped into what the rows offer.
	 *
	 * @param Int minimumAlphaThreshold The atlas's alpha threshold.
	 * @return QuadMeshOptions The clamped options.
	 */
	override fun clamped(minimumAlphaThreshold: Int): QuadMeshOptions =
		QuadMeshOptions(margin.coerceIn(0, IMPORT_MAX_MARGIN), alphaThreshold.coerceIn(alphaThresholdFloor(minimumAlphaThreshold), MAXIMUM_ALPHA_THRESHOLD))

	/**
	 * The Margin row, then the Alpha Threshold row.
	 *
	 * @param Int minimumAlphaThreshold The atlas's alpha threshold, the threshold row's floor.
	 * @return List<OperatorParameter> The rows.
	 */
	override fun rows(minimumAlphaThreshold: Int): List<OperatorParameter> {
		val shown = clamped(minimumAlphaThreshold)

		return listOf(
			OperatorParameter.IntParameter(QuadMeshParameterKeys.MARGIN, QuadMeshParameterKeys.MARGIN, shown.margin, 0, IMPORT_MAX_MARGIN, unit = ParameterUnit.Pixels),
			OperatorParameter.IntParameter(MeshGenerationParameterKeys.ALPHA_THRESHOLD, MeshGenerationParameterKeys.ALPHA_THRESHOLD, shown.alphaThreshold, alphaThresholdFloor(minimumAlphaThreshold), MAXIMUM_ALPHA_THRESHOLD),
		)
	}

	/**
	 * The options the rows describe, over these for a row that is missing, clamped.
	 *
	 * @param List<OperatorParameter> parameters            The strip's rows.
	 * @param Int                     minimumAlphaThreshold The atlas's alpha threshold.
	 * @return QuadMeshOptions The options.
	 */
	override fun fromRows(parameters: List<OperatorParameter>, minimumAlphaThreshold: Int): QuadMeshOptions =
		QuadMeshOptions(parameters.intValue(QuadMeshParameterKeys.MARGIN, margin), parameters.intValue(MeshGenerationParameterKeys.ALPHA_THRESHOLD, alphaThreshold)).clamped(minimumAlphaThreshold)

	/**
	 * Nothing: no row seeds another.
	 *
	 * @param AdjustableOperation record                The record with the adjusted rows.
	 * @param Int                 minimumAlphaThreshold The atlas's alpha threshold.
	 * @return List<OperatorParameter>? Always null.
	 */
	override fun reseededRows(record: AdjustableOperation, minimumAlphaThreshold: Int): List<OperatorParameter>? = null
}