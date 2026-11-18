package org.umamo.format.cmo3.serialize

import org.jdom.Element
import java.util.IdentityHashMap

/** Mutable read-side state: the xs.id -> instance map used to resolve xs.ref. */
internal class ReadContext(
	private val registry: SerializerRegistry,
	private val diagnostics: SerializeDiagnostics,
) {
	val references = HashMap<String, Any>()

	/** The ordered child sequence read for each object, keyed by owner then serializer tag (level). */
	val childOrder = IdentityHashMap<Any, MutableMap<String, MutableList<ChildSlot>>>()

	/** Names of the attributes actually present on read, keyed by owner then serializer tag. */
	val presentAttrs = IdentityHashMap<Any, MutableMap<String, MutableSet<String>>>()

	/**
	 * The recorded child sequence of an owner at one class level, created on first use.
	 *
	 * @param Any owner The object the record is kept for.
	 * @param String tag The class level of the owner, by tag.
	 */
	private fun slots(owner: Any, tag: String) =
		childOrder.getOrPut(owner) { HashMap() }.getOrPut(tag) { ArrayList() }

	/**
	 * Records, in document order, a recognized child field of [owner] at the [tag] level.
	 *
	 * @param Any owner The object the record is kept for.
	 * @param String tag The class level of the owner, by tag.
	 * @param String propertyName The field the child was read into.
	 */
	fun recordKnownChild(owner: Any, tag: String, propertyName: String) =
		slots(owner, tag).add(ChildSlot.KnownField(propertyName))

	/**
	 * Records, in document order, a child of [owner] the [tag] serializer did not recognise.
	 *
	 * @param Any owner The object the record is kept for.
	 * @param String tag The class level of the owner, by tag.
	 * @param Element element The unrecognized child, which is kept as a copy.
	 */
	fun recordVerbatimChild(owner: Any, tag: String, element: Element) =
		slots(owner, tag).add(ChildSlot.VerbatimChild(element.clone() as Element))

	/**
	 * Records, in document order, the position of the `<super>` element of [owner] at the [tag] level.
	 *
	 * @param Any owner The object the record is kept for.
	 * @param String tag The class level of the owner, by tag.
	 */
	fun recordSuperChild(owner: Any, tag: String) = slots(owner, tag).add(ChildSlot.Super)

	/**
	 * Records that attribute [attrName] of [owner] (at the [tag] level) was present in the source.
	 *
	 * @param Any owner The object the record is kept for.
	 * @param String tag The class level of the owner, by tag.
	 * @param String attrName The attribute's name.
	 */
	fun recordPresentAttr(owner: Any, tag: String, attrName: String) {
		presentAttrs.getOrPut(owner) { HashMap() }.getOrPut(tag) { HashSet() }.add(attrName)
	}

	/**
	 * Materializes the value for [element]: null marker, a resolved xs.ref, or a freshly
	 * created+populated value via the tag's serializer.
	 *
	 * @param Element element The source element.
	 * @return Any? The created value.
	 */
	fun createObjectFromElement(element: Element): Any? {
		val tag = element.name

		if (tag == TAG_NULL) {
			return null
		}

		element.getAttributeValue(ATTR_REF)?.let { return references[it] }
		val serializer = registry.serializerForTag(tag)

		if (serializer == null) {
			// Unmodeled tag: keep verbatim so the document still round-trips, and report it.
			diagnostics.onUnmodeledTag(tag, element.getAttributeValue(ATTR_NAME) ?: "(item)")

			return VerbatimNode(element.clone() as Element)
		}

		// A typed serializer may still fail on evolved/unknown content (e.g. a newer enum constant,
		// an added field, a missing no-arg constructor).  Fall back to verbatim + report, so format
		// evolution never crashes or loses data - it just isn't typed yet.
		return try {
			val instance = serializer.createInstance(element, this) ?: return null
			element.getAttributeValue(ATTR_ID)?.let { references[it] = instance }
			serializer.setupInstance(element, instance, this)
			instance
		} catch (failure: Exception) {
			diagnostics.onUnmodeledTag(tag, "deserialize-failed: ${failure.message}")
			VerbatimNode(element.clone() as Element)
		}
	}
}