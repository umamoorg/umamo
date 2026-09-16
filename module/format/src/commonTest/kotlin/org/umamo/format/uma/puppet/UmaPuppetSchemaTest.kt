@file:OptIn(ExperimentalSerializationApi::class)

package org.umamo.format.uma.puppet

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Guards the retained-tree merge against schema drift: every array of objects the puppet entry can hold must
 * have a list rule - an identity, or replaced whole - or a save could not decide what its elements' unknown
 * keys belong to (D10).
 */
class UmaPuppetSchemaTest {
	/**
	 * Every array of objects reachable from the puppet entry's root, through maps included, has a list rule.
	 */
	@Test
	fun everyArrayOfObjectsHasAnIdentity() {
		val missing = ArrayList<String>()
		val visited = HashSet<String>()

		/**
		 * Walks one descriptor, recording arrays of objects without a list rule.
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
					if (element.kind == StructureKind.CLASS && UmaPuppetEntry.identities.ruleFor(element.serialName) == null) {
						missing += "$path (${element.serialName})"
					}
					walk(element, "$path[]")
				}

				StructureKind.MAP -> walk(descriptor.getElementDescriptor(1), "$path{}")

				else -> {}
			}
		}
		walk(UmaPuppet.serializer().descriptor, "puppet")
		assertTrue(missing.isEmpty(), "arrays of objects without a list rule: $missing")
	}
}