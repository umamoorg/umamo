package org.umamo.format.cmo3.serialize

import org.umamo.format.cmo3.model.type.CAffine
import org.umamo.format.xml.Element

/**
 * Custom serializer for CAffine: `<CAffine m00="…" m01="…" m02="…" m10="…" m11="…" m12="…" />`.
 * The six matrix floats are written/read as attributes.
 *
 * @see <a href="https://docs.umamo.org/format/CMO3.md">CMO3.md §3 Serializer mechanics</a>
 */
internal object CAffineSerializer : XmlSerializer {
	override fun createElement(name: String?, value: Any, ctx: WriteContext): Element {
		val affine = value as CAffine
		val element = Element("CAffine")
		element.setFieldName(name)
		element.setAttribute("m00", affine.m00.toString())
		element.setAttribute("m01", affine.m01.toString())
		element.setAttribute("m02", affine.m02.toString())
		element.setAttribute("m10", affine.m10.toString())
		element.setAttribute("m11", affine.m11.toString())
		element.setAttribute("m12", affine.m12.toString())
		return element
	}

	override fun createInstance(element: Element, ctx: ReadContext): Any =
		CAffine().apply {
			m00 = requiredMatrixCell(element, "m00")
			m01 = requiredMatrixCell(element, "m01")
			m02 = requiredMatrixCell(element, "m02")
			m10 = requiredMatrixCell(element, "m10")
			m11 = requiredMatrixCell(element, "m11")
			m12 = requiredMatrixCell(element, "m12")
		}

	/**
	 * Reads one required CAffine matrix attribute.  A missing cell is malformed input, and the
	 * throw lands in the engine's verbatim fallback like any other deserialize failure.
	 *
	 * @param Element element       The CAffine element.
	 * @param String  attributeName The matrix cell attribute name.
	 * @return Float The parsed cell value.
	 */
	private fun requiredMatrixCell(element: Element, attributeName: String): Float =
		element.getAttributeValue(attributeName)?.toFloat()
			?: error("CAffine is missing attribute $attributeName")
}

/**
 * Registers the value-type subsystem (GVector2, CRect, CColor reflective; CAffine custom).
 *
 * @param SerializerRegistry registry The registry to populate.
 */
internal fun registerValueTypeSubsystem(registry: SerializerRegistry) {
	registry.register(org.umamo.format.cmo3.model.type.GVector2::class)
	registry.register(org.umamo.format.cmo3.model.type.CRect::class)
	registry.register(org.umamo.format.cmo3.model.type.CColor::class)
	registry.registerCustom(CAffine::class, CAffineSerializer)
}