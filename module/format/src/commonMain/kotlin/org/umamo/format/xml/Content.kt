package org.umamo.format.xml

/**
 * A node that owns an ordered content list: an [Element] or the [Document].
 *
 * Mirrors the org.jdom.Parent subset the CMO3 serializer uses.  Lookup and removal are by object
 * IDENTITY, matching JDOM 1.1.3, whose Content does not override equals — two value-equal nodes are
 * still distinct list entries, and the serializer's hoist-and-replace moves depend on that.
 */
public sealed interface Parent {
	/**
	 * The index of [content] in this parent's content list, by identity.
	 *
	 * @param Content content The node to locate.
	 * @return Int The index, or -1 when [content] is not a direct child of this parent.
	 */
	public fun indexOf(content: Content): Int

	/**
	 * Removes [content] from this parent's content list, by identity, detaching its parent link.
	 *
	 * @param Content content The node to remove.
	 * @return Boolean Whether the node was found and removed.
	 */
	public fun removeContent(content: Content): Boolean
}

/**
 * A node that can appear in a [Parent]'s content list.
 *
 * Mirrors the org.jdom.Content subset the CMO3 serializer uses: a parent back-link and deep copy.
 * Deliberately no equals/hashCode overrides — identity semantics are load-bearing (see [Parent]).
 */
public sealed class Content {
	/** The parent holding this node, or null while detached.  Managed by attach/remove only. */
	public var parent: Parent? = null
		internal set

	/**
	 * Deep-copies this node.  The copy is detached (null parent), matching JDOM clone semantics.
	 *
	 * @return Content The detached copy.
	 */
	public abstract fun clone(): Content
}

/**
 * A run of character data.
 *
 * Stands in for both org.jdom.Text and CDATA sections: the parser folds CDATA content into plain
 * text (the CMO3 corpus contains none, and JDOM's getText aggregation treats them identically).
 */
public class Text(
	/** The raw character data, unescaped and untrimmed (trimming is the emitter's job). */
	public var text: String,
) : Content() {
	/**
	 * Deep-copies this text node.
	 *
	 * @return Text The detached copy.
	 */
	override fun clone(): Text = Text(text)
}

/**
 * An XML comment, preserved verbatim.
 *
 * The CMO3 corpus is machine-written and carries none; the node exists so a third-party-written
 * document with a comment survives a round trip instead of crashing the reader.
 */
public class Comment(
	/** The comment text between the delimiters, verbatim. */
	public var text: String,
) : Content() {
	/**
	 * Deep-copies this comment node.
	 *
	 * @return Comment The detached copy.
	 */
	override fun clone(): Comment = Comment(text)
}

/**
 * A processing instruction, e.g. the CMO3 prologue's `<?version Name:N?>` / `<?import fqcn?>` PIs.
 *
 * CMO3: main.xml document-level PIs before the root element (CMO3.md §3 Document shape).
 */
public class ProcessingInstruction(
	/** The PI target (the first token, e.g. "version" or "import"). */
	public val target: String,
	/** The PI data (everything after the target, without the trailing "?>"). */
	public val data: String,
) : Content() {
	/**
	 * Deep-copies this processing instruction.
	 *
	 * @return ProcessingInstruction The detached copy.
	 */
	override fun clone(): ProcessingInstruction = ProcessingInstruction(target, data)
}