package org.umamo.render.puppet

import org.umamo.render.DecodedImage
import org.umamo.render.device.GpuTexture
import org.umamo.render.device.RenderDevice
import org.umamo.render.device.TextureFilter
import org.umamo.render.device.TextureFormat
import org.umamo.render.device.TextureWrap

/** The sample affine of an uploaded crop, which is the trim itself. */
private val IDENTITY_SAMPLE = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f)

/**
 * One placement crop as a frame draws it: the texture it samples and how.
 *
 * @property GpuTexture texture The tile's layer texture, or the uploaded crop.
 * @property FloatArray quadToWorld The quad's unit-corner-to-display affine.
 * @property FloatArray sampleAffine The quad's unit coordinates to the texture's coordinates.
 */
internal class ResolvedCropQuad(
	val texture: GpuTexture,
	val quadToWorld: FloatArray,
	val sampleAffine: FloatArray,
)

/**
 * A placement preview as a frame draws it: the scrims, then the crops with their textures resolved.
 *
 * @property OverlayColor scrimColor The scrim's color.
 * @property List<FloatArray> scrimQuads The scrims' quads.
 * @property List<ResolvedCropQuad> crops The movers' crops, then the ghost's.
 */
internal class ResolvedPlacement(
	val scrimColor: OverlayColor,
	val scrimQuads: List<FloatArray>,
	val crops: List<ResolvedCropQuad>,
)

/**
 * The device objects one UV area's scene keeps between renders: its overlay's ([DirectOverlayResidency]) and
 * the textures of the placement crops it uploaded.  Kept per area, so two UV areas showing different surfaces
 * never re-upload each other's meshes.  A placement crop uploads once per crop image (by identity) and is freed
 * on the first render that no longer shows it; a crop whose tile has a resident layer texture uploads nothing.
 *
 * Render thread only; [applyOverlay] and [applyPlacement] are resource operations that run between frames,
 * never inside one.
 *
 * @param RenderDevice device The backend the buffers, the store, and the crops live on.
 */
internal class UvSceneResidency(
	private val device: RenderDevice,
) {
	/** The area's overlay: its store, its meshes' buffers, and the value they reflect. */
	val overlay = DirectOverlayResidency(device)

	/** The placement preview the next frame draws, its textures resolved, or null for none. */
	var placement: ResolvedPlacement? = null
		private set

	// The crops this area uploaded, by crop image identity.
	private val cropTextures = HashMap<DecodedImage, GpuTexture>()

	/**
	 * Brings the area's overlay to [direct] (see [DirectOverlayResidency.applyOverlay]).
	 *
	 * @param DirectMeshOverlay? direct The overlay to reflect, or null for none.
	 */
	fun applyOverlay(direct: DirectMeshOverlay?) {
		overlay.applyOverlay(direct)
	}

	/**
	 * Brings the placement crops to [preview]: each crop resolves to its tile's resident layer texture, else
	 * to its own uploaded crop (uploaded on first sight), and every uploaded crop the preview no longer shows
	 * is freed.  A null preview frees them all.  A resource operation, run between frames.
	 *
	 * @param PlacementPreview? preview The preview to draw, or null for none.
	 * @param Function layerTexture The resident layer texture of a tile key, or null.
	 */
	fun applyPlacement(preview: PlacementPreview?, layerTexture: (String) -> GpuTexture?) {
		if (preview == null) {
			freeCropsExcept(emptySet())
			placement = null

			return
		}

		val shownCrops = HashSet<DecodedImage>()

		/**
		 * One quad's draw, or null when it has no texture to draw from.
		 *
		 * @param PlacementCropQuad quad The quad.
		 * @return ResolvedCropQuad? The draw.
		 */
		fun resolve(quad: PlacementCropQuad): ResolvedCropQuad? {
			val resident = layerTexture(quad.tileKey)

			if (resident != null) {
				return ResolvedCropQuad(resident, quad.quadToWorld, quad.sampleAffine)
			}

			val crop = quad.crop ?: return null
			shownCrops.add(crop)
			val texture =
				cropTextures.getOrPut(crop) {
					// Linear with a transparent border, like the layer texture it stands in for, so both sources
					// draw the same pixels.
					device.createTexture(crop.width, crop.height, TextureFormat.Rgba8, TextureFilter.Linear, crop.rgba, TextureWrap.ClampToTransparentBorder)
				}

			return ResolvedCropQuad(texture, quad.quadToWorld, IDENTITY_SAMPLE)
		}
		val crops = ArrayList<ResolvedCropQuad>(preview.crops.size + preview.ghostCrops.size)
		preview.crops.mapNotNullTo(crops, ::resolve)
		preview.ghostCrops.mapNotNullTo(crops, ::resolve)
		freeCropsExcept(shownCrops)
		placement = ResolvedPlacement(preview.scrimColor, preview.scrimQuads, crops)
	}

	/** Frees every buffer set, the store, and the uploaded crops.  Must run with the device's context current. */
	fun dispose() {
		freeCropsExcept(emptySet())
		placement = null
		overlay.dispose()
	}

	/**
	 * Frees every uploaded crop not in [kept].
	 *
	 * @param Set<DecodedImage> kept The crops still shown.
	 */
	private fun freeCropsExcept(kept: Set<DecodedImage>) {
		val entries = cropTextures.entries.iterator()

		while (entries.hasNext()) {
			val entry = entries.next()

			if (entry.key !in kept) {
				device.destroyTexture(entry.value)
				entries.remove()
			}
		}
	}
}