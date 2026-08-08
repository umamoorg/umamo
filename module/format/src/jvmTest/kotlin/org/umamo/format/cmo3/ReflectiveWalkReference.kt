package org.umamo.format.cmo3

import org.umamo.format.cmo3.model.custom.CImageResource
import org.umamo.format.cmo3.serialize.ATTR_REF
import org.umamo.format.cmo3.serialize.ChildSlot
import org.umamo.format.cmo3.serialize.ModelGraph
import org.umamo.format.cmo3.serialize.VerbatimNode
import org.umamo.format.xml.Element

/**
 * Frozen java.lang.reflect reference implementations of the two model-graph walks, kept
 * permanently as the behavior baseline WalkerParityTest compares the production walkers against
 * over the corpus.  These copies descend by declared-field enumeration with package gates (an
 * allow-list for the image-resource walk, a deny-list for the reachability walk); the production
 * walkers must visit exactly the same objects however they enumerate a class's state.
 */
internal object ReflectiveWalkReference {
	private const val MODEL_PACKAGE = "org.umamo.format.cmo3.model"

	/**
	 * Reference copy of the [Cmo3Model.imageResources] walk: every [CImageResource] reachable from
	 * the graph root and the shared pool, in LIFO visit order.
	 *
	 * @param ModelGraph graph The model graph to walk.
	 * @return List The reachable image resources in visit order.
	 */
	fun imageResources(graph: ModelGraph): List<CImageResource> {
		val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())
		val out = ArrayList<CImageResource>()
		val stack = ArrayDeque<Any?>()
		graph.root?.let(stack::addLast)
		graph.sharedOrder.forEach(stack::addLast)
		while (stack.isNotEmpty()) {
			val obj = stack.removeLast() ?: continue
			if (obj is CharSequence || obj is Number || obj is Boolean || obj is Char || obj is Enum<*>) continue
			if (!seen.add(obj)) continue
			if (obj is CImageResource) out.add(obj)
			when (obj) {
				is Iterable<*> -> obj.forEach(stack::addLast)
				is Map<*, *> -> obj.values.forEach(stack::addLast)
				is Array<*> -> obj.forEach(stack::addLast)
				else ->
					if (obj::class.java.name.startsWith(MODEL_PACKAGE)) {
						var cls: Class<*>? = obj::class.java
						while (cls != null && cls != Any::class.java) {
							for (field in cls.declaredFields) {
								if (java.lang.reflect.Modifier.isStatic(field.modifiers)) continue
								field.isAccessible = true
								stack.addLast(field.get(obj))
							}
							cls = cls.superclass
						}
					}
			}
		}
		return out
	}

	/**
	 * Reference copy of the reachability walk behind [Cmo3GraphEditor.pruneUnreachableShared]: the
	 * identity set of every non-leaf object reachable from the model root, following typed fields,
	 * collections, and xs.ref attributes inside verbatim subtrees.
	 *
	 * @param ModelGraph graph The model graph to walk.
	 * @return Set The reachable objects (an identity set).
	 */
	fun reachableObjects(graph: ModelGraph): Set<Any> {
		val sharedById = HashMap<String, Any>()
		for (instance in graph.sharedOrder) {
			graph.sharedInfo[instance]?.let { sharedRef -> sharedById[sharedRef.id] = instance }
		}
		val reachable = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())
		val stack = ArrayDeque<Any?>()
		graph.root?.let(stack::addLast)
		while (stack.isNotEmpty()) {
			val obj = stack.removeLast() ?: continue
			if (obj is CharSequence || obj is Number || obj is Boolean || obj is Char || obj is Enum<*>) {
				continue
			}
			if (!reachable.add(obj)) {
				continue
			}
			graph.childOrder[obj]?.values?.forEach { slotsAtTag ->
				for (slot in slotsAtTag) {
					if (slot is ChildSlot.VerbatimChild) {
						addVerbatimRefTargets(slot.element, sharedById, stack)
					}
				}
			}
			when (obj) {
				is VerbatimNode -> addVerbatimRefTargets(obj.element, sharedById, stack)
				is Iterable<*> -> obj.forEach(stack::addLast)
				is Map<*, *> -> {
					obj.keys.forEach(stack::addLast)
					obj.values.forEach(stack::addLast)
				}

				is Array<*> -> obj.forEach(stack::addLast)
				else ->
					if (shouldWalkFields(obj)) {
						var currentClass: Class<*>? = obj::class.java
						while (currentClass != null && currentClass != Any::class.java) {
							for (field in currentClass.declaredFields) {
								if (java.lang.reflect.Modifier.isStatic(field.modifiers)) {
									continue
								}
								field.isAccessible = true
								stack.addLast(field.get(obj))
							}
							currentClass = currentClass.superclass
						}
					}
			}
		}
		return reachable
	}

	/**
	 * True when the reference reachability walk descends into [obj]'s declared fields: every
	 * graph-attached class except platform and XML DOM types.
	 *
	 * @param Any obj The object under consideration.
	 * @return Boolean Whether to walk its fields.
	 */
	private fun shouldWalkFields(obj: Any): Boolean {
		val className = obj::class.java.name
		return !className.startsWith("java.") &&
			!className.startsWith("javax.") &&
			!className.startsWith("kotlin.") &&
			!className.startsWith("org.umamo.format.xml.")
	}

	/**
	 * Pushes the shared objects targeted by xs.ref attributes anywhere in [element]'s subtree onto
	 * the reachability [stack].
	 *
	 * @param Element    element    The verbatim subtree to scan.
	 * @param Map        sharedById The pool keyed by preserved xs.id.
	 * @param ArrayDeque stack      The walk stack to extend.
	 */
	private fun addVerbatimRefTargets(element: Element, sharedById: Map<String, Any>, stack: ArrayDeque<Any?>) {
		element.getAttributeValue(ATTR_REF)?.let { refId -> sharedById[refId]?.let(stack::addLast) }
		for (child in element.children) {
			addVerbatimRefTargets(child, sharedById, stack)
		}
	}
}