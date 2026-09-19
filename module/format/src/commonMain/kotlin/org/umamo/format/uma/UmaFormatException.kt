package org.umamo.format.uma

/**
 * Why a file could not be opened as UMA at all (docs/format/UMA.md §3.4).  A file that opens but cannot
 * be edited is not a failure; see [UmaModel.readOnlyReasons].
 */
public sealed interface UmaReadFailure {
	/** A human-readable account of the failure, for logs. */
	public val description: String

	/**
	 * The file is not a UMA document: it has no `mimetype` entry, the wrong one, or a manifest whose
	 * `format` is not `uma`.
	 *
	 * @property String detail What identified it as foreign.
	 */
	public data class NotUma(val detail: String) : UmaReadFailure {
		override val description: String get() = "not a UMA document: $detail"
	}

	/**
	 * The container version is newer than this reader understands, so nothing in the file can be trusted
	 * to mean what this reader thinks.
	 *
	 * @property Int found     The file's container version.
	 * @property Int supported The newest container version this reader understands.
	 */
	public data class UnsupportedContainerVersion(val found: Int, val supported: Int) : UmaReadFailure {
		override val description: String get() = "container version $found is newer than the supported version $supported"
	}

	/**
	 * The ZIP archive itself is truncated, corrupt, or unsupported.
	 *
	 * @property String detail The ZIP reader's account.
	 */
	public data class CorruptContainer(val detail: String) : UmaReadFailure {
		override val description: String get() = "corrupt container: $detail"
	}

	/**
	 * The manifest is missing, is not valid JSON, or breaks one of its rules.
	 *
	 * @property String detail Which rule it breaks.
	 */
	public data class MalformedManifest(val detail: String) : UmaReadFailure {
		override val description: String get() = "malformed manifest: $detail"
	}

	/**
	 * The file names an entry the archive does not hold: a path the manifest lists, or a pixel entry an index
	 * record names.
	 *
	 * @property String path The named path.
	 */
	public data class MissingEntry(val path: String) : UmaReadFailure {
		override val description: String get() = "the file names '$path', which the archive does not hold"
	}

	/**
	 * A domain entry this reader understands is not what its kind requires.
	 *
	 * @property String path   The entry's path.
	 * @property String detail What is wrong with it.
	 */
	public data class MalformedEntry(val path: String, val detail: String) : UmaReadFailure {
		override val description: String get() = "malformed entry '$path': $detail"
	}
}

/**
 * A file that cannot be opened as UMA, with the typed reason.
 *
 * @param UmaReadFailure failure Why the file could not be opened.
 * @param Throwable?     cause   The underlying error, when there is one.
 */
public class UmaFormatException(
	public val failure: UmaReadFailure,
	cause: Throwable? = null,
) : RuntimeException(failure.description, cause)

/**
 * A document holds a value the UMA format cannot represent, so it cannot be saved.
 *
 * @param String     path   Where the value sits: an entry path, or a path within the entry.
 * @param String     detail What is wrong with it.
 * @param Throwable? cause  The underlying error, when there is one.
 */
public class UmaWriteException(
	public val path: String,
	public val detail: String,
	cause: Throwable? = null,
) : RuntimeException("cannot write $path: $detail", cause)