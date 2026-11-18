package org.umamo.format.cmo3.serialize

import org.jdom.Attribute
import org.jdom.Document
import org.jdom.Element
import org.jdom.ProcessingInstruction
import java.util.IdentityHashMap
import kotlin.reflect.KClass

/**
 * Top-level model (de)serializer: turns a typed root object into a `<root><shared/><main/></root>`
 * JDOM document (and back), reproducing the editor's shared-pool hoisting and `<?version?>` PIs.
 *
 * @see <a href="https://docs.umamo.org/format/CMO3.md">CMO3.md §3 Payload: main.xml</a>
 */
public class SerializeEngine internal constructor(
	private val registry: SerializerRegistry,
	private val diagnostics: SerializeDiagnostics,
) {
	/**
	 * Serializes [root] into a model document.
	 *
	 * @param Any root The model root object (e.g. CModelSource).
	 * @return Document The `<root>` document with shared pool and version PIs.
	 */
	public fun writeRoot(root: Any): Document {
		val context = WriteContext(registry)
		val rootElement = Element(ModelDocument.ROOT_ELEMENT)
		val sharedElement = Element(ModelDocument.SHARED_ELEMENT)
		val mainElement = Element(ModelDocument.MAIN_ELEMENT)
		rootElement.addContent(sharedElement)
		rootElement.addContent(mainElement)

		mainElement.addContent(context.createElementFromObject(null, root))

		// Hoist each shared def into <shared> and replace its first (inline) occurrence with a ref.
		for (def in context.sharedDefs.sortedBy { it.index }) {
			val defElement = def.element
			val parent = defElement.parent as Element
			val position = parent.indexOf(defElement)
			val reference = Element(defElement.name)
			defElement.getAttributeValue(ATTR_NAME)?.let { reference.setAttribute(ATTR_NAME, it) }
			reference.setAttribute(ATTR_REF, def.refId)
			parent.removeContent(defElement)
			parent.addContent(position, reference)
			defElement.removeAttribute(ATTR_NAME)
			defElement.setAttribute(ATTR_INDEX, def.index.toString())
			sharedElement.addContent(defElement)
		}

		val document = Document(rootElement)
		registry.versions().forEachIndexed { piIndex, (tag, version) ->
			document.addContent(piIndex, ProcessingInstruction("version", "$tag:$version"))
		}

		return document
	}

	/**
	 * Deserializes a model document's `<main>` root object, resolving the shared pool first.
	 *
	 * @param Document document The model document.
	 * @return Any? The reconstructed root object.
	 */
	public fun readRoot(document: Document): Any? {
		val rootElement = document.rootElement
		val context = ReadContext(registry, diagnostics)

		@Suppress("UNCHECKED_CAST")
		val sharedChildren = (rootElement.getChild(ModelDocument.SHARED_ELEMENT)?.children as List<Element>?).orEmpty()
		val created = ArrayList<Pair<Element, Any>>()

		for (element in sharedChildren) {
			val id = element.getAttributeValue(ATTR_ID) ?: continue
			val serializer = registry.serializerForTag(element.name) ?: continue
			val instance = serializer.createInstance(element, context) ?: continue
			context.references[id] = instance
			created.add(element to instance)
		}

		for ((element, instance) in created) {
			registry.serializerForTag(element.name)?.setupInstance(element, instance, context)
		}

		@Suppress("UNCHECKED_CAST")
		val mainChild =
			(rootElement.getChild(ModelDocument.MAIN_ELEMENT)?.children as List<Element>?)
				?.firstOrNull() ?: return null

		return context.createObjectFromElement(mainChild)
	}

	/**
	 * Reads a model document into a [ModelGraph]: the `<main>` root object plus the shared pool
	 * (preserving each shared object's xs.id/xs.idx and order), the processing instructions, and the
	 * `<root>` attributes - everything needed to re-emit byte-identically.
	 *
	 * @param Document document The model document.
	 * @return ModelGraph The reconstructed graph with preserved identity metadata.
	 */
	public fun readModel(document: Document): ModelGraph {
		val rootElement = document.rootElement
		val context = ReadContext(registry, diagnostics)
		val sharedOrder = ArrayList<Any>()
		val sharedInfo = IdentityHashMap<Any, SharedRef>()

		@Suppress("UNCHECKED_CAST")
		val sharedChildren = (rootElement.getChild(ModelDocument.SHARED_ELEMENT)?.children as List<Element>?).orEmpty()
		val typedToSetup = ArrayList<Pair<Element, Any>>()

		for (element in sharedChildren) {
			val id = element.getAttributeValue(ATTR_ID) ?: continue
			val index = element.getAttributeValue(ATTR_INDEX)?.toIntOrNull() ?: -1
			val serializer = registry.serializerForTag(element.name)
			val instance: Any =
				if (serializer == null) {
					diagnostics.onUnmodeledTag(element.name, "(shared)")
					VerbatimNode(element.clone() as Element)
				} else {
					serializer.createInstance(element, context) ?: continue
				}
			context.references[id] = instance
			sharedInfo[instance] = SharedRef(id, index, element.name)
			sharedOrder.add(instance)

			if (serializer != null) {
				typedToSetup.add(element to instance)
			}
		}

		for ((element, instance) in typedToSetup) {
			registry.serializerForTag(element.name)?.setupInstance(element, instance, context)
		}

		@Suppress("UNCHECKED_CAST")
		val mainChild = (rootElement.getChild(ModelDocument.MAIN_ELEMENT)?.children as List<Element>?)?.firstOrNull()
		val rootObject = mainChild?.let { context.createObjectFromElement(it) }

		val instructions =
			document.content.filterIsInstance<ProcessingInstruction>().map { it.clone() as ProcessingInstruction }

		@Suppress("UNCHECKED_CAST")
		val rootAttributes = (rootElement.attributes as List<Attribute>).map { it.name to it.value }

		return ModelGraph(
			rootObject,
			sharedOrder,
			sharedInfo,
			instructions,
			rootAttributes,
			context.childOrder,
			context.presentAttrs,
		)
	}

	/**
	 * Re-emits a [ModelGraph] to a document, reusing the preserved shared ids/order and PIs so an
	 * unedited graph round-trips byte-identical.
	 *
	 * @param ModelGraph graph The graph to serialize.
	 * @return Document The reconstructed model document.
	 */
	public fun writeModel(graph: ModelGraph): Document {
		val rootElement = Element(ModelDocument.ROOT_ELEMENT)
		for ((attrName, attrValue) in graph.rootAttributes) rootElement.setAttribute(attrName, attrValue)
		val sharedElement = Element(ModelDocument.SHARED_ELEMENT)
		val mainElement = Element(ModelDocument.MAIN_ELEMENT)
		rootElement.addContent(sharedElement)
		rootElement.addContent(mainElement)

		val context = WriteContext(registry, graph.sharedInfo, graph.childOrder, graph.presentAttrs)
		graph.root?.let { mainElement.addContent(context.createElementFromObject(null, it)) }

		for (instance in graph.sharedOrder) {
			val sharedRef = graph.sharedInfo.getValue(instance)
			val defElement: Element =
				if (instance is VerbatimNode) {
					instance.element.clone() as Element
				} else {
					val element = registry.serializerForValue(instance).createElement(null, instance, context)
					element.setAttribute(ATTR_ID, sharedRef.id)
					element.setAttribute(ATTR_INDEX, sharedRef.index.toString())
					element
				}
			sharedElement.addContent(defElement)
		}

		// Hoist fresh shared defs (objects added after read whose second use stamped an xs.id on the
		// first inline def) into <shared>, the same replace-with-ref move writeRoot performs.  This
		// runs after both the main and the preserved-pool emission so defs nested inside preserved
		// shared subtrees are caught too.  xs.idx continues past the preserved maximum, keeping fresh
		// entries clear of the source document's pool indexes.
		var nextSharedIndex = (graph.sharedInfo.values.maxOfOrNull { sharedRef -> sharedRef.index } ?: -1) + 1

		for (def in context.sharedDefs.sortedBy { it.index }) {
			val defElement = def.element
			val parent = defElement.parent as Element
			val position = parent.indexOf(defElement)
			val reference = Element(defElement.name)
			defElement.getAttributeValue(ATTR_NAME)?.let { reference.setAttribute(ATTR_NAME, it) }
			reference.setAttribute(ATTR_REF, def.refId)
			parent.removeContent(defElement)
			parent.addContent(position, reference)
			defElement.removeAttribute(ATTR_NAME)
			defElement.setAttribute(ATTR_INDEX, nextSharedIndex.toString())
			sharedElement.addContent(defElement)
			nextSharedIndex++
		}

		val document = Document(rootElement)
		graph.processingInstructions.forEachIndexed { piIndex, instruction ->
			document.addContent(piIndex, instruction.clone() as ProcessingInstruction)
		}

		return document
	}

	public companion object {
		/**
		 * Builds an engine for the given model classes (registered by their @SerialTag / simple name).
		 *
		 * @param Collection         classes     The model classes to register.
		 * @param SerializeDiagnostics diagnostics Sink for unmodeled-tag reports (default no-op).
		 * @return SerializeEngine A ready engine.
		 */
		public fun of(
			classes: Collection<KClass<*>>,
			diagnostics: SerializeDiagnostics = SerializeDiagnostics.None,
		): SerializeEngine {
			val registry = SerializerRegistry()
			classes.forEach(registry::register)

			return SerializeEngine(registry, diagnostics)
		}
	}
}