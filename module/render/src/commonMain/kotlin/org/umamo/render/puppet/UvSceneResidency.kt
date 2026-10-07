package org.umamo.render.puppet

import org.umamo.render.DecodedImage
import org.umamo.render.device.DeformedPositionStore
import org.umamo.render.device.GpuTexture
import org.umamo.render.device.OverlayMeshBuffers
import org.umamo.render.device.OverlayMeshSpec
import org.umamo.render.device.RenderDevice
import org.umamo.render.device.TextureFilter
import org.umamo.render.device.TextureFormat
import org.umamo.render.device.TextureWrap
import org.umamo.runtime.model.DrawableId

/** The sample affine of an uploaded crop, which is the trim itself. */
private val IDENTITY_SAMPLE = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f)

/**
 * One direct overlay mesh as a UV scene holds it: its value, the arrays it was uploaded from, and its draw
 * entry.
 *
 * @property MeshOverlayMesh mesh The mesh's overlay value.
 * @property FloatArray positions The display positions in the store (compared by identity).
 * @property IntArray triangleIndices The triangle indices its buffers were built from (by identity).
 * @property OverlayDrawEntry entry Its buffers, store offset, and resolved actives.
 */
internal class DirectOverlayResident(
	val mesh: MeshOverlayMesh,
	val positions: FloatArray,
	val triangleIndices: IntArray,
	val entry: OverlayDrawEntry,
)

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
 * The device objects one UV area's scene keeps between renders: the store its overlay's positions are
 * uploaded into, one buffer set per overlay mesh, and the textures of the placement crops it uploaded.
 * Kept per area, so two UV areas showing different surfaces never re-upload each other's meshes.
 *
 * What costs what, by design: a value whose position arrays are the same instances uploads nothing; a
 * moved mesh (a new positions array) uploads its own positions alone; a selection change updates the flags
 * of the meshes whose flag arrays changed by identity; a new edge or index array re-creates that mesh's
 * buffers; the store only grows, and a larger one re-uploads every mesh into it.  A placement crop uploads
 * once per crop image (by identity) and is freed on the first render that no longer shows it; a crop whose
 * tile has a resident layer texture uploads nothing.
 *
 * Render thread only; [applyOverlay] is a resource operation that runs between frames, never inside one.
 *
 * @param RenderDevice device The backend the buffers and the store live on.
 */
internal class UvSceneResidency(
	private val device: RenderDevice,
) {
	/** The area's position store, or null before its first overlay. */
	var store: DeformedPositionStore? = null
		private set

	private var storeCapacity = 0

	/** The value the device objects reflect, or null when the area shows no overlay. */
	var applied: DirectMeshOverlay? = null
		private set

	/** The placed meshes, in store order. */
	var residents: List<DirectOverlayResident> = emptyList()
		private set

	/** The placement preview the next frame draws, its textures resolved, or null for none. */
	var placement: ResolvedPlacement? = null
		private set

	// The crops this area uploaded, by crop image identity.
	private val cropTextures = HashMap<DecodedImage, GpuTexture>()

	/**
	 * Brings the device objects to [direct]: plans its layout, grows the store when needed, keeps or
	 * re-creates each mesh's buffers, updates flags in place, uploads the positions that changed, and frees
	 * what left.  A null value frees the buffers and keeps the store for the next overlay.
	 *
	 * @param DirectMeshOverlay? direct The overlay to reflect, or null for none.
	 */
	fun applyOverlay(direct: DirectMeshOverlay?) {
		if (direct === applied) {
			return
		}
		if (direct == null) {
			freeBuffers(residents)
			residents = emptyList()
			applied = null
			return
		}
		val layout = planDirectOverlayLayout(direct)
		var storeReplaced = false
		if (layout.totalVertexCount > storeCapacity) {
			store?.let { outgrown -> device.destroyDeformedPositionStore(outgrown) }
			store = device.createDeformedPositionStore(layout.totalVertexCount)
			storeCapacity = layout.totalVertexCount
			storeReplaced = true
		}
		val previousById = residents.associateBy { resident -> resident.mesh.drawableId }
		val placedIds = HashSet<DrawableId>(layout.placed.size)
		val next = ArrayList<DirectOverlayResident>(layout.placed.size)
		for (placement in layout.placed) {
			val mesh = placement.mesh
			val previous = previousById[mesh.drawableId]
			val buffers = buffersFor(mesh, placement.triangleIndices, previous)
			val positionsMoved = previous == null || previous.positions !== placement.positions || previous.entry.baseOffset != placement.baseOffset
			if (storeReplaced || positionsMoved) {
				store?.let { liveStore -> device.updateDeformedPositions(liveStore, placement.baseOffset, placement.positions) }
			}
			placedIds.add(mesh.drawableId)
			next.add(DirectOverlayResident(mesh, placement.positions, placement.triangleIndices, overlayDrawEntry(mesh, buffers, placement.baseOffset, placement.triangleIndices)))
		}
		freeBuffers(residents.filter { resident -> resident.mesh.drawableId !in placedIds })
		residents = next
		applied = direct
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
		freeBuffers(residents)
		residents = emptyList()
		store?.let { live -> device.destroyDeformedPositionStore(live) }
		store = null
		storeCapacity = 0
		applied = null
	}

	/**
	 * One mesh's buffers: the previous set when its edge and index arrays are the same instances (flags
	 * updated in place when they changed by identity), else a fresh set, freeing the previous one.
	 *
	 * @param MeshOverlayMesh mesh The mesh's overlay value.
	 * @param IntArray triangleIndices Its triangle indices.
	 * @param DirectOverlayResident? previous The mesh as the last value placed it, or null.
	 * @return OverlayMeshBuffers The buffers.
	 */
	private fun buffersFor(mesh: MeshOverlayMesh, triangleIndices: IntArray, previous: DirectOverlayResident?): OverlayMeshBuffers {
		val vertexFlags = overlayFlagsOrZeros(mesh.vertexFlags, mesh.vertexCount)
		val edgeFlags = overlayFlagsOrZeros(mesh.edgeFlags, mesh.edgeCount)
		val faceFlags = overlayFlagsOrZeros(mesh.faceFlags, triangleIndices.size / 3)
		if (previous != null && previous.mesh.edgeEndpoints === mesh.edgeEndpoints && previous.triangleIndices === triangleIndices) {
			if (overlayFlagsDiffer(previous.mesh.vertexFlags, mesh.vertexFlags) ||
				overlayFlagsDiffer(previous.mesh.edgeFlags, mesh.edgeFlags) ||
				overlayFlagsDiffer(previous.mesh.faceFlags, mesh.faceFlags)
			) {
				device.updateOverlayMeshFlags(previous.entry.buffers, vertexFlags, edgeFlags, faceFlags)
			}
			return previous.entry.buffers
		}
		previous?.let { stale -> device.destroyOverlayMeshBuffers(stale.entry.buffers) }
		return device.createOverlayMeshBuffers(OverlayMeshSpec(mesh.edgeEndpoints, triangleIndices, vertexFlags, edgeFlags, faceFlags))
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

	/**
	 * Frees the buffer sets of the given residents.
	 *
	 * @param List<DirectOverlayResident> leaving The residents whose buffers go.
	 */
	private fun freeBuffers(leaving: List<DirectOverlayResident>) {
		for (resident in leaving) {
			device.destroyOverlayMeshBuffers(resident.entry.buffers)
		}
	}
}