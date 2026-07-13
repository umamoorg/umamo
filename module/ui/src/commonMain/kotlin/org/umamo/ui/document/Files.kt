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
