package org.umamo.format.cmo3.serialize

import org.umamo.format.cmo3.model.type.FileRef
import org.umamo.format.cmo3.serialize.gen.GeneratedDescriptors
import org.umamo.format.xml.Element

/**
 * Serializer for the `<file xs.n="…" path="…"/>` element. Mirrors the editor's writeFile: the path
 * names a de-duplicated CAFF entry; the bytes are managed by the container, not the XML.
 *
 * @see <a href="https://docs.umamo.org/format/CMO3.md">CMO3.md §1 Container / §3 Payload</a>
 */
internal object FileSerializer : XmlSerializer {
	override fun createElement(name: String?, value: Any, ctx: WriteContext): Element {
		val element = Element("file")
		element.setFieldName(name)
		val fileRef = value as FileRef
		val archivePath = fileRef.archivePath
		if (archivePath != null) {
			element.setAttribute("path", archivePath)
		} else {
			element.text = fileRef.textPath ?: ""
		}
		return element
	}

	override fun createInstance(element: Element, ctx: ReadContext): Any {
		val path = element.getAttributeValue("path")
		return FileRef().apply {
			if (path != null) archivePath = path else textPath = element.text
		}
	}
}

/**
 * Registers the hand-written custom subsystem: the `<file>` element plus the attribute-serialized
 * leaf/value classes (modelled with @SerialAttribute descriptor properties), including the
 * CModelSource root.
 *
 * @param SerializerRegistry registry The registry to populate.
 */
internal fun registerCustomSubsystem(registry: SerializerRegistry) {
	registry.registerCustomTag("file", FileRef::class, FileSerializer)
	registry.register(GeneratedDescriptors.cFloatColor)
	registry.register(GeneratedDescriptors.cLabelColor)
	registry.register(GeneratedDescriptors.warpDeformerOriginalShape)
	registry.register(GeneratedDescriptors.rotationDeformerOriginalShape)
	registry.register(GeneratedDescriptors.cSize)
	registry.register(GeneratedDescriptors.cRotationDeformerForm)
	registry.register(GeneratedDescriptors.cWritableImage)
	registry.register(GeneratedDescriptors.cImageResource)
	registry.register(GeneratedDescriptors.filterInstance)
	registry.register(GeneratedDescriptors.cModelImage)
	registry.register(GeneratedDescriptors.gEditableMesh2)
	registry.register(GeneratedDescriptors.cLayer)
	registry.register(GeneratedDescriptors.cModelSource)
}