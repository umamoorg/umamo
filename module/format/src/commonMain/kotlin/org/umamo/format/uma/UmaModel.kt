package org.umamo.format.uma

import kotlinx.serialization.json.JsonObject
import org.umamo.format.binary.ZipEntry
import org.umamo.format.uma.puppet.UmaPuppet
import org.umamo.format.uma.puppet.UmaPuppetEntry

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
) {
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
		UmaPuppetEntry.decode((entry.content as UmaEntryContent.Live).tree, entry.path)
	}

	/**
	 * This document with its puppet entry set to [puppet], laid over the entry's tree as read so every key
	 * this writer does not own survives (D10), or added when the document has no puppet entry.
	 *
	 * @param UmaPuppet puppet The puppet.
	 * @return UmaModel The updated document.
	 * @throws UmaWriteException When the puppet holds a value the format cannot represent.
	 * @throws IllegalStateException When the document's puppet entry is too new to interpret.
	 */
	public fun withPuppet(puppet: UmaPuppet): UmaModel {
		val path = entries.firstOrNull { entry -> UmaEntryKind.ofWireName(entry.kind) == UmaEntryKind.Puppet }?.path ?: UmaEntryKind.Puppet.defaultPath
		val encoded = UmaPuppetEntry.encode(puppet, path)
		val merged = mergeRetainedTree(liveContent(UmaEntryKind.Puppet), encoded, UmaPuppet.serializer().descriptor, UmaPuppetEntry.identities)
		return withLiveContent(UmaEntryKind.Puppet, merged as JsonObject)
	}

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
		UmaModel(writer, entries, payloads, readOnlyReasons, manifestTree, archiveOrder)

	public companion object {
		/**
		 * An empty document with no entries, written by [writer].
		 *
		 * @param UmaWriterInfo writer The application creating the document.
		 * @return UmaModel The document.
		 */
		public fun create(writer: UmaWriterInfo): UmaModel = UmaModel(writer, emptyList(), emptyList(), emptyList(), null, emptyList())
	}
}