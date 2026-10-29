package org.umamo.ui.viewport

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import org.umamo.edit.mesh.MeshRestPositions
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.WarpLatticeForm

/**
 * The rest shapes and lattice points an Edit- or Pose-mode modal transform is previewing, for a read-only surface
 * outside the gesture's own area that has to follow it (the sidebar's Vertex / Median panel, and the 2D viewport's
 * lattice cage, which a rest lattice drag moves while the art stays put, and a posed one moves with the art).
 *
 * The 2D modal transforms push their preview straight to the render service rather than through
 * PuppetRenderSync's preview state, which the UV areas read and which a positions-only drag must not recompose
 * them with every frame.  This is that preview's own, narrower channel.  One slot serves the window: a session
 * latches one mesh operator at a time, so at most one gesture writes it.  The transform that owns the gesture
 * writes each published drive and clears the slot as the gesture ends, so a reader never holds a preview the
 * renderer has dropped.
 */
internal class LiveMeshPreview {
	/** The previewed rest shape per moving mesh, or null while no Edit-mode modal transform has published one. */
	var restByDrawable: Map<DrawableId, MeshRestPositions>? by mutableStateOf(null)

	/**
	 * The previewed world points per moving lattice, interleaved x, y, or null while no Edit-mode modal transform has
	 * published one.
	 */
	var latticeWorldByDeformer: Map<DeformerId, FloatArray>? by mutableStateOf(null)

	/**
	 * The previewed pending lattice per moving warp at the pose, in its parent's space before blend shapes, or null
	 * while no Pose-mode lattice drag has published one.  The cage evaluates every posed warp through these, so a
	 * child warp's unmoved points follow a moved parent the way the art does.
	 */
	var latticeFormsByDeformer: Map<DeformerId, WarpLatticeForm>? by mutableStateOf(null)
}

/** The window's live mesh preview; the shell provides one instance, and a standalone overlay has none. */
internal val LocalLiveMeshPreview = staticCompositionLocalOf<LiveMeshPreview?> { null }