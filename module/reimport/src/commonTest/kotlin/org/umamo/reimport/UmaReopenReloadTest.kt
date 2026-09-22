package org.umamo.reimport

import org.umamo.format.art.LayerBounds
import org.umamo.format.art.LayerRaster
import org.umamo.format.binary.contentHashOf
import org.umamo.format.png.PngCodec
import org.umamo.format.raster.RasterImage
import org.umamo.format.uma.Uma
import org.umamo.format.uma.UmaModel
import org.umamo.format.uma.UmaWriterInfo
import org.umamo.format.uma.textures.UmaPixelSource
import org.umamo.format.uma.textures.UmaRenderPagePixels
import org.umamo.interop.art.ArtSourceDescriptor
import org.umamo.interop.art.CanvasOffset
import org.umamo.interop.art.SourceArtImport
import org.umamo.interop.art.SourceArtImportOptions
import org.umamo.interop.art.placedBy
import org.umamo.interop.art.placedFor
import org.umamo.interop.uma.UmaDocumentBridge
import org.umamo.runtime.model.AtlasTileId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.PuppetModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The reopen-then-reload promise the native format makes (docs/format/UMA.md §5, §6): a reload planned against a
 * document before it was saved and one planned against the same document reopened from its `.uma` are the same
 * plan, because the file carries the whole re-import baseline - the inventory with its flags and hashes, the
 * bindings, the lineage, the file's placement on the canvas, and the tiles' pixels byte for byte.
 */
class UmaReopenReloadTest {
	private val options = SourceArtImportOptions(alphaThreshold = 1, birthMeshMargin = 2)

	private val repainted = TestLayer("lyid:1", "One", 0, LayerBounds(10, 20, 4, 4), solidRaster(4, 4, 1))
	private val kept = TestLayer("lyid:2", "Two", 1, LayerBounds(30, 40, 4, 4), solidRaster(4, 4, 2))
	private val removed = TestLayer("lyid:3", "Three", 2, LayerBounds(50, 10, 6, 6), solidRaster(6, 6, 3), visible = false)
	private val ignored = TestLayer("lyid:4", "Sketch", 3, LayerBounds(0, 0, 8, 8), LayerRaster(8, 8, ByteArray(8 * 8 * 4)))

	/**
	 * A reloaded file's lines, reduced to text that is equal exactly when the plans are: meshes by their arrays,
	 * rasters by their content hash, and everything else by its data-class form.
	 *
	 * @param ReloadPlan? plan The plan, or null when there is nothing to do.
	 * @return List<String> The lines.
	 */
	private fun fingerprintOf(plan: ReloadPlan?): List<String> {
		if (plan == null) {
			return listOf("no plan")
		}

		/**
		 * A mesh as text.
		 *
		 * @param DrawableMesh? mesh The mesh.
		 * @return String The text.
		 */
		fun meshText(mesh: DrawableMesh?): String = mesh?.let { present -> "${present.positions.contentToString()} ${present.uvs.contentToString()} ${present.indices.contentToString()}" } ?: "none"
		val reload = plan.reload
		val lines = ArrayList<String>()
		lines += "source ${reload.source}"
		reload.replacedTiles.forEach { replaced -> lines += "replaced ${replaced.oldId.raw} with ${replaced.tile}" }
		reload.drawableMeshes.entries.sortedBy { (drawableId, _) -> drawableId.raw }.forEach { (drawableId, mesh) -> lines += "mesh ${drawableId.raw} ${meshText(mesh)}" }
		reload.additions?.let { additions ->
			lines += "added source ${additions.source}"
			additions.tiles.forEach { tile -> lines += "added tile $tile" }
			additions.drawables.forEach { drawable -> lines += "added drawable ${drawable.copy(mesh = null)} ${meshText(drawable.mesh)}" }
			additions.parts.forEach { part -> lines += "added part $part" }
			lines += "added root ${additions.rootChildren} insertions ${additions.insertions}"
		}
		lines += "outgrown ${reload.outgrown} retired ${reload.retiredTiles} visibility ${reload.drawableVisibility}"
		plan.rasterByTile.entries.sortedBy { (tileId, _) -> tileId.raw }.forEach { (tileId, raster) -> lines += "raster ${tileId.raw} ${raster.width}x${raster.height} ${contentHashOf(raster.rgba)}" }
		lines += "report ${plan.report.results}"
		lines += "notices ${plan.notices}"
		lines += "leftovers ${plan.leftovers}"
		return lines
	}

	/**
	 * A reload of a file whose first layer was repainted, whose third was deleted, whose ignored layer gained pixels,
	 * and which gained a fifth layer plans the same against the document and against its reopened `.uma`.
	 */
	@Test
	fun aReopenedDocumentReloadsAsTheOriginalDoes() {
		val fileBytes = "the file as imported".encodeToByteArray()
		// The file was placed on the canvas when it was added, so every read of it is placed the same way.
		val offset = CanvasOffset(7, 11)
		val imported = SourceArtImport.fromSourceArt(TestArt(listOf(repainted, kept, removed, ignored)).placedBy(offset), ArtSourceDescriptor("a.clip", "/art/a.clip", "clip", contentHashOf(fileBytes), 1_757_894_400_000L), options)
		val sourceId = imported.puppet.sources.single().id
		// Rig work the reload must respect: the rigger ignored the sketch layer.
		val source = imported.puppet.sources.single().copy(offsetX = offset.x, offsetZ = offset.z)
		val model = imported.puppet.copy(sources = listOf(source.copy(layers = source.layers.map { layer -> if (layer.key == "lyid:4") layer.copy(ignored = true) else layer })))
		assertTrue(model.sources.single().layers.single { layer -> layer.key == "lyid:4" }.empty, "the sketch reads as an empty layer")

		val pngByTile = imported.rasterByTile.mapKeys { (tileId, _) -> tileId.raw }.mapValues { (_, raster) -> PngCodec.write(RasterImage(raster.width, raster.height, raster.rgba)) }
		val saved = UmaDocumentBridge.documentOf(UmaModel.create(UmaWriterInfo("Umamo", "test")), model, UmaPixelSource({ tileId -> pngByTile[tileId] }, UmaRenderPagePixels.Derived, null))
		val document = Uma.read(Uma.write(saved))
		val reopened: PuppetModel = UmaDocumentBridge.modelOf(document)
		assertEquals(offset.x to offset.z, reopened.sources.single().let { record -> record.offsetX to record.offsetZ }, "the placement survives the save")
		val pages = UmaDocumentBridge.pagesOf(document)
		val reopenedRasterOf: (AtlasTileId) -> LayerRaster? = { tileId -> pages.tilePng(tileId)?.let(PngCodec::read)?.let { image -> LayerRaster(image.width, image.height, image.rgba) } }

		val newArt =
			TestArt(
				listOf(
					TestLayer("lyid:1", "One", 0, LayerBounds(10, 20, 5, 4), solidRaster(5, 4, 9)),
					kept,
					TestLayer("lyid:4", "Sketch", 2, LayerBounds(0, 0, 8, 8), solidRaster(8, 8, 4)),
					TestLayer("lyid:5", "Five", 3, LayerBounds(60, 60, 3, 3), solidRaster(3, 3, 5)),
				),
			).placedFor(reopened.sources.single())
		val newHash = contentHashOf("the file as saved again".encodeToByteArray())
		val before = ArtworkReloadPlanner.plan(model, sourceId, newArt, options, { tileId -> imported.rasterByTile[tileId] }, newHash)
		val after = ArtworkReloadPlanner.plan(reopened, sourceId, newArt, options, reopenedRasterOf, newHash)

		val plan = assertNotNull(before, "the changed file plans a reload")
		assertTrue(plan.reload.replacedTiles.isNotEmpty(), "the repainted layer supersedes its tile")
		assertTrue(plan.reload.additions != null, "the new layer is added")
		assertTrue(plan.report.needsReview.isNotEmpty(), "the deleted layer is left for review")
		assertEquals(offset.x to offset.z, plan.reload.source.let { record -> record.offsetX to record.offsetZ }, "the refreshed record keeps the placement")
		assertEquals(fingerprintOf(before), fingerprintOf(after))
	}
}