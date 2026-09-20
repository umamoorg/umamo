package org.umamo.format.uma.sources

import kotlinx.serialization.Serializable

/*
 * The sources entry's schema (docs/format/UMA.md §6): the artwork files the document was imported from, each
 * with the layer inventory it had at its last read.  The source bytes themselves are never stored (UMA §6).
 */

/** UMA §6.4: the algorithm prefix every recorded hash carries in front of its lowercase hex digest. */
public const val UMA_SHA256_PREFIX: String = "sha256:"

/**
 * UMA §6.1: the sources entry's root.
 *
 * @property List<UmaSource>? sources The linked artwork files, in document order.
 */
@Serializable
public data class UmaSources(
	val sources: List<UmaSource>? = null,
)

/**
 * UMA §6.2: one linked artwork file.
 *
 * @property String                id           The document-local identity a tile's source reference names.
 * @property String                name         The file's display name.
 * @property String                format       The source format's file extension.
 * @property String?               path         The advisory external path, absent when unknown.
 * @property String?               contentHash  The whole-file hash as `sha256:<hex>`, absent when the document
 *   never read the file's bytes.
 * @property Long?                 lastModified The file's modification time at the last read, in epoch
 *   milliseconds, absent when unknown.
 * @property List<UmaSourceLayer>? layers       The layer inventory as of the last read, in the file's draw order.
 */
@Serializable
public data class UmaSource(
	val id: String,
	val name: String,
	val format: String,
	val path: String? = null,
	val contentHash: String? = null,
	val lastModified: Long? = null,
	val layers: List<UmaSourceLayer>? = null,
)

/**
 * UMA §6.3: one layer of a source's inventory.
 *
 * @property String   key         The reader's layer key, the string a tile's source reference carries.
 * @property String   name        The layer's name at the last read.
 * @property String   groupPath   The slash-joined enclosing folder path, empty at the root.
 * @property Int      left        The layer's canvas x in source pixels.
 * @property Int      top         The layer's canvas y in source pixels.
 * @property Int      width       The layer's raster width in source pixels.
 * @property Int      height      The layer's raster height in source pixels.
 * @property Boolean  visible     The layer's own eye toggle at the last read.
 * @property Boolean? present     Whether the file still had the layer at the last read; absent means true.
 * @property String?  contentHash The hash of the layer's pixels as `sha256:<hex>`, absent when never decoded.
 * @property Boolean? empty       Whether the layer had no pixel with any alpha; absent means false.
 * @property Boolean? replaced    Whether a missing row was lost to Replace Artwork; absent means false.
 * @property Boolean? ignored     Whether a reload leaves the layer out of the rig; absent means false.
 */
@Serializable
public data class UmaSourceLayer(
	val key: String,
	val name: String,
	val groupPath: String,
	val left: Int,
	val top: Int,
	val width: Int,
	val height: Int,
	val visible: Boolean,
	val present: Boolean? = null,
	val contentHash: String? = null,
	val empty: Boolean? = null,
	val replaced: Boolean? = null,
	val ignored: Boolean? = null,
)