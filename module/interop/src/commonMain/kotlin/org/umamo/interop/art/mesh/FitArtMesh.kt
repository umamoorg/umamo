package org.umamo.interop.art.mesh

import org.umamo.format.art.AlphaChannel
import org.umamo.geometry.triangulation.refine.PiecewiseLinearGuide
import kotlin.coroutines.cancellation.CancellationException

/**
 * How a mesh was made to fit when the settings asked for could not fit it.
 *
 * @property Int?    budget      The vertex budget it was meshed under, or null when the asked budget held.
 * @property Boolean holesFilled Whether its holes were filled, the settings leaving Fill Holes off.
 */
public data class MeshFit(val budget: Int?, val holesFilled: Boolean)

/**
 * The mesher's answer for one piece of art and how it was made to fit.  A plain class: it holds the result.
 *
 * @property ArtMeshResult result The mesh, or why there is none.
 * @property MeshFit?      fit    How the mesh was made to fit, or null when the settings asked for fit it.
 */
public class FittedArtMesh(public val result: ArtMeshResult, public val fit: MeshFit?)

/**
 * Meshes art under the settings asked for and, while the mesher finds it over the vertex budget, again:
 * first under a raised budget (doubled, or straight to the count the mesher reported, up to [budgetCap]),
 * then with holes filled from the asked budget up.  Art the settings cannot fit is meshed however it fits
 * rather than skipped - lace and webs whose every hole is a ring of its own, layers of many specks - and
 * a refusal for any other reason stands at once.
 *
 * @param AlphaChannel    alpha           The art's alpha.
 * @param ArtMeshSettings settings        The settings asked for.
 * @param DoubleArray     pins            The pins.
 * @param DoubleArray     pinSurroundings The pins' old triangles.
 * @param Int             budgetCap       The highest budget to raise to.
 * @param PiecewiseLinearGuide? guide     The old mesh's guide, kept through every refit, or null.
 * @return FittedArtMesh The last answer and how it was fitted.
 */
public fun fitArtMesh(
	alpha: AlphaChannel,
	settings: ArtMeshSettings,
	pins: DoubleArray = DoubleArray(0),
	pinSurroundings: DoubleArray = DoubleArray(0),
	budgetCap: Int = MAXIMUM_ART_MESH_VERTEX_BUDGET,
	guide: PiecewiseLinearGuide? = null,
): FittedArtMesh {
	val holeStages = if (settings.fillHoles) listOf(true) else listOf(false, true)
	var last: FittedArtMesh? = null

	for (fillHoles in holeStages) {
		var budget = settings.vertexBudget

		while (true) {
			val result = generateArtMesh(alpha, settings.copy(vertexBudget = budget, fillHoles = fillHoles), pins, pinSurroundings, guide)
			val holesFilled = fillHoles && !settings.fillHoles
			val fit = if (budget != settings.vertexBudget || holesFilled) MeshFit(budget.takeIf { raised -> raised != settings.vertexBudget }, holesFilled) else null
			last = FittedArtMesh(result, fit)
			val overBudget = result.notices.firstNotNullOfOrNull { notice -> notice as? ArtMeshNotice.OverBudget }

			if (result.mesh != null || overBudget == null) {
				return last
			}

			if (budget >= budgetCap) {
				break
			}

			budget = minOf(budgetCap, maxOf(2 * budget, overBudget.vertexCount))
		}
	}

	return last!!
}

/**
 * Whether a throwable is a guard inside the meshing refusing to go on - a require (an
 * IllegalArgumentException) or a check (an IllegalStateException) in the mesher, the triangulator,
 * or the transfer - which a caller turns into a skip or a notice rather than a crash.  A cancellation
 * is an IllegalStateException too and is never one: it propagates.
 *
 * @return Boolean True for a guard's refusal.
 */
public fun Throwable.isMeshingGuardFailure(): Boolean = this !is CancellationException && (this is IllegalArgumentException || this is IllegalStateException)