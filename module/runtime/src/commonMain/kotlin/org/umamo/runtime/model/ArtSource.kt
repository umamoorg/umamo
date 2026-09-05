package org.umamo.runtime.model

/*
 * Where a document's art came from: the artwork files it was imported from, each with the layer
 * inventory it had at that import, and the per-tile link back to one of those layers.
 *
 * Model state because re-import is a model operation: the reconcile diffs a re-read file against
 * the inventory recorded here and rebinds tiles through the refs, so both have to survive undo, a
 * document swap, and the native format.  Pixels are deliberately absent - a tile's raster belongs to
 * the document, and the source file itself is linked, never embedded.
 */

/**
 * One artwork file the document was imported from, and the layers it had when it was last read.
 *
 * The inventory is the re-import baseline: a later read of the same file is diffed against it to
 * classify each layer as matched, added, removed, or renamed.  It records identity and layout only,
 * never pixels, so a document with hundreds of layers carries it for free.
 *
 * @property ArtSourceId          id     The document-local identity every [SourceLayerRef] points at.
 * @property String               name   The file's display name (its file name at import).
 * @property String?              path   The advisory external path the file was read from, or null when
 *   unknown or platform-volatile; a missing file only stalls re-import, never the rig.
 * @property String               format The source format's file extension ("psd", "clip", "kra", or a
 *   flat raster's), recorded so a listing can say what a file is without re-reading it.
 * @property List<ArtSourceLayer> layers The layer inventory as of the last import, in the file's own
 *   draw order (top-most first); empty for a source whose inventory was never walked.
 */
data class ArtSource(
	val id: ArtSourceId,
	val name: String,
	val path: String?,
	val format: String,
	val layers: List<ArtSourceLayer> = emptyList(),
)

/**
 * One layer of an [ArtSource]'s inventory: what the reader knew about it at import, without pixels.
 *
 * Skipped layers (non-raster, empty) are listed too, so a re-import can tell "this layer was there
 * and unusable" from "this layer is new".
 *
 * @property String  key       The reader's layer key - the same string a [SourceLayerRef] carries.
 * @property String  name      The layer's name at import.
 * @property String  groupPath The slash-joined enclosing-folder path at import ("" at the root).
 * @property Int     left      The layer's canvas position, top-left origin in source pixels.
 * @property Int     top       See [left].
 * @property Int     width     The layer's raster width in source pixels.
 * @property Int     height    The layer's raster height in source pixels.
 * @property Boolean visible   The layer's own eye toggle at import.
 */
data class ArtSourceLayer(
	val key: String,
	val name: String,
	val groupPath: String,
	val left: Int,
	val top: Int,
	val width: Int,
	val height: Int,
	val visible: Boolean,
)

/**
 * A tile's link to the source layer its art came from: which file, and which layer within it.
 *
 * The key is whatever the reader minted for the layer and is opaque here: a CLIP layer uuid, a Krita
 * layer uuid, a PSD lyid, or a PSD name-plus-order fallback.  [stableKey] says which of those it is
 * in the one way that matters - whether the key survives a rename or reorder in the art program - so
 * the reconcile knows when to trust it outright and a listing knows what to show.
 *
 * @property ArtSourceId sourceId  The [ArtSource] the layer belongs to.
 * @property String      layerKey  The reader's key for the layer within that source.
 * @property Boolean     stableKey Whether the key is a format-minted id (true) or a name-and-order
 *   fallback that only holds as long as the artist's layer organisation does (false).
 */
data class SourceLayerRef(
	val sourceId: ArtSourceId,
	val layerKey: String,
	val stableKey: Boolean,
)