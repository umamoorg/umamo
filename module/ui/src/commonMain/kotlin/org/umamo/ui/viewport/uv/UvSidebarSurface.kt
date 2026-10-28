package org.umamo.ui.viewport.uv

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.compositionLocalOf
import org.umamo.ui.viewport.gizmo.GizmoMeshGeometry

/**
 * What a UV editor area hands the panels of the sidebar drawn over it: the shown surface and the editing state a
 * UV panel needs and the sidebar's context, which is the same for every surface, cannot carry.  The area provides
 * it around its drawer, remembered on these values, so a panel never keys a remember on the instance itself: the
 * geometry changes with every frame a drag previews.
 *
 * @property UvEditFrame frame The shown page or source layer's authoring frame.
 * @property List<GizmoMeshGeometry> geometries The shown meshes in display space, built from the model as it looks
 *   now (a drag's preview while one runs), so a readout over them follows the drag; never a capture's base.
 * @property MutableState<Float?> radiusState The shown surface's proportional radius in texels, seeded on first use.
 * @property UvPlacementSurface? placementSurface The page a placement moves on, or null where a placement cannot be
 *   moved: over a source layer, with no page shown, or with no source art to recompose the pages from.
 * @property UvPlacementSceneState placementScene The area's own placement drag, which a placement readout follows.
 */
internal class UvSidebarSurface(
	val frame: UvEditFrame,
	val geometries: List<GizmoMeshGeometry>,
	val radiusState: MutableState<Float?>,
	val placementSurface: UvPlacementSurface?,
	val placementScene: UvPlacementSceneState,
)

/** The UV editor area's surface, for the sidebar drawn over it; null everywhere else. */
internal val LocalUvSidebarSurface = compositionLocalOf<UvSidebarSurface?> { null }