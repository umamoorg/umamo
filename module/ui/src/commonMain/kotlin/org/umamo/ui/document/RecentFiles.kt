package org.umamo.ui.document

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.umamo.settings.Settings

private const val RECENT_FILES_KEY = "app.recentFiles"
private const val RECENT_FILES_MAX = 10

/**
 * The recently-opened file paths, most-recent first (the `app.recentFiles` setting).
 *
 * @return List<String> The recent paths.
 */
fun Settings.recentFiles(): List<String> =
	(get(RECENT_FILES_KEY) as? JsonArray)
		?.mapNotNull { (it as? JsonPrimitive)?.takeIf { primitive -> primitive.isString }?.content }
		?: emptyList()

/**
 * Records [path] as the most-recently-opened, moving it to the front (de-duplicated) and capping the list.
 * Persists immediately and emits the change (so the Open Recent menu refreshes).
 *
 * @param String path The opened file path.
 */
fun Settings.addRecentFile(path: String) {
	val updated = (listOf(path) + recentFiles().filterNot { it == path }).take(RECENT_FILES_MAX)
	set(RECENT_FILES_KEY, JsonArray(updated.map { JsonPrimitive(it) }))
}
