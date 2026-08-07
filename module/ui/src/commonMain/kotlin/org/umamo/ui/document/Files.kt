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

/** The source extensions an export strips before suggesting a name. */
private val SOURCE_EXTENSIONS = listOf(".cmo3", ".moc3")

/**
 * The base name to seed an export's save dialog with: [displayName] minus its source extension.
 *
 * The strip ignores case, and covers BOTH source extensions regardless of which format is being
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
