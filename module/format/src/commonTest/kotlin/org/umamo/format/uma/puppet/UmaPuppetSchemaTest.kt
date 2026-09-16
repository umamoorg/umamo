@file:OptIn(ExperimentalSerializationApi::class)

package org.umamo.format.uma.puppet

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Guards the retained-tree merge against schema drift: every array of objects the puppet entry can hold must
 * have an identity rule, or the unknown keys its elements carry could not survive a save (D10).
 */
class UmaPuppetSchemaTest {
	/**
	 * Every array of objects reachable from the puppet entry's root has an identity rule.
	 */
	@Test
	fun everyArrayOfObjectsHasAnIdentity() {
		val missing = ArrayList<String>()
		val visited = HashSet<String>()

		/**
		 * Walks one descriptor, recording arrays of objects without an identity rule.
		 *
		 * @param SerialDescriptor descriptor The schema node.
		 * @param String           path       Its position, for the report.
		 */
		fun walk(descriptor: SerialDescriptor, path: String) {
			when (descriptor.kind) {
				StructureKind.CLASS -> {
					if (!visited.add(descriptor.serialName.removeSuffix("?"))) {
						return
					}
					for (elementIndex in 0 until descriptor.elementsCount) {
						walk(descriptor.getElementDescriptor(elementIndex), "$path.${descriptor.getElementName(elementIndex)}")
					}
				}

				StructureKind.LIST -> {
					val element = descriptor.getElementDescriptor(0)
					if (element.kind == StructureKind.CLASS && UmaPuppetEntry.identities.ruleFor(element.serialName.removeSuffix("?")) == null) {
						missing += "$path (${element.serialName})"
					}
					walk(element, "$path[]")
				}

				else -> {}
			}
		}
		walk(UmaPuppet.serializer().descriptor, "puppet")
		assertTrue(missing.isEmpty(), "arrays of objects without an identity rule: $missing")
	}
}