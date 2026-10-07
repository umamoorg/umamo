package org.umamo.render.puppet

import org.umamo.render.DecodedImage
import org.umamo.render.LayerDrawPlan
import org.umamo.render.LayerRasterBatch
import org.umamo.render.PuppetTextures
import org.umamo.render.device.GpuTexture
import org.umamo.render.device.RenderDevice
import org.umamo.render.device.TextureFilter
import org.umamo.render.device.TextureFormat
import org.umamo.render.device.TextureWrap
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.PuppetModel

/**
 * The art a puppet samples, resident on the GPU: its atlas pages, and the source artwork it displays
 * from instead when the document shows its layers.
 *
 * It owns the textures and decides which one each resident drawable points at; the drawables themselves
 * belong to [residency].  Freeing a texture and re-pointing the residents that sampled it are always one
 * operation here, never two a caller must pair, because a resident left pointing at a freed texture
 * draws garbage.
 *
 * Render thread only.  Constructing it touches no device; every call that uploads or frees must run with
 * the device's context current.
 *
 * @property RenderDevice      device    The backend the textures live on.
 * @property DrawableResidency residency The resident drawables this re-points.
 * @property PuppetTextures    textures  The page set the uploaded pages came from.
 */
internal class ArtResidency(
	private val device: RenderDevice,
	private val residency: DrawableResidency,
	textures: PuppetTextures,
) {
	/**
	 * The page set the uploaded pages came from.  The session owns the effective page set - a repack
	 * swaps it mid-session through [setAtlasPages], which re-points this at the set the uploaded handles
	 * came from.
	 */
	var textures: PuppetTextures = textures
		private set

	// The uploaded atlas pages, index-parallel to PuppetTextures.atlases. Retained so a structural reconcile
	// can bind a newly-uploaded drawable to its page.
	private var atlasHandles: List<GpuTexture> = emptyList()

	// Which artwork every drawable would display from - the mapping, complete for the whole document and
	// carrying no pixels.  Retained so a structural reconcile can re-stamp a rebuilt resident, exactly as
	// atlasHandles is, and so a layer dropped for budget can be promoted again without rebuilding it.
	private var layerPlan: LayerDrawPlan = LayerDrawPlan.EMPTY

	// The uploaded artwork, keyed by LAYER (duplicated art is shared, so this is the count of distinct
	// images, not of drawables).  Only applySourceLayerDisplay adds to or removes from it, because
	// freeing a texture is only safe together with the re-stamp that stops drawables pointing at it.
	private val layerTextures = HashMap<String, GpuTexture>()

	// Layers the document references but whose pixels would not decode.  Held so the ask stops repeating
	// them; cleared with the plan, because a new document may well decode them.
	private val undecodableLayerKeys = HashSet<String>()

	// Whether the puppet is actually displaying from artwork.  False until every mapped layer has landed,
	// which is what keeps the mode from ever showing a half-artwork puppet.
	private var sourceLayerDisplayReady = false

	/** Uploads the atlas page(s).  Must run with the device's context current. */
	fun uploadAtlasPages() {
		atlasHandles = textures.atlases.map(::atlasPageTexture)
	}

	/**
	 * The atlas page a drawable being uploaded samples, or null when it has none.
	 *
	 * @param Drawable drawable The drawable being uploaded.
	 * @return GpuTexture? The page's texture.
	 */
	fun atlasTextureAtUpload(drawable: Drawable): GpuTexture? {
		// The atlas mapping is keyed by the SOURCE format's drawable ids, so a session-created copy resolves
		// its page through the drawable it was duplicated from (Drawable.textureSourceId).
		val atlasIndex = textures.atlasIndexByDrawableId[(drawable.textureSourceId ?: drawable.id).raw]
		return atlasIndex?.let { atlasHandles[it] }
	}

	/**
	 * One atlas page's pixels, or null when the index is out of range.
	 *
	 * @param Int pageIndex The page's index in the current set.
	 * @return DecodedImage? The page.
	 */
	fun pageImage(pageIndex: Int): DecodedImage? = textures.atlases.getOrNull(pageIndex)

	/**
	 * One atlas page's uploaded texture, or null when the index is out of range.
	 *
	 * @param Int pageIndex The page's index in the current set.
	 * @return GpuTexture? The page's texture.
	 */
	fun pageTexture(pageIndex: Int): GpuTexture? = atlasHandles.getOrNull(pageIndex)

	/**
	 * Sets which artwork every drawable would display from.  An empty plan returns the whole puppet to
	 * its atlas pages.
	 *
	 * The plan carries no pixels, so this is cheap.  The pixels follow through
	 * [deliverSourceLayerRasters], and the puppet keeps displaying from its atlas until they ALL have -
	 * the producer streams them in chunks rather than holding the document's artwork decoded at once.
	 *
	 * Render thread only - it frees GPU resources through the display pass below.
	 *
	 * @param LayerDrawPlan plan The mapping to display by, or [LayerDrawPlan.EMPTY] for the atlas.
	 */
	fun setSourceLayerPlan(plan: LayerDrawPlan) {
		if (plan === layerPlan) {
			return
		}
		layerPlan = plan
		// A new plan is a new document or a new drawable set; a layer that would not decode for the old
		// one deserves another chance rather than being written off for the renderer's life.
		undecodableLayerKeys.clear()
		applySourceLayerDisplay()
	}

	/**
	 * Takes delivery of decoded artwork, uploading it for the current plan.
	 *
	 * Render thread only: it creates and destroys GPU resources, so it must run with the context
	 * current.
	 *
	 * The batch is consumed, never retained - its images are uploaded and the reference dropped, so the
	 * decoded bytes do not outlive the call.
	 *
	 * @param LayerRasterBatch batch The decoded artwork to take up.
	 */
	fun deliverSourceLayerRasters(batch: LayerRasterBatch) {
		undecodableLayerKeys.addAll(batch.undecodableLayerKeys)
		for ((layerKey, image) in batch.rastersByLayerKey) {
			if (layerKey in layerTextures || layerKey !in layerPlan.layerByteCostByKey) {
				continue
			}
			layerTextures[layerKey] =
				device.createTexture(
					image.width,
					image.height,
					TextureFormat.Rgba8,
					TextureFilter.Linear,
					image.rgba,
					// A drawable's mesh overhangs the art it samples, so its recovered coordinates run past
					// this image - across the corpus by up to 443 layer pixels.  An atlas page absorbs that
					// overhang in its own padding; a layer image has no padding to absorb it, so the wrap
					// mode is what has to supply the transparency (see TextureWrap).
					TextureWrap.ClampToTransparentBorder,
				)
		}
		applySourceLayerDisplay()
	}

	/**
	 * Frees artwork the current plan does not want, re-points every resident, and republishes the ask.
	 *
	 * THE ONLY MUTATOR of [layerTextures]: freeing a texture while a drawable still points at it leaves a
	 * dangling handle that draws garbage, so freeing and the re-stamp are one operation rather than two a
	 * caller must remember to pair.
	 *
	 * Display is ALL OR NOTHING.  Source-artwork display is an inspection mode - the rigger is looking at
	 * the art to judge it - so a puppet showing artwork for some drawables and atlas pages for the rest
	 * is not a partial success, it is a view that cannot be trusted, because nothing on screen
	 * distinguishes the two.  Until every layer the plan maps has either landed or proved undecodable,
	 * the whole puppet stays on its atlas.
	 *
	 * The one permitted mix is artwork that does not EXIST: a drawable the document retains no art for,
	 * or art that will not decode, keeps its atlas page and is counted and reported to the rigger.  That
	 * is honest - the mode cannot show what is not there - and it is bounded and named, where a
	 * memory-driven mix would be silent and arbitrary.
	 *
	 * Also run after a reconcile: a rebuilt resident comes back on its atlas page, so the whole set is
	 * re-pointed at the artwork the document displays from.
	 */
	fun applySourceLayerDisplay() {
		val wanted = layerPlan.layerByteCostByKey.keys - undecodableLayerKeys
		// Artwork no longer mapped is freed whether or not the mode is engaged: it is dead either way, and
		// holding it would let a document switch leak the previous one's pages.
		val obsolete = layerTextures.keys.filter { layerKey -> layerKey !in wanted }
		for (layerKey in obsolete) {
			layerTextures.remove(layerKey)?.let { texture -> device.destroyTexture(texture) }
		}
		sourceLayerDisplayReady = wanted.isNotEmpty() && layerTextures.keys.containsAll(wanted)
		for ((drawableId, gpuDrawable) in residency.residents) {
			stampSourceLayer(gpuDrawable, drawableId)
		}
	}

	/**
	 * The resident layer texture of one tile, or null when its art is not uploaded (outside source-art
	 * display mode, or before its batch arrives).  It holds the tile's whole decoded raster, so a UV scene can
	 * sample a placement crop through it rather than upload one.
	 *
	 * @param String layerKey The tile's layer key (its atlas tile id).
	 * @return GpuTexture? The texture, or null.
	 */
	fun layerTexture(layerKey: String): GpuTexture? = layerTextures[layerKey]

	/**
	 * Whether the puppet is displaying from source artwork, for tests and diagnostics.
	 *
	 * @return Triple Whether the mode is engaged, the resident layer count, and how many the plan maps.
	 */
	fun sourceLayerDisplayState(): Triple<Boolean, Int, Int> =
		Triple(sourceLayerDisplayReady, layerTextures.size, layerPlan.layerByteCostByKey.size)

	/**
	 * Swaps the atlas page set: destroys the uploaded pages, uploads [next]'s, re-points [textures], and
	 * re-stamps every resident's page binding.  One operation, because freeing a page is only safe
	 * together with the re-stamp that stops drawables sampling it - the same rule
	 * [applySourceLayerDisplay] states for artwork.  Must run with the device's context current.
	 *
	 * @param PuppetTextures next  The new page set.
	 * @param PuppetModel    model The model the residents currently reflect, for each drawable's
	 *   binding source.
	 */
	fun setAtlasPages(next: PuppetTextures, model: PuppetModel) {
		if (next === textures) {
			// The set the handles already came from - a re-published wrapper around unchanged pages.
			return
		}
		for (handle in atlasHandles) {
			device.destroyTexture(handle)
		}
		textures = next
		atlasHandles = next.atlases.map(::atlasPageTexture)
		applyAtlasBinding(model)
	}

	/**
	 * Frees the uploaded artwork and atlas pages.  Must run with the device's context current.
	 *
	 * The artwork is created and destroyed across the renderer's life rather than uploaded once, so
	 * letting it die with the context is not enough: an engine that outlives one renderer would leak
	 * everything the previous one had admitted.
	 */
	fun dispose() {
		for (texture in layerTextures.values) {
			device.destroyTexture(texture)
		}
		layerTextures.clear()
		for (texture in atlasHandles) {
			device.destroyTexture(texture)
		}
		atlasHandles = emptyList()
	}

	/**
	 * Points one resident at its source artwork, or back at its atlas page when the current set does not
	 * cover it.
	 *
	 * Applied to EVERY resident, not just the drawn ones: a mask source is posed and drawn into the
	 * coverage pass while sitting outside the shown set, and a mask sampling one frame while the art it
	 * clips samples the other cuts the silhouette to garbage.
	 *
	 * Nothing points at artwork until the mode is READY - every mapped layer landed - so the puppet is
	 * never caught half in one frame and half in the other.  After that, the only drawables left on the
	 * atlas are the ones whose artwork does not exist, which are counted and reported.
	 *
	 * @param GpuDrawable gpuDrawable The resident to stamp.
	 * @param DrawableId  drawableId  Its id, keying into the current plan.
	 */
	private fun stampSourceLayer(gpuDrawable: GpuDrawable, drawableId: DrawableId) {
		val draw = if (sourceLayerDisplayReady) layerPlan.drawsByDrawableId[drawableId.raw] else null
		val texture = draw?.let { layerTextures[it.layerKey] }
		gpuDrawable.layerTexture = texture
		gpuDrawable.layerUvAffine = if (texture != null) draw.uvAffine else null
	}

	/**
	 * Uploads one atlas page.
	 *
	 * The page wraps to a transparent border, like a layer image: a document's pages are not always
	 * packed atlases - a CMO3 draws some drawables straight from their model image - and a mesh
	 * overhangs its art, so an edge-clamped page would repeat its border across the overhang as a
	 * streak.  On a packed page the overhang lands on padding and the wrap never comes into play.
	 *
	 * @param DecodedImage page The page's pixels.
	 * @return GpuTexture The texture handle.
	 */
	private fun atlasPageTexture(page: DecodedImage): GpuTexture =
		device.createTexture(page.width, page.height, TextureFormat.Rgba8, TextureFilter.Linear, page.rgba, TextureWrap.ClampToTransparentBorder)

	/**
	 * Re-points every resident at its atlas page under the current [textures] - the atlas twin of
	 * [applySourceLayerDisplay]'s stamp pass.  The index map is re-read per resident, not just the
	 * handles: a repack can move a drawable to a different page, so the old index is as stale as the
	 * old texture.
	 *
	 * @param PuppetModel model The model the residents currently reflect.
	 */
	private fun applyAtlasBinding(model: PuppetModel) {
		val bindingSourceById = model.drawables.associateBy({ it.id }, { it.textureSourceId ?: it.id })
		for ((drawableId, gpuDrawable) in residency.residents) {
			// The atlas mapping is keyed by the SOURCE format's drawable ids, exactly as at upload: a
			// session-created copy resolves its page through the drawable it was duplicated from.
			val bindingKey = (bindingSourceById[drawableId] ?: drawableId).raw
			gpuDrawable.atlasTexture = textures.atlasIndexByDrawableId[bindingKey]?.let { atlasHandles.getOrNull(it) }
		}
	}
}