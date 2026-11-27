package org.umamo.ui.model.artwork

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import org.umamo.format.art.LayerRaster
import org.umamo.interop.art.BirthMesher
import org.umamo.interop.art.BirthOutcome
import org.umamo.interop.art.BirthRefusal
import org.umamo.interop.art.BirthResolution
import org.umamo.interop.art.PendingBirth
import org.umamo.interop.art.mesh.ArtMeshSettings
import org.umamo.interop.art.mesh.FittedArtMesh
import org.umamo.interop.art.mesh.isMeshingGuardFailure
import org.umamo.storage.UmamoLog
import org.umamo.ui.progress.ProgressHandle
import org.umamo.ui.progress.ProgressLabels
import org.umamo.ui.viewport.gizmo.mapInBalancedChunks

/*
 * The birth meshes an import, an add, or a reload names, made in parallel with their count on the status
 * bar.  The bridge and the reload planner name the births (BirthMeshes.kt); this meshes them, since the
 * threads live here.
 */

/**
 * The meshes one operation's births were fitted to, by the layer's pixels and the settings.  A mesh is in
 * the raster's own pixels, before the art is placed on the canvas, so an adjustment that only moves the
 * art - the Align and Offset rows - finds every mesh already made, and one that changes the threshold
 * meshes afresh.  Lives as long as the operation's request, which the strip's record keeps.
 */
internal class BirthMeshCache {
	private val lock = SynchronizedObject()
	private val fittedByArt = HashMap<Pair<LayerRaster, ArtMeshSettings>, FittedArtMesh>()

	/** How many meshes it holds. */
	val size: Int
		get() = synchronized(lock) { fittedByArt.size }

	/**
	 * The fitted mesh of one birth's art: the one made before, or a new one.  The meshing runs outside the
	 * lock so births mesh in parallel; two threads asking for the same art at once both mesh it, and the
	 * first to finish is kept.
	 *
	 * @param PendingBirth pending The birth.
	 * @return FittedArtMesh The mesher's answer for its art.
	 */
	fun fitted(pending: PendingBirth): FittedArtMesh {
		val key = pending.raster to pending.settings
		val known = synchronized(lock) { fittedByArt[key] }

		if (known != null) {
			return known
		}

		val made = BirthMesher.fit(pending)

		return synchronized(lock) { fittedByArt.getOrPut(key) { made } }
	}
}

/**
 * Meshes every pending birth, in parallel chunks on the caller's dispatcher, counting each on [progress].
 * A guard inside the mesher that refuses one birth leaves that drawable its quad and the rest go on; a
 * cancellation propagates.
 *
 * @param List<PendingBirth> pending  The births.
 * @param BirthMeshCache?    cache    The operation's cache, or null to mesh everything afresh.
 * @param ProgressHandle?    progress The operation's task, moved to its meshing stage; or null.
 * @return BirthResolution Every birth's outcome.
 */
internal suspend fun resolveBirths(pending: List<PendingBirth>, cache: BirthMeshCache?, progress: ProgressHandle?): BirthResolution {
	if (pending.isEmpty()) {
		return BirthResolution.None
	}

	progress?.stage(ProgressLabels.ARTWORK_MESHING, total = pending.size)
	val outcomes =
		mapInBalancedChunks(pending, weightOf = { birth -> (birth.raster.width.toLong() * birth.raster.height / 1024 + 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() }) { birth ->
			val outcome =
				try {
					BirthMesher.outcomeOf(birth, cache?.fitted(birth) ?: BirthMesher.fit(birth))
				} catch (failure: Exception) {
					if (!failure.isMeshingGuardFailure()) {
						throw failure
					}

					UmamoLog.error("birth mesh: meshing '${birth.layerName}' failed; it keeps its quad", failure)
					BirthOutcome.Refused(BirthRefusal.MesherFailed)
				}
			progress?.advance()
			birth.drawableId to outcome
		}

	return BirthResolution(outcomes.toMap())
}