package org.umamo.interop.uma

import org.umamo.format.uma.sources.UmaSource
import org.umamo.format.uma.sources.UmaSourceLayer
import org.umamo.format.uma.sources.UmaSources
import org.umamo.runtime.model.ArtSource
import org.umamo.runtime.model.ArtSourceId
import org.umamo.runtime.model.ArtSourceLayer
import org.umamo.runtime.model.PuppetModel

/**
 * Lowers a [PuppetModel]'s linked source art onto the sources entry's schema (docs/format/UMA.md §6), and back:
 * every inventory row with every flag, and the hashes in the file's prefixed form (UMA §6.4).
 */
object UmaSourcesBridge {
	/**
	 * The sources entry's content for [model].
	 *
	 * @param PuppetModel model The model.
	 * @return UmaSources The content; an empty object when the document links no source art.
	 */
	fun sourcesOf(model: PuppetModel): UmaSources =
		UmaSources(
			model.sources.map { source ->
				UmaSource(
					id = source.id.raw,
					name = source.name,
					format = source.format,
					path = source.path,
					contentHash = prefixedHashOf(source.contentHash),
					lastModified = source.lastModified,
					layers =
						source.layers.map { layer ->
							UmaSourceLayer(
								key = layer.key,
								name = layer.name,
								groupPath = layer.groupPath,
								left = layer.left,
								top = layer.top,
								width = layer.width,
								height = layer.height,
								visible = layer.visible,
								present = layer.present.takeIf { present -> !present },
								contentHash = prefixedHashOf(layer.contentHash),
								empty = layer.empty.takeIf { empty -> empty },
								replaced = layer.replaced.takeIf { replaced -> replaced },
								ignored = layer.ignored.takeIf { ignored -> ignored },
							)
						}.ifEmpty { null },
				)
			}.ifEmpty { null },
		)

	/**
	 * The linked source art the sources entry describes.
	 *
	 * @param UmaSources? sources The entry's content, or null when the document has none.
	 * @return List<ArtSource> The sources, in document order.
	 */
	fun sourcesOf(sources: UmaSources?): List<ArtSource> =
		sources?.sources.orEmpty().map { source ->
			ArtSource(
				id = ArtSourceId(source.id),
				name = source.name,
				path = source.path,
				format = source.format,
				layers =
					source.layers.orEmpty().map { layer ->
						ArtSourceLayer(
							key = layer.key,
							name = layer.name,
							groupPath = layer.groupPath,
							left = layer.left,
							top = layer.top,
							width = layer.width,
							height = layer.height,
							visible = layer.visible,
							present = layer.present ?: true,
							contentHash = digestOf(layer.contentHash),
							empty = layer.empty ?: false,
							replaced = layer.replaced ?: false,
							ignored = layer.ignored ?: false,
						)
					},
				contentHash = digestOf(source.contentHash),
				lastModified = source.lastModified,
			)
		}
}