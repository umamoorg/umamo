@file:OptIn(ExperimentalSerializationApi::class)

package org.umamo.format.uma

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/*
 * The retained-tree merge (D10, docs/format/UMA.md §4.7): a domain entry is written by laying the freshly
 * encoded schema object over the JSON tree the file was read with, so every key this reader does not know
 * survives the save.  The schema's own descriptors say which keys are known, so the merge never drifts from
 * the classes; the one thing they cannot say - which array elements are the same object across an edit -
 * comes from an identity table per entry kind.
 */

/** How an array of one element class merges across a save (UMA §4.7). */
internal sealed interface UmaListRule {
	/**
	 * Elements are matched by an identity read from their JSON, so each keeps its unknown keys.
	 *
	 * @property Function identityOf Reads an element's identity, or null when it carries none.
	 */
	class ByIdentity(val identityOf: (JsonObject) -> String?) : UmaListRule

	/** The array is a value: the new array replaces the old one whole, unknown keys inside it included. */
	data object Whole : UmaListRule
}

/**
 * How an entry kind's arrays of objects merge, keyed by the element class's serial name.
 *
 * @param Map rules Each element class's serial name, mapped to its rule.
 */
internal class UmaIdentityTable(private val rules: Map<String, UmaListRule>) {
	/**
	 * The rule for arrays of the class named [serialName], or null when there is none.
	 *
	 * @param String serialName The element class's serial name.
	 * @return UmaListRule? The rule.
	 */
	fun ruleFor(serialName: String): UmaListRule? = rules[serialName.removeSuffix("?")]
}

/**
 * [updated] laid over [retained]: known keys from [updated] in schema order, each merged with the retained
 * value it replaces, followed by [retained]'s unknown keys in their original order.
 *
 * A map merges value by value under the same key.  Arrays of objects merge element by element through
 * [identities]: the output follows [updated]'s order,
 * each element merged with the retained element of the same identity, and a retained element with no
 * counterpart - a deleted object - leaves with its unknown keys.  Arrays of values are replaced whole.  A
 * known key [updated] omits, because its value is the default, is omitted from the output too - unless it
 * holds an object that carries unknown keys, which keeps just those.
 *
 * @param JsonElement?     retained   The tree as read, or null for an object new to the file.
 * @param JsonElement      updated    The freshly encoded tree.
 * @param SerialDescriptor descriptor The schema of [updated].
 * @param UmaIdentityTable identities The element identities.
 * @return JsonElement The merged tree.
 * @throws IllegalStateException When the schema holds an array of objects with no identity rule.
 */
internal fun mergeRetainedTree(retained: JsonElement?, updated: JsonElement, descriptor: SerialDescriptor, identities: UmaIdentityTable): JsonElement =
	when (descriptor.kind) {
		StructureKind.CLASS -> mergeObject(retained as? JsonObject, updated, descriptor, identities)
		StructureKind.LIST -> mergeArray(retained as? JsonArray, updated, descriptor.getElementDescriptor(0), identities)
		StructureKind.MAP -> mergeMap(retained as? JsonObject, updated, descriptor.getElementDescriptor(1), identities)
		else -> updated
	}

/**
 * Where [tree] breaks the identity rules: an element of an identity-merged array with no identity, or two
 * elements sharing one.
 *
 * @param JsonElement      tree       The tree as read.
 * @param SerialDescriptor descriptor The schema of [tree].
 * @param UmaIdentityTable identities The element identities.
 * @param String           path       The tree's position, for the report.
 * @return String? A description of the first problem, or null when there is none.
 */
internal fun firstIdentityProblem(tree: JsonElement, descriptor: SerialDescriptor, identities: UmaIdentityTable, path: String): String? {
	when (descriptor.kind) {
		StructureKind.CLASS -> {
			val treeObject = tree as? JsonObject ?: return null
			for (elementIndex in 0 until descriptor.elementsCount) {
				val child = treeObject[descriptor.getElementName(elementIndex)] ?: continue
				val childPath = if (path.isEmpty()) descriptor.getElementName(elementIndex) else "$path.${descriptor.getElementName(elementIndex)}"
				firstIdentityProblem(child, descriptor.getElementDescriptor(elementIndex), identities, childPath)?.let { problem -> return problem }
			}
		}

		StructureKind.LIST -> {
			val treeArray = tree as? JsonArray ?: return null
			val elementDescriptor = descriptor.getElementDescriptor(0)
			val rule = identities.ruleFor(elementDescriptor.serialName) as? UmaListRule.ByIdentity
			val seen = HashSet<String>()
			for ((elementIndex, element) in treeArray.withIndex()) {
				if (rule != null && element is JsonObject) {
					val identity = rule.identityOf(element) ?: return "$path[$elementIndex] has no identity"
					if (!seen.add(identity)) {
						return "$path[$elementIndex] repeats the identity '${identity.replace(Char(0), ',')}'"
					}
				}
				firstIdentityProblem(element, elementDescriptor, identities, "$path[$elementIndex]")?.let { problem -> return problem }
			}
		}

		StructureKind.MAP -> {
			val treeObject = tree as? JsonObject ?: return null
			val valueDescriptor = descriptor.getElementDescriptor(1)
			for ((key, value) in treeObject) {
				firstIdentityProblem(value, valueDescriptor, identities, "$path.$key")?.let { problem -> return problem }
			}
		}

		else -> {}
	}
	return null
}

/**
 * The object merge; see [mergeRetainedTree].
 *
 * @param JsonObject?      retained   The object as read, or null.
 * @param JsonElement      updated    The freshly encoded object.
 * @param SerialDescriptor descriptor The object's schema.
 * @param UmaIdentityTable identities The element identities.
 * @return JsonElement The merged object.
 */
private fun mergeObject(retained: JsonObject?, updated: JsonElement, descriptor: SerialDescriptor, identities: UmaIdentityTable): JsonElement {
	val updatedObject = updated as? JsonObject ?: return updated
	val merged = LinkedHashMap<String, JsonElement>()
	val knownKeys = HashSet<String>(descriptor.elementsCount)
	for (elementIndex in 0 until descriptor.elementsCount) {
		val key = descriptor.getElementName(elementIndex)
		knownKeys += key
		val elementDescriptor = descriptor.getElementDescriptor(elementIndex)
		val value = updatedObject[key]
		if (value != null) {
			merged[key] = mergeRetainedTree(retained?.get(key), value, elementDescriptor, identities)
			continue
		}
		// UMA §4.7: an object omitted because everything it knows is at its default (a part's composite) still
		// carries the keys this reader did not know, so it keeps an object holding just those.  An object with a
		// required key (a tile's placement, a drawable's mesh) is omitted because it is gone, and its unknown keys
		// leave with it, as a deleted array element's do; keeping them would write an object no reader accepts.
		val retainedChild = retained?.get(key) as? JsonObject
		if (retainedChild != null && elementDescriptor.kind == StructureKind.CLASS && everyElementIsOptional(elementDescriptor)) {
			val unknownOnly = mergeObject(retainedChild, JsonObject(emptyMap()), elementDescriptor, identities) as JsonObject
			if (unknownOnly.isNotEmpty()) {
				merged[key] = unknownOnly
			}
		}
	}
	// UMA §4.7: unknown keys follow the known ones, so a document without them writes the same bytes
	// whichever file it was last read from.
	retained?.forEach { (key, value) ->
		if (key !in knownKeys) {
			merged[key] = value
		}
	}
	return JsonObject(merged)
}

/**
 * Whether every key of the class [descriptor] describes may be absent, so an object holding none of them is
 * still one a reader accepts.
 *
 * @param SerialDescriptor descriptor The class's schema.
 * @return Boolean True when no key is required.
 */
private fun everyElementIsOptional(descriptor: SerialDescriptor): Boolean = (0 until descriptor.elementsCount).all { elementIndex -> descriptor.isElementOptional(elementIndex) }

/**
 * The array merge; see [mergeRetainedTree].
 *
 * @param JsonArray?       retained          The array as read, or null.
 * @param JsonElement      updated           The freshly encoded array.
 * @param SerialDescriptor elementDescriptor The elements' schema.
 * @param UmaIdentityTable identities        The element identities.
 * @return JsonElement The merged array.
 */
private fun mergeArray(retained: JsonArray?, updated: JsonElement, elementDescriptor: SerialDescriptor, identities: UmaIdentityTable): JsonElement {
	val updatedArray = updated as? JsonArray ?: return updated
	if (elementDescriptor.kind != StructureKind.CLASS) {
		return updatedArray
	}
	val rule =
		when (
			val listRule =
				checkNotNull(identities.ruleFor(elementDescriptor.serialName)) {
					"${elementDescriptor.serialName} has no list rule, so its unknown keys could not survive a save"
				}
		) {
			UmaListRule.Whole -> return updatedArray
			is UmaListRule.ByIdentity -> listRule.identityOf
		}
	val retainedByIdentity = HashMap<String, JsonObject>()
	retained?.forEach { element ->
		val retainedElement = element as? JsonObject ?: return@forEach
		val identity = rule(retainedElement) ?: return@forEach
		// Identities are unique in any tree that was read (firstIdentityProblem); the first wins regardless.
		if (identity !in retainedByIdentity) {
			retainedByIdentity[identity] = retainedElement
		}
	}
	return JsonArray(
		updatedArray.map { element ->
			val twin = (element as? JsonObject)?.let(rule)?.let(retainedByIdentity::get)
			mergeRetainedTree(twin, element, elementDescriptor, identities)
		},
	)
}

/**
 * The map merge; see [mergeRetainedTree].  Keys follow [updated]; a key [updated] no longer holds leaves.
 *
 * @param JsonObject?      retained        The map as read, or null.
 * @param JsonElement      updated         The freshly encoded map.
 * @param SerialDescriptor valueDescriptor The values' schema.
 * @param UmaIdentityTable identities      The element identities.
 * @return JsonElement The merged map.
 */
private fun mergeMap(retained: JsonObject?, updated: JsonElement, valueDescriptor: SerialDescriptor, identities: UmaIdentityTable): JsonElement {
	val updatedObject = updated as? JsonObject ?: return updated
	return JsonObject(updatedObject.mapValuesTo(LinkedHashMap()) { (key, value) -> mergeRetainedTree(retained?.get(key), value, valueDescriptor, identities) })
}