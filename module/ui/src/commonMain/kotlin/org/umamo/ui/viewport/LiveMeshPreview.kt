package org.umamo.ui.viewport

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import org.umamo.edit.mesh.MeshRestPositions
import org.umamo.runtime.model.DrawableId

/**
 * The rest shapes an Edit-mode modal transform is previewing, for a read-only surface outside the gesture's own
 * area that has to follow it (the sidebar's Vertex / Median panel).
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
}

/** The window's live mesh preview; the shell provides one instance, and a standalone overlay has none. */
internal val LocalLiveMeshPreview = staticCompositionLocalOf<LiveMeshPreview?> { null }