package org.umamo.interop.art

import org.umamo.format.art.LayerRaster
import org.umamo.format.art.alphaChannel
import org.umamo.geometry.mesh.PlanarTriangleMesh
import org.umamo.interop.art.mesh.ArtMeshNotice
import org.umamo.interop.art.mesh.ArtMeshSettings
import org.umamo.interop.art.mesh.FittedArtMesh
import org.umamo.interop.art.mesh.MeshFit
import org.umamo.interop.art.mesh.fitArtMesh
import org.umamo.interop.art.mesh.isMeshingGuardFailure
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh

/*
 * The meshes an import gives its new drawables.  Every drawable is minted with its quad; under the
 * Automatic mode the import also names it as a pending birth, and the caller meshes the pending births -
 * in parallel and with progress where it can, one after another where it cannot - and hands the meshes
 * back.  Minting never waits on the mesher, so the bridge and the reload planner stay free of threads,
 * and a layer the mesher cannot mesh simply keeps the quad it already has.
 *
 * A born mesh is an ordinary mesh: nothing remembers how it was made, so the modeller's first edit and
 * every reload after treat it exactly like one drawn by hand.
 */

/** How an import gives each new drawable its first mesh. */
sealed interface BirthMeshMode {
	/** The quad over the layer's opaque bounds, grown by the import's margin. */
	data object Quad : BirthMeshMode

	/**
	 * The art mesher's mesh under [settings]; a layer the mesher cannot mesh keeps its quad.
	 *
	 * @property ArtMeshSettings settings The settings to mesh under; the import raises their threshold to its own.
	 */
	data class Automatic(val settings: ArtMeshSettings) : BirthMeshMode
}

/**
 * One drawable an import minted with a quad that its mode wants meshed by the art mesher: the art to
 * mesh, where it lies on the canvas, and the settings to mesh it under.  A plain class: it holds pixels.
 *
 * @property DrawableId      drawableId The drawable the mesh is for.
 * @property String          layerName  The layer's name, for the notice a refusal raises.
 * @property LayerRaster     raster     The layer's pixels.
 * @property Int             layerLeft  The canvas x of the raster's left edge.
 * @property Int             layerTop   The canvas y of the raster's top edge, y down.
 * @property ArtMeshSettings settings   The settings to mesh under.
 */
class PendingBirth(
	val drawableId: DrawableId,
	val layerName: String,
	val raster: LayerRaster,
	val layerLeft: Int,
	val layerTop: Int,
	val settings: ArtMeshSettings,
)

/** Why the mesher gave a pending birth no mesh, so the drawable kept its quad. */
enum class BirthRefusal {
	/** No pixel reaches the mesher's threshold - one raised to the atlas's above the import's. */
	NothingOpaque,

	/** More vertices than any budget the fit raises to. */
	OverBudget,

	/** The mesh failed its structural check and was withheld. */
	Withheld,

	/** The mesher gave no mesh for any other reason. */
	MesherRefused,

	/** A guard inside the mesher refused to go on. */
	MesherFailed,
}

/** What became of one pending birth. */
sealed interface BirthOutcome {
	/**
	 * The drawable's new mesh.
	 *
	 * @property DrawableMesh mesh The mesh, placed on the canvas.
	 * @property MeshFit?     fit  How it was made to fit, or null when the settings fit it as they were.
	 */
	class Born(val mesh: DrawableMesh, val fit: MeshFit?) : BirthOutcome

	/**
	 * The drawable keeps its quad.
	 *
	 * @property BirthRefusal reason Why.
	 */
	data class Refused(val reason: BirthRefusal) : BirthOutcome
}

/**
 * Every pending birth's outcome, by drawable.
 *
 * @property Map outcomes The outcome of each pending birth, by its drawable.
 */
class BirthResolution(val outcomes: Map<DrawableId, BirthOutcome>) {
	companion object {
		/** The resolution of an import with no pending births. */
		val None: BirthResolution = BirthResolution(emptyMap())
	}
}

/**
 * Meshes pending births: the mesh one fitted answer stands for, and a plain loop over many for the
 * callers with no threads to spread them over.
 */
object BirthMesher {
	/**
	 * Meshes one pending birth's art, raising the budget and then filling holes while the settings
	 * cannot fit it.  A guard inside the mesher may throw; [outcomeOf] the answer, and catch with
	 * [isMeshingGuardFailure].
	 *
	 * @param PendingBirth pending The pending birth.
	 * @return FittedArtMesh The mesher's answer.
	 */
	fun fit(pending: PendingBirth): FittedArtMesh = fitArtMesh(pending.raster.alphaChannel(), pending.settings)

	/**
	 * What one fitted answer means for its pending birth: the mesh placed on the canvas, or why the
	 * drawable keeps its quad.
	 *
	 * @param PendingBirth  pending The pending birth.
	 * @param FittedArtMesh fitted  The mesher's answer for its art.
	 * @return BirthOutcome The outcome.
	 */
	fun outcomeOf(pending: PendingBirth, fitted: FittedArtMesh): BirthOutcome {
		val mesh = fitted.result.mesh ?: return BirthOutcome.Refused(refusalOf(fitted.result.notices))

		return BirthOutcome.Born(placed(mesh, pending), fitted.fit)
	}

	/**
	 * Meshes every pending birth in order, on the calling thread.  A guard failure refuses that one birth
	 * and the rest go on.
	 *
	 * @param List<PendingBirth> pending The pending births.
	 * @return BirthResolution Their outcomes.
	 */
	fun resolveSequentially(pending: List<PendingBirth>): BirthResolution {
		val outcomes = LinkedHashMap<DrawableId, BirthOutcome>()

		for (birth in pending) {
			outcomes[birth.drawableId] =
				try {
					outcomeOf(birth, fit(birth))
				} catch (failure: Exception) {
					if (!failure.isMeshingGuardFailure()) {
						throw failure
					}

					BirthOutcome.Refused(BirthRefusal.MesherFailed)
				}
		}

		return BirthResolution(outcomes)
	}

	/**
	 * A mesh in the raster's own pixels placed as a drawable's mesh: canvas positions at the raster's
	 * canvas origin plus each pixel coordinate, and art-frame texture coordinates, each pixel coordinate
	 * over the raster's size - the two views of the art the birth quad gives too.  The triangles keep
	 * the mesher's orientation, which is the quad's.
	 *
	 * @param PlanarTriangleMesh mesh    The mesh, raster pixels, y down.
	 * @param PendingBirth       pending The pending birth it is for.
	 * @return DrawableMesh The drawable's mesh.
	 */
	fun placed(mesh: PlanarTriangleMesh, pending: PendingBirth): DrawableMesh {
		val canvasLeft = pending.layerLeft.toFloat()
		val canvasTop = pending.layerTop.toFloat()
		val width = pending.raster.width.toFloat()
		val height = pending.raster.height.toFloat()
		val positions = FloatArray(mesh.positions.size)
		val uvs = FloatArray(mesh.positions.size)

		for (vertex in 0 until mesh.vertexCount) {
			val x = mesh.positions[2 * vertex]
			val y = mesh.positions[2 * vertex + 1]
			positions[2 * vertex] = canvasLeft + x
			positions[2 * vertex + 1] = canvasTop + y
			uvs[2 * vertex] = x / width
			uvs[2 * vertex + 1] = y / height
		}

		return DrawableMesh.withLocalEqualToCanvas(positions, uvs, mesh.triangles.copyOf())
	}

	/**
	 * The refusal a mesher that gave no mesh stands for, read from its notices.
	 *
	 * @param List<ArtMeshNotice> notices The mesher's notices.
	 * @return BirthRefusal The refusal.
	 */
	private fun refusalOf(notices: List<ArtMeshNotice>): BirthRefusal =
		when {
			notices.any { notice -> notice is ArtMeshNotice.NothingOpaque } -> BirthRefusal.NothingOpaque
			notices.any { notice -> notice is ArtMeshNotice.OverBudget } -> BirthRefusal.OverBudget
			notices.any { notice -> notice is ArtMeshNotice.StructureCheckFailed } -> BirthRefusal.Withheld
			else -> BirthRefusal.MesherRefused
		}
}

/**
 * [drawables] with every pending birth's outcome applied: a born drawable takes its new mesh, a refused
 * one keeps its quad.
 *
 * @param List<Drawable>     drawables  The drawables, each pending birth among them with its quad.
 * @param List<PendingBirth> pending    The pending births.
 * @param BirthResolution    resolution Their outcomes; one for every pending birth.
 * @return List<Drawable> The drawables.
 */
internal fun bornDrawables(drawables: List<Drawable>, pending: List<PendingBirth>, resolution: BirthResolution): List<Drawable> {
	val pendingIds = pending.mapTo(HashSet()) { birth -> birth.drawableId }
	require(resolution.outcomes.keys.containsAll(pendingIds)) { "the resolution misses ${pendingIds - resolution.outcomes.keys}" }

	return drawables.map { drawable ->
		val born = resolution.outcomes[drawable.id] as? BirthOutcome.Born

		if (drawable.id in pendingIds && born != null) drawable.copy(mesh = born.mesh) else drawable
	}
}

/**
 * The notices the refused pending births raise, in the order the births were named.
 *
 * @param List<PendingBirth> pending    The pending births.
 * @param BirthResolution    resolution Their outcomes.
 * @return List<SourceArtImportNotice> One notice per refusal.
 */
internal fun birthRefusalNotices(pending: List<PendingBirth>, resolution: BirthResolution): List<SourceArtImportNotice> =
	pending.mapNotNull { birth ->
		val refused = resolution.outcomes[birth.drawableId] as? BirthOutcome.Refused

		refused?.let { outcome -> SourceArtImportNotice.BirthMeshFellBackToQuad(birth.layerName, outcome.reason) }
	}