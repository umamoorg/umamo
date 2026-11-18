package org.umamo.format.cmo3.serialize

import org.jdom.Element
import java.util.IdentityHashMap

/**
 * Mutable write-side state: object-identity tracking and the shared pool (written-object map,
 * shared list, ref-id and write-index counters).
 *
 * In reconcile mode ([preassignedShared] != null) objects that were shared on read are emitted as
 * xs.ref using their preserved id, so a round-trip stays byte-identical even through verbatim content.
 * Any other object referenced more than once - fresh or newly-shared, in or out of reconcile mode -
 * goes through the same hoist-on-second-use path, with fresh ids continuing past the preserved
 * maximum so they never collide with one.
 */
internal class WriteContext(
	private val registry: SerializerRegistry,
	private val preassignedShared: IdentityHashMap<Any, SharedRef>? = null,
	private val preservedChildOrder: IdentityHashMap<Any, MutableMap<String, MutableList<ChildSlot>>>? = null,
	private val preservedPresentAttrs: IdentityHashMap<Any, MutableMap<String, MutableSet<String>>>? = null,
) {
	private val written = IdentityHashMap<Any, WrittenRecord>()
	val sharedDefs = ArrayList<SharedDef>()

	// Fresh ids continue past the preserved pool's numeric ids: the reader keys references by id,
	// so a new shared object reusing an id already bound by the source document would hijack every
	// later xs.ref to that id.
	private var refIdCounter = nextFreshRefId(preassignedShared)
	private var writtenIndexCounter = 0

	/**
	 * Records [value]'s element on first write (subsequent calls are no-ops, matching the editor).
	 *
	 * @param Any value The object that was written.
	 * @param Element element The element it was written as.
	 */
	fun setWritten(value: Any, element: Element) {
		if (!written.containsKey(value)) {
			written[value] = WrittenRecord(element, writtenIndexCounter++)
		}
	}

	/**
	 * The child sequence read for [owner] at the [tag] level, or null for a freshly constructed object.
	 * When non-null it is replayed verbatim (order, presence and unknown children all preserved), so a
	 * round-trip reproduces the source exactly - independent of schema version.
	 *
	 * @param Any owner The object the record is kept for.
	 * @param String tag The class level of the owner, by tag.
	 * @return List<ChildSlot>? The recorded child sequence, or null.
	 */
	fun childOrderFor(owner: Any, tag: String): List<ChildSlot>? = preservedChildOrder?.get(owner)?.get(tag)

	/**
	 * The attributes present when [owner] was read at the [tag] level, or null for a fresh object.
	 *
	 * @param Any owner The object the record is kept for.
	 * @param String tag The class level of the owner, by tag.
	 * @return Set<String>? The recorded attribute names, or null.
	 */
	fun presentAttrsFor(owner: Any, tag: String): Set<String>? = preservedPresentAttrs?.get(owner)?.get(tag)

	/**
	 * Builds the element for a field/item value: `<null>` for null, an xs.ref for an already-written
	 * object (hoisting its def into the shared pool on first reuse), else delegates to the serializer.
	 *
	 * @param String? name  The owning field name (xs.n), or null for collection items.
	 * @param Any?    value The value to serialize.
	 * @return Element The element (def, reference, or null marker).
	 */
	fun createElementFromObject(name: String?, value: Any?): Element {
		if (value == null) {
			val nullElement = Element(TAG_NULL)
			nullElement.setFieldName(name)

			return nullElement
		}

		// Reconcile mode: a known-shared object is always emitted as a reference to its preserved id,
		// reusing the def's exact tag captured at read time.
		preassignedShared?.get(value)?.let { sharedRef ->
			val reference = Element(sharedRef.tag)
			reference.setFieldName(name)
			reference.setAttribute(ATTR_REF, sharedRef.id)

			return reference
		}

		if (value is VerbatimNode) {
			// Re-emit the preserved subtree, re-stamping the owning field name (or dropping it for items).
			val clone = value.element.clone() as Element

			if (name != null) {
				clone.setAttribute(ATTR_NAME, name)
			} else {
				clone.removeAttribute(ATTR_NAME)
			}

			return clone
		}

		written[value]?.let { record ->
			var id = record.element.getAttributeValue(ATTR_ID)

			if (id == null) {
				id = "#${refIdCounter++}"
				record.element.setAttribute(ATTR_ID, id)
				sharedDefs.add(SharedDef(record.element, id, record.index))
			}

			val reference = Element(record.element.name)
			reference.setFieldName(name)
			reference.setAttribute(ATTR_REF, id)

			return reference
		}

		return registry.serializerForValue(value).createElement(name, value, this)
	}
}

/** Identity record for an object already written, with its element and global write order. */
internal class WrittenRecord(val element: Element, val index: Int)

/** A shared (referenced >= 2x) object's definition element, its xs.id, and write order (xs.idx). */
internal class SharedDef(val element: Element, val refId: String, val index: Int)

/**
 * The first fresh ref id not colliding with a preserved pool id ("#N" numeric ids only; the pool's
 * non-numeric ids can never collide with the "#counter" form fresh ids take).
 *
 * @param IdentityHashMap? preassigned The preserved shared identities, or null outside reconcile mode.
 * @return Int The starting counter value.
 */
private fun nextFreshRefId(preassigned: IdentityHashMap<Any, SharedRef>?): Int {
	val maxPreserved =
		preassigned?.values?.maxOfOrNull { sharedRef ->
			sharedRef.id.removePrefix("#").toIntOrNull() ?: -1
		} ?: -1

	return maxPreserved + 1
}