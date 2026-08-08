package org.umamo.format.cmo3.serialize

import org.umamo.format.cmo3.serialize.descriptors.ClassDescriptor
import org.umamo.format.cmo3.serialize.descriptors.PropertyDescriptor
import org.umamo.format.cmo3.serialize.descriptors.ScalarKind
import org.umamo.format.xml.Element

// Serializer attribute/tag names (the on-disk xs.* attributes and primitive tags).
internal const val ATTR_NAME: String = "xs.n"
internal const val ATTR_ID: String = "xs.id"
internal const val ATTR_REF: String = "xs.ref"
internal const val ATTR_INDEX: String = "xs.idx"
internal const val ATTR_COUNT: String = "count"
internal const val TAG_NULL: String = "null"
internal const val NAME_SUPER: String = "super"

/**
 * Maps a model value to/from an XML element:
 * objects are created empty ([createInstance]) then populated ([setupInstance]) so cyclic/shared
 * references resolve; primitives ignore [setupInstance] and return their value from [createInstance].
 *
 * @see <a href="https://docs.umamo.org/format/CMO3.md">CMO3.md §3 Serializer mechanics</a>
 */
internal interface XmlSerializer {
	/**
	 * Builds the element for [value] (already known non-null and not a back-reference).
	 *
	 * @param String?      name  The owning field name (becomes xs.n), or null for collection items.
	 * @param Any          value The value to serialize.
	 * @param WriteContext ctx   Shared write state (object identity, shared pool).
	 * @return Element The new element.
	 */
	fun createElement(name: String?, value: Any, ctx: WriteContext): Element

	/**
	 * Creates the value/instance for [element] (empty for class objects; the final value for leaves).
	 *
	 * @param Element     element The source element.
	 * @param ReadContext ctx     Shared read state (reference map).
	 * @return Any? The created value (null only for the `<null>` tag, handled by the engine).
	 */
	fun createInstance(element: Element, ctx: ReadContext): Any?

	/**
	 * Populates a class instance's fields from [element]. No-op for leaf values.
	 *
	 * @param Element     element  The source element.
	 * @param Any         instance The instance created by [createInstance].
	 * @param ReadContext ctx      Shared read state.
	 */
	fun setupInstance(element: Element, instance: Any, ctx: ReadContext) {}
}

/** Sets the xs.n attribute when [name] is non-null (collection items omit it). */
internal fun Element.setFieldName(name: String?) {
	if (name != null) setAttribute(ATTR_NAME, name)
}

/**
 * Leaf serializer for a primitive/string: `<tag xs.n="field">value</tag>` (value = toString()).
 * Scalars are written with their natural string form (e.g. 1.0, true).
 */
internal class PrimitiveSerializer(
	private val tag: String,
	private val parse: (String) -> Any,
) : XmlSerializer {
	override fun createElement(name: String?, value: Any, ctx: WriteContext): Element {
		val element = Element(tag)
		element.setFieldName(name)
		element.text = value.toString()
		return element
	}

	override fun createInstance(element: Element, ctx: ReadContext): Any = parse(element.text)
}

/**
 * Serializer for ordered collections: `<tag count="n" xs.n="field">` with positional item children
 * (items carry no xs.n). Covers ArrayList ("array_list") and CArrayList ("carray_list"); [factory]
 * builds the concrete list type so the tag round-trips (read tag -> type -> same tag on write).
 */
internal class ListSerializer(
	private val tag: String,
	private val factory: () -> MutableList<Any?>,
) : XmlSerializer {
	override fun createElement(name: String?, value: Any, ctx: WriteContext): Element {
		val list = value as List<*>
		val element = Element(tag)
		element.setFieldName(name)
		element.setAttribute(ATTR_COUNT, list.size.toString())
		for (item in list) element.addContent(ctx.createElementFromObject(null, item))
		return element
	}

	override fun createInstance(element: Element, ctx: ReadContext): Any {
		val out = factory()
		for (child in element.children) out.add(ctx.createObjectFromElement(child))
		return out
	}
}

/**
 * Descriptor-driven serializer for a model class, bound to one class in the hierarchy: writes the
 * descriptor's properties as child elements (xs.n = the serial name) in backing-field declaration
 * order, and nests the superclass as a `<SuperTag xs.n="super">` child via the super descriptor.
 *
 * @see <a href="https://docs.umamo.org/format/CMO3.md">CMO3.md §3 Serializer mechanics</a>
 */
internal class DescriptorClassSerializer(
	private val descriptor: ClassDescriptor,
	private val registry: SerializerRegistry,
) : XmlSerializer {
	private val tag: String = descriptor.tag

	// Attribute-serialized properties are written on the tag; the rest are child elements.
	private val attributeProperties: List<PropertyDescriptor> =
		descriptor.properties.filter { it.attributeName != null }
	private val childProperties: List<PropertyDescriptor> =
		descriptor.properties.filter { it.attributeName == null }
	private val childPropertiesByName: Map<String, PropertyDescriptor> =
		childProperties.associateBy { it.serialName }

	// Keyed by Kotlin property name, for replaying a recorded child order on write.
	private val childPropertiesByPropName: Map<String, PropertyDescriptor> =
		childProperties.associateBy { it.name }

	// The resolved @DontSerializeIfDefault set: skip a property equal to the default instance's value.
	private val skipIfDefault: Set<String> =
		descriptor.properties.filter { it.skipIfDefault }.map { it.name }.toSet()
	private val defaultInstance: Any? by lazy {
		if (skipIfDefault.isEmpty()) null else runCatching { descriptor.factory() }.getOrNull()
	}

	// Serializer for the (serializable, non-Object) superclass, if any.  Super descriptors reach
	// here through the descriptor chain and need no tag registration of their own.
	private val superSerializer: DescriptorClassSerializer? by lazy {
		descriptor.superDescriptor?.let { superDescriptor -> registry.classSerializer(superDescriptor) }
	}

	override fun createElement(name: String?, value: Any, ctx: WriteContext): Element {
		val element = Element(tag)
		element.setFieldName(name)
		ctx.setWritten(value, element) // before fields, so self/cyclic refs resolve
		// Attributes (on the tag): when this object was read, keep exactly the attributes it had; for a
		// freshly built object fall back to dropping @DontSerializeIfDefault defaults.
		val presentAttrs = ctx.presentAttrsFor(value, tag)
		for (property in attributeProperties) {
			val propertyValue = property.get(value)
			val omit =
				if (presentAttrs != null) {
					property.name !in presentAttrs
				} else {
					property.name in skipIfDefault && isDefaultValue(property, propertyValue)
				}
			if (omit) continue
			if (propertyValue != null) element.setAttribute(property.attributeName!!, propertyValue.toString())
		}
		// Child elements: replay the exact sequence read (order, presence and unknown children all
		// preserved across schema versions); otherwise emit super + own fields in declaration order.
		val order = ctx.childOrderFor(value, tag)
		if (order != null) {
			for (slot in order) {
				when (slot) {
					is ChildSlot.Super ->
						superSerializer?.let {
							element.addContent(
								it.createElement(
									NAME_SUPER,
									value,
									ctx,
								),
							)
						}

					is ChildSlot.VerbatimChild -> element.addContent(slot.element.clone())
					is ChildSlot.KnownField ->
						childPropertiesByPropName[slot.propertyName]?.let { property ->
							element.addContent(ctx.createElementFromObject(property.serialName, property.get(value)))
						}
				}
			}
		} else {
			superSerializer?.let { element.addContent(it.createElement(NAME_SUPER, value, ctx)) }
			for (property in childProperties) {
				val propertyValue = property.get(value)
				if (property.name in skipIfDefault && isDefaultValue(property, propertyValue)) continue
				element.addContent(ctx.createElementFromObject(property.serialName, propertyValue))
			}
		}
		return element
	}

	private fun isDefaultValue(property: PropertyDescriptor, propertyValue: Any?): Boolean {
		val default = defaultInstance ?: return false
		return propertyValue == property.get(default)
	}

	override fun createInstance(element: Element, ctx: ReadContext): Any = descriptor.factory()

	override fun setupInstance(element: Element, instance: Any, ctx: ReadContext) {
		for (property in attributeProperties) {
			val raw = element.getAttributeValue(property.attributeName!!) ?: continue
			ctx.recordPresentAttr(instance, tag, property.name)
			// A hand-built descriptor without a kind falls back to the raw string, matching the
			// historical scalar parse for non-primitive attribute types.
			property.set(instance, (property.scalarKind ?: ScalarKind.STRING).parse(raw))
		}
		for (child in element.children) {
			val fieldName = child.getAttributeValue(ATTR_NAME)
			if (fieldName == NAME_SUPER) {
				val superSerializer = this.superSerializer
				if (superSerializer != null) {
					superSerializer.setupInstance(child, instance, ctx)
					ctx.recordSuperChild(instance, tag)
				} else {
					ctx.recordVerbatimChild(instance, tag, child) // unmodeled superclass - keep verbatim
				}
				continue
			}
			val property = childPropertiesByName[fieldName]
			if (property == null) {
				ctx.recordVerbatimChild(instance, tag, child) // a field newer than our schema - keep it
				continue
			}
			ctx.recordKnownChild(instance, tag, property.name)
			property.set(instance, ctx.createObjectFromElement(child))
		}
	}
}