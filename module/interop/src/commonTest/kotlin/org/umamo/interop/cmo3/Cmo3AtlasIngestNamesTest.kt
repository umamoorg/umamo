package org.umamo.interop.cmo3

import org.umamo.format.cmo3.model.custom.CImageResource
import org.umamo.format.cmo3.model.custom.CModelImage
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.format.cmo3.model.gen.CLayeredImage
import org.umamo.format.cmo3.model.gen.CModelImageGroup
import org.umamo.format.cmo3.model.gen.CTextureManager
import org.umamo.format.cmo3.model.gen.LayeredImageWrapper
import org.umamo.format.cmo3.model.identity.Guid
import org.umamo.format.cmo3.model.identity.Id
import org.umamo.interop.cmo3.import.cmo3AtlasIngest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * How the CMO3 atlas ingest names a tile and an artwork source: by the one display-name rule, with the
 * guid standing in where there is no name.  A name is never a key here - placement, pixels, and the
 * drawable binding all join on the guid - so a fallback changes what the rigger reads and nothing else.
 */
class Cmo3AtlasIngestNamesTest {
	private fun guidOf(uuid: String): Guid = Guid("").apply { this.uuid = uuid }

	/**
	 * A model image whose name slot holds [name].
	 *
	 * @param String uuid The image's guid, which is also its tile id.
	 * @param Any? name What the loosely typed name slot holds.
	 * @return CModelImage The model image.
	 */
	private fun modelImage(uuid: String, name: Any?): CModelImage =
		CModelImage().apply {
			guid = guidOf(uuid)
			this.name = name
			// An image with no resource has no art to show, and is not a tile at all.
			_filteredImage = CImageResource()
		}

	/**
	 * An artwork file named [name].
	 *
	 * @param String uuid The file's guid, which is also its source id.
	 * @param String? name The file's stored name.
	 * @return CLayeredImage The layered image.
	 */
	private fun layeredImage(uuid: String, name: String?): CLayeredImage =
		CLayeredImage().apply {
			guid = guidOf(uuid)
			this.name = name
		}

	private fun modelWith(modelImages: List<CModelImage> = emptyList(), layeredImages: List<CLayeredImage> = emptyList()): CModelSource =
		CModelSource().apply {
			textureManager =
				CTextureManager().apply {
					_modelImageGroups = listOf(CModelImageGroup().apply { _modelImages = modelImages })
					_rawImages = layeredImages.map { layered -> LayeredImageWrapper().apply { image = layered } }
				}
		}

	@Test
	fun aTileTakesItsOwnNameElseItsGuid() {
		val ingest =
			cmo3AtlasIngest(
				modelWith(
					modelImages =
						listOf(
							modelImage("uuid-named", "Front Hair"),
							modelImage("uuid-padded", " Eye L "),
							modelImage("uuid-empty", ""),
							modelImage("uuid-blank", " \t"),
							modelImage("uuid-absent", null),
						),
				),
			)

		assertEquals(
			listOf("Front Hair", " Eye L ", "uuid-empty", "uuid-blank", "uuid-absent"),
			ingest.atlas.tiles.map { tile -> tile.name },
		)
		// The name never decides which tile is which.
		assertEquals(
			listOf("uuid-named", "uuid-padded", "uuid-empty", "uuid-blank", "uuid-absent"),
			ingest.atlas.tiles.map { tile -> tile.id.raw },
		)
	}

	@Test
	fun aTileWhoseNameSlotHoldsAnIdTakesTheIdString() {
		val ingest =
			cmo3AtlasIngest(
				modelWith(
					modelImages =
						listOf(
							modelImage("uuid-id", Id("").apply { idstr = "ArtMesh7" }),
							modelImage("uuid-empty-id", Id("")),
						),
				),
			)

		assertEquals(listOf("ArtMesh7", "uuid-empty-id"), ingest.atlas.tiles.map { tile -> tile.name })
	}

	@Test
	fun anArtworkSourceTakesItsOwnNameElseItsGuid() {
		val ingest =
			cmo3AtlasIngest(
				modelWith(
					layeredImages =
						listOf(
							layeredImage("uuid-named", "hair.png"),
							layeredImage("uuid-blank", "  "),
							layeredImage("uuid-absent", null),
						),
				),
			)

		assertEquals(listOf("hair.png", "uuid-blank", "uuid-absent"), ingest.sources.map { source -> source.name })
		// The format is read off the name's extension, and a guid has none.
		assertEquals(listOf("png", "psd", "psd"), ingest.sources.map { source -> source.format })
	}
}