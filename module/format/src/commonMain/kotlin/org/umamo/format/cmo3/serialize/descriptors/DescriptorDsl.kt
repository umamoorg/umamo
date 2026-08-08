package org.umamo.format.cmo3.serialize.descriptors

import kotlin.enums.enumEntries

/**
 * Collects a class's [PropertyDescriptor]s with typed get/set lambdas, hiding the one unchecked
 * instance cast here so generated descriptor code stays short and cast-free on the instance side.
 * Value casts (e.g. `value as Boolean`) remain in the generated set lambdas where the property
 * type demands one.
 */
internal class ClassDescriptorBuilder<T : Any> {
	val properties = ArrayList<PropertyDescriptor>()

	/**
	 * Adds a child-element property (`<Tag xs.n="serialName">`).
	 *
	 * @param String  name          The Kotlin property name (replay slots key on it).
	 * @param Function get          Reads the property from a typed instance.
	 * @param Function set          Writes the property on a typed instance.
	 * @param String  serialName    The on-disk xs.n child name; defaults to [name].
	 * @param Boolean skipIfDefault The resolved @DontSerializeIfDefault flag.
	 */
	@Suppress("UNCHECKED_CAST")
	fun property(
		name: String,
		get: (T) -> Any?,
		set: (T, Any?) -> Unit,
		serialName: String = name,
		skipIfDefault: Boolean = false,
	) {
		properties.add(
			PropertyDescriptor(
				name = name,
				serialName = serialName,
				attributeName = null,
				skipIfDefault = skipIfDefault,
				scalarKind = null,
				get = { instance -> get(instance as T) },
				set = { instance, value -> set(instance as T, value) },
			),
		)
	}

	/**
	 * Adds a property written on the owning tag as an XML attribute (@SerialAttribute).
	 *
	 * @param String     name          The Kotlin property name.
	 * @param ScalarKind kind          How the raw attribute text parses.
	 * @param Function   get           Reads the property from a typed instance.
	 * @param Function   set           Writes the property on a typed instance.
	 * @param String     attributeName The on-disk attribute name; defaults to [name].
	 * @param Boolean    skipIfDefault The resolved @DontSerializeIfDefault flag.
	 */
	@Suppress("UNCHECKED_CAST")
	fun attribute(
		name: String,
		kind: ScalarKind,
		get: (T) -> Any?,
		set: (T, Any?) -> Unit,
		attributeName: String = name,
		skipIfDefault: Boolean = false,
	) {
		properties.add(
			PropertyDescriptor(
				name = name,
				serialName = name,
				attributeName = attributeName,
				skipIfDefault = skipIfDefault,
				scalarKind = kind,
				get = { instance -> get(instance as T) },
				set = { instance, value -> set(instance as T, value) },
			),
		)
	}
}

/**
 * Builds a [ClassDescriptor] for [T] with typed property lambdas.
 *
 * @param String          tag             The element tag (@SerialTag, else the simple name).
 * @param Function        factory         No-arg constructor reference producing a default instance.
 * @param ClassDescriptor superDescriptor The serializable superclass's descriptor, or null.
 * @param Int             version         The @SerialTag version; -1 when the class declares none.
 * @param Function        builder         Property registration block, in backing-field order.
 * @return ClassDescriptor The finished descriptor.
 */
internal inline fun <reified T : Any> classDescriptor(
	tag: String,
	noinline factory: () -> T,
	superDescriptor: ClassDescriptor?,
	version: Int = -1,
	builder: ClassDescriptorBuilder<T>.() -> Unit = {},
): ClassDescriptor {
	val collected = ClassDescriptorBuilder<T>()
	collected.builder()
	return ClassDescriptor(
		tag = tag,
		version = version,
		kClass = T::class,
		factory = factory,
		properties = collected.properties,
		superDescriptor = superDescriptor,
	)
}

/**
 * Builds an [EnumDescriptor] for enum [T] with its constants in declaration order.
 *
 * @param String tag     The element tag (@SerialTag, else the simple name).
 * @param Int    version The @SerialTag version; -1 when the enum declares none.
 * @return EnumDescriptor The finished descriptor.
 */
internal inline fun <reified T : Enum<T>> enumDescriptor(
	tag: String,
	version: Int = -1,
): EnumDescriptor = EnumDescriptor(tag, version, T::class, enumEntries<T>())