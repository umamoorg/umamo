package org.umamo.reimport

/**
 * What a document does when a watched artwork file changes on disk - the `import.watchMode` setting.
 *
 * @property String key The setting value.
 */
enum class WatchMode(val key: String) {
	/** Reload the changed files as soon as the editor is idle, as one undo step. */
	Auto("auto"),

	/** Say that files changed and light the Reload button; nothing changes until it is pressed. */
	Notify("notify"),

	/** Watch nothing. */
	Off("off"),
	;

	companion object {
		/** The mode a fresh install runs with. */
		val Default: WatchMode = Auto

		/**
		 * The mode a setting value names, or [Default] for a value no mode carries (a stale or absent
		 * setting must not turn watching off by accident).
		 *
		 * @param String? key The setting value.
		 * @return WatchMode The mode.
		 */
		fun fromKey(key: String?): WatchMode = entries.firstOrNull { mode -> mode.key == key } ?: Default
	}
}