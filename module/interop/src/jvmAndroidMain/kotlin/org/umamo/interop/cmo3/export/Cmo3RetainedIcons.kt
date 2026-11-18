package org.umamo.interop.cmo3.export

import org.umamo.format.cmo3.model.custom.CLayer
import org.umamo.format.cmo3.model.custom.CModelImage
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.format.cmo3.model.custom.CWritableImage
import org.umamo.format.cmo3.model.gen.CArtMeshSource
import org.umamo.format.cmo3.model.gen.CImageIcon
import org.umamo.format.raster.RasterImage

/**
 * The icons of a retained graph, re-rendered for changed art: a drawable's two over its texture
 * patch, the model's three from its thumbnail, and a rewritten layer's or model image's own.  An
 * icon whose entry the archive holds has its bytes replaced in place; one the file never carried is
 * minted with its slot recorded.
 */
internal class Cmo3RetainedIcons(
	context: Cmo3ExportContext,
	private val archive: Cmo3RetainedArchive,
) {
	private val target = context.target
	private val editor = context.editor

	/**
	 * A drawable's own icons over its texture patch, embedded now.
	 *
	 * @param RasterImage raster The art the drawable samples, in its own frame.
	 * @param FloatArray? artUvs The drawable's texture coordinates in that frame, or null for the whole art.
	 * @return Pair The 32px and 16px icons.
	 */
	fun drawableIcons(raster: RasterImage, artUvs: FloatArray?): Pair<CImageIcon, CImageIcon> {
		val patch = Cmo3Icons.patchOf(raster, artUvs)
		val icon32 = Cmo3Icons.iconOf(patch, 32, archive.names.nextIconPath(), archive.pendingEntries)
		val icon16 = Cmo3Icons.iconOf(patch, 16, archive.names.nextIconPath(), archive.pendingEntries)
		archive.embedPending()

		return icon32 to icon16
	}

	/**
	 * Re-renders a file-side drawable's icons over its (changed) art, in place where the icons have
	 * entries and minted with their slots recorded where the file carried none.
	 *
	 * @param CArtMeshSource mesh   The drawable source.
	 * @param RasterImage    raster The art it samples, in its own frame.
	 * @param FloatArray?    artUvs Its texture coordinates in that frame, or null for the whole art.
	 */
	fun replaceDrawableIcons(mesh: CArtMeshSource, raster: RasterImage, artUvs: FloatArray?) {
		val patch = Cmo3Icons.patchOf(raster, artUvs)
		// CMO3: ACDrawableSource fields icon32 / icon16, in that order after invertClippingMask.
		refreshIcon(mesh.icon32, patch, 32) { icon ->
			mesh.icon32 = icon
			editor.ensureChildSlot(mesh, "ACDrawableSource", "icon32", "icon16")
		}
		refreshIcon(mesh.icon16, patch, 16) { icon ->
			mesh.icon16 = icon
			editor.ensureChildSlot(mesh, "ACDrawableSource", "icon16")
		}
	}

	/**
	 * Writes the model's three icons as fits of [thumbnail], in place where they have entries and
	 * minted with their slots recorded where the file carried none.
	 *
	 * @param CModelSource modelSource The model root.
	 * @param RasterImage  thumbnail   The model's rest-pose thumbnail.
	 */
	fun replaceModelIcons(modelSource: CModelSource, thumbnail: RasterImage) {
		// CMO3: CModelSource fields _icon64 / _icon32 / _icon16, in that order before gameMotionSet.
		refreshIcon(modelSource._icon64, thumbnail, 64) { icon ->
			modelSource._icon64 = icon
			editor.ensureChildSlot(modelSource, "CModelSource", "_icon64", "_icon32")
		}
		refreshIcon(modelSource._icon32, thumbnail, 32) { icon ->
			modelSource._icon32 = icon
			editor.ensureChildSlot(modelSource, "CModelSource", "_icon32", "_icon16")
		}
		refreshIcon(modelSource._icon16, thumbnail, 16) { icon ->
			modelSource._icon16 = icon
			editor.ensureChildSlot(modelSource, "CModelSource", "_icon16", "gameMotionSet")
		}
	}

	/**
	 * Re-renders one icon slot: the existing entry's bytes are replaced when the slot names one the
	 * archive holds, else a fresh icon is minted and handed to [assign] to set and record its slot.
	 *
	 * @param Any?        current The slot's icon today.
	 * @param RasterImage raster  The art to fit.
	 * @param Int         size    The square's edge.
	 * @param Function    assign  Sets a minted icon on its owner and records the slot.
	 */
	private fun refreshIcon(current: Any?, raster: RasterImage, size: Int, assign: (CImageIcon) -> Unit) {
		val path = Cmo3Icons.archivePathOf(current)

		if (path != null && target.archive.byPath(path) != null) {
			target.replacePng(path, Cmo3Icons.iconPngOf(raster, size))
			(current as CImageIcon).image.let { image ->
				if (image is CWritableImage) {
					image.width = size
					image.height = size
				}
			}

			return
		}

		assign(Cmo3Icons.iconOf(raster, size, archive.names.nextIconPath(), archive.pendingEntries))
		archive.embedPending()
	}

	/**
	 * Re-renders a rewritten layer's icons over its new art.
	 *
	 * @param CLayer      layer  The layer.
	 * @param RasterImage raster Its new pixels.
	 */
	fun replaceLayerIcons(layer: CLayer, raster: RasterImage) {
		// CMO3: CLayer fields icon16 / icon64, in that order before layerInfo.
		refreshIcon(layer.icon16, raster, 16) { icon ->
			layer.icon16 = icon
			editor.ensureChildSlot(layer, "CLayer", "icon16", "icon64")
		}
		refreshIcon(layer.icon64, raster, 64) { icon ->
			layer.icon64 = icon
			editor.ensureChildSlot(layer, "CLayer", "icon64", "layerInfo")
		}
	}

	/**
	 * Re-renders a rewritten model image's icon over its new art.
	 *
	 * @param CModelImage modelImage The model image.
	 * @param RasterImage raster     Its new pixels.
	 */
	fun replaceModelImageIcon(modelImage: CModelImage, raster: RasterImage) {
		// CMO3: CModelImage field icon16, before _materialLocalToCanvasTransform.
		refreshIcon(modelImage.icon16, raster, 16) { icon ->
			modelImage.icon16 = icon
			editor.ensureChildSlot(modelImage, "CModelImage", "icon16", "_materialLocalToCanvasTransform")
		}
	}
}