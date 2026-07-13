package org.umamo.settings

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import org.umamo.storage.AppStorage

/**
 * The app's settings: a merged JSON tree of bundled defaults ← user overrides (← vendor extension
 * defaults, later), addressed by dotted keys (`getString("interface.theme")`). No caller ever passes a
 * default - `defaultSettings.json` is the single source of baseline values, so reads are
 * default-by-construction. Writes go to the user layer only, re-merge, persist via [AppStorage], and emit
 * the changed key on [changes] (the reactive spine the UI/keymap/History collect).
 *
 * Build it with [Settings.load]. The tree is held as a dynamic [JsonObject] (not typed `@Serializable`
 * classes) because settings are open-ended and merged; typed per-domain views can layer on top later.
 */
class Settings private constructor(
	private val storage: AppStorage,
	private val userFile: okio.Path,
	private val defaults: JsonObject,
	private var user: JsonObject,
) {
	private var merged: JsonObject = deepMerge(defaults, user)

	private val mutableChanges = MutableSharedFlow<String>(extraBufferCapacity = 64)

	/** Emits a setting's dotted key whenever it is written, so consumers re-read just what changed. */
	val changes: SharedFlow<String> = mutableChanges.asSharedFlow()

	/**
	 * Resolves a dotted [key] to its merged value.
	 *
	 * @param String key Dotted key, e.g. `"input.keybinding.preset"`.
	 * @return JsonElement? The value (user override, else default), or null if absent.
	 */
	fun get(key: String): JsonElement? {
		var current: JsonElement = merged
		for (segment in key.split('.')) {
			current = (current as? JsonObject)?.get(segment) ?: return null
		}
		return current
	}

	/** @return String? The [key]'s string value, or null if absent / not a string. */
	fun getString(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

	/** @return Boolean? The [key]'s boolean value, or null if absent / not a boolean. */
	fun getBoolean(key: String): Boolean? = (get(key) as? JsonPrimitive)?.booleanOrNull

	/** @return Int? The [key]'s integer value, or null if absent / not an integer. */
	fun getInt(key: String): Int? = (get(key) as? JsonPrimitive)?.intOrNull

	/** @return Double? The [key]'s number value, or null if absent / not a number. */
	fun getDouble(key: String): Double? = (get(key) as? JsonPrimitive)?.doubleOrNull

	/** Sets a string value at [key]. */
	fun setString(key: String, value: String) = set(key, JsonPrimitive(value))

	/** Sets a boolean value at [key]. */
	fun setBoolean(key: String, value: Boolean) = set(key, JsonPrimitive(value))

	/** Sets an integer value at [key]. */
	fun setInt(key: String, value: Int) = set(key, JsonPrimitive(value))

	/** Sets a number value at [key]. */
	fun setDouble(key: String, value: Double) = set(key, JsonPrimitive(value))

	/**
	 * Writes [value] into the user layer at dotted [key] (creating intermediate objects), re-merges over
	 * the defaults, persists the user layer, and emits [key] on [changes].
	 *
	 * @param Path        key   Dotted key.
	 * @param JsonElement value The value to store.
	 */
	fun set(key: String, value: JsonElement) {
		user = putAtPath(user, key.split('.'), value)
		merged = deepMerge(defaults, user)
		storage.writeText(userFile, JSON.encodeToString(JsonObject.serializer(), user))
		mutableChanges.tryEmit(key)
	}

	companion object {
		private val JSON =
			Json {
				ignoreUnknownKeys = true
				prettyPrint = true
			}

		/**
		 * Loads settings: parses bundled [defaultSettingsJson] as the baseline and overlays the user file
		 * ([fileName] under [storage]'s config directory) if present and valid. A missing or corrupt user
		 * file falls back to defaults-only (never throws).
		 *
		 * @param AppStorage storage             Where the user file lives + IO.
		 * @param String     defaultSettingsJson The bundled `defaultSettings.json` contents.
		 * @param String     fileName            The user settings file name under the config directory.
		 * @return Settings The loaded settings.
		 */
		fun load(storage: AppStorage, defaultSettingsJson: String, fileName: String = "settings.json"): Settings {
			val defaults = parseObjectOrEmpty(defaultSettingsJson)
			val userFile = storage.configDirectory / fileName
			val user = storage.readText(userFile)?.let { parseObjectOrEmpty(it) } ?: JsonObject(emptyMap())
			return Settings(storage, userFile, defaults, user)
		}

		/** Parses [text] to a [JsonObject], or an empty object when it is absent/blank/not an object/invalid. */
		private fun parseObjectOrEmpty(text: String): JsonObject =
			runCatching { JSON.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: JsonObject(emptyMap())

		/** Deep-merges [overlay] onto [base]: nested objects merge recursively; any other value is replaced. */
		private fun deepMerge(base: JsonObject, overlay: JsonObject): JsonObject {
			val result = base.toMutableMap()
			for ((key, overlayValue) in overlay) {
				val baseValue = result[key]
				result[key] =
					if (baseValue is JsonObject && overlayValue is JsonObject) {
						deepMerge(baseValue, overlayValue)
					} else {
						overlayValue
					}
			}
			return JsonObject(result)
		}

		/** Returns a copy of [root] with [value] set at the dotted [path], creating intermediate objects. */
		private fun putAtPath(root: JsonObject, path: List<String>, value: JsonElement): JsonObject {
			if (path.isEmpty()) {
				return root
			}
			val head = path.first()
			val child =
				if (path.size == 1) {
					value
				} else {
					putAtPath(root[head] as? JsonObject ?: JsonObject(emptyMap()), path.drop(1), value)
				}
			return JsonObject(root.toMutableMap().apply { put(head, child) })
		}
	}
}
