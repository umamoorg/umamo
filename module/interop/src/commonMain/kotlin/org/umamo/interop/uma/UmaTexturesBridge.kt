package org.umamo.interop.uma

import org.umamo.format.uma.UmaEntryKind
import org.umamo.format.uma.UmaWriteException
import org.umamo.format.uma.textures.UmaComposition
import org.umamo.format.uma.textures.UmaPage
import org.umamo.format.uma.textures.UmaPlacement
import org.umamo.format.uma.textures.UmaSourceRef
import org.umamo.format.uma.textures.UmaTextures
import org.umamo.format.uma.textures.UmaTile
import org.umamo.runtime.model.ArtSourceId
import org.umamo.runtime.model.AtlasComposition
import org.umamo.runtime.model.AtlasPage
import org.umamo.runtime.model.AtlasPlacement
import org.umamo.runtime.model.AtlasTile
import org.umamo.runtime.model.AtlasTileId
import org.umamo.runtime.model.PuppetAtlas
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.SourceLayerRef

/**
 * Lowers a [PuppetModel]'s atlas onto the textures entry's schema (docs/format/UMA.md §5), and back.
 *
 * The atlas records only; the images the drawables sample (the render pages) and every path are the format layer's
 * to lay out from the pixels a save hands it.  A value is left out only when it is the model's default.
 */
object UmaTexturesBridge {
	/**
	 * The textures entry's records for [model].
	 *
	 * @param PuppetModel model The model.
	 * @return UmaTextures The records, without paths.
	 * @throws UmaWriteException When a placement holds a value JSON cannot.
	 */
	fun texturesOf(model: PuppetModel): UmaTextures {
		val atlas = model.atlas
		val composition = atlas.composition
		val default = AtlasComposition.Default
		return UmaTextures(
			storedUvsAddressPages = atlas.storedUvsAddressPages.takeIf { addressesPages -> !addressesPages },
			composition =
				UmaComposition(
					alphaThreshold = composition.alphaThreshold.takeIf { threshold -> threshold != default.alphaThreshold },
					extrude = composition.extrude.takeIf { extrude -> extrude != default.extrude },
				).takeIf { record -> record != UmaComposition() },
			pages = atlas.pages.map { page -> UmaPage(page.width, page.height) }.ifEmpty { null },
			tiles = atlas.tiles.map(::tileOf).ifEmpty { null },
		)
	}

	/**
	 * UMA §5.4: one tile's record.
	 *
	 * @param AtlasTile tile The tile.
	 * @return UmaTile The record.
	 * @throws UmaWriteException When its placement holds a value JSON cannot.
	 */
	private fun tileOf(tile: AtlasTile): UmaTile {
		val path = "tiles[${tile.id.raw}].placement"

		/**
		 * [value], refused when it is not finite.
		 *
		 * @param Float  value The value.
		 * @param String key   The placement key, for the failure.
		 * @return Float The value.
		 */
		fun finite(value: Float, key: String): Float = finiteInline(value, UmaEntryKind.Textures.defaultPath, "$path.$key")
		return UmaTile(
			id = tile.id.raw,
			name = tile.name,
			width = tile.width,
			height = tile.height,
			placement =
				tile.placement?.let { placement ->
					UmaPlacement(
						page = placement.pageIndex,
						positionX = finite(placement.positionX, "positionX"),
						positionY = finite(placement.positionY, "positionY"),
						scaleX = finite(placement.scaleX, "scaleX"),
						scaleY = finite(placement.scaleY, "scaleY"),
						rotationDegrees = finite(placement.rotationDegrees, "rotationDegrees"),
					)
				},
			source = tile.source?.let { source -> UmaSourceRef(source.sourceId.raw, source.layerKey, source.stableKey) },
			pinned = tile.pinned.takeIf { pinned -> pinned },
			replaces = tile.replaces?.raw,
		)
	}

	/**
	 * The atlas the textures entry describes.
	 *
	 * @param UmaTextures? textures The entry's content, or null when the document has none.
	 * @return PuppetAtlas The atlas.
	 */
	fun atlasOf(textures: UmaTextures?): PuppetAtlas {
		if (textures == null) {
			return PuppetAtlas.Empty
		}
		val tiles =
			textures.tiles.orEmpty().map { record ->
				AtlasTile(
					id = AtlasTileId(record.id),
					name = record.name,
					width = record.width,
					height = record.height,
					placement =
						record.placement?.let { placement ->
							AtlasPlacement(placement.page, placement.positionX, placement.positionY, placement.scaleX, placement.scaleY, placement.rotationDegrees)
						},
					source = record.source?.let { source -> SourceLayerRef(ArtSourceId(source.art), source.layerKey, source.stableKey) },
					pinned = record.pinned ?: false,
					replaces = record.replaces?.let(::AtlasTileId),
				)
			}
		val default = AtlasComposition.Default
		return PuppetAtlas(
			pages = textures.pages.orEmpty().map { page -> AtlasPage(page.width, page.height) },
			tiles = tiles,
			storedUvsAddressPages = textures.storedUvsAddressPages ?: true,
			// UMA §5.2: the format layer has already refused a composition outside the ranges the model allows.
			composition = AtlasComposition(textures.composition?.alphaThreshold ?: default.alphaThreshold, textures.composition?.extrude ?: default.extrude),
		)
	}
}