package org.umamo.ui.model.mesh

import org.umamo.edit.AdjustableOperation
import org.umamo.edit.OperatorParameter
import org.umamo.edit.mesh.MeshGenerationKind

/**
 * What one mesh generation makes and the operation strip rows that show it: Generate Mesh from Art's
 * mesher settings ([MeshGenerationOptions]) or Generate Quad Mesh's margin and threshold
 * ([QuadMeshOptions]).  The rows are the recipe, so one value carries a run from the command through the
 * pipeline to the strip and back; every read is clamped, so no row can make the run refuse.
 */
internal sealed interface MeshRecipe {
	/** What the run generates, which names its undo step and picks its notices. */
	val kind: MeshGenerationKind

	/**
	 * This recipe clamped into what its rows offer, the alpha threshold never below the atlas's own.
	 *
	 * @param Int minimumAlphaThreshold The atlas's alpha threshold.
	 * @return MeshRecipe The clamped recipe.
	 */
	fun clamped(minimumAlphaThreshold: Int): MeshRecipe

	/**
	 * The strip's rows showing this recipe.
	 *
	 * @param Int minimumAlphaThreshold The atlas's alpha threshold, the threshold row's floor.
	 * @return List<OperatorParameter> The rows.
	 */
	fun rows(minimumAlphaThreshold: Int): List<OperatorParameter>

	/**
	 * The recipe the strip's rows describe, over this one for anything a row does not carry, clamped.
	 *
	 * @param List<OperatorParameter> parameters            The strip's rows.
	 * @param Int                     minimumAlphaThreshold The atlas's alpha threshold.
	 * @return MeshRecipe The recipe.
	 */
	fun fromRows(parameters: List<OperatorParameter>, minimumAlphaThreshold: Int): MeshRecipe

	/**
	 * The rows an adjustment re-seeds before the rerun reads them, with this recipe as the one the rows
	 * were last seeded from; null leaves the rows as the rigger set them.
	 *
	 * @param AdjustableOperation record                The record with the adjusted rows.
	 * @param Int                 minimumAlphaThreshold The atlas's alpha threshold.
	 * @return List<OperatorParameter>? The re-seeded rows, or null.
	 */
	fun reseededRows(record: AdjustableOperation, minimumAlphaThreshold: Int): List<OperatorParameter>?
}