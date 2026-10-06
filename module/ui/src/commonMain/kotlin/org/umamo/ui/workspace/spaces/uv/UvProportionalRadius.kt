package org.umamo.ui.workspace.spaces.uv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember

/**
 * Which surface a UV editor's proportional radius belongs to.  The radius is in display texels, so it
 * means one thing per texel size: two pages of the same size share one, and a source layer keeps its own.
 *
 * @property Int displayWidth The shown surface's width in texels.
 * @property Int displayHeight The shown surface's height in texels.
 * @property String? layerKey The shown source layer, or null over an atlas page.
 */
internal data class UvRadiusSurfaceKey(
	val displayWidth: Int,
	val displayHeight: Int,
	val layerKey: String?,
)

/**
 * The UV editor's proportional influence radius for the shown surface, in display (texel) units: null
 * until the gesture machinery seeds it from the surface's size.
 *
 * The session's radiusWorld is scaled for the puppet canvas and means nothing on a texture surface, so only
 * the falloff curve and Connected Only are shared with it.  Each surface keeps its own radius for the
 * area's life: a radius seeded on an 8192-texel page means something else entirely on a 576-texel layer, so
 * carrying one into the other would arrive absurdly large or vanishingly small, and going back to a surface
 * brings back the radius it was left with.
 *
 * @param String areaId The UV editor area.
 * @param UvRadiusSurfaceKey surface The shown surface.
 * @return MutableState<Float?> The surface's radius state, the same one each time the surface is shown.
 */
@Composable
internal fun rememberUvProportionalRadius(areaId: String, surface: UvRadiusSurfaceKey): MutableState<Float?> {
	val radiusBySurface = remember(areaId) { HashMap<UvRadiusSurfaceKey, MutableState<Float?>>() }
	return remember(radiusBySurface, surface) { radiusBySurface.getOrPut(surface) { mutableStateOf(null) } }
}