package org.umamo.ui.document

import org.umamo.format.FileKind

/**
 * The display name for a stored file path (titles, the Open Recent menu). A pure string helper — it
 * never touches the filesystem — so it degrades gracefully for Android SAF `content://` URIs, whose
 * last segment stands in until real SAF display-name resolution lands.
 *
 * @param String path The stored path or URI string.
 * @return String The trailing segment, with both separator conventions handled.
 */
fun fileDisplayName(path: String): String = path.substringAfterLast('/').substringAfterLast('\\')

/**
 * Whether [path] is a path the file system can probe, read, and watch, as opposed to a platform uri.
 * Android's SAF hands back `content://` handles with no path behind them, so every file-system
 * operation over a stored path asks this first and treats a uri as unknowable rather than missing.
 *
 * @param String path The stored path or uri string.
 * @return Boolean True for a file-system path, false for a uri.
 */
fun isFileSystemPath(path: String): Boolean = !path.contains("://")

/**
 * The source extensions an export strips before suggesting a name.
 *
 * Named members rather than a filter over [FileKind]: this is the set a puppet document can be OPEN
 * from, which is narrower than "everything readable" - the art sources are readable too and must never
 * suggest a name here.
 */
private val SOURCE_EXTENSIONS = listOf(FileKind.Uma, FileKind.Cmo3, FileKind.Moc3).map { kind -> ".${kind.extension}" }

/**
 * The base name to seed an export's save dialog with: [displayName] minus its source extension.
 *
 * The strip ignores case, and covers EVERY source extension regardless of which format is being
 * exported - the point is to reach the model's own name, and a rigger exporting `Model.moc3` to CMO3
 * wants `Model.cmo3`, not `Model.moc3.cmo3`.  FileKit re-appends the destination extension itself, so
 * this deliberately returns a bare name.
 *
 * @param String displayName The open document's file name.
 * @return String The name without its source extension.
 */
fun exportSuggestedName(displayName: String): String =
	SOURCE_EXTENSIONS.firstOrNull { extension -> displayName.endsWith(extension, ignoreCase = true) }
		?.let { extension -> displayName.dropLast(extension.length) }
		?: displayName

/**
 * The base name to seed a Save As dialog with: [displayName] minus whatever file extension it carries.
 *
 * Broader than [exportSuggestedName] on purpose.  A document can start from an artwork file (a rig built
 * by importing `hero.psd` into a new document is still named after it), and its first save should suggest
 * `hero`, never `hero.psd.uma`.  Only a trailing extension of a few letters is stripped, so a name with a
 * dot in it (`v1.2 sketch`) is left alone.  FileKit re-appends `.uma` itself.
 *
 * @param String displayName The document's file name.
 * @return String The name without its extension.
 */
fun saveSuggestedName(displayName: String): String {
	val dot = displayName.lastIndexOf('.')
	val extension = if (dot > 0) displayName.substring(dot + 1) else ""
	return if (extension.length in 1..MAXIMUM_EXTENSION_LENGTH && extension.all { character -> character.isLetterOrDigit() }) {
		displayName.substring(0, dot)
	} else {
		displayName
	}
}

/** The longest extension a source file plausibly carries (`.webp`, `.jpeg`, `.tiff`, `.clip`). */
private const val MAXIMUM_EXTENSION_LENGTH = 5