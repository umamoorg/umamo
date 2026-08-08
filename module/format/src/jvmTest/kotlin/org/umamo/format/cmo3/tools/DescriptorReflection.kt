package org.umamo.format.cmo3.tools

import org.umamo.format.cmo3.serialize.annotations.DontSerialize
import org.umamo.format.cmo3.serialize.annotations.DontSerializeIfDefault
import org.umamo.format.cmo3.serialize.annotations.SerialAttribute
import org.umamo.format.cmo3.serialize.annotations.SerialName
import org.umamo.format.cmo3.serialize.annotations.SerialTag
import org.umamo.format.cmo3.serialize.annotations.SuppressSerializeSuperClass
import org.umamo.format.cmo3.serialize.descriptors.ClassDescriptor
import org.umamo.format.cmo3.serialize.descriptors.EnumDescriptor
import org.umamo.format.cmo3.serialize.descriptors.PropertyDescriptor
import org.umamo.format.cmo3.serialize.descriptors.ScalarKind
import kotlin.reflect.KClass
import kotlin.reflect.KMutableProperty1
import kotlin.reflect.full.declaredMemberProperties
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.jvm.isAccessible
import kotlin.reflect.jvm.javaField

/**
 * Derives serialization descriptors from model classes with kotlin-reflect, using the exact
 * discovery semantics the CMO3 engine's descriptors encode: declared mutable properties only
 * (vals are dropped), @DontSerialize excluded, backing-field declaration order with field-less
 * properties last, class-level @DontSerializeIfDefault promoting every property, and the
 * transitive superclass closure stopping at Any and interfaces.
 *
 * This is the single reflective source of truth on the JVM test side: the descriptor generator
 * emits its output from these derivations, the drift and wiring gates compare the checked-in
 * generated descriptors against them, and synthetic-class test fixtures build engines through
 * them without needing generated code.
 */
internal object DescriptorReflection {
	/**
	 * The element tag for [kClass]: its @SerialTag, else its simple name.
	 *
	 * @param KClass kClass The model class or enum.
	 * @return String The element tag.
	 */
	fun tagOf(kClass: KClass<*>): String =
		kClass.findAnnotation<SerialTag>()?.tag ?: kClass.simpleName
			?: error("anonymous class cannot be serialized: $kClass")

	/**
	 * The @SerialTag version of [kClass], or -1 when it declares none.
	 *
	 * @param KClass kClass The model class or enum.
	 * @return Int The declared version, or -1.
	 */
	fun versionOf(kClass: KClass<*>): Int = kClass.findAnnotation<SerialTag>()?.version ?: -1

	/**
	 * Derives the [EnumDescriptor] for enum class [kClass].
	 *
	 * @param KClass kClass The enum class.
	 * @return EnumDescriptor The derived descriptor with constants in declaration order.
	 */
	fun enumDescriptorFor(kClass: KClass<*>): EnumDescriptor {
		val constants = kClass.java.enumConstants ?: error("not an enum class: $kClass")
		return EnumDescriptor(tagOf(kClass), versionOf(kClass), kClass, constants.map { it as Enum<*> })
	}

	/**
	 * Derives the [ClassDescriptor] for [kClass], recursing through its serializable superclass
	 * chain.  [cache] deduplicates shared supers so one derivation run yields one descriptor
	 * instance per class.
	 *
	 * @param KClass       kClass The model class.
	 * @param MutableMap   cache  Per-run class-to-descriptor cache (supers land here too).
	 * @return ClassDescriptor The derived descriptor.
	 */
	fun classDescriptorFor(
		kClass: KClass<*>,
		cache: MutableMap<KClass<*>, ClassDescriptor> = HashMap(),
	): ClassDescriptor {
		cache[kClass]?.let { return it }
		val suppressSuper = kClass.findAnnotation<SuppressSerializeSuperClass>() != null
		val superDescriptor =
			if (suppressSuper) {
				null
			} else {
				val superJava = kClass.java.superclass
				if (superJava == null || superJava == Any::class.java || superJava.isInterface) {
					null
				} else {
					classDescriptorFor(superJava.kotlin, cache)
				}
			}
		val descriptor =
			ClassDescriptor(
				tag = tagOf(kClass),
				version = versionOf(kClass),
				kClass = kClass,
				// Constructor resolution stays inside the factory: a class without a no-arg
				// constructor fails at instantiation time, where the engine's verbatim-fallback
				// net catches it, not at descriptor-derivation time.
				factory = {
					val constructor = kClass.java.getDeclaredConstructor()
					constructor.isAccessible = true
					constructor.newInstance()
				},
				properties = serializedProperties(kClass).map { property -> propertyDescriptorFor(kClass, property) },
				superDescriptor = superDescriptor,
			)
		cache[kClass] = descriptor
		return descriptor
	}

	/**
	 * The declared, mutable, serialized properties of [kClass] in backing-field declaration order
	 * (field-less properties sort last), each opened for reflective access.
	 *
	 * @param KClass kClass The model class.
	 * @return List The ordered serialized properties.
	 */
	@Suppress("UNCHECKED_CAST")
	fun serializedProperties(kClass: KClass<*>): List<KMutableProperty1<Any, Any?>> {
		val fieldOrder = kClass.java.declaredFields.map { it.name }
		return kClass.declaredMemberProperties
			.filterIsInstance<KMutableProperty1<Any, Any?>>()
			.filter { it.findAnnotation<DontSerialize>() == null }
			.sortedBy { property ->
				val fieldName = property.javaField?.name ?: property.name
				fieldOrder.indexOf(fieldName).let { if (it < 0) Int.MAX_VALUE else it }
			}
			.onEach { it.isAccessible = true }
	}

	/**
	 * Derives one [PropertyDescriptor] from [property] of [kClass], resolving the annotation
	 * overrides (@SerialName, @SerialAttribute name, class-level @DontSerializeIfDefault).
	 *
	 * @param KClass             kClass   The declaring model class.
	 * @param KMutableProperty1  property The serialized property.
	 * @return PropertyDescriptor The derived descriptor.
	 */
	fun propertyDescriptorFor(
		kClass: KClass<*>,
		property: KMutableProperty1<Any, Any?>,
	): PropertyDescriptor {
		val classDefaultIfDefault = kClass.findAnnotation<DontSerializeIfDefault>() != null
		val attributeAnnotation = property.findAnnotation<SerialAttribute>()
		return PropertyDescriptor(
			name = property.name,
			serialName = property.findAnnotation<SerialName>()?.name ?: property.name,
			attributeName = attributeAnnotation?.name?.ifEmpty { property.name },
			skipIfDefault = classDefaultIfDefault || property.findAnnotation<DontSerializeIfDefault>() != null,
			scalarKind = if (attributeAnnotation != null) scalarKindOf(property) else null,
			get = { instance -> property.get(instance) },
			set = { instance, value -> property.set(instance, value) },
		)
	}

	/**
	 * The attribute parse kind for [property], from its declared type's classifier (nullability
	 * unwrapped); every non-primitive type receives the raw string.
	 *
	 * @param KMutableProperty1 property The attribute property.
	 * @return ScalarKind The parse kind.
	 */
	fun scalarKindOf(property: KMutableProperty1<Any, Any?>): ScalarKind =
		when (property.returnType.classifier) {
			Int::class -> ScalarKind.INT
			Float::class -> ScalarKind.FLOAT
			Double::class -> ScalarKind.DOUBLE
			Long::class -> ScalarKind.LONG
			Short::class -> ScalarKind.SHORT
			Byte::class -> ScalarKind.BYTE
			Boolean::class -> ScalarKind.BOOLEAN
			Char::class -> ScalarKind.CHAR
			else -> ScalarKind.STRING
		}
}