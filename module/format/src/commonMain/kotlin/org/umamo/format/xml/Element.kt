package org.umamo.format.xml

/**
 * An XML element: a name, ordered attributes, and an ordered content list.
 *
 * Mirrors the org.jdom.Element 1.1.3 subset the CMO3 serializer uses, member for member, so the
 * serializer port is an import swap.  Namespaces are absent by design — the CMO3 envelope has none
 * and the parser rejects them.  See docs/format/CMO3.md §3.
 */
public class Element(
	/** The element tag name. */
	public val name: String,
) : Content(), Parent {
	private val attributeList = ArrayList<Attribute>()
	private val contentList = ArrayList<Content>()

	/** The attributes in document order (live read-only view). */
	public val attributes: List<Attribute> get() = attributeList

	/** The content nodes in document order (live read-only view). */
	public val content: List<Content> get() = contentList

	/** The child elements in document order, skipping text/comment/PI content. */
	public val children: List<Element> get() = contentList.filterIsInstance<Element>()

	/**
	 * The concatenated text of the direct Text children, or "" when there are none.  Never trimmed
	 * — trimming happens at emission (JDOM Element.getText semantics).
	 *
	 * Setting replaces the WHOLE content list with a single [Text] node, even for the empty string
	 * (JDOM Element.setText semantics).
	 */
	public var text: String
		get() {
			val builder = StringBuilder()
			for (node in contentList) {
				if (node is Text) {
					builder.append(node.text)
				}
			}
			return builder.toString()
		}
		set(value) {
			for (node in contentList) {
				node.parent = null
			}
			contentList.clear()
			addContent(Text(value))
		}

	/**
	 * The value of the attribute named [attributeName], or null when absent.
	 *
	 * @param String attributeName The attribute name.
	 * @return String? The value, or null.
	 */
	public fun getAttributeValue(attributeName: String): String? =
		attributeList.firstOrNull { attribute -> attribute.name == attributeName }?.value

	/**
	 * Sets attribute [attributeName] to [value].  An existing attribute of that name is replaced
	 * IN PLACE, keeping its position in document order; a new one appends (JDOM AttributeList.add
	 * duplicate handling).
	 *
	 * @param String attributeName The attribute name.
	 * @param String value         The attribute value, unescaped.
	 */
	public fun setAttribute(attributeName: String, value: String) {
		val existingIndex = attributeList.indexOfFirst { attribute -> attribute.name == attributeName }
		if (existingIndex >= 0) {
			attributeList[existingIndex] = Attribute(attributeName, value)
		} else {
			attributeList.add(Attribute(attributeName, value))
		}
	}

	/**
	 * Removes the attribute named [attributeName], if present.
	 *
	 * @param String attributeName The attribute name.
	 * @return Boolean Whether an attribute was removed.
	 */
	public fun removeAttribute(attributeName: String): Boolean {
		val existingIndex = attributeList.indexOfFirst { attribute -> attribute.name == attributeName }
		if (existingIndex < 0) {
			return false
		}
		attributeList.removeAt(existingIndex)
		return true
	}

	/**
	 * The first child element named [childName], or null.
	 *
	 * @param String childName The child element name.
	 * @return Element? The first matching child element, or null.
	 */
	public fun getChild(childName: String): Element? =
		contentList.firstOrNull { node -> node is Element && node.name == childName } as Element?

	/**
	 * Appends [child] to the content list and attaches it.
	 *
	 * @param Content child The node to append; must be detached.
	 * @return Element This element, for chaining.
	 */
	public fun addContent(child: Content): Element {
		attach(child)
		contentList.add(child)
		return this
	}

	/**
	 * Inserts [child] at [index] in the content list and attaches it.
	 *
	 * @param Int     index The insertion index.
	 * @param Content child The node to insert; must be detached.
	 * @return Element This element, for chaining.
	 */
	public fun addContent(index: Int, child: Content): Element {
		attach(child)
		contentList.add(index, child)
		return this
	}

	override fun indexOf(content: Content): Int = contentList.indexOfIdentity(content)

	override fun removeContent(content: Content): Boolean {
		val index = contentList.indexOfIdentity(content)
		if (index < 0) {
			return false
		}
		contentList.removeAt(index)
		content.parent = null
		return true
	}

	/**
	 * Deep-copies this element: attributes (shared — immutable) and the recursively cloned content
	 * subtree.  The copy is detached.
	 *
	 * @return Element The detached copy.
	 */
	override fun clone(): Element {
		val copy = Element(name)
		copy.attributeList.addAll(attributeList)
		for (node in contentList) {
			copy.addContent(node.clone())
		}
		return copy
	}

	/**
	 * Marks [child] as owned by this element.  Adding an already-attached node is a bug in the
	 * caller (it would silently alias one node into two trees), so it throws, matching JDOM's
	 * IllegalAddException.
	 *
	 * @param Content child The node about to enter the content list.
	 */
	private fun attach(child: Content) {
		require(child.parent == null) { "content already has a parent; clone() it or remove it first" }
		child.parent = this
	}
}

/**
 * The index of [node] in this list by object identity, or -1.  JDOM's ContentList reaches the same
 * behavior through equals on classes that never override it; ours states the intent directly.
 *
 * @param Content node The node to locate.
 * @return Int The index, or -1.
 */
internal fun List<Content>.indexOfIdentity(node: Content): Int {
	for (index in indices) {
		if (this[index] === node) {
			return index
		}
	}
	return -1
}