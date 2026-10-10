package org.umamo.ui.workspace.spaces.uv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

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
 * A UV editor area's proportional influence radii, one per surface, in display (texel) units, and which
 * surface the area shows.  Held on the area's [UvEditorViewState] because the header and the body are
 * sibling subtrees: the body's gesture machinery seeds and resizes the shown surface's radius, and the
 * header's Proportional Size row reads and edits the same one.  Lives for the area's life and is never
 * saved.
 *
 * The session's radiusWorld is scaled for the puppet canvas and means nothing on a texture surface, so only
 * the falloff curve and Connected Only are shared with it.  Each surface keeps its own radius: a radius
 * seeded on an 8192-texel page means something else entirely on a 576-texel layer, so carrying one into the
 * other would arrive absurdly large or vanishingly small, and going back to a surface brings back the radius
 * it was left with.
 */
internal class UvProportionalRadii {
	private val radiusBySurface = HashMap<UvRadiusSurfaceKey, MutableState<Float?>>()

	/** The surface the area's body shows, or null while it shows none; written by the body. */
	var shownSurface by mutableStateOf<UvRadiusSurfaceKey?>(null)

	/**
	 * A surface's radius state: null until the gesture machinery or the header row seeds it from the
	 * surface's size, and the same state each time the surface is asked for.
	 *
	 * @param UvRadiusSurfaceKey surface The surface.
	 * @return MutableState<Float?> The surface's radius state.
	 */
	fun stateFor(surface: UvRadiusSurfaceKey): MutableState<Float?> = radiusBySurface.getOrPut(surface) { mutableStateOf(null) }
}

/**
 * The UV editor's proportional influence radius for the shown surface, in display (texel) units: null
 * until the gesture machinery seeds it from the surface's size (see [UvProportionalRadii]).
 *
 * @param UvProportionalRadii radii The area's radii.
 * @param UvRadiusSurfaceKey surface The shown surface.
 * @return MutableState<Float?> The surface's radius state, the same one each time the surface is shown.
 */
@Composable
internal fun rememberUvProportionalRadius(radii: UvProportionalRadii, surface: UvRadiusSurfaceKey): MutableState<Float?> =
	remember(radii, surface) { radii.stateFor(surface) }