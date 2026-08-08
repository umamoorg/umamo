package org.umamo.format.xml

/**
 * One element attribute: a name/value pair.
 *
 * Immutable, unlike org.jdom.Attribute — [Element.setAttribute] replaces the instance in place, so
 * mutability buys nothing and immutability lets [Element.clone] share instances safely.
 */
public class Attribute(
	/** The attribute name (no namespace — the CMO3 envelope has none). */
	public val name: String,
	/** The attribute value, unescaped. */
	public val value: String,
)