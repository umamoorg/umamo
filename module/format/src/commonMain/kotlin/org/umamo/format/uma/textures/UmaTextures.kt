package org.umamo.format.uma.textures

import kotlinx.serialization.Serializable

/*
 * The textures entry's schema (docs/format/UMA.md §5): the document's atlas - its pages, its tiles with
 * their placements and source bindings - and the pixel entries those records name.  Every optional value is
 * a nullable with a null default, and the bridge writes a value only when it is not the model's default.
 *
 * Paths are the format layer's: a record a writer hands in carries no path, and the layout that precedes a
 * save assigns one (§5.7).
 */

/**
 * UMA §5.2: the textures entry's root.
 *
 * @property Boolean?         storedUvsAddressPages Whether the drawables' stored texture coordinates address the
 *   packed pages rather than the art; absent means true.
 * @property UmaComposition?  composition           The trim and extrusion policy the pages derive under; absent
 *   means the packer's defaults.
 * @property List<UmaPage>?   pages                 The atlas's pages, in page order: the sizes placements index.
 * @property List<UmaTile>?   tiles                 Every piece of source art, in document order.
 * @property UmaRenderPages?  renderPages           The imported page images the drawables sample, absent when the
 *   pages derive from the tiles (UMA §5.5).
 * @property UmaThumbnail?    thumbnail             The document's thumbnail, absent when there is none.
 */
@Serializable
public data class UmaTextures(
	val storedUvsAddressPages: Boolean? = null,
	val composition: UmaComposition? = null,
	val pages: List<UmaPage>? = null,
	val tiles: List<UmaTile>? = null,
	val renderPages: UmaRenderPages? = null,
	val thumbnail: UmaThumbnail? = null,
)

/**
 * UMA §5.2: the policy the pages derive under.
 *
 * @property Int? alphaThreshold The minimum alpha (1 to 255) a pixel needs to count as opaque when a tile is
 *   trimmed; absent means 1.
 * @property Int? extrude        How many pixels of gutter around each tile carry its edge color, at alpha 0;
 *   absent means 2.
 */
@Serializable
public data class UmaComposition(
	val alphaThreshold: Int? = null,
	val extrude: Int? = null,
)

/**
 * UMA §5.3: one atlas page's size.
 *
 * @property Int width  The page width in pixels.
 * @property Int height The page height in pixels.
 */
@Serializable
public data class UmaPage(
	val width: Int,
	val height: Int,
)

/**
 * UMA §5.5: the page images a document's drawables sample while its pages are the ones it was imported with - a
 * CMO3's embedded images, a MOC3's texture files - and which image each drawable samples.
 *
 * @property List<UmaRenderPage> pages         The images, in the order the map indexes.
 * @property Map<String, Int>?   drawablePages Each sampling drawable's id, mapped to its image's index; absent when
 *   none samples one.
 */
@Serializable
public data class UmaRenderPages(
	val pages: List<UmaRenderPage>,
	val drawablePages: Map<String, Int>? = null,
)

/**
 * UMA §5.5: one render page image.
 *
 * @property Int    width  The image's width in pixels.
 * @property Int    height The image's height in pixels.
 * @property String path   The archive entry holding its pixels.
 */
@Serializable
public data class UmaRenderPage(
	val width: Int,
	val height: Int,
	val path: String,
)

/**
 * UMA §5.4: one tile - a piece of source art the atlas packs.
 *
 * @property String        id        The tile's document-local identity.
 * @property String        name      The tile's display name.
 * @property Int           width     The art's width in pixels.
 * @property Int           height    The art's height in pixels.
 * @property String?       path      The archive entry holding the tile's pixels; required in a file, assigned
 *   by the layout before a save.
 * @property UmaPlacement? placement Where the tile sits on its page, or absent when it was never packed.
 * @property UmaSourceRef? source    The source layer the art came from, or absent when the document retains
 *   no binding.
 * @property Boolean?      pinned    Whether a repack keeps the placement where it is; absent means false.
 * @property String?       replaces  The tile this one superseded when its layer was reloaded, or absent.
 */
@Serializable
public data class UmaTile(
	val id: String,
	val name: String,
	val width: Int,
	val height: Int,
	val path: String? = null,
	val placement: UmaPlacement? = null,
	val source: UmaSourceRef? = null,
	val pinned: Boolean? = null,
	val replaces: String? = null,
)

/**
 * UMA §5.4: where a tile's upright art sits on a page - the packer's transform as translation, scale, and
 * rotation, in page pixels with y down.
 *
 * @property Int   page            The page the art packs onto, indexing the entry's pages.
 * @property Float positionX       The packing origin's x on the page.
 * @property Float positionY       The packing origin's y on the page.
 * @property Float scaleX          The horizontal scale applied; negative mirrors.
 * @property Float scaleY          The vertical scale applied; negative mirrors.
 * @property Float rotationDegrees The rotation applied, counter-clockwise degrees.
 */
@Serializable
public data class UmaPlacement(
	val page: Int,
	val positionX: Float,
	val positionY: Float,
	val scaleX: Float,
	val scaleY: Float,
	val rotationDegrees: Float,
)

/**
 * UMA §5.4: a tile's link to the source layer its art came from.
 *
 * @property String  art       The source's id in the sources entry.
 * @property String  layerKey  The reader's key for the layer within that source.
 * @property Boolean stableKey Whether the key is a format-minted id that survives a rename or a reorder.
 */
@Serializable
public data class UmaSourceRef(
	val art: String,
	val layerKey: String,
	val stableKey: Boolean,
)

/**
 * UMA §5.6: the document's thumbnail.
 *
 * @property Int    width  The thumbnail's width in pixels.
 * @property Int    height The thumbnail's height in pixels.
 * @property String path   The archive entry holding its pixels.
 */
@Serializable
public data class UmaThumbnail(
	val width: Int,
	val height: Int,
	val path: String,
)

/**
 * The pixels a save writes for the textures entry (UMA §5.7): a PNG per tile the file does not already hold, the
 * render pages by mode, and the thumbnail.
 *
 * @property Function            tile        Yields a tile's PNG by tile id.  Called only for a tile the file does
 *   not hold yet, since a tile's pixels never change; null refuses the save.
 * @property UmaRenderPagePixels renderPages What the drawables sample.
 * @property ByteArray?          thumbnail   The thumbnail's PNG, or null for no thumbnail.
 */
public class UmaPixelSource(
	public val tile: (String) -> ByteArray?,
	public val renderPages: UmaRenderPagePixels,
	public val thumbnail: ByteArray?,
)

/** What a save records about the images a document's drawables sample (UMA §5.5). */
public sealed interface UmaRenderPagePixels {
	/** The pages derive from the tiles placed on them, so nothing is stored. */
	public data object Derived : UmaRenderPagePixels

	/**
	 * The document samples these imported images, replacing whatever render pages the file held.
	 *
	 * @property List<ByteArray>  pages         The PNG per image.
	 * @property Map<String, Int> drawablePages Each sampling drawable's id, mapped to its image's index in [pages].
	 */
	public class Stored(
		public val pages: List<ByteArray>,
		public val drawablePages: Map<String, Int>,
	) : UmaRenderPagePixels

	/** The file's own render pages are kept as they are, images and map both. */
	public data object Retained : UmaRenderPagePixels
}