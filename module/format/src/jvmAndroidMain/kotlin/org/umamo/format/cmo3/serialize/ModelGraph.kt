package org.umamo.format.cmo3.serialize

import org.jdom.Element
import org.jdom.ProcessingInstruction
import java.util.IdentityHashMap

/**
 * A deserialized model with the metadata needed to re-emit it byte-identically: the main root
 * object, the shared pool in document order (with preserved xs.id/xs.idx), the processing
 * instructions, and the `<root>` attributes.
 *
 * [root] (and the shared objects) are typed where a serializer is registered, else [VerbatimNode].
 *
 * @see <a href="https://docs.umamo.org/format/CMO3.md">CMO3.md §3 Payload: main.xml</a>
 */
public class ModelGraph internal constructor(
	/** The `<main>` root object (e.g. CModelSource), typed or verbatim. */
	public val root: Any?,
	internal val sharedOrder: MutableList<Any>,
	internal val sharedInfo: IdentityHashMap<Any, SharedRef>,
	internal val processingInstructions: List<ProcessingInstruction>,
	internal val rootAttributes: List<Pair<String, String>>,
	internal val childOrder: IdentityHashMap<Any, MutableMap<String, MutableList<ChildSlot>>>,
	internal val presentAttrs: IdentityHashMap<Any, MutableMap<String, MutableSet<String>>>,
) {
	/**
	 * Ensures the replayed child order for [owner] at the [tag] level contains a KnownField slot for
	 * [propertyName], so a field assigned after read reaches the re-emitted document even when the
	 * source carried no such element.
	 *
	 * Amends only an EXISTING recorded order: an owner without one already emits every declared field,
	 * and creating an order here would collapse its output to this single slot.
	 *
	 * @param Any     owner              The object whose child order is amended.
	 * @param String  tag                The serializer tag level the order was recorded under.
	 * @param String  propertyName       The typed property the slot replays.
	 * @param String? beforePropertyName The anchor property to insert before, or null to append.
	 */
	internal fun ensureKnownChildSlot(owner: Any, tag: String, propertyName: String, beforePropertyName: String?) {
		val slots = childOrder[owner]?.get(tag) ?: return
		val alreadyRecorded = slots.any { slot -> slot is ChildSlot.KnownField && slot.propertyName == propertyName }

		if (alreadyRecorded) {
			return
		}

		val newSlot = ChildSlot.KnownField(propertyName)
		val anchorIndex =
			beforePropertyName?.let { anchor ->
				slots.indexOfFirst { slot -> slot is ChildSlot.KnownField && slot.propertyName == anchor }
			} ?: -1

		if (anchorIndex >= 0) {
			slots.add(anchorIndex, newSlot)
		} else {
			slots.add(newSlot)
		}
	}

	/**
	 * Ensures the @SerialAttribute property [propertyName] of [owner] (at the [tag] level) is emitted
	 * on write even when the source document omitted the attribute.
	 *
	 * Amends only an EXISTING record: an owner with no recorded attributes at [tag] already emits every
	 * non-default attribute value.  Attributes emit in declaration order, so no anchor is needed.
	 *
	 * @param Any    owner        The object whose attribute record is amended.
	 * @param String tag          The serializer tag level the record was captured under.
	 * @param String propertyName The Kotlin property name of the attribute to emit.
	 */
	internal fun ensurePresentAttr(owner: Any, tag: String, propertyName: String) {
		presentAttrs[owner]?.get(tag)?.add(propertyName)
	}
}

/**
 * A shared object's preserved identity (xs.id), pool index (xs.idx), and exact def tag, captured at
 * read time.  The [tag] is reused verbatim for references so it always matches the def - even where
 * one class covers many tags (Guid/Id) or the tag differs from the class name (float-array, etc.).
 */
internal class SharedRef(val id: String, val index: Int, val tag: String)

/**
 * One child slot of a reflective object, captured on read so the exact child sequence can be replayed
 * on write.  This makes typed classes tolerant of format evolution (added, removed, or reordered
 * fields) across schema versions while staying byte-identical: a known field is re-serialized from the
 * typed value, an unrecognized one is re-emitted verbatim, and the superclass keeps its position.
 */
internal sealed interface ChildSlot {
	/** A field the serializer recognized, replayed from the typed property [propertyName]. */
	class KnownField(val propertyName: String) : ChildSlot

	/** A child newer than (or unknown to) our schema, replayed verbatim. */
	class VerbatimChild(val element: Element) : ChildSlot

	/** The `<super>` element (superclass fields), replayed via the super serializer. */
	object Super : ChildSlot
}