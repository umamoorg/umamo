package org.umamo.ui.model

import org.umamo.edit.withAtlasRepack
import org.umamo.format.atlas.AtlasPackOptions
import org.umamo.format.atlas.packAtlas
import org.umamo.format.cmo3.Cmo3
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.format.png.PngCodec
import org.umamo.interop.cmo3.Cmo3Import
import org.umamo.interop.cmo3.cmo3AtlasIngest
import org.umamo.render.DecodedImage
import org.umamo.render.atlasPlacementFromPack
import org.umamo.runtime.model.AtlasPage
import org.umamo.runtime.model.AtlasPlacement
import org.umamo.runtime.model.AtlasTileId
import org.umamo.runtime.model.PuppetModel
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The repack's mesh-disjointness gate: after a repack, no two tiles' MESH footprints may intersect
 * on a page, and every footprint stays on its page.
 *
 * Pixel trims alone cannot guarantee this - a mesh rings outside its art's opaque region (Erica: up
 * to 58px past the trim, and 127 of 128 meshes past the raster itself), so a pack spaced by trims
 * put 308 tile pairs over each other and hung meshes off the page edge.  The official editor's own
 * packing keeps mesh footprints disjoint (its only overlaps are deliberate duplicate-art sharing),
 * which is the behaviour the mesh reserve reproduces.  This drives the SHIPPED input path
 * (buildRepackPackInput), so what it proves disjoint is exactly what the command packs.
 */
class AtlasRepackMeshDisjointTest {
	private val sample: File? = System.getProperty("cmo3.sample")?.let(::File)?.takeIf { it.isFile }

	private class Rect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
		fun intersectionArea(other: Rect): Float {
			val width = min(right, other.right) - max(left, other.left)
			val height = min(bottom, other.bottom) - max(top, other.top)
			return if (width > 0f && height > 0f) width * height else 0f
		}

		fun union(other: Rect): Rect =
			Rect(min(left, other.left), min(top, other.top), max(right, other.right), max(bottom, other.bottom))

		override fun toString(): String = "[%.1f,%.1f %.0fx%.0f]".format(left, top, right - left, bottom - top)
	}

	/** Per placed+bound tile: the union of its drawables' mesh bboxes in PAGE pixels, keyed by page. */
	private fun meshFootprintsByTile(model: PuppetModel): Map<AtlasTileId, Pair<Int, Rect>> {
		val tileById = model.atlas.tiles.associateBy { tile -> tile.id }
		val footprints = HashMap<AtlasTileId, Pair<Int, Rect>>()
		for (drawable in model.drawables) {
			val tileId = drawable.atlasTileId ?: continue
			val placement = tileById[tileId]?.placement ?: continue
			val page = model.atlas.pages.getOrNull(placement.pageIndex) ?: continue
			val uvs = drawable.mesh?.uvs ?: continue
			if (uvs.size < 2) {
				continue
			}
			var minU = Float.MAX_VALUE
			var maxU = -Float.MAX_VALUE
			var minV = Float.MAX_VALUE
			var maxV = -Float.MAX_VALUE
			var componentIndex = 0
			while (componentIndex + 1 < uvs.size) {
				minU = min(minU, uvs[componentIndex])
				maxU = max(maxU, uvs[componentIndex])
				minV = min(minV, uvs[componentIndex + 1])
				maxV = max(maxV, uvs[componentIndex + 1])
				componentIndex += 2
			}
			val bounds = Rect(minU * page.width, minV * page.height, maxU * page.width, maxV * page.height)
			val existing = footprints[tileId]
			footprints[tileId] = placement.pageIndex to (existing?.second?.union(bounds) ?: bounds)
		}
		return footprints
	}

	@Test
	fun repackedMeshFootprintsAreDisjointAndOnThePage() {
		val file = sample
		if (file == null) {
			println("cmo3.sample not present; skipping the repack mesh-disjointness gate")
			return
		}
		val cmo3 = Cmo3.read(file.readBytes())
		val root = cmo3.root as? CModelSource ?: error("root is not a CModelSource")
		val imported = Cmo3Import.fromModelSource(root)
		if (imported.atlas.tiles.isEmpty() || !imported.atlas.storedUvsAddressPages) {
			println("${file.name} is not repackable; skipping the mesh-disjointness gate")
			return
		}
		val ingest = cmo3AtlasIngest(root)
		val decodeRaster = { tileId: AtlasTileId ->
			ingest.imageResourceByTile[tileId]?.let(cmo3::extractLayerPng)?.let { bytes ->
				val image = PngCodec.read(bytes)
				DecodedImage(image.rgba, image.width, image.height)
			}
		}

		// The SHIPPED pack input (pixels + mesh reserves), packed at the document's own page size.
		val packInput = buildRepackPackInput(imported, decodeRaster)
		val options =
			AtlasPackOptions(maxPageSize = imported.atlas.pages.maxOf { page -> max(page.width, page.height) })
		val packResult = packAtlas(packInput.items, options)
		assertTrue(
			repackRefusals(imported, packResult.skipped, packInput.undecodableTileIds).isEmpty(),
			"the corpus sample must repack cleanly",
		)
		val packedByKey = packResult.placements.associateBy { placement -> placement.key }
		val placements = HashMap<AtlasTileId, AtlasPlacement?>()
		for (tile in imported.atlas.tiles) {
			placements[tile.id] = packedByKey[tile.id.raw]?.let { packed -> atlasPlacementFromPack(packed) }
		}
		val repacked =
			imported.withAtlasRepack(packResult.pages.map { page -> AtlasPage(page.width, page.height) }, placements)

		val tileNames = imported.atlas.tiles.associateBy({ tile -> tile.id }, { tile -> tile.name })
		val footprints = meshFootprintsByTile(repacked).entries.toList()
		val overlaps = ArrayList<String>()
		for (firstIndex in footprints.indices) {
			for (secondIndex in firstIndex + 1 until footprints.size) {
				val (firstPage, firstRect) = footprints[firstIndex].value
				val (secondPage, secondRect) = footprints[secondIndex].value
				if (firstPage != secondPage) {
					continue
				}
				val area = firstRect.intersectionArea(secondRect)
				if (area > 0f) {
					overlaps.add(
						"'${tileNames[footprints[firstIndex].key]}' x '${tileNames[footprints[secondIndex].key]}'" +
							" ${"%.0f".format(area)}px2 $firstRect vs $secondRect",
					)
				}
			}
		}
		val offPage = ArrayList<String>()
		for ((tileId, footprint) in footprints.map { entry -> entry.key to entry.value }) {
			val (pageIndex, rect) = footprint
			val page = repacked.atlas.pages[pageIndex]
			if (rect.left < 0f || rect.top < 0f || rect.right > page.width || rect.bottom > page.height) {
				offPage.add("'${tileNames[tileId]}' $rect off the ${page.width}x${page.height} page")
			}
		}
		println(
			"[repack-gate] ${footprints.size} mesh footprints on ${packResult.pages.size} page(s), " +
				"${overlaps.size} overlap(s), ${offPage.size} off-page",
		)
		assertTrue(overlaps.isEmpty(), "mesh footprints overlap after repack:\n" + overlaps.take(10).joinToString("\n"))
		assertTrue(offPage.isEmpty(), "mesh footprints leave the page after repack:\n" + offPage.take(10).joinToString("\n"))
	}
}