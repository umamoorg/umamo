package org.umamo.format.cmo3.serialize

import org.umamo.format.cmo3.serialize.annotations.SerialTag
import org.umamo.format.cmo3.type.CArrayList
import org.umamo.format.cmo3.type.CHashMap
import kotlin.reflect.KClass
import kotlin.reflect.full.findAnnotation

/**
 * Resolves element tags <-> classes and owns the serializer instances (primitives, collections, and
 * lazily-built reflective class serializers).
 */
internal class SerializerRegistry {
	private val tagToClass = HashMap<String, KClass<*>>()
	private val classToTag = HashMap<KClass<*>, String>()
	private val classSerializerCache = HashMap<KClass<*>, ReflectiveClassSerializer>()

	private val intSerializer = PrimitiveSerializer("i") { it.toInt() }
	private val floatSerializer = PrimitiveSerializer("f") { it.toFloat() }
	private val doubleSerializer = PrimitiveSerializer("d") { it.toDouble() }
	private val longSerializer = PrimitiveSerializer("l") { it.toLong() }
	private val shortSerializer = PrimitiveSerializer("short") { it.toShort() }
	private val byteSerializer = PrimitiveSerializer("byte") { it.toByte() }
	private val charSerializer = PrimitiveSerializer("char") { it[0] }
	private val boolSerializer = PrimitiveSerializer("b") { it == "true" }
	private val stringSerializer = PrimitiveSerializer("s") { it }
	private val arrayListSerializer = ListSerializer("array_list") { ArrayList() }
	private val carrayListSerializer = ListSerializer("carray_list") { CArrayList() }

	// hash_map reads into an order-preserving CHashMap so non-empty maps re-emit in document order.
	private val hashMapSerializer = MapSerializer("hash_map") { CHashMap() }
	private val linkedMapSerializer = MapSerializer("linked_map") { LinkedHashMap() }
	private val linkedSetSerializer = SetSerializer("linked_set") { LinkedHashSet() }

	private val floatArraySerializer =
		ArraySerializer(
			"float-array",
			{ (it as FloatArray).size },
			{ (it as FloatArray).joinToString(" ") { value -> value.toString() } },
			{ tokens -> FloatArray(tokens.size) { tokens[it].toFloat() } },
		)
	private val intArraySerializer =
		ArraySerializer(
			"int-array",
			{ (it as IntArray).size },
			{ (it as IntArray).joinToString(" ") { value -> value.toString() } },
			{ tokens -> IntArray(tokens.size) { tokens[it].toInt() } },
		)
	private val longArraySerializer =
		ArraySerializer(
			"long-array",
			{ (it as LongArray).size },
			{ (it as LongArray).joinToString(" ") { value -> value.toString() } },
			{ tokens -> LongArray(tokens.size) { tokens[it].toLong() } },
		)
	private val shortArraySerializer =
		ArraySerializer(
			"short-array",
			{ (it as ShortArray).size },
			{ (it as ShortArray).joinToString(" ") { value -> value.toString() } },
			{ tokens -> ShortArray(tokens.size) { tokens[it].toShort() } },
		)
	private val doubleArraySerializer =
		ArraySerializer(
			"double-array",
			{ (it as DoubleArray).size },
			{ (it as DoubleArray).joinToString(" ") { value -> value.toString() } },
			{ tokens -> DoubleArray(tokens.size) { tokens[it].toDouble() } },
		)
	private val byteArraySerializer =
		ArraySerializer(
			"byte-array",
			{ (it as ByteArray).size },
			{ (it as ByteArray).joinToString(" ") { value -> value.toString() } },
			{ tokens -> ByteArray(tokens.size) { tokens[it].toByte() } },
		)
	private val charArraySerializer =
		ArraySerializer(
			"char-array",
			{ (it as CharArray).size },
			{ (it as CharArray).joinToString(" ") { value -> value.toString() } },
			{ tokens -> CharArray(tokens.size) { tokens[it][0] } },
		)
	private val boolArraySerializer =
		ArraySerializer(
			"bool-array",
			{ (it as BooleanArray).size },
			{ (it as BooleanArray).joinToString(" ") { value -> value.toString() } },
			{ tokens -> BooleanArray(tokens.size) { tokens[it] == "true" } },
		)

	private val enumSerializerCache = HashMap<KClass<*>, EnumSerializer>()
	private val customByClass = HashMap<KClass<*>, XmlSerializer>()
	private val customByTag = HashMap<String, XmlSerializer>()

	/**
	 * Registers a model class so its tag resolves on read and its version PI can be emitted.
	 *
	 * @param KClass<*> kClass The model class to register.
	 */
	fun register(kClass: KClass<*>) {
		val tag = tagFor(kClass)
		tagToClass[tag] = kClass
		classToTag[kClass] = tag
	}

	/**
	 * Registers a class with a hand-written (e.g. attribute-based) serializer, e.g. value-types.
	 *
	 * @param KClass<*> kClass The class to register.
	 * @param XmlSerializer serializer The serializer that reads and writes it.
	 */
	fun registerCustom(kClass: KClass<*>, serializer: XmlSerializer) {
		register(kClass)
		customByClass[kClass] = serializer
		customByTag[classToTag.getValue(kClass)] = serializer
	}

	/**
	 * Registers a custom serializer for an explicit [tag] backed by [kClass].  Lets one class cover
	 * many tags (e.g. a single Guid type carrying its kind for all *Guid tags); the serializer must
	 * derive the element tag from the value on write.
	 *
	 * @param String       tag        The element tag to handle.
	 * @param KClass       kClass     The backing model class (shared across tags).
	 * @param XmlSerializer serializer The hand-written serializer.
	 */
	fun registerCustomTag(tag: String, kClass: KClass<*>, serializer: XmlSerializer) {
		tagToClass[tag] = kClass
		classToTag.putIfAbsent(kClass, tag)
		customByClass[kClass] = serializer
		customByTag[tag] = serializer
	}

	/**
	 * The cached serializer for an enum class.
	 *
	 * @param Class<*> enumClass The enum's class.
	 * @return EnumSerializer The serializer.
	 */
	private fun enumSerializerFor(enumClass: Class<*>): EnumSerializer {
		val kClass = enumClass.kotlin

		return enumSerializerCache.getOrPut(kClass) { EnumSerializer(tagFor(kClass), enumClass) }
	}

	/**
	 * The enum class of a constant, which for a constant with a body of its own is the superclass.
	 *
	 * @param Enum<*> value The enum constant.
	 * @return Class<*> The enum class.
	 */
	private fun enumClassOf(value: Enum<*>): Class<*> =
		value.javaClass.let { if (it.isEnum) it else it.superclass }

	/**
	 * The element tag for [kClass]: its @SerialTag, else its simple name.
	 *
	 * @param KClass<*> kClass The class to name.
	 * @return String The element tag.
	 */
	fun tagFor(kClass: KClass<*>): String =
		classToTag[kClass] ?: (
			kClass.findAnnotation<SerialTag>()?.tag ?: kClass.simpleName
				?: error("anonymous class cannot be serialized: $kClass")
		)

	/**
	 * The class registered under [tag], or null if unknown (caller may fall back to verbatim).
	 *
	 * @param String tag The element tag.
	 * @return KClass<*>? The registered class, or null.
	 */
	fun classForTag(tag: String): KClass<*>? = tagToClass[tag]

	/**
	 * Cached reflective serializer for [kClass].
	 *
	 * @param KClass<*> kClass The class to serialize.
	 * @return ReflectiveClassSerializer The class's serializer.
	 */
	fun classSerializer(kClass: KClass<*>): ReflectiveClassSerializer =
		classSerializerCache.getOrPut(kClass) { ReflectiveClassSerializer(kClass, this) }

	/**
	 * Serializer for a runtime value, dispatched by type (write path).
	 *
	 * @param Any value The value about to be written.
	 * @return XmlSerializer The serializer for its type.
	 */
	fun serializerForValue(value: Any): XmlSerializer {
		customByClass[value::class]?.let { return it }

		return when (value) {
			is Enum<*> -> enumSerializerFor(enumClassOf(value))

			is Int -> intSerializer

			is Float -> floatSerializer

			is Double -> doubleSerializer

			is Long -> longSerializer

			is Short -> shortSerializer

			is Byte -> byteSerializer

			is Char -> charSerializer

			is Boolean -> boolSerializer

			is String -> stringSerializer

			is FloatArray -> floatArraySerializer

			is IntArray -> intArraySerializer

			is LongArray -> longArraySerializer

			is ShortArray -> shortArraySerializer

			is DoubleArray -> doubleArraySerializer

			is ByteArray -> byteArraySerializer

			is CharArray -> charArraySerializer

			is BooleanArray -> boolArraySerializer

			// Order matters: subtypes before supertypes so the on-disk tag round-trips exactly.
			is CHashMap<*, *> -> hashMapSerializer

			is LinkedHashMap<*, *> -> linkedMapSerializer

			is Map<*, *> -> hashMapSerializer

			is CArrayList<*> -> carrayListSerializer

			is List<*> -> arrayListSerializer

			is Set<*> -> linkedSetSerializer

			else -> classSerializer(value::class)
		}
	}

	/**
	 * Serializer for an element tag (read path), or null if the tag is unknown.
	 *
	 * @param String tag The element tag.
	 * @return XmlSerializer? The serializer, or null.
	 */
	fun serializerForTag(tag: String): XmlSerializer? {
		customByTag[tag]?.let { return it }

		return when (tag) {
			"i" -> {
				intSerializer
			}

			"f" -> {
				floatSerializer
			}

			"d" -> {
				doubleSerializer
			}

			"l" -> {
				longSerializer
			}

			"short" -> {
				shortSerializer
			}

			"byte" -> {
				byteSerializer
			}

			"char" -> {
				charSerializer
			}

			"b" -> {
				boolSerializer
			}

			"s" -> {
				stringSerializer
			}

			"array_list" -> {
				arrayListSerializer
			}

			"carray_list" -> {
				carrayListSerializer
			}

			"hash_map" -> {
				hashMapSerializer
			}

			"linked_map" -> {
				linkedMapSerializer
			}

			"linked_set" -> {
				linkedSetSerializer
			}

			"float-array" -> {
				floatArraySerializer
			}

			"int-array" -> {
				intArraySerializer
			}

			"long-array" -> {
				longArraySerializer
			}

			"short-array" -> {
				shortArraySerializer
			}

			"double-array" -> {
				doubleArraySerializer
			}

			"byte-array" -> {
				byteArraySerializer
			}

			"char-array" -> {
				charArraySerializer
			}

			"bool-array" -> {
				boolArraySerializer
			}

			else -> {
				classForTag(tag)?.let { kClass ->
					if (kClass.java.isEnum) enumSerializerFor(kClass.java) else classSerializer(kClass)
				}
			}
		}
	}

	/**
	 * Serializer for the serializable superclass of [kClass], or null.  Never instantiated directly.
	 *
	 * @param KClass<*> kClass The class whose superclass to look up.
	 * @param Boolean suppress True to report no superclass at all.
	 * @return ReflectiveClassSerializer? The superclass's serializer, or null.
	 */
	fun superSerializerOf(kClass: KClass<*>, suppress: Boolean): ReflectiveClassSerializer? {
		if (suppress) {
			return null
		}

		val superJava = kClass.java.superclass ?: return null

		if (superJava == Any::class.java || superJava.isInterface) {
			return null
		}

		return classSerializer(superJava.kotlin)
	}

	/**
	 * Registered (tag -> version) pairs for classes that declare a @SerialTag version.
	 *
	 * @return List<Pair<String, Int>> One tag and version pair per versioned class.
	 */
	fun versions(): List<Pair<String, Int>> =
		classToTag.entries.mapNotNull { (kClass, tag) ->
			kClass.findAnnotation<SerialTag>()?.version?.takeIf { it >= 0 }?.let { tag to it }
		}
}