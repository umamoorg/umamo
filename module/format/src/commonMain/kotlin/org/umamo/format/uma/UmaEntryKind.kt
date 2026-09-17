package org.umamo.format.uma

/**
 * The domain entry kinds this reader understands, each with the schema version it reads and writes
 * (docs/format/UMA.md §3.2).
 *
 * The wire name is the stable identifier a manifest record's `kind` carries and the one thing a reader
 * dispatches on; the enum constant's own name never reaches a file, so renaming one changes nothing on
 * disk.  An entry whose kind is not listed here, or whose `minVersion` is above [version], is preserved
 * byte for byte rather than read.
 *
 * @property String  wireName    The manifest `kind` value.
 * @property String  defaultPath Where a writer puts a new entry of this kind.  A read entry keeps the
 *   path its file gave it.
 * @property Int     version     The schema version this reader understands and this writer emits.
 * @property Int     minVersion  The `minVersion` this writer declares: the oldest schema version a reader
 *   may treat the entry as.
 * @property Boolean required    Whether understanding the entry is necessary to interpret or edit the
 *   document at all.
 * @property String? bufferPath  The buffer entry this kind owns for its bulk arrays, or null when it has none
 *   (D19: an entry's accessors name only buffers it owns, and no two entries share one).
 */
public enum class UmaEntryKind(
	public val wireName: String,
	public val defaultPath: String,
	public val version: Int,
	public val minVersion: Int,
	public val required: Boolean,
	public val bufferPath: String? = null,
) {
	// UMA §3.2: the base puppet graph.
	Puppet("puppet", "model/puppet.json", 1, 1, true, "model/buffers.bin"),

	// UMA §3.2: the atlas and the pixel entries it names.
	Textures("textures", "textures/index.json", 1, 1, true),

	// UMA §3.2: the linked source art and its layer inventories.
	Sources("sources", "source/index.json", 1, 1, false),

	// UMA §3.2: document-scoped editor state, which is never load-bearing and so never required.
	Editor("editor", "editor/state.json", 1, 1, false),
	;

	public companion object {
		/**
		 * The kind a manifest record's `kind` names, or null when this reader does not know it.
		 *
		 * @param String wireName The manifest `kind` value.
		 * @return UmaEntryKind? The kind, or null.
		 */
		public fun ofWireName(wireName: String): UmaEntryKind? = entries.firstOrNull { kind -> kind.wireName == wireName }
	}
}

/** The container-level constants every UMA archive shares (docs/format/UMA.md §2, §3). */
internal object UmaContainer {
	// UMA §2: the mimetype entry's name and exact content.
	const val MIMETYPE_PATH = "mimetype"
	const val MIMETYPE = "application/vnd.umamo.uma+zip"

	// UMA §3: the manifest's path and the value its `format` key holds.
	const val MANIFEST_PATH = "manifest.json"
	const val FORMAT_NAME = "uma"

	// UMA §3.1: the one container version this reader understands and this writer emits.
	const val CONTAINER_VERSION = 1

	/** The mimetype entry's content as bytes. */
	val MIMETYPE_BYTES: ByteArray = MIMETYPE.encodeToByteArray()

	/**
	 * Whether [path] is one of the bootstrap entries no manifest record may name.
	 *
	 * @param String path An entry path.
	 * @return Boolean True for `mimetype` or `manifest.json`.
	 */
	fun isReservedPath(path: String): Boolean = path == MIMETYPE_PATH || path == MANIFEST_PATH
}