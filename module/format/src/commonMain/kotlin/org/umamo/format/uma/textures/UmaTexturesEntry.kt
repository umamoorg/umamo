package org.umamo.format.uma.textures

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.umamo.format.png.pngDimensionsOf
import org.umamo.format.uma.UmaEntryJson
import org.umamo.format.uma.UmaFormatException
import org.umamo.format.uma.UmaIdentityTable
import org.umamo.format.uma.UmaListRule
import org.umamo.format.uma.UmaReadFailure
import org.umamo.format.uma.UmaWriteException
import org.umamo.format.uma.firstIdentityProblem

/**
 * What laying the textures entry out for a save produced: the index with every path assigned, and the
 * pixel entries the save writes or drops.
 *
 * @property UmaTextures textures The index, every record naming its pixel entry.
 * @property Map         payloads The pixel paths this save owns, in write order: bytes to write stored, or
 *   null for a path the index no longer names, which is dropped (UMA §5.7, D21).
 */
internal class UmaTexturesLayout(
	val textures: UmaTextures,
	val payloads: Map<String, ByteArray?>,
)

/**
 * The textures entry's codec over its JSON tree: decoding with every rule UMA §5 sets, the layout that
 * assigns pixel paths before a save, and the identities its arrays merge by.
 */
internal object UmaTexturesEntry {
	// UMA §5.7: the pixel paths this entry mints.
	private const val TILE_PATH_PREFIX = "textures/tile-"
	private const val PAGE_PATH_PREFIX = "textures/page-"
	private const val PNG_SUFFIX = ".png"
	private const val THUMBNAIL_PATH = "thumbnail.png"

	/**
	 * UMA §5.8: tiles match by id across a save; atlas pages have no identity (D12) and render pages are one
	 * imported set, so both are replaced whole.
	 */
	val identities: UmaIdentityTable =
		UmaIdentityTable(
			mapOf(
				UmaTile.serializer().descriptor.serialName to
					UmaListRule.ByIdentity { element -> (element["id"] as? JsonPrimitive)?.takeIf { primitive -> primitive.isString }?.content },
				UmaPage.serializer().descriptor.serialName to UmaListRule.Whole,
				UmaRenderPage.serializer().descriptor.serialName to UmaListRule.Whole,
			),
		)

	/**
	 * Decodes a textures entry's tree, failing loudly on anything the schema or the pixel entries do not allow.
	 *
	 * @param JsonObject tree          The entry's JSON.
	 * @param String     path          The entry's path, for the failure.
	 * @param Function   payloadHeader Resolves an archive path to the first bytes of its payload (a PNG header's
	 *   worth at least, when the payload is that long), or null when the archive holds no such payload.
	 * @return UmaTextures The index.
	 * @throws UmaFormatException When the tree breaks the schema, repeats a tile id, names a pixel entry the
	 *   archive does not hold, or names one whose PNG header disagrees with its record.
	 */
	fun decode(tree: JsonObject, path: String, payloadHeader: (String) -> ByteArray?): UmaTextures {
		val textures =
			try {
				UmaEntryJson.decodeFromJsonElement(UmaTextures.serializer(), tree)
			} catch (failure: SerializationException) {
				throw UmaFormatException(UmaReadFailure.MalformedEntry(path, failure.message.orEmpty()), failure)
			} catch (failure: IllegalArgumentException) {
				throw UmaFormatException(UmaReadFailure.MalformedEntry(path, failure.message.orEmpty()), failure)
			}
		firstIdentityProblem(tree, UmaTextures.serializer().descriptor, identities, "")?.let { problem ->
			throw UmaFormatException(UmaReadFailure.MalformedEntry(path, problem))
		}
		firstProblem(textures, path, payloadHeader)?.let { failure -> throw UmaFormatException(failure) }
		return textures
	}

	/**
	 * Encodes a laid-out index for a save.
	 *
	 * @param UmaTextures textures The index, every record naming its pixel entry.
	 * @return JsonObject The entry's JSON, before the merge.
	 */
	fun encode(textures: UmaTextures): JsonObject = UmaEntryJson.encodeToJsonElement(UmaTextures.serializer(), textures) as JsonObject

	/**
	 * Assigns every record its pixel entry for a save (UMA §5.7): a tile the file already holds keeps its path and
	 * its bytes, a new tile takes a minted path and its PNG from [pixels], the render pages follow [pixels]'s mode,
	 * and the thumbnail is recorded from its PNG's own header.  Paths and render pages the caller set on the index
	 * are ignored; the layout owns them.
	 *
	 * @param UmaTextures    updated       The index to save.
	 * @param UmaTextures?   retained      The index as read from the file, or null for a document without one.
	 * @param UmaPixelSource pixels        The pixels to write.
	 * @param String         entryPath     The entry's path, for a failure.
	 * @param Set<String>    pathsInUse    Every path the archive holds or a save already owns, so a minted path
	 *   never collides with one, whoever named it.
	 * @param Function       payloadHeader Resolves an archive path to its payload's first bytes, or null.
	 * @return UmaTexturesLayout The laid-out index and the pixel entries to write or drop.
	 * @throws UmaWriteException When a new tile has no pixels, kept render pages are missing, a PNG is not one, or
	 *   the laid-out index breaks a rule a reader would refuse.
	 */
	fun layOut(
		updated: UmaTextures,
		retained: UmaTextures?,
		pixels: UmaPixelSource,
		entryPath: String,
		pathsInUse: Set<String>,
		payloadHeader: (String) -> ByteArray?,
	): UmaTexturesLayout {
		val written = LinkedHashMap<String, ByteArray?>()
		val tiles = layOutTiles(updated.tiles.orEmpty(), retained?.tiles.orEmpty(), pixels.tile, entryPath, pathsInUse, written)
		val renderPages = layOutRenderPages(retained?.renderPages, pixels.renderPages, entryPath, pathsInUse, written)
		val thumbnail =
			pixels.thumbnail?.let { bytes ->
				val (width, height) = pngDimensionsOf(bytes) ?: throw UmaWriteException("$entryPath: thumbnail", "the thumbnail is not a PNG")
				written[THUMBNAIL_PATH] = bytes
				UmaThumbnail(width, height, THUMBNAIL_PATH)
			}
		val laidOut =
			updated.copy(
				tiles = tiles.takeIf { list -> list.isNotEmpty() },
				renderPages = renderPages,
				thumbnail = thumbnail,
			)
		val payloads = LinkedHashMap<String, ByteArray?>(written)
		val named = namedPaths(laidOut)
		for (path in namedPaths(retained)) {
			// UMA §5.7 (D21): a pixel entry the index no longer names leaves the file.
			if (path !in named) {
				payloads[path] = null
			}
		}
		firstProblem(laidOut, entryPath) { path -> written[path] ?: payloadHeader(path) }?.let { failure ->
			throw UmaWriteException(entryPath, failure.description)
		}
		return UmaTexturesLayout(laidOut, payloads)
	}

	/**
	 * The render pages [mode] records.  A stored image whose bytes equal a PNG this save already writes (a tile a
	 * drawable samples directly) names that entry rather than a copy of it.
	 *
	 * @param UmaRenderPages?     retained   The render pages as read.
	 * @param UmaRenderPagePixels mode       What the drawables sample.
	 * @param String              entryPath  The entry's path, for a failure.
	 * @param Set<String>         pathsInUse Every path in use.
	 * @param MutableMap          written    The pixel entries this save writes so far; receives the new ones.
	 * @return UmaRenderPages? The laid-out render pages, or null when the pages derive.
	 * @throws UmaWriteException When kept render pages are missing, or a stored image is not a PNG.
	 */
	private fun layOutRenderPages(
		retained: UmaRenderPages?,
		mode: UmaRenderPagePixels,
		entryPath: String,
		pathsInUse: Set<String>,
		written: MutableMap<String, ByteArray?>,
	): UmaRenderPages? =
		when (mode) {
			UmaRenderPagePixels.Derived -> null

			UmaRenderPagePixels.Retained -> retained ?: throw UmaWriteException("$entryPath: renderPages", "the file holds no render pages to keep")

			is UmaRenderPagePixels.Stored -> {
				val pathByContent = HashMap<Int, MutableList<String>>()
				for ((path, bytes) in written) {
					if (bytes != null) {
						pathByContent.getOrPut(bytes.contentHashCode()) { ArrayList() } += path
					}
				}
				var nextNumber = nextNumberFor(PAGE_PATH_PREFIX, pathsInUse)
				val pages =
					mode.pages.mapIndexed { pageIndex, bytes ->
						val (width, height) = pngDimensionsOf(bytes) ?: throw UmaWriteException("$entryPath: renderPages.pages[$pageIndex]", "the image is not a PNG")
						val shared = pathByContent[bytes.contentHashCode()]?.firstOrNull { path -> written[path]?.contentEquals(bytes) == true }
						val path =
							shared ?: "$PAGE_PATH_PREFIX$nextNumber$PNG_SUFFIX".also { minted ->
								nextNumber++
								written[minted] = bytes
								pathByContent.getOrPut(bytes.contentHashCode()) { ArrayList() } += minted
							}
						UmaRenderPage(width, height, path)
					}
				UmaRenderPages(pages, mode.drawablePages.takeIf { map -> map.isNotEmpty() })
			}
		}

	/**
	 * The tile records with their pixel entries assigned: a tile the file holds keeps its path, and a new one is
	 * minted a path and written from [tilePng].
	 *
	 * @param List<UmaTile> updated    The tiles to save.
	 * @param List<UmaTile> retained   The tiles as read.
	 * @param Function      tilePng    Yields a new tile's PNG by id.
	 * @param String        entryPath  The entry's path, for a failure.
	 * @param Set<String>   pathsInUse Every path in use.
	 * @param MutableMap    written    Receives the pixel entries to write.
	 * @return List<UmaTile> The laid-out tiles.
	 * @throws UmaWriteException When a new tile has no pixels.
	 */
	private fun layOutTiles(
		updated: List<UmaTile>,
		retained: List<UmaTile>,
		tilePng: (String) -> ByteArray?,
		entryPath: String,
		pathsInUse: Set<String>,
		written: MutableMap<String, ByteArray?>,
	): List<UmaTile> {
		val retainedPathById = HashMap<String, String>()
		for (tile in retained) {
			val path = tile.path ?: continue
			if (tile.id !in retainedPathById) {
				retainedPathById[tile.id] = path
			}
		}
		var nextNumber = nextNumberFor(TILE_PATH_PREFIX, pathsInUse)
		return updated.map { tile ->
			// UMA §5.7: a tile's pixels never change, so a tile the file holds keeps its entry untouched.
			val existing = retainedPathById[tile.id]
			if (existing != null) {
				tile.copy(path = existing)
			} else {
				val path = "$TILE_PATH_PREFIX$nextNumber$PNG_SUFFIX"
				nextNumber++
				written[path] = tilePng(tile.id) ?: throw UmaWriteException("$entryPath: tiles[${tile.id}]", "no pixels for the tile")
				tile.copy(path = path)
			}
		}
	}

	/**
	 * The number after the highest one any path in [pathsInUse] carries under [prefix], so a minted path collides
	 * with nothing the archive holds, whoever named it.
	 *
	 * @param String      prefix     The path prefix before the number.
	 * @param Set<String> pathsInUse Every path in use.
	 * @return Int The next free number.
	 */
	private fun nextNumberFor(prefix: String, pathsInUse: Set<String>): Int {
		var highest = -1
		for (path in pathsInUse) {
			if (!path.startsWith(prefix) || !path.endsWith(PNG_SUFFIX)) {
				continue
			}
			val number = path.substring(prefix.length, path.length - PNG_SUFFIX.length).toIntOrNull() ?: continue
			if (number > highest) {
				highest = number
			}
		}
		return highest + 1
	}

	/**
	 * Every pixel path [textures]'s records name: the pixel entries the textures entry owns (D21).
	 *
	 * @param UmaTextures? textures The index, or null.
	 * @return Set<String> The paths, in index order.
	 */
	fun namedPaths(textures: UmaTextures?): Set<String> {
		if (textures == null) {
			return emptySet()
		}
		val paths = LinkedHashSet<String>()
		textures.tiles?.forEach { tile -> tile.path?.let(paths::add) }
		textures.renderPages?.pages?.forEach { page -> paths.add(page.path) }
		textures.thumbnail?.let { thumbnail -> paths.add(thumbnail.path) }
		return paths
	}

	/**
	 * Where [textures] first breaks a rule of UMA §5 that the schema classes cannot enforce on their own.
	 *
	 * @param UmaTextures textures      The index.
	 * @param String      entryPath     The entry's path, for the failure.
	 * @param Function    payloadHeader Resolves an archive path to its payload's first bytes, or null.
	 * @return UmaReadFailure? The failure, or null when every rule holds.
	 */
	private fun firstProblem(textures: UmaTextures, entryPath: String, payloadHeader: (String) -> ByteArray?): UmaReadFailure? {
		/**
		 * The malformed-entry failure for [detail].
		 *
		 * @param String detail What is wrong, and where.
		 * @return UmaReadFailure The failure.
		 */
		fun malformed(detail: String): UmaReadFailure = UmaReadFailure.MalformedEntry(entryPath, detail)

		/**
		 * The failure a pixel entry earns when the archive does not hold it or its PNG header disagrees with its
		 * record's size, or null when it is sound.
		 *
		 * @param String path   The pixel entry's path.
		 * @param Int    width  The record's width.
		 * @param Int    height The record's height.
		 * @param String where  The record's position, for the failure.
		 * @return UmaReadFailure? The failure.
		 */
		fun pixelProblem(path: String, width: Int, height: Int, where: String): UmaReadFailure? {
			val header = payloadHeader(path) ?: return UmaReadFailure.MissingEntry(path)
			// UMA §5.1: every pixel entry is a PNG exactly the size its record says.
			val (pngWidth, pngHeight) = pngDimensionsOf(header) ?: return malformed("$where names '$path', which is not a PNG")
			if (pngWidth != width || pngHeight != height) {
				return malformed("$where is $width x $height but '$path' holds $pngWidth x $pngHeight")
			}
			return null
		}

		textures.composition?.let { composition ->
			composition.alphaThreshold?.let { threshold ->
				if (threshold !in 1..255) {
					return malformed("composition.alphaThreshold is $threshold, outside 1 to 255")
				}
			}
			composition.extrude?.let { extrude ->
				if (extrude < 0) {
					return malformed("composition.extrude is $extrude, which is negative")
				}
			}
		}
		val pages = textures.pages.orEmpty()
		for ((pageIndex, page) in pages.withIndex()) {
			if (page.width < 0 || page.height < 0) {
				return malformed("pages[$pageIndex] has a negative size")
			}
		}
		for ((tileIndex, tile) in textures.tiles.orEmpty().withIndex()) {
			val where = "tiles[$tileIndex]"
			if (tile.width < 0 || tile.height < 0) {
				return malformed("$where has a negative size")
			}
			val path = tile.path ?: return malformed("$where has no path")
			pixelProblem(path, tile.width, tile.height, where)?.let { failure -> return failure }
			tile.placement?.let { placement ->
				if (placement.page !in pages.indices) {
					return malformed("$where.placement.page is ${placement.page}, outside the ${pages.size} pages")
				}
			}
		}
		textures.renderPages?.let { renderPages ->
			for ((pageIndex, page) in renderPages.pages.withIndex()) {
				pixelProblem(page.path, page.width, page.height, "renderPages.pages[$pageIndex]")?.let { failure -> return failure }
			}
			for ((drawableId, pageIndex) in renderPages.drawablePages.orEmpty()) {
				if (pageIndex !in renderPages.pages.indices) {
					return malformed("renderPages.drawablePages.$drawableId is $pageIndex, outside the ${renderPages.pages.size} render pages")
				}
			}
		}
		textures.thumbnail?.let { thumbnail ->
			pixelProblem(thumbnail.path, thumbnail.width, thumbnail.height, "thumbnail")?.let { failure -> return failure }
		}
		return null
	}
}