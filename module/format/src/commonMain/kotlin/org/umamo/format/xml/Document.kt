package org.umamo.format.xml

/**
 * An XML document: one root [Element] plus any document-level [ProcessingInstruction]s/[Comment]s,
 * in order.
 *
 * Mirrors the org.jdom.Document 1.1.3 subset the CMO3 serializer uses.  CMO3 keeps its prologue
 * PIs (`<?version?>`/`<?import?>`) as document content BEFORE the root element (CMO3.md §3
 * Document shape), which is why the content list — not just the root — is part of the model.
 */
public class Document(rootElement: Element) : Parent {
	private val contentList = ArrayList<Content>()

	init {
		require(rootElement.parent == null) { "root element already has a parent" }
		rootElement.parent = this
		contentList.add(rootElement)
	}

	/** The document content in order: PIs/comments and the single root element (live read-only view). */
	public val content: List<Content> get() = contentList

	/** The root element (the single Element in the content list). */
	public val rootElement: Element
		get() = contentList.first { node -> node is Element } as Element

	/**
	 * Inserts [content] at [index] in the document content list and attaches it.  A second Element
	 * is rejected — a document has exactly one root.
	 *
	 * @param Int     index   The insertion index.
	 * @param Content content The node to insert; must be detached and not an Element.
	 * @return Document This document, for chaining.
	 */
	public fun addContent(index: Int, content: Content): Document {
		require(content !is Element) { "document already has a root element" }
		require(content.parent == null) { "content already has a parent; clone() it or remove it first" }
		content.parent = this
		contentList.add(index, content)
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
}