package org.umamo.format.uma.sources

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.umamo.format.uma.UmaEntryJson
import org.umamo.format.uma.UmaFormatException
import org.umamo.format.uma.UmaIdentityTable
import org.umamo.format.uma.UmaListRule
import org.umamo.format.uma.UmaReadFailure
import org.umamo.format.uma.UmaWriteException
import org.umamo.format.uma.firstIdentityProblem

/**
 * The sources entry's codec over its JSON tree: decoding with every rule UMA §6 sets, encoding for a save,
 * and the identities its arrays merge by.
 */
internal object UmaSourcesEntry {
	// UMA §6.4 (D16): the one hash algorithm this reader knows, and its digest's length in hex digits.
	private const val HASH_PREFIX = "sha256:"
	private const val HASH_DIGITS = 64

	/** UMA §6.5: sources match by id, and a source's layers by key. */
	val identities: UmaIdentityTable =
		UmaIdentityTable(
			mapOf(
				UmaSource.serializer().descriptor.serialName to byKey("id"),
				UmaSourceLayer.serializer().descriptor.serialName to byKey("key"),
			),
		)

	/**
	 * Decodes a sources entry's tree, failing loudly on anything the schema does not allow.
	 *
	 * @param JsonObject tree The entry's JSON.
	 * @param String     path The entry's path, for the failure.
	 * @return UmaSources The sources.
	 * @throws UmaFormatException When the tree breaks the schema, repeats an identity, or holds a hash this
	 *   reader does not know.
	 */
	fun decode(tree: JsonObject, path: String): UmaSources {
		val sources =
			try {
				UmaEntryJson.decodeFromJsonElement(UmaSources.serializer(), tree)
			} catch (failure: SerializationException) {
				throw UmaFormatException(UmaReadFailure.MalformedEntry(path, failure.message.orEmpty()), failure)
			} catch (failure: IllegalArgumentException) {
				throw UmaFormatException(UmaReadFailure.MalformedEntry(path, failure.message.orEmpty()), failure)
			}
		val problem = firstIdentityProblem(tree, UmaSources.serializer().descriptor, identities, "") ?: firstProblem(sources)
		if (problem != null) {
			throw UmaFormatException(UmaReadFailure.MalformedEntry(path, problem))
		}
		return sources
	}

	/**
	 * Encodes sources for a save, refusing what a reader would refuse.
	 *
	 * @param UmaSources sources The sources.
	 * @param String     path    The entry's path, for the failure.
	 * @return JsonObject The entry's JSON, before the merge.
	 * @throws UmaWriteException When a hash is not in the form the entry holds, or a size is negative.
	 */
	fun encode(sources: UmaSources, path: String): JsonObject {
		firstProblem(sources)?.let { problem -> throw UmaWriteException(path, problem) }
		return UmaEntryJson.encodeToJsonElement(UmaSources.serializer(), sources) as JsonObject
	}

	/**
	 * Where [sources] first breaks a rule of UMA §6 that the schema classes cannot enforce on their own, apart
	 * from repeated identities.
	 *
	 * @param UmaSources sources The sources.
	 * @return String? A description of the first problem, or null when every rule holds.
	 */
	private fun firstProblem(sources: UmaSources): String? {
		for ((sourceIndex, source) in sources.sources.orEmpty().withIndex()) {
			val sourcePath = "sources[$sourceIndex]"
			hashProblem(source.contentHash, "$sourcePath.contentHash")?.let { problem -> return problem }
			for ((layerIndex, layer) in source.layers.orEmpty().withIndex()) {
				val layerPath = "$sourcePath.layers[$layerIndex]"
				if (layer.width < 0 || layer.height < 0) {
					return "$layerPath has a negative size"
				}
				hashProblem(layer.contentHash, "$layerPath.contentHash")?.let { problem -> return problem }
			}
		}
		return null
	}

	/**
	 * What is wrong with a recorded hash, or null when it is absent or `sha256:` followed by 64 lowercase hex
	 * digits.
	 *
	 * @param String? hash The hash as written.
	 * @param String  path Where it sits, for the report.
	 * @return String? The problem.
	 */
	private fun hashProblem(hash: String?, path: String): String? {
		if (hash == null) {
			return null
		}
		// UMA §6.4: a hash under another algorithm is unknown to this reader, like an unknown enum value; a writer
		// that changes the algorithm raises the entry's minVersion.
		if (!hash.startsWith(HASH_PREFIX)) {
			return "$path is '$hash', which is not a sha256 hash"
		}
		val digest = hash.substring(HASH_PREFIX.length)
		if (digest.length != HASH_DIGITS || digest.any { character -> character !in '0'..'9' && character !in 'a'..'f' }) {
			return "$path is not 64 lowercase hexadecimal digits"
		}
		return null
	}

	/**
	 * The rule matching elements by the string at [key].
	 *
	 * @param String key The identifying key.
	 * @return UmaListRule The rule.
	 */
	private fun byKey(key: String): UmaListRule = UmaListRule.ByIdentity { element -> (element[key] as? JsonPrimitive)?.takeIf { primitive -> primitive.isString }?.content }
}