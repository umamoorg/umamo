package org.umamo.runtime.model

/*
 * The document's atlas: the pages it packs onto and the tiles packed onto them.
 *
 * This is the source-to-atlas indirection made explicit.  A drawable's uvs address a page, but what
 * they MEAN is a position in its source art, and the placement is the only thing that connects the
 * two - so a repack that moves pixels around a page must move uvs with them and leave the art mapping
 * untouched.  Keeping the placement as model state is what lets a repack be an undoable edit and lets
 * an export write where the art actually went, rather than both guessing from uvs.
 */

/**
 * One packed page's dimensions.
 *
 * Pixels are not model state - a page's bytes belong to the document, and hundreds of undo snapshots
 * holding them would be ruinous - but the SIZE is, because a placement in page pixels means nothing
 * without it and every uv derivation needs it.
 *
 * @property Int width  The page width in pixels.
 * @property Int height The page height in pixels.
 */
data class AtlasPage(
	val width: Int,
	val height: Int,
)

/**
 * One piece of source art the atlas packs: its identity, its size, and where it landed.
 *
 * A tile is per ARTWORK, not per drawable - several drawables can sample one piece of art, and they all
 * move together when it is repacked, which is why the placement lives here and not on the drawable.
 *
 * A null [placement] means the art is in the document but was never packed onto a page; drawables
 * bound to it sample it directly and their uvs already address it.
 *
 * @property AtlasTileId     id              The tile's stable document-local identity.
 * @property String          name            The tile's display name.
 * @property Int             width           The art's width in pixels.
 * @property Int             height          The art's height in pixels.
 * @property AtlasPlacement? placement       Where it sits on its page, or null when it was never packed.
 * @property String?         sourceLayerName The originating artwork layer's name when exactly one
 *   composites into this tile, else null.  Advisory provenance only - the real source binding is a
 *   later phase's, and nothing reads this yet.
 */
data class AtlasTile(
	val id: AtlasTileId,
	val name: String,
	val width: Int,
	val height: Int,
	val placement: AtlasPlacement? = null,
	val sourceLayerName: String? = null,
)

/**
 * A document's whole atlas: its pages and the tiles packed onto them.
 *
 * Empty for a document with no source art at all - a MOC3-origin rig, whose pages are the packed
 * endpoint of someone else's pipeline and whose tiles were never separable.
 *
 * @property List<AtlasPage> pages The packed pages, in the document's own page order.
 * @property List<AtlasTile> tiles Every piece of source art, in document order.
 */
data class PuppetAtlas(
	val pages: List<AtlasPage> = emptyList(),
	val tiles: List<AtlasTile> = emptyList(),
) {
	/** True when the document carries no source art at all. */
	val isEmpty: Boolean
		get() = pages.isEmpty() && tiles.isEmpty()

	/** Tiles by id, for the per-drawable resolution every uv derivation starts from. */
	val tileById: Map<AtlasTileId, AtlasTile> by lazy { tiles.associateBy { tile -> tile.id } }

	companion object {
		/** The atlas of a document with no source art. */
		val Empty: PuppetAtlas = PuppetAtlas()
	}
}