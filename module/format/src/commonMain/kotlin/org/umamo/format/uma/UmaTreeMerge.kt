@file:OptIn(ExperimentalSerializationApi::class)

package org.umamo.format.uma

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/*
 * The retained-tree merge (docs/format/UMA.md §4.7): a domain entry is written by laying the freshly
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
	 * @property Function identityIn Given the object holding the array, the reader of an element's identity, which
	 *   yields null for an element that carries none.  The holder is resolved once per array, so an identity that
	 *   depends on it (a grid cell's key values, read through its grid's axes) costs nothing per element.
	 */
	class ByIdentity(val identityIn: (JsonObject) -> (JsonObject) -> String?) : UmaListRule

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

/** The holder an array with no enclosing object resolves its identities in. */
private val NO_HOLDER: JsonObject = JsonObject(emptyMap())

/**
 * The rule matching elements by the JSON string at [key], the identity most arrays of objects carry (an `id`, a
 * `parameter`, a layer `key`).
 *
 * @param String key The identifying key.
 * @return UmaListRule The rule; an element whose value at [key] is absent or not a string has no identity.
 */
internal fun identityByStringKey(key: String): UmaListRule = UmaListRule.ByIdentity { _ -> { element -> jsonStringOrNull(element[key]) } }

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
		StructureKind.LIST -> mergeArray(retained as? JsonArray, updated, descriptor.getElementDescriptor(0), identities, NO_HOLDER, NO_HOLDER)
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
 * @param JsonObject       holder     The object holding [tree], which an array's identities resolve in.
 * @return String? A description of the first problem, or null when there is none.
 */
internal fun firstIdentityProblem(tree: JsonElement, descriptor: SerialDescriptor, identities: UmaIdentityTable, path: String, holder: JsonObject = NO_HOLDER): String? {
	when (descriptor.kind) {
		StructureKind.CLASS -> {
			val treeObject = tree as? JsonObject ?: return null
			for (elementIndex in 0 until descriptor.elementsCount) {
				val child = treeObject[descriptor.getElementName(elementIndex)] ?: continue
				val childPath = if (path.isEmpty()) descriptor.getElementName(elementIndex) else "$path.${descriptor.getElementName(elementIndex)}"
				firstIdentityProblem(child, descriptor.getElementDescriptor(elementIndex), identities, childPath, treeObject)?.let { problem -> return problem }
			}
		}

		StructureKind.LIST -> {
			val treeArray = tree as? JsonArray ?: return null
			val elementDescriptor = descriptor.getElementDescriptor(0)
			val identityOf = (identities.ruleFor(elementDescriptor.serialName) as? UmaListRule.ByIdentity)?.identityIn?.invoke(holder)
			val seen = HashSet<String>()
			for ((elementIndex, element) in treeArray.withIndex()) {
				if (identityOf != null && element is JsonObject) {
					val identity = identityOf(element) ?: return "$path[$elementIndex] has no identity"
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
			// UMA §4.7: an array's identities resolve in the object holding it - the retained elements in the object as
			// read, the new ones in the object as written - so a grid's cells read their key values through their own axes.
			merged[key] =
				if (elementDescriptor.kind == StructureKind.LIST) {
					mergeArray(retained?.get(key) as? JsonArray, value, elementDescriptor.getElementDescriptor(0), identities, retained ?: NO_HOLDER, updatedObject)
				} else {
					mergeRetainedTree(retained?.get(key), value, elementDescriptor, identities)
				}
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
 * @param JsonObject       retainedHolder    The object holding [retained], as read.
 * @param JsonObject       updatedHolder     The object holding [updated], as written.
 * @return JsonElement The merged array.
 */
private fun mergeArray(
	retained: JsonArray?,
	updated: JsonElement,
	elementDescriptor: SerialDescriptor,
	identities: UmaIdentityTable,
	retainedHolder: JsonObject,
	updatedHolder: JsonObject,
): JsonElement {
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
			is UmaListRule.ByIdentity -> listRule
		}
	val retainedIdentityOf = rule.identityIn(retainedHolder)
	val updatedIdentityOf = rule.identityIn(updatedHolder)
	val retainedByIdentity = HashMap<String, JsonObject>()
	retained?.forEach { element ->
		val retainedElement = element as? JsonObject ?: return@forEach
		val identity = retainedIdentityOf(retainedElement) ?: return@forEach
		// Identities are unique in any tree that was read (firstIdentityProblem); the first wins regardless.
		if (identity !in retainedByIdentity) {
			retainedByIdentity[identity] = retainedElement
		}
	}
	return JsonArray(
		updatedArray.map { element ->
			val twin = (element as? JsonObject)?.let(updatedIdentityOf)?.let(retainedByIdentity::get)
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

/**
 * [patch] applied to [target] as a JSON Merge Patch (RFC 7386), which is how the editor entry is written over the
 * tree as read (docs/format/UMA.md §7.5).
 *
 * The editor entry is a dynamic tree with no schema descriptor to say which keys a writer knows, so it cannot go
 * through [mergeRetainedTree].  The patch says it instead: a member it names is set, a member it names `null` is
 * removed, and a member it does not name survives - which is what keeps a newer writer's members in the file.
 * Objects merge member by member; any other value replaces whole.  An object the merge leaves with no members is
 * removed in turn, so a deviation that returns to its default does not leave an empty block behind.
 *
 * Members keep the order [target] had them in, and new ones follow in [patch]'s order, so the same state always
 * writes the same bytes.
 *
 * @param JsonObject target The tree as read.
 * @param JsonObject patch  The members to set, and the ones to remove as nulls.
 * @return JsonObject The merged tree, holding no null this patch placed.
 */
internal fun applyMergePatch(target: JsonObject, patch: JsonObject): JsonObject {
	val merged = LinkedHashMap<String, JsonElement>(target)
	for ((memberName, patchValue) in patch) {
		when (patchValue) {
			// UMA §7.5: null removes.
			is JsonNull -> merged.remove(memberName)

			is JsonObject -> {
				val mergedChild = applyMergePatch(merged[memberName] as? JsonObject ?: JsonObject(emptyMap()), patchValue)
				if (mergedChild.isEmpty()) {
					merged.remove(memberName)
				} else {
					merged[memberName] = mergedChild
				}
			}

			else -> merged[memberName] = patchValue
		}
	}
	return JsonObject(merged)
}