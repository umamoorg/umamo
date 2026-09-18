package org.umamo.format.uma

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/*
 * The manifest's JSON: parsing it with every rule docs/format/UMA.md §3 sets, and emitting it back over the
 * tree it was read from, so keys this writer does not own stay where they were.
 */

/**
 * The JSON every UMA entry this codec emits goes through: pretty-printed with tabs, keys in tree order.
 * Parsing is plain JSON; unknown keys are kept by working on trees rather than decoding into classes.
 */
@OptIn(ExperimentalSerializationApi::class)
internal val UmaJson: Json =
	Json {
		prettyPrint = true
		prettyPrintIndent = "\t"
	}

/**
 * The JSON a domain entry's schema classes decode from and encode to, as trees.
 *
 * Unknown keys are ignored here because the retained tree keeps them (D10); nulls are omitted, so an
 * absent optional key and a null are one thing; special floats are refused, since JSON cannot carry them.
 */
internal val UmaEntryJson: Json =
	Json {
		ignoreUnknownKeys = true
		explicitNulls = false
		encodeDefaults = false
	}

/**
 * A domain entry's tree decoded into its schema class, a tree the schema does not allow reported as the entry being
 * malformed (UMA §3.4).
 *
 * @param Json                    json       The JSON to decode with: [UmaEntryJson], or one with buffers bound.
 * @param DeserializationStrategy serializer The schema class's deserializer.
 * @param JsonObject              tree       The entry's JSON.
 * @param String                  path       The entry's path, for the failure.
 * @return T The decoded content.
 * @throws UmaFormatException When the tree breaks the schema.
 */
internal fun <T> decodeUmaEntry(json: Json, serializer: DeserializationStrategy<T>, tree: JsonObject, path: String): T =
	try {
		json.decodeFromJsonElement(serializer, tree)
	} catch (failure: SerializationException) {
		throw UmaFormatException(UmaReadFailure.MalformedEntry(path, failure.message.orEmpty()), failure)
	} catch (failure: IllegalArgumentException) {
		throw UmaFormatException(UmaReadFailure.MalformedEntry(path, failure.message.orEmpty()), failure)
	}

// UMA §3.3: the deepest the manifest or a live entry may nest, the root counting as one level.  The JSON parser and
// every walk over a tree - the accessor check, the identity check, the merge, the buffer layout - recurse, so the bound
// keeps each one shallow on any thread whatever the file holds.
internal const val UMA_MAXIMUM_JSON_DEPTH = 256

/**
 * Whether the JSON [text] nests deeper than [UMA_MAXIMUM_JSON_DEPTH] levels, read off its brackets before it is parsed:
 * the parser itself recurses once per nested array, so a tree too deep to walk is too deep to parse.  Brackets inside
 * strings are not counted.
 *
 * @param String text The JSON text.
 * @return Boolean True when an object or array opens deeper than the limit.
 */
internal fun jsonTextNestsTooDeep(text: String): Boolean {
	var depth = 0
	var inString = false
	var escaped = false
	for (character in text) {
		if (inString) {
			when {
				escaped -> escaped = false
				character == '\\' -> escaped = true
				character == '"' -> inString = false
			}
			continue
		}
		when (character) {
			'"' -> inString = true
			'{', '[' -> {
				depth++
				if (depth > UMA_MAXIMUM_JSON_DEPTH) {
					return true
				}
			}

			'}', ']' -> depth--
		}
	}
	return false
}

/**
 * Whether [tree] nests deeper than [UMA_MAXIMUM_JSON_DEPTH] levels, for a tree a save built rather than parsed.  The
 * walk stops as soon as it passes the limit, so its own recursion never goes deeper than the limit however deep the
 * tree.
 *
 * @param JsonElement tree  The tree.
 * @param Int         depth The level [tree] sits at, the root being 1.
 * @return Boolean True when an object or array sits deeper than the limit.
 */
internal fun jsonNestsTooDeep(tree: JsonElement, depth: Int = 1): Boolean {
	val children =
		when (tree) {
			is JsonObject -> tree.values
			is JsonArray -> tree
			else -> return false
		}
	if (depth > UMA_MAXIMUM_JSON_DEPTH) {
		return true
	}
	for (child in children) {
		if (child !is JsonPrimitive && jsonNestsTooDeep(child, depth + 1)) {
			return true
		}
	}
	return false
}

// UMA §3.1: the manifest's own keys.
private const val FORMAT_KEY = "format"
private const val CONTAINER_VERSION_KEY = "containerVersion"
private const val WRITER_KEY = "writer"
private const val ENTRIES_KEY = "entries"

// UMA §3.1: the writer record's keys.
private const val WRITER_APP_KEY = "app"
private const val WRITER_VERSION_KEY = "version"

// UMA §3.1: an entry record's keys.
private const val PATH_KEY = "path"
private const val KIND_KEY = "kind"
private const val VERSION_KEY = "version"
private const val MIN_VERSION_KEY = "minVersion"
private const val REQUIRED_KEY = "required"

/**
 * One validated manifest entry record.
 *
 * @property String     path       The entry's path.
 * @property String     kind       The entry's kind.
 * @property Int        version    The declared schema version.
 * @property Int        minVersion The declared minimum schema version.
 * @property Boolean    required   Whether the entry is declared required.
 * @property JsonObject record     The record as read.
 */
internal class UmaManifestRecord(
	val path: String,
	val kind: String,
	val version: Int,
	val minVersion: Int,
	val required: Boolean,
	val record: JsonObject,
)

/**
 * A validated manifest.
 *
 * @property UmaWriterInfo?          writer  The writer record, or null when the manifest has none.
 * @property List<UmaManifestRecord> records The entry records, in manifest order.
 * @property JsonObject              tree    The manifest as read.
 */
internal class UmaParsedManifest(
	val writer: UmaWriterInfo?,
	val records: List<UmaManifestRecord>,
	val tree: JsonObject,
)

/**
 * Parses and validates a manifest.
 *
 * `format` is checked before anything else, so a foreign ZIP that happens to carry a `manifest.json`
 * reads as not UMA rather than as a malformed UMA.
 *
 * @param ByteArray bytes The manifest entry's contents.
 * @return UmaParsedManifest The validated manifest.
 * @throws UmaFormatException When the manifest is not UMA's, too new, or breaks a rule.
 */
internal fun parseUmaManifest(bytes: ByteArray): UmaParsedManifest {
	val tree = parseJsonObject(bytes) { detail -> UmaReadFailure.MalformedManifest(detail) }

	// UMA §3.1: `format` must be "uma".
	val format = tree[FORMAT_KEY]
	if (format !is JsonPrimitive || !format.isString || format.content != UmaContainer.FORMAT_NAME) {
		throw UmaFormatException(UmaReadFailure.NotUma("the manifest's format is ${format ?: "absent"}, not \"${UmaContainer.FORMAT_NAME}\""))
	}

	// UMA §3.1: a container version this reader does not know refuses the whole file.
	val containerVersion = jsonIntegerOrNull(tree[CONTAINER_VERSION_KEY]) ?: malformedManifest("containerVersion is missing or not an integer")
	if (containerVersion > UmaContainer.CONTAINER_VERSION) {
		throw UmaFormatException(UmaReadFailure.UnsupportedContainerVersion(containerVersion, UmaContainer.CONTAINER_VERSION))
	}
	if (containerVersion < 1) {
		malformedManifest("containerVersion $containerVersion is below 1")
	}

	val writer =
		tree[WRITER_KEY]?.let { element ->
			val writerRecord = element as? JsonObject ?: malformedManifest("writer is not an object")
			UmaWriterInfo(
				app = jsonStringOrNull(writerRecord[WRITER_APP_KEY]) ?: malformedManifest("writer.app is missing or not a string"),
				version = jsonStringOrNull(writerRecord[WRITER_VERSION_KEY]) ?: malformedManifest("writer.version is missing or not a string"),
			)
		}

	val entriesArray = tree[ENTRIES_KEY] as? JsonArray ?: malformedManifest("entries is missing or not an array")
	val records = ArrayList<UmaManifestRecord>(entriesArray.size)
	val seenPaths = HashSet<String>()
	val seenKnownKinds = HashSet<UmaEntryKind>()
	for ((recordIndex, element) in entriesArray.withIndex()) {
		val record = element as? JsonObject ?: malformedManifest("entries[$recordIndex] is not an object")
		val path = jsonStringOrNull(record[PATH_KEY]) ?: malformedManifest("entries[$recordIndex].path is missing or not a string")
		val kind = jsonStringOrNull(record[KIND_KEY]) ?: malformedManifest("entries[$recordIndex].kind is missing or not a string")
		val version = jsonIntegerOrNull(record[VERSION_KEY]) ?: malformedManifest("entries[$recordIndex].version is missing or not an integer")
		val minVersion = jsonIntegerOrNull(record[MIN_VERSION_KEY]) ?: malformedManifest("entries[$recordIndex].minVersion is missing or not an integer")
		val required = jsonBooleanOrNull(record[REQUIRED_KEY]) ?: malformedManifest("entries[$recordIndex].required is missing or not a boolean")
		// UMA §3.1: record rules.
		if (path.isEmpty() || path.endsWith('/') || UmaContainer.isReservedPath(path)) {
			malformedManifest("entries[$recordIndex] names '$path', which cannot hold a domain entry")
		}
		if (!seenPaths.add(path)) {
			malformedManifest("'$path' is listed twice")
		}
		if (minVersion < 1 || minVersion > version) {
			malformedManifest("'$path' declares minVersion $minVersion against version $version")
		}
		UmaEntryKind.ofWireName(kind)?.let { knownKind ->
			if (!seenKnownKinds.add(knownKind)) {
				malformedManifest("the ${knownKind.wireName} kind is listed twice")
			}
		}
		records += UmaManifestRecord(path, kind, version, minVersion, required, record)
	}
	return UmaParsedManifest(writer, records, tree)
}

/**
 * The manifest [model] writes: its manifest as read with the keys this writer owns overwritten, so every
 * other key keeps its place (D10).
 *
 * Live entries are declared at this writer's schema version and requiredness (D8, always write current);
 * preserved entries keep their records exactly.
 *
 * @param UmaModel model The document being written.
 * @return ByteArray The manifest's JSON.
 */
internal fun emitUmaManifest(model: UmaModel): ByteArray {
	val manifest = LinkedHashMap<String, JsonElement>(model.manifestTree ?: emptyMap())
	manifest[FORMAT_KEY] = JsonPrimitive(UmaContainer.FORMAT_NAME)
	manifest[CONTAINER_VERSION_KEY] = JsonPrimitive(UmaContainer.CONTAINER_VERSION)
	val writer = model.writer
	if (writer == null) {
		manifest.remove(WRITER_KEY)
	} else {
		val writerRecord = LinkedHashMap<String, JsonElement>((manifest[WRITER_KEY] as? JsonObject) ?: emptyMap())
		writerRecord[WRITER_APP_KEY] = JsonPrimitive(writer.app)
		writerRecord[WRITER_VERSION_KEY] = JsonPrimitive(writer.version)
		manifest[WRITER_KEY] = JsonObject(writerRecord)
	}
	manifest[ENTRIES_KEY] =
		JsonArray(
			model.entries.map { entry ->
				when (val content = entry.content) {
					is UmaEntryContent.Preserved -> entry.record ?: error("a preserved entry always carries the record it was read with")
					is UmaEntryContent.Live -> {
						val record = LinkedHashMap<String, JsonElement>(entry.record ?: emptyMap())
						record[PATH_KEY] = JsonPrimitive(entry.path)
						record[KIND_KEY] = JsonPrimitive(content.kind.wireName)
						record[VERSION_KEY] = JsonPrimitive(content.kind.version)
						record[MIN_VERSION_KEY] = JsonPrimitive(content.kind.minVersion)
						record[REQUIRED_KEY] = JsonPrimitive(content.kind.required)
						JsonObject(record)
					}
				}
			},
		)
	return encodeUmaJson(JsonObject(manifest))
}

/**
 * Parses [bytes] as one JSON object, reporting any failure through [failureOf].
 *
 * @param ByteArray bytes     The JSON as UTF-8.
 * @param Function  failureOf Builds the failure for a detail message.
 * @return JsonObject The object.
 * @throws UmaFormatException When the bytes are not UTF-8, not JSON, or not an object.
 */
internal fun parseJsonObject(bytes: ByteArray, failureOf: (String) -> UmaReadFailure): JsonObject {
	val text =
		try {
			bytes.decodeToString(throwOnInvalidSequence = true)
		} catch (failure: CharacterCodingException) {
			throw UmaFormatException(failureOf("not valid UTF-8"), failure)
		}
	if (jsonTextNestsTooDeep(text)) {
		throw UmaFormatException(failureOf("nested deeper than $UMA_MAXIMUM_JSON_DEPTH levels"))
	}
	val element =
		try {
			UmaJson.parseToJsonElement(text)
		} catch (failure: SerializationException) {
			throw UmaFormatException(failureOf("not valid JSON: ${failure.message}"), failure)
		}
	return element as? JsonObject ?: throw UmaFormatException(failureOf("not a JSON object"))
}

/**
 * [tree] as the UTF-8 JSON this codec writes.
 *
 * @param JsonObject tree The JSON.
 * @return ByteArray The encoded bytes.
 */
internal fun encodeUmaJson(tree: JsonObject): ByteArray = UmaJson.encodeToString(JsonElement.serializer(), tree).encodeToByteArray()

/**
 * Throws the malformed-manifest failure for [detail].
 *
 * @param String detail Which rule the manifest breaks.
 * @return Nothing Never returns.
 */
private fun malformedManifest(detail: String): Nothing = throw UmaFormatException(UmaReadFailure.MalformedManifest(detail))

/**
 * [element] as a JSON string's content, or null when it is not a string.
 *
 * @param JsonElement? element The value.
 * @return String? The string.
 */
internal fun jsonStringOrNull(element: JsonElement?): String? = (element as? JsonPrimitive)?.takeIf { primitive -> primitive.isString }?.content

/**
 * [element] as a JSON integer, or null when it is not a number with an integer literal.
 *
 * @param JsonElement? element The value.
 * @return Int? The integer.
 */
private fun jsonIntegerOrNull(element: JsonElement?): Int? = (element as? JsonPrimitive)?.takeIf { primitive -> !primitive.isString }?.intOrNull

/**
 * [element] as a JSON boolean, or null when it is not one.
 *
 * @param JsonElement? element The value.
 * @return Boolean? The boolean.
 */
private fun jsonBooleanOrNull(element: JsonElement?): Boolean? = (element as? JsonPrimitive)?.takeIf { primitive -> !primitive.isString }?.booleanOrNull