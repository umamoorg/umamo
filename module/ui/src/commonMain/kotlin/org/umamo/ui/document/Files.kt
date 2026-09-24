package org.umamo.ui.document

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
 * The base name to seed a Save As or an export dialog with: [displayName] minus whatever file extension it
 * carries.
 *
 * Any extension, not only the formats a rig is saved in.  A document can start from an artwork file (a rig
 * built by importing `hero.psd` into a new document is still named after it), and its first save should
 * suggest `hero`, never `hero.psd.uma`; an export of it `hero.png`, never `hero.psd.png`.  Only a trailing
 * extension of a few letters is stripped, so a name with a dot in it (`v1.2 sketch`) is left alone.  FileKit
 * re-appends the destination's extension itself.
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