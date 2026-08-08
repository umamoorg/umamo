package org.umamo.format.cmo3.tools

import org.umamo.format.cmo3.model.identity.Guid
import org.umamo.format.cmo3.model.type.FileRef
import org.umamo.format.cmo3.serialize.gen.GeneratedDescriptors
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Verifies the generated descriptors against live reflection, covering what the drift gate's
 * byte-compare cannot: the byte-compare re-runs the same emitter, so an emitter bug reproduces
 * identically on both sides, and the corpus gates only exercise corpus-reachable classes.  Here
 * every descriptor's structure is re-derived reflectively and every get/set lambda is probed to
 * prove it targets exactly the property its name claims.
 */
class DescriptorWiringTest {
	@Test
	fun classDescriptorsMatchReflectionStructurally() {
		for (descriptor in GeneratedDescriptors.allClassDescriptors) {
			val kClass = descriptor.kClass
			val className = kClass.simpleName.orEmpty()
			assertEquals(DescriptorReflection.tagOf(kClass), descriptor.tag, "$className tag")
			assertEquals(DescriptorReflection.versionOf(kClass), descriptor.version, "$className version")
			assertEquals(kClass, descriptor.factory()::class, "$className factory class")

			val expectedSuper =
				kClass.java.superclass
					?.takeIf { it != Any::class.java && !it.isInterface }?.kotlin
			assertEquals(expectedSuper, descriptor.superDescriptor?.kClass, "$className super chain")

			val expectedProperties = DescriptorReflection.serializedProperties(kClass)
			assertEquals(
				expectedProperties.map { it.name },
				descriptor.properties.map { it.name },
				"$className property names and backing-field order",
			)
			for (propertyIndex in expectedProperties.indices) {
				val expected = DescriptorReflection.propertyDescriptorFor(kClass, expectedProperties[propertyIndex])
				val actual = descriptor.properties[propertyIndex]
				val propertyLabel = "$className.${actual.name}"
				assertEquals(expected.serialName, actual.serialName, "$propertyLabel serialName")
				assertEquals(expected.attributeName, actual.attributeName, "$propertyLabel attributeName")
				assertEquals(expected.skipIfDefault, actual.skipIfDefault, "$propertyLabel skipIfDefault")
				assertEquals(expected.scalarKind, actual.scalarKind, "$propertyLabel scalarKind")
			}
		}
	}

	@Test
	fun enumDescriptorsMatchReflection() {
		for (descriptor in GeneratedDescriptors.allEnumDescriptors) {
			val kClass = descriptor.kClass
			val enumName = kClass.simpleName.orEmpty()
			assertEquals(DescriptorReflection.tagOf(kClass), descriptor.tag, "$enumName tag")
			val constants = kClass.java.enumConstants
			assertNotNull(constants, "$enumName must be an enum class")
			assertEquals(constants.size, descriptor.entries.size, "$enumName constant count")
			for (constantIndex in constants.indices) {
				assertSame(constants[constantIndex], descriptor.entries[constantIndex], "$enumName entry order")
			}
		}
	}

	@Test
	fun descriptorLambdasTargetTheirNamedProperty() {
		for (descriptor in GeneratedDescriptors.allClassDescriptors) {
			val kClass = descriptor.kClass
			val className = kClass.simpleName.orEmpty()
			val reflectionByName = DescriptorReflection.serializedProperties(kClass).associateBy { it.name }
			val instance = descriptor.factory()
			descriptor.properties.forEachIndexed { propertyIndex, property ->
				val reflectionProperty = reflectionByName.getValue(property.name)
				val propertyLabel = "$className.${property.name}"
				val probe = probeValueFor(reflectionProperty.returnType, propertyIndex)
				if (probe == null) {
					// No constructible probe for this type; a nullable slot still verifies wiring
					// through the null write plus the get-parity read below.
					assertTrue(reflectionProperty.returnType.isMarkedNullable, "$propertyLabel needs a probe")
					property.set(instance, null)
					assertEquals(null, reflectionProperty.get(instance), "$propertyLabel set(null) wiring")
					assertEquals(null, property.get(instance), "$propertyLabel get(null) wiring")
					return@forEachIndexed
				}
				// Reference probes are fresh instances, so assertSame catches crossed wiring even
				// between value-equal slots; primitives compare by value against distinct probes.
				property.set(instance, probe)
				val readBack = reflectionProperty.get(instance)
				if (isReferenceProbe(probe)) {
					assertSame(probe, readBack, "$propertyLabel descriptor-set to reflection-get")
					assertSame(probe, property.get(instance), "$propertyLabel descriptor-get")
				} else {
					assertEquals(probe, readBack, "$propertyLabel descriptor-set to reflection-get")
					assertEquals(probe, property.get(instance), "$propertyLabel descriptor-get")
				}
			}
		}
	}

	@Test
	fun overriddenOptionPropertyAppearsAtBothHierarchyLevels() {
		// CLayer overrides ACLayerEntry._optionOfIOption, so the value serializes both in <super>
		// and directly; declaredMemberProperties semantics put the property in BOTH descriptors.
		val layerEntry = GeneratedDescriptors.aCLayerEntry
		val layer = GeneratedDescriptors.cLayer
		assertTrue(layerEntry.properties.any { it.name == "_optionOfIOption" }, "ACLayerEntry level")
		assertTrue(layer.properties.any { it.name == "_optionOfIOption" }, "CLayer level")
	}

	/**
	 * A distinct, type-correct probe value for [type], or null when the type has no cheap
	 * constructible probe (the caller then falls back to the null-write check).
	 *
	 * @param KType type          The property's declared type.
	 * @param Int   propertyIndex Salt so sibling probes differ, exposing crossed scalar wiring.
	 * @return Any? The probe value, or null.
	 */
	private fun probeValueFor(type: KType, propertyIndex: Int): Any? {
		val classifier = type.classifier as? KClass<*> ?: return null
		return when (classifier) {
			Int::class -> 1000 + propertyIndex
			Float::class -> 1000.5f + propertyIndex
			Double::class -> 2000.5 + propertyIndex
			Long::class -> 3000L + propertyIndex
			Short::class -> (100 + propertyIndex).toShort()
			Byte::class -> (1 + (propertyIndex % 100)).toByte()
			Boolean::class -> true
			Char::class -> 'A' + (propertyIndex % 26)
			String::class, Any::class -> "probe$propertyIndex"
			FloatArray::class -> floatArrayOf(propertyIndex.toFloat())
			Guid::class -> Guid("ProbeGuid")
			FileRef::class -> FileRef()
			else ->
				if (classifier.java.isEnum) {
					classifier.java.enumConstants?.firstOrNull()
				} else {
					// Descriptor-covered model classes construct through their own factory.
					GeneratedDescriptors.allClassDescriptors.firstOrNull { it.kClass == classifier }?.factory()
				}
		}
	}

	/**
	 * True when [probe] should compare by identity (a fresh reference instance) rather than value.
	 *
	 * @param Any probe The probe value.
	 * @return Boolean Whether to use identity comparison.
	 */
	private fun isReferenceProbe(probe: Any): Boolean =
		probe !is Int &&
			probe !is Float &&
			probe !is Double &&
			probe !is Long &&
			probe !is Short &&
			probe !is Byte &&
			probe !is Boolean &&
			probe !is Char &&
			probe !is Enum<*>
}