@file:OptIn(ExperimentalSerializationApi::class)

package org.umamo.format.uma

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import org.umamo.format.uma.puppet.UmaPuppet
import org.umamo.format.uma.puppet.UmaPuppetEntry
import org.umamo.format.uma.sources.UmaSources
import org.umamo.format.uma.sources.UmaSourcesEntry
import org.umamo.format.uma.textures.UmaTextures
import org.umamo.format.uma.textures.UmaTexturesEntry
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Guards the retained-tree merge against schema drift: every array of objects a domain entry can hold must have a
 * list rule - an identity, or replaced whole - or a save could not decide what its elements' unknown keys belong
 * to (D10).
 */
class UmaEntrySchemaTest {
	/**
	 * The arrays of objects reachable from [root], through maps included, that [identities] has no rule for.
	 *
	 * @param SerialDescriptor root       The entry's root schema.
	 * @param UmaIdentityTable identities The entry's list rules.
	 * @param String           name       The entry's name, for the report.
	 * @return List<String> Each array without a rule, by path.
	 */
	private fun arraysWithoutARule(root: SerialDescriptor, identities: UmaIdentityTable, name: String): List<String> {
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
					if (element.kind == StructureKind.CLASS && identities.ruleFor(element.serialName) == null) {
						missing += "$path (${element.serialName})"
					}
					walk(element, "$path[]")
				}

				StructureKind.MAP -> walk(descriptor.getElementDescriptor(1), "$path{}")

				else -> {}
			}
		}
		walk(root, name)
		return missing
	}

	/**
	 * Every array of objects in the puppet, textures, and sources entries has a list rule.
	 */
	@Test
	fun everyArrayOfObjectsHasARule() {
		val missing =
			arraysWithoutARule(UmaPuppet.serializer().descriptor, UmaPuppetEntry.identities, "puppet") +
				arraysWithoutARule(UmaTextures.serializer().descriptor, UmaTexturesEntry.identities, "textures") +
				arraysWithoutARule(UmaSources.serializer().descriptor, UmaSourcesEntry.identities, "sources")
		assertTrue(missing.isEmpty(), "arrays of objects without a list rule: $missing")
	}
}