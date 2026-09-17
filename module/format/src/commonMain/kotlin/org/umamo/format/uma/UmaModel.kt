package org.umamo.format.uma

import kotlinx.serialization.json.JsonObject
import org.umamo.format.binary.ZipEntry
import org.umamo.format.binary.ZipFormatException
import org.umamo.format.binary.ZipRecords
import org.umamo.format.binary.decodeZipPayload
import org.umamo.format.uma.puppet.UmaPuppet
import org.umamo.format.uma.puppet.UmaPuppetEntry
import org.umamo.format.uma.sources.UmaSources
import org.umamo.format.uma.sources.UmaSourcesEntry
import org.umamo.format.uma.textures.UmaPixelSource
import org.umamo.format.uma.textures.UmaTextures
import org.umamo.format.uma.textures.UmaTexturesEntry

/**
 * The application that last wrote a UMA file, as its manifest's `writer` record says.
 *
 * @property String app     The application's name.
 * @property String version The application's version.
 */
public data class UmaWriterInfo(
	val app: String,
	val version: String,
)

/** Why a manifest-listed entry makes the document read-only (docs/format/UMA.md §3.3). */
public sealed interface UmaReadOnlyCause {
	/** The entry's kind is one this reader does not know. */
	public data object UnknownKind : UmaReadOnlyCause

	/**
	 * The entry's kind is known, but its `minVersion` says a reader of this version would misread it.
	 *
	 * @property Int minVersion The oldest schema version the file allows a reader to treat it as.
	 * @property Int supported  The schema version this reader understands.
	 */
	public data class UnsupportedVersion(val minVersion: Int, val supported: Int) : UmaReadOnlyCause
}

/**
 * One required entry this reader cannot interpret, which keeps the document from being edited or saved.
 *
 * @property String           path  The entry's path.
 * @property String           kind  The entry's manifest `kind`.
 * @property UmaReadOnlyCause cause Why this reader cannot interpret it.
 */
public data class UmaReadOnlyReason(
	val path: String,
	val kind: String,
	val cause: UmaReadOnlyCause,
)

/**
 * An archive entry kept exactly as it was read: its ZIP metadata and its payload as stored, for writing
 * back without inflating or recompressing it.
 *
 * @property ZipEntry  zipEntry   The entry as the archive declared it.
 * @property ByteArray rawPayload The payload exactly as stored.
 */
internal class UmaRawEntry(
	val zipEntry: ZipEntry,
	val rawPayload: ByteArray,
)

/** What a manifest-listed entry holds in memory. */
internal sealed interface UmaEntryContent {
	/**
	 * An entry this reader understands, held as its parsed JSON tree so unknown keys ride along (D10).
	 *
	 * @property UmaEntryKind kind The entry's kind.
	 * @property JsonObject   tree The entry's JSON.
	 */
	class Live(val kind: UmaEntryKind, val tree: JsonObject) : UmaEntryContent

	/**
	 * An entry this reader does not understand, carried byte for byte.
	 *
	 * @property UmaRawEntry raw The entry as read.
	 */
	class Preserved(val raw: UmaRawEntry) : UmaEntryContent
}

/**
 * One manifest-listed domain entry.
 *
 * @property String  path       Where the entry lives in the archive.
 * @property String  kind       The manifest `kind` value.
 * @property Int     version    The schema version the file declares.
 * @property Int     minVersion The oldest schema version the file allows a reader to treat it as.
 * @property Boolean required   Whether the file declares the entry necessary to interpret the document.
 */
public class UmaEntry internal constructor(
	public val path: String,
	public val kind: String,
	public val version: Int,
	public val minVersion: Int,
	public val required: Boolean,
	/** The manifest record as read, whose unknown keys the writer keeps in place; null for a new entry. */
	internal val record: JsonObject?,
	internal val content: UmaEntryContent,
) {
	/** The kind this reader interprets the entry as, or null when the entry is carried byte for byte. */
	public val liveKind: UmaEntryKind?
		get() = (content as? UmaEntryContent.Live)?.kind
}

/**
 * A payload an entry owns that a save rebuilt or dropped (UMA §3.3): a puppet's buffer (§4.9), a textures
 * entry's pixel entries (§5.6).
 *
 * @property UmaEntryKind owner The entry kind that owns the payload, which a new payload is written after.
 * @property ByteArray?   bytes The bytes to write stored, or null when the payload leaves the file.
 */
internal class UmaOwnedPayload(
	val owner: UmaEntryKind,
	val bytes: ByteArray?,
)

/**
 * An archive entry no manifest record names - a texture, a buffer, a thumbnail, or anything a newer
 * writer added - carried byte for byte.
 *
 * @property String path Where the entry lives in the archive.
 */
public class UmaPayload internal constructor(
	public val path: String,
	internal val raw: UmaRawEntry,
)

/**
 * A UMA document at the container level: the manifest's entries, each either understood or preserved,
 * and every other archive entry preserved (docs/format/UMA.md §3).
 *
 * The domain contents stay raw JSON trees here; the typed schemas are layered on per entry kind.
 * Nothing this model did not create is ever dropped: preserved entries and payloads are written back
 * exactly as read, and an entry this reader cannot interpret that the file marks required makes the
 * document read-only instead of editable.
 *
 * @property UmaWriterInfo?          writer          The application that last wrote the file, or null.
 * @property List<UmaEntry>          entries         The manifest-listed entries, in manifest order.
 * @property List<UmaPayload>        payloads        Every other archive entry, in archive order.
 * @property List<UmaReadOnlyReason> readOnlyReasons Each required entry this reader cannot interpret.
 */
public class UmaModel internal constructor(
	public val writer: UmaWriterInfo?,
	public val entries: List<UmaEntry>,
	public val payloads: List<UmaPayload>,
	public val readOnlyReasons: List<UmaReadOnlyReason>,
	/** The manifest as read, whose unknown keys the writer keeps in place; null for a new document. */
	internal val manifestTree: JsonObject?,
	/** Every non-bootstrap entry path in the order the archive held them; empty for a new document. */
	internal val archiveOrder: List<String>,
	/**
	 * The payloads an entry owns that a save rebuilt or dropped, by path, in write order (UMA §3.3): each written
	 * stored in place of the payload read, or left out of the file when its bytes are null.
	 */
	internal val ownedPayloads: Map<String, UmaOwnedPayload> = emptyMap(),
) {
	/**
	 * Each buffer payload's decompressed bytes by path, decompressed on first use to resolve accessors.  Held as
	 * lazy values so a document read from several threads decompresses each buffer once and safely.  Pixel
	 * entries never come through here: holding every tile's bytes a second time for the model's life would
	 * double an open's memory.
	 */
	private val decodedPayloads: Map<String, Lazy<ByteArray>> by lazy {
		payloads.associate { payload ->
			payload.path to
				lazy { decodeVerified(payload) }
		}
	}

	/** Whether the document holds a required entry this reader cannot interpret, which forbids saving it. */
	public val isReadOnly: Boolean
		get() = readOnlyReasons.isNotEmpty()

	/**
	 * The puppet entry's content (docs/format/UMA.md §4), or null when the document has no live puppet entry.
	 *
	 * Decoded once per model; a model read from a file has already decoded it, so a malformed puppet entry
	 * fails the read rather than a later access.
	 */
	public val puppet: UmaPuppet? by lazy {
		val entry = entries.firstOrNull { candidate -> candidate.liveKind == UmaEntryKind.Puppet } ?: return@lazy null
		UmaPuppetEntry.decode((entry.content as UmaEntryContent.Live).tree, entry.path, ::bufferBytes)
	}

	/**
	 * The textures entry's content (docs/format/UMA.md §5), or null when the document has no live textures entry.
	 *
	 * Decoded once per model; a model read from a file has already decoded it, so a malformed index, or one that
	 * names a pixel entry the archive does not hold, fails the read.
	 */
	public val textures: UmaTextures? by lazy {
		val entry = entries.firstOrNull { candidate -> candidate.liveKind == UmaEntryKind.Textures } ?: return@lazy null
		UmaTexturesEntry.decode((entry.content as UmaEntryContent.Live).tree, entry.path, ::payloadHeader)
	}

	/**
	 * The sources entry's content (docs/format/UMA.md §6), or null when the document has no live sources entry.
	 *
	 * Decoded once per model; a model read from a file has already decoded it.
	 */
	public val sources: UmaSources? by lazy {
		val entry = entries.firstOrNull { candidate -> candidate.liveKind == UmaEntryKind.Sources } ?: return@lazy null
		UmaSourcesEntry.decode((entry.content as UmaEntryContent.Live).tree, entry.path)
	}

	/**
	 * This document with its puppet entry set to [puppet], laid over the entry's tree as read so every key
	 * this writer does not own survives (D10), or added when the document has no puppet entry.
	 *
	 * The puppet's buffer is rebuilt in the same step: every accessor in the merged tree - the new arrays and
	 * any a newer writer left under keys this one does not know - is laid out afresh in document order.
	 *
	 * @param UmaPuppet puppet The puppet.
	 * @return UmaModel The updated document.
	 * @throws UmaWriteException When the puppet holds a value the format cannot represent.
	 * @throws IllegalStateException When the document's puppet entry is too new to interpret.
	 */
	public fun withPuppet(puppet: UmaPuppet): UmaModel {
		val kind = UmaEntryKind.Puppet
		val path = entryPathOf(kind)
		val scratch = UmaScratchBuffer()
		val encoded = UmaPuppetEntry.encode(puppet, path, scratch)
		val merged = mergeRetainedTree(liveContent(kind), encoded, UmaPuppet.serializer().descriptor, UmaPuppetEntry.identities)
		val ownedPath = checkNotNull(kind.bufferPath)
		check(entries.none { entry -> entry.path == ownedPath }) { "'$ownedPath' is a manifest-listed entry, so the puppet cannot own it as its buffer" }
		val scratchBytes = scratch.bytes()
		val layout = layOutBuffer(merged, ownedPath) { source -> if (source == UmaAccessor.SCRATCH_BUFFER) scratchBytes else bufferBytes(source) }
		return withLiveContent(kind, layout.tree as JsonObject).withOwnedPayloads(mapOf(ownedPath to UmaOwnedPayload(kind, layout.buffer.takeIf { layout.hasAccessors })))
	}

	/**
	 * This document with its textures entry set to [textures], laid over the entry's tree as read so every key
	 * this writer does not own survives (D10), or added when the document has no textures entry.
	 *
	 * The pixel entries are laid out in the same step (UMA §5.7): a tile the file holds keeps its entry byte for
	 * byte and [pixels] is not asked for it, a new tile's PNG is written at a minted path, the render pages follow
	 * [pixels]'s mode, the thumbnail is written when given, and every pixel entry the index no longer names leaves
	 * the file (D21).  The paths and render pages [textures] carries are ignored.
	 *
	 * @param UmaTextures    textures The index.
	 * @param UmaPixelSource pixels   The pixels the save writes.
	 * @return UmaModel The updated document.
	 * @throws UmaWriteException When a new tile has no pixels, kept render pages are missing, or the index breaks a
	 *   rule a reader would refuse.
	 * @throws IllegalStateException When the document's textures entry is too new to interpret.
	 */
	public fun withTextures(textures: UmaTextures, pixels: UmaPixelSource): UmaModel {
		val kind = UmaEntryKind.Textures
		val path = entryPathOf(kind)
		val pathsInUse = HashSet<String>(archiveOrder)
		entries.mapTo(pathsInUse) { entry -> entry.path }
		pathsInUse += ownedPayloads.keys
		val layout = UmaTexturesEntry.layOut(textures, this.textures, pixels, path, pathsInUse, ::payloadHeader)
		val merged = mergeRetainedTree(liveContent(kind), UmaTexturesEntry.encode(layout.textures), UmaTextures.serializer().descriptor, UmaTexturesEntry.identities)
		return withLiveContent(kind, merged as JsonObject).withOwnedPayloads(layout.payloads.mapValues { (_, bytes) -> UmaOwnedPayload(kind, bytes) })
	}

	/**
	 * This document with its sources entry set to [sources], laid over the entry's tree as read so every key this
	 * writer does not own survives (D10), or added when the document has no sources entry.
	 *
	 * @param UmaSources sources The sources.
	 * @return UmaModel The updated document.
	 * @throws UmaWriteException When the sources hold a value a reader would refuse.
	 * @throws IllegalStateException When the document's sources entry is too new to interpret.
	 */
	public fun withSources(sources: UmaSources): UmaModel {
		val kind = UmaEntryKind.Sources
		val encoded = UmaSourcesEntry.encode(sources, entryPathOf(kind))
		val merged = mergeRetainedTree(liveContent(kind), encoded, UmaSources.serializer().descriptor, UmaSourcesEntry.identities)
		return withLiveContent(kind, merged as JsonObject)
	}

	/**
	 * The bytes of the payload at [path] - a tile's or a page's PNG, say - as the next save would write them:
	 * the bytes a save laid out, else the archive's payload decompressed and verified afresh on every call.
	 *
	 * @param String path The payload's path.
	 * @return ByteArray? The bytes, or null when the document holds no such payload.
	 * @throws UmaFormatException When the payload's bytes fail their size or CRC-32 check.
	 */
	public fun payloadBytes(path: String): ByteArray? {
		ownedPayloads[path]?.let { owned -> return owned.bytes }
		val payload = payloads.firstOrNull { candidate -> candidate.path == path } ?: return null
		return decodeVerified(payload)
	}

	/**
	 * The first bytes of the payload at [path], enough to read a PNG header, without decompressing a stored
	 * payload or keeping anything: a check of a pixel entry's size needs no more, and its bytes are verified
	 * whenever they are read.
	 *
	 * @param String path The payload's path.
	 * @return ByteArray? At most [PAYLOAD_HEADER_BYTES] bytes, or null when the document holds no such payload.
	 * @throws UmaFormatException When a compressed payload's bytes fail their size or CRC-32 check.
	 */
	internal fun payloadHeader(path: String): ByteArray? {
		ownedPayloads[path]?.let { owned -> return owned.bytes?.let { bytes -> bytes.copyOf(minOf(bytes.size, PAYLOAD_HEADER_BYTES)) } }
		val payload = payloads.firstOrNull { candidate -> candidate.path == path } ?: return null
		val zipEntry = payload.raw.zipEntry
		if (zipEntry.method == ZipRecords.METHOD_STORED && !zipEntry.isEncrypted) {
			val raw = payload.raw.rawPayload
			return raw.copyOf(minOf(raw.size, PAYLOAD_HEADER_BYTES))
		}
		val bytes = decodeVerified(payload)
		return bytes.copyOf(minOf(bytes.size, PAYLOAD_HEADER_BYTES))
	}

	/**
	 * The bytes of the buffer at [path]: a buffer a save rebuilt, else an archive payload decompressed on first
	 * use and kept.
	 *
	 * @param String path The buffer's path.
	 * @return ByteArray? The bytes, or null when the document holds no such buffer.
	 * @throws UmaFormatException When the payload's bytes fail their size or CRC-32 check.
	 */
	internal fun bufferBytes(path: String): ByteArray? {
		ownedPayloads[path]?.let { owned -> return owned.bytes }
		return decodedPayloads[path]?.value
	}

	/**
	 * A payload's bytes, decompressed and checked against the sizes and CRC-32 its entry declares.
	 *
	 * @param UmaPayload payload The payload.
	 * @return ByteArray The bytes.
	 * @throws UmaFormatException When they fail the check.
	 */
	private fun decodeVerified(payload: UmaPayload): ByteArray =
		try {
			decodeZipPayload(payload.raw.zipEntry, payload.raw.rawPayload, 0)
		} catch (failure: ZipFormatException) {
			throw UmaFormatException(UmaReadFailure.CorruptContainer(failure.message.orEmpty()), failure)
		}

	/**
	 * The path of this document's entry of [kind], or the kind's default path when it has none.
	 *
	 * @param UmaEntryKind kind The entry kind.
	 * @return String The path.
	 */
	private fun entryPathOf(kind: UmaEntryKind): String = entries.firstOrNull { entry -> UmaEntryKind.ofWireName(entry.kind) == kind }?.path ?: kind.defaultPath

	/**
	 * This document with [owned] laid over the payloads a save already owns; a path already owned keeps its place
	 * in the write order.
	 *
	 * @param Map owned The payloads, by path.
	 * @return UmaModel The updated document.
	 */
	private fun withOwnedPayloads(owned: Map<String, UmaOwnedPayload>): UmaModel =
		UmaModel(writer, entries, payloads, readOnlyReasons, manifestTree, archiveOrder, ownedPayloads + owned)

	/**
	 * The live JSON tree of [kind], or null when the document has no live entry of that kind.
	 *
	 * @param UmaEntryKind kind The entry kind.
	 * @return JsonObject? The entry's tree.
	 */
	internal fun liveContent(kind: UmaEntryKind): JsonObject? =
		entries.firstNotNullOfOrNull { entry -> (entry.content as? UmaEntryContent.Live)?.takeIf { live -> live.kind == kind }?.tree }

	/**
	 * This document with [kind]'s live content replaced by [tree], or added at the kind's default path when
	 * the document has none.
	 *
	 * @param UmaEntryKind kind The entry kind.
	 * @param JsonObject   tree The entry's new JSON.
	 * @return UmaModel The updated document.
	 * @throws IllegalStateException When the kind is occupied by an entry too new to interpret, or its
	 *   default path is taken by another entry.
	 */
	internal fun withLiveContent(kind: UmaEntryKind, tree: JsonObject): UmaModel {
		val existingIndex = entries.indexOfFirst { entry -> UmaEntryKind.ofWireName(entry.kind) == kind }
		if (existingIndex >= 0) {
			val existing = entries[existingIndex]
			// UMA §3.3: an entry too new to interpret occupies its kind; writing a second one beside it would
			// leave the file with two entries of one kind.
			check(existing.content is UmaEntryContent.Live) {
				"'${existing.path}' holds a ${kind.wireName} entry this reader cannot interpret, so it cannot be replaced"
			}
			val replaced = UmaEntry(existing.path, existing.kind, existing.version, existing.minVersion, existing.required, existing.record, UmaEntryContent.Live(kind, tree))
			return copy(entries = entries.toMutableList().also { list -> list[existingIndex] = replaced })
		}
		check(entries.none { entry -> entry.path == kind.defaultPath } && payloads.none { payload -> payload.path == kind.defaultPath }) {
			"'${kind.defaultPath}' is already taken, so a new ${kind.wireName} entry has nowhere to go"
		}
		val added = UmaEntry(kind.defaultPath, kind.wireName, kind.version, kind.minVersion, kind.required, null, UmaEntryContent.Live(kind, tree))
		return copy(entries = entries + added)
	}

	/**
	 * This document with its writer record replaced.
	 *
	 * @param UmaWriterInfo writer The application writing the file.
	 * @return UmaModel The updated document.
	 */
	public fun withWriter(writer: UmaWriterInfo): UmaModel = copy(writer = writer)

	/**
	 * A copy with the given fields replaced.
	 *
	 * @param UmaWriterInfo?  writer   The writer record.
	 * @param List<UmaEntry>  entries  The manifest-listed entries.
	 * @return UmaModel The copy.
	 */
	private fun copy(writer: UmaWriterInfo? = this.writer, entries: List<UmaEntry> = this.entries): UmaModel =
		UmaModel(writer, entries, payloads, readOnlyReasons, manifestTree, archiveOrder, ownedPayloads)

	public companion object {
		/** How many leading bytes [payloadHeader] returns: a PNG signature and its IHDR chunk's header fields. */
		private const val PAYLOAD_HEADER_BYTES = 64

		/**
		 * An empty document with no entries, written by [writer].
		 *
		 * @param UmaWriterInfo writer The application creating the document.
		 * @return UmaModel The document.
		 */
		public fun create(writer: UmaWriterInfo): UmaModel = UmaModel(writer, emptyList(), emptyList(), emptyList(), null, emptyList())
	}
}