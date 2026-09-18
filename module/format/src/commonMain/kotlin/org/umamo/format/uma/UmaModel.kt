package org.umamo.format.uma

import kotlinx.serialization.json.JsonObject
import org.umamo.format.binary.ZipEntry
import org.umamo.format.binary.ZipFormatException
import org.umamo.format.binary.ZipRecords
import org.umamo.format.binary.decodeZipPayload
import org.umamo.format.binary.inflateRawDeflate
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
 * back without inflating or recompressing it.  The payload stays where it is in the file's bytes rather than
 * being copied out, so a document holds its file once.
 *
 * @property ZipEntry  zipEntry The entry as the archive declared it; its payload offset locates the payload.
 * @property ByteArray archive  The whole file's bytes, which hold the payload.
 */
internal class UmaRawEntry(
	val zipEntry: ZipEntry,
	val archive: ByteArray,
) {
	/**
	 * The first [length] bytes of the payload as stored, or all of them when it is shorter.
	 *
	 * @param Int length The most bytes to take.
	 * @return ByteArray The bytes.
	 */
	fun storedPrefix(length: Int): ByteArray = archive.copyOfRange(zipEntry.payloadOffset, zipEntry.payloadOffset + minOf(zipEntry.compressedSize, length))
}

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
 * A document read from a file keeps that file's bytes for its life: its preserved entries and payloads are slices of
 * them, never copies, so the file is held once rather than twice.
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
	/** The puppet decode of the document this one derives from, when it is finished and still describes this one. */
	inheritedPuppet: Lazy<UmaPuppet?>? = null,
	/** The textures decode of the document this one derives from, when it is finished and still describes this one. */
	inheritedTextures: Lazy<UmaTextures?>? = null,
	/** The sources decode of the document this one derives from, when it is finished and still describes this one. */
	inheritedSources: Lazy<UmaSources?>? = null,
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

	/** Each payload by path, so a lookup per tile at open and at save stays constant-time however many payloads there are. */
	private val payloadByPath: Map<String, UmaPayload> by lazy { payloads.associateBy { payload -> payload.path } }

	/** The puppet entry's decode, run once per document; a document derived from this one reuses it while it holds. */
	private val puppetDecode: Lazy<UmaPuppet?> =
		inheritedPuppet ?: lazy {
			entries.firstOrNull { candidate -> candidate.liveKind == UmaEntryKind.Puppet }?.let { entry ->
				UmaPuppetEntry.decode((entry.content as UmaEntryContent.Live).tree, entry.path, ::bufferBytes)
			}
		}

	/** The textures entry's decode, run once per document; a document derived from this one reuses it while it holds. */
	private val texturesDecode: Lazy<UmaTextures?> =
		inheritedTextures ?: lazy {
			entries.firstOrNull { candidate -> candidate.liveKind == UmaEntryKind.Textures }?.let { entry ->
				UmaTexturesEntry.decode((entry.content as UmaEntryContent.Live).tree, entry.path, ::payloadHeader)
			}
		}

	/** The sources entry's decode, run once per document; a document derived from this one reuses it while it holds. */
	private val sourcesDecode: Lazy<UmaSources?> =
		inheritedSources ?: lazy {
			entries.firstOrNull { candidate -> candidate.liveKind == UmaEntryKind.Sources }?.let { entry ->
				UmaSourcesEntry.decode((entry.content as UmaEntryContent.Live).tree, entry.path)
			}
		}

	/** Whether the document holds a required entry this reader cannot interpret, which forbids saving it. */
	public val isReadOnly: Boolean
		get() = readOnlyReasons.isNotEmpty()

	/**
	 * Whether the document's entry of [kind] is one this reader cannot interpret (UMA §3.3).  Such an entry occupies
	 * its kind: a save carries it byte for byte and cannot set that kind's content, so an editor can hold back the
	 * edits that would need to.
	 *
	 * @param UmaEntryKind kind The entry kind.
	 * @return Boolean True when the kind's entry is carried rather than read.
	 */
	public fun holdsTooNewEntry(kind: UmaEntryKind): Boolean = entries.any { entry -> entry.liveKind == null && UmaEntryKind.ofWireName(entry.kind) == kind }

	/**
	 * The puppet entry's content (docs/format/UMA.md §4), or null when the document has no live puppet entry.
	 *
	 * Decoded once per model, and reused by a model derived from this one that keeps the entry; a model read from a file
	 * has already decoded it, so a malformed puppet entry fails the read rather than a later access.
	 */
	public val puppet: UmaPuppet? by puppetDecode

	/**
	 * The textures entry's content (docs/format/UMA.md §5), or null when the document has no live textures entry.
	 *
	 * Decoded once per model, and reused by a model derived from this one that keeps the entry; a model read from a file
	 * has already decoded it, so a malformed index, or one that names a pixel entry the archive does not hold, fails the
	 * read.
	 */
	public val textures: UmaTextures? by texturesDecode

	/**
	 * The sources entry's content (docs/format/UMA.md §6), or null when the document has no live sources entry.
	 *
	 * Decoded once per model, and reused by a model derived from this one that keeps the entry; a model read from a file
	 * has already decoded it.
	 */
	public val sources: UmaSources? by sourcesDecode

	/**
	 * This document with its puppet entry set to [puppet], laid over the entry's tree as read so every key
	 * this writer does not own survives (D10), or added when the document has no puppet entry.
	 *
	 * The puppet's buffer is rebuilt in the same step: every accessor in the merged tree - the new arrays and
	 * any a newer writer left under keys this one does not know - is laid out afresh in document order.
	 *
	 * @param UmaPuppet puppet The puppet.
	 * @return UmaModel The updated document.
	 * @throws UmaWriteException When the puppet holds a value the format cannot represent, or the document's puppet
	 *   entry is too new to replace.
	 */
	public fun withPuppet(puppet: UmaPuppet): UmaModel {
		val kind = UmaEntryKind.Puppet
		val path = entryPathOf(kind)
		val scratch = UmaScratchBuffer()
		val encoded = UmaPuppetEntry.encode(puppet, path, scratch)
		val merged = mergeRetainedTree(liveContent(kind), encoded, UmaPuppet.serializer().descriptor, UmaPuppetEntry.identities)
		val ownedPath = checkNotNull(kind.bufferPath)
		// The contract's exception, not check(): the caller's save flow catches UmaWriteException to report
		// a document it cannot save, and a foreign writer's manifest can list this path.
		if (entries.any { entry -> entry.path == ownedPath }) {
			throw UmaWriteException(ownedPath, "is listed in the manifest as an entry, so the puppet cannot own it as its buffer")
		}
		val scratchBytes = scratch.bytes()
		val layout = layOutBuffer(merged, ownedPath) { source -> if (source == UmaAccessor.SCRATCH_BUFFER) scratchBytes else bufferBytes(source) }
		return withLiveContent(kind, layout.tree as JsonObject).withOwnedPayloads(kind, mapOf(ownedPath to UmaOwnedPayload(kind, layout.buffer.takeIf { layout.hasAccessors })))
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
	 * @throws UmaWriteException When a new tile has no pixels, kept render pages are missing, the index breaks a rule
	 *   a reader would refuse, or the document's textures entry is too new to replace.
	 */
	public fun withTextures(textures: UmaTextures, pixels: UmaPixelSource): UmaModel {
		val kind = UmaEntryKind.Textures
		val path = entryPathOf(kind)
		val pathsInUse = HashSet<String>(archiveOrder)
		entries.mapTo(pathsInUse) { entry -> entry.path }
		pathsInUse += ownedPayloads.keys
		val layout = UmaTexturesEntry.layOut(textures, this.textures, pixels, path, pathsInUse, ::payloadHeader, ::payloadBytes)
		val merged = mergeRetainedTree(liveContent(kind), UmaTexturesEntry.encode(layout.textures, path), UmaTextures.serializer().descriptor, UmaTexturesEntry.identities)
		// UMA §5.7: pixel entries new to the file follow the index in index order - tiles, render pages, thumbnail -
		// whichever save wrote them, so a document saved twice before it is written orders them as one save does.
		val owned = LinkedHashMap<String, UmaOwnedPayload>()
		for (namedPath in UmaTexturesEntry.namedPaths(layout.textures)) {
			val bytes = layout.payloads[namedPath]
			val payload = if (bytes != null) UmaOwnedPayload(kind, bytes) else ownedPayloads[namedPath]?.takeIf { earlier -> earlier.owner == kind }
			payload?.let { laidOut -> owned[namedPath] = laidOut }
		}
		for ((droppedPath, bytes) in layout.payloads) {
			if (bytes == null) {
				owned[droppedPath] = UmaOwnedPayload(kind, null)
			}
		}
		return withLiveContent(kind, merged as JsonObject).withOwnedPayloads(kind, owned)
	}

	/**
	 * This document with its sources entry set to [sources], laid over the entry's tree as read so every key this
	 * writer does not own survives (D10), or added when the document has no sources entry.
	 *
	 * @param UmaSources sources The sources.
	 * @return UmaModel The updated document.
	 * @throws UmaWriteException When the sources hold a value a reader would refuse, or the document's sources entry
	 *   is too new to replace.
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
		val payload = payloadByPath[path] ?: return null
		return decodeVerified(payload)
	}

	/**
	 * The first bytes of the payload at [path], enough to read a PNG header, without decompressing more of it than
	 * that or keeping anything: a check of a pixel entry's size needs no more, and its bytes are verified whenever
	 * they are read.
	 *
	 * @param String path The payload's path.
	 * @return ByteArray? At most [PAYLOAD_HEADER_BYTES] bytes, or null when the document holds no such payload.
	 * @throws UmaFormatException When the payload is encrypted or compressed with a method this reader cannot inflate.
	 */
	internal fun payloadHeader(path: String): ByteArray? {
		ownedPayloads[path]?.let { owned -> return owned.bytes?.let { bytes -> bytes.copyOf(minOf(bytes.size, PAYLOAD_HEADER_BYTES)) } }
		val payload = payloadByPath[path] ?: return null
		val raw = payload.raw
		val zipEntry = raw.zipEntry
		if (!zipEntry.isEncrypted && zipEntry.method == ZipRecords.METHOD_STORED) {
			return raw.storedPrefix(PAYLOAD_HEADER_BYTES)
		}
		if (!zipEntry.isEncrypted && zipEntry.method == ZipRecords.METHOD_DEFLATED) {
			// Only the stream's first bytes are inflated: a deflate block's Huffman tables and 64 bytes of
			// output fit well inside the bound, and the inflater copies whatever it is handed before it starts,
			// so handing it the whole payload would copy a page-sized PNG to read its header.  A stream too
			// sparse for the bound (a run of empty blocks) inflates short and takes the full path instead.
			val bounded = inflateRawDeflate(raw.archive, zipEntry.payloadOffset, minOf(zipEntry.compressedSize, PAYLOAD_HEADER_SOURCE_BYTES), PAYLOAD_HEADER_BYTES)
			if (bounded.size >= minOf(PAYLOAD_HEADER_BYTES, zipEntry.uncompressedSize)) {
				return bounded
			}
		}
		// An entry this reader cannot inflate at all fails as reading its bytes does.
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
			decodeZipPayload(payload.raw.zipEntry, payload.raw.archive, payload.raw.zipEntry.payloadOffset)
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
	 * This document with [owned] laid over the payloads a save already owns, in [owned]'s order: the latest layout
	 * decides where a payload new to the file is written.
	 *
	 * @param UmaEntryKind kind  The kind whose save laid the payloads out.
	 * @param Map          owned The payloads, by path, in write order.
	 * @return UmaModel The updated document.
	 */
	private fun withOwnedPayloads(kind: UmaEntryKind, owned: Map<String, UmaOwnedPayload>): UmaModel =
		derived(writer, entries, ownedPayloads.filterKeys { path -> path !in owned } + owned, kind)

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
	 * @throws UmaWriteException When the kind is occupied by an entry too new to interpret, its default path is taken by
	 *   another entry, or the tree nests deeper than a reader accepts.
	 */
	internal fun withLiveContent(kind: UmaEntryKind, tree: JsonObject): UmaModel {
		// UMA §3.5: a reader refuses a tree nested past the limit, so a save that wrote one could never be reopened.
		if (jsonNestsTooDeep(tree)) {
			throw UmaWriteException(entryPathOf(kind), "the entry nests deeper than $UMA_MAXIMUM_JSON_DEPTH levels")
		}
		val existingIndex = entries.indexOfFirst { entry -> UmaEntryKind.ofWireName(entry.kind) == kind }
		if (existingIndex >= 0) {
			val existing = entries[existingIndex]
			// UMA §3.3: an entry too new to interpret occupies its kind; writing a second one beside it would
			// leave the file with two entries of one kind, and replacing it would lose what a newer writer put there.
			if (existing.content !is UmaEntryContent.Live) {
				throw UmaWriteException(existing.path, "the file's ${kind.wireName} entry is too new for this reader to replace")
			}
			val replaced = UmaEntry(existing.path, existing.kind, existing.version, existing.minVersion, existing.required, existing.record, UmaEntryContent.Live(kind, tree))
			return derived(writer, entries.toMutableList().also { list -> list[existingIndex] = replaced }, ownedPayloads, kind)
		}
		if (entries.any { entry -> entry.path == kind.defaultPath } || payloads.any { payload -> payload.path == kind.defaultPath }) {
			throw UmaWriteException(kind.defaultPath, "the path is already taken, so a new ${kind.wireName} entry has nowhere to go")
		}
		val added = UmaEntry(kind.defaultPath, kind.wireName, kind.version, kind.minVersion, kind.required, null, UmaEntryContent.Live(kind, tree))
		return derived(writer, entries + added, ownedPayloads, kind)
	}

	/**
	 * This document with its writer record replaced.
	 *
	 * @param UmaWriterInfo writer The application writing the file.
	 * @return UmaModel The updated document.
	 */
	public fun withWriter(writer: UmaWriterInfo): UmaModel = derived(writer, entries, ownedPayloads, null)

	/**
	 * A document over the given fields and this one's payloads, handed every finished decode of this one except the
	 * kind a save set afresh: an entry's decode depends only on its own tree and the payloads its kind owns, so every
	 * other kind's still describes the new document.
	 *
	 * @param UmaWriterInfo? writer        The writer record.
	 * @param List<UmaEntry> entries       The manifest-listed entries.
	 * @param Map            ownedPayloads The payloads a save owns.
	 * @param UmaEntryKind?  replaced      The kind whose entry or payloads the new document sets, or null.
	 * @return UmaModel The document.
	 */
	private fun derived(writer: UmaWriterInfo?, entries: List<UmaEntry>, ownedPayloads: Map<String, UmaOwnedPayload>, replaced: UmaEntryKind?): UmaModel =
		UmaModel(
			writer,
			entries,
			payloads,
			readOnlyReasons,
			manifestTree,
			archiveOrder,
			ownedPayloads,
			inheritedPuppet = puppetDecode.takeIf { decode -> replaced != UmaEntryKind.Puppet && decode.isInitialized() },
			inheritedTextures = texturesDecode.takeIf { decode -> replaced != UmaEntryKind.Textures && decode.isInitialized() },
			inheritedSources = sourcesDecode.takeIf { decode -> replaced != UmaEntryKind.Sources && decode.isInitialized() },
		)

	public companion object {
		/** How many leading bytes [payloadHeader] returns: a PNG signature and its IHDR chunk's header fields. */
		private const val PAYLOAD_HEADER_BYTES = 64

		/**
		 * How much of a deflated payload the header probe hands the inflater: enough for a block's
		 * Huffman tables plus [PAYLOAD_HEADER_BYTES] of literals many times over, and nothing like a page.
		 */
		private const val PAYLOAD_HEADER_SOURCE_BYTES = 4096

		/**
		 * An empty document with no entries, written by [writer].
		 *
		 * @param UmaWriterInfo writer The application creating the document.
		 * @return UmaModel The document.
		 */
		public fun create(writer: UmaWriterInfo): UmaModel = UmaModel(writer, emptyList(), emptyList(), emptyList(), null, emptyList())
	}
}