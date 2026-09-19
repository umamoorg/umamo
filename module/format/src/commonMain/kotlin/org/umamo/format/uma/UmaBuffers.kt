@file:OptIn(ExperimentalSerializationApi::class)

package org.umamo.format.uma

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.modules.SerializersModule
import okio.Buffer
import org.umamo.format.binary.ByteReader

/*
 * Bulk arrays in buffer entries (docs/format/UMA.md §4.9, D9, D10, D19).  A domain entry's per-vertex arrays
 * live in a little-endian buffer entry the domain owns, each named from the JSON by an accessor - an object
 * holding exactly `buffer`, `byteOffset`, `byteLength`, `count`, and `componentType`.
 *
 * Accessors are recognized anywhere in a tree, under a known key or not, and every save lays the owned buffer
 * out afresh from them in document order.  That is what lets an accessor a newer writer put under a key this
 * reader does not know keep its bytes, instead of pointing at an offset the rewritten buffer moved.
 */

/** UMA §4.9: the alignment of an accessor whose component type this reader does not know, the largest a type may need. */
private const val UNKNOWN_COMPONENT_ALIGNMENT = 8

/** UMA §4.9: the component types this reader interprets, each four bytes little-endian. */
internal object UmaComponentType {
	const val FLOAT32 = "float32"
	const val INT32 = "int32"

	/**
	 * The size in bytes of one component of [componentType], or null for a type this reader does not know.
	 *
	 * @param String componentType The accessor's component type.
	 * @return Int? The component size.
	 */
	fun sizeOf(componentType: String): Int? =
		when (componentType) {
			FLOAT32, INT32 -> 4
			else -> null
		}
}

/**
 * One accessor's fields.
 *
 * @property String buffer        The buffer entry's path.
 * @property Int    byteOffset    Where the array starts in the buffer.
 * @property Int    byteLength    The array's length in bytes.
 * @property Int    count         The number of components.
 * @property String componentType The component type.
 */
internal class UmaAccessor(
	val buffer: String,
	val byteOffset: Int,
	val byteLength: Int,
	val count: Int,
	val componentType: String,
) {
	/**
	 * This accessor as the JSON object the file holds, keys in their written order.
	 *
	 * @return JsonObject The accessor.
	 */
	fun toJson(): JsonObject =
		JsonObject(
			linkedMapOf(
				BUFFER_KEY to JsonPrimitive(buffer),
				BYTE_OFFSET_KEY to JsonPrimitive(byteOffset),
				BYTE_LENGTH_KEY to JsonPrimitive(byteLength),
				COUNT_KEY to JsonPrimitive(count),
				COMPONENT_TYPE_KEY to JsonPrimitive(componentType),
			),
		)

	companion object {
		// UMA §4.9: an accessor's keys, all five required and nothing else.
		private const val BUFFER_KEY = "buffer"
		private const val BYTE_OFFSET_KEY = "byteOffset"
		private const val BYTE_LENGTH_KEY = "byteLength"
		private const val COUNT_KEY = "count"
		private const val COMPONENT_TYPE_KEY = "componentType"

		/** The name a freshly encoded array's accessor gives its buffer until the layout places it. */
		const val SCRATCH_BUFFER = "umamo:scratch"

		/**
		 * [element] as an accessor, or null when it is not one: an object with exactly the five accessor
		 * keys, `buffer` and `componentType` strings, and the three sizes non-negative integers.
		 *
		 * @param JsonElement element The value.
		 * @return UmaAccessor? The accessor.
		 */
		fun of(element: JsonElement): UmaAccessor? {
			val candidate = element as? JsonObject ?: return null
			if (candidate.size != 5) {
				return null
			}
			return UmaAccessor(
				buffer = jsonStringOrNull(candidate[BUFFER_KEY]) ?: return null,
				byteOffset = sizeOf(candidate[BYTE_OFFSET_KEY]) ?: return null,
				byteLength = sizeOf(candidate[BYTE_LENGTH_KEY]) ?: return null,
				count = sizeOf(candidate[COUNT_KEY]) ?: return null,
				componentType = jsonStringOrNull(candidate[COMPONENT_TYPE_KEY]) ?: return null,
			)
		}

		/**
		 * [element] as a non-negative integer within a byte array's range, or null.
		 *
		 * @param JsonElement? element The value.
		 * @return Int? The integer.
		 */
		private fun sizeOf(element: JsonElement?): Int? =
			(element as? JsonPrimitive)?.takeIf { primitive -> !primitive.isString }?.longOrNull?.takeIf { value -> value in 0..Int.MAX_VALUE.toLong() }?.toInt()
	}
}

/**
 * Where the first accessor in [tree] breaks a rule: a buffer that does not exist, a known component type
 * whose length or alignment is wrong, or bytes past the buffer's end.
 *
 * @param JsonElement tree    The entry's JSON.
 * @param Function    buffers Resolves a buffer path to its bytes, or null when there is no such buffer.
 * @param String      path    The tree's position, for the report.
 * @return String? A description of the first problem, or null when every accessor is sound.
 */
internal fun firstAccessorProblem(tree: JsonElement, buffers: (String) -> ByteArray?, path: String = ""): String? {
	UmaAccessor.of(tree)?.let { accessor ->
		val bytes = buffers(accessor.buffer) ?: return "$path names the buffer '${accessor.buffer}', which the archive does not hold"
		val componentSize = UmaComponentType.sizeOf(accessor.componentType)
		if (componentSize != null) {
			if (accessor.byteLength.toLong() != accessor.count.toLong() * componentSize) {
				return "$path declares ${accessor.count} ${accessor.componentType} components in ${accessor.byteLength} bytes"
			}
			if (accessor.byteOffset % componentSize != 0) {
				return "$path starts at byte ${accessor.byteOffset}, which is not aligned to ${accessor.componentType}"
			}
		}
		if (accessor.byteOffset.toLong() + accessor.byteLength > bytes.size) {
			return "$path reaches byte ${accessor.byteOffset.toLong() + accessor.byteLength} of '${accessor.buffer}', which holds ${bytes.size}"
		}
		return null
	}
	when (tree) {
		is JsonObject -> {
			for ((key, value) in tree) {
				firstAccessorProblem(value, buffers, if (path.isEmpty()) key else "$path.$key")?.let { problem -> return problem }
			}
		}

		is JsonArray -> {
			for ((elementIndex, element) in tree.withIndex()) {
				firstAccessorProblem(element, buffers, "$path[$elementIndex]")?.let { problem -> return problem }
			}
		}

		else -> {}
	}
	return null
}

/**
 * The growable little-endian buffer a save encodes its new arrays into, before the layout places them.
 */
internal class UmaScratchBuffer {
	private val output = Buffer()

	/**
	 * Appends [values] as float32 and returns the accessor naming them.
	 *
	 * @param FloatArray values The array.
	 * @return UmaAccessor The accessor.
	 */
	fun appendFloats(values: FloatArray): UmaAccessor {
		val offset = output.size.toInt()
		for (value in values) {
			output.writeIntLe(value.toRawBits())
		}
		return UmaAccessor(UmaAccessor.SCRATCH_BUFFER, offset, values.size * 4, values.size, UmaComponentType.FLOAT32)
	}

	/**
	 * Appends [values] as int32 and returns the accessor naming them.
	 *
	 * @param IntArray values The array.
	 * @return UmaAccessor The accessor.
	 */
	fun appendInts(values: IntArray): UmaAccessor {
		val offset = output.size.toInt()
		for (value in values) {
			output.writeIntLe(value)
		}
		return UmaAccessor(UmaAccessor.SCRATCH_BUFFER, offset, values.size * 4, values.size, UmaComponentType.INT32)
	}

	/**
	 * Everything appended so far.
	 *
	 * @return ByteArray The bytes.
	 */
	fun bytes(): ByteArray = output.copy().readByteArray()
}

/**
 * The result of laying an entry's accessors out into its owned buffer.
 *
 * @property JsonElement tree          The tree with every accessor pointing into the owned buffer.
 * @property ByteArray   buffer        The owned buffer's bytes.
 * @property Boolean     hasAccessors  Whether the tree holds any accessor, so the buffer is referenced.
 */
internal class UmaBufferLayout(
	val tree: JsonElement,
	val buffer: ByteArray,
	val hasAccessors: Boolean,
)

/**
 * Lays every accessor in [tree] out into one buffer at [ownedPath], in document order: each accessor's bytes
 * are copied from the buffer it names, aligned to its component size (eight bytes for a type this reader does
 * not know), and the accessor is rewritten to point into the new buffer.
 *
 * @param JsonElement tree      The entry's JSON.
 * @param String      ownedPath The path of the buffer the entry owns.
 * @param Function    sources   Resolves a buffer path, the scratch buffer included, to its bytes.
 * @return UmaBufferLayout The rewritten tree and the owned buffer.
 * @throws IllegalStateException When an accessor names bytes its source does not hold; a tree read from a
 *   file has already been validated, so this is a bug in the caller.
 */
internal fun layOutBuffer(tree: JsonElement, ownedPath: String, sources: (String) -> ByteArray?): UmaBufferLayout {
	val output = Buffer()
	var accessorCount = 0

	/**
	 * Rewrites one subtree.
	 *
	 * @param JsonElement element The subtree.
	 * @return JsonElement The rewritten subtree.
	 */
	fun rewrite(element: JsonElement): JsonElement {
		val accessor = UmaAccessor.of(element)
		if (accessor != null) {
			val source = checkNotNull(sources(accessor.buffer)) { "an accessor names the buffer '${accessor.buffer}', which is not available" }
			check(accessor.byteOffset.toLong() + accessor.byteLength <= source.size) { "an accessor reaches past the end of '${accessor.buffer}'" }
			// UMA §4.9: each array starts on a multiple of its component size.  A type this reader does not know aligns to
			// 8, a multiple of every component size a newer reader may check it against.
			val alignment = UmaComponentType.sizeOf(accessor.componentType) ?: UNKNOWN_COMPONENT_ALIGNMENT
			while (output.size % alignment != 0L) {
				output.writeByte(0)
			}
			val offset = output.size.toInt()
			output.write(source, accessor.byteOffset, accessor.byteLength)
			accessorCount++
			return UmaAccessor(ownedPath, offset, accessor.byteLength, accessor.count, accessor.componentType).toJson()
		}
		return when (element) {
			is JsonObject -> JsonObject(element.mapValuesTo(LinkedHashMap()) { (_, value) -> rewrite(value) })
			is JsonArray -> JsonArray(element.map(::rewrite))
			else -> element
		}
	}
	val rewritten = rewrite(tree)
	check(output.size <= Int.MAX_VALUE - 8L) { "the buffer '$ownedPath' exceeds what a byte array can hold" }
	return UmaBufferLayout(rewritten, output.readByteArray(), accessorCount > 0)
}

/**
 * Reads accessors into float arrays while decoding, and writes float arrays out as accessors while encoding.
 *
 * @param Function          buffers Resolves a buffer path to its bytes, for decoding.
 * @param UmaScratchBuffer? scratch The buffer new arrays append to, for encoding.
 */
private class Float32AccessorSerializer(
	private val buffers: (String) -> ByteArray?,
	private val scratch: UmaScratchBuffer?,
) : KSerializer<FloatArray> {
	override val descriptor: SerialDescriptor = buildClassSerialDescriptor("org.umamo.format.uma.Float32Accessor")

	/**
	 * Writes [value] into the scratch buffer and its accessor into the tree.
	 *
	 * @param Encoder    encoder The JSON encoder.
	 * @param FloatArray value   The array.
	 */
	override fun serialize(encoder: Encoder, value: FloatArray) {
		val sink = scratch ?: throw SerializationException("no buffer to write a float32 array into")
		(encoder as JsonEncoder).encodeJsonElement(sink.appendFloats(value).toJson())
	}

	/**
	 * Reads the accessor at the decoder's position and slices its floats out of the buffer it names.
	 *
	 * @param Decoder decoder The JSON decoder.
	 * @return FloatArray The array.
	 */
	override fun deserialize(decoder: Decoder): FloatArray {
		val (accessor, bytes) = resolveAccessor(decoder, UmaComponentType.FLOAT32, buffers)
		val reader = ByteReader(bytes, littleEndian = true)
		return FloatArray(accessor.count) { componentIndex -> Float.fromBits(reader.u32AsInt(accessor.byteOffset + componentIndex * 4)) }
	}
}

/**
 * Reads accessors into int arrays while decoding, and writes int arrays out as accessors while encoding.
 *
 * @param Function          buffers Resolves a buffer path to its bytes, for decoding.
 * @param UmaScratchBuffer? scratch The buffer new arrays append to, for encoding.
 */
private class Int32AccessorSerializer(
	private val buffers: (String) -> ByteArray?,
	private val scratch: UmaScratchBuffer?,
) : KSerializer<IntArray> {
	override val descriptor: SerialDescriptor = buildClassSerialDescriptor("org.umamo.format.uma.Int32Accessor")

	/**
	 * Writes [value] into the scratch buffer and its accessor into the tree.
	 *
	 * @param Encoder  encoder The JSON encoder.
	 * @param IntArray value   The array.
	 */
	override fun serialize(encoder: Encoder, value: IntArray) {
		val sink = scratch ?: throw SerializationException("no buffer to write an int32 array into")
		(encoder as JsonEncoder).encodeJsonElement(sink.appendInts(value).toJson())
	}

	/**
	 * Reads the accessor at the decoder's position and slices its ints out of the buffer it names.
	 *
	 * @param Decoder decoder The JSON decoder.
	 * @return IntArray The array.
	 */
	override fun deserialize(decoder: Decoder): IntArray {
		val (accessor, bytes) = resolveAccessor(decoder, UmaComponentType.INT32, buffers)
		val reader = ByteReader(bytes, littleEndian = true)
		return IntArray(accessor.count) { componentIndex -> reader.u32AsInt(accessor.byteOffset + componentIndex * 4) }
	}
}

/**
 * The accessor at [decoder]'s position and the bytes of the buffer it names, checked against the component
 * type the field requires.
 *
 * @param Decoder  decoder       The JSON decoder.
 * @param String   componentType The component type the field requires.
 * @param Function buffers       Resolves a buffer path to its bytes.
 * @return Pair The accessor and its buffer's bytes.
 * @throws SerializationException When the value is not a sound accessor of that type.
 */
private fun resolveAccessor(decoder: Decoder, componentType: String, buffers: (String) -> ByteArray?): Pair<UmaAccessor, ByteArray> {
	val element = (decoder as JsonDecoder).decodeJsonElement()
	val accessor = UmaAccessor.of(element) ?: throw SerializationException("expected a $componentType accessor, found $element")
	if (accessor.componentType != componentType) {
		throw SerializationException("expected a $componentType accessor, found ${accessor.componentType}")
	}
	val bytes = buffers(accessor.buffer) ?: throw SerializationException("the accessor names the buffer '${accessor.buffer}', which the archive does not hold")
	if (accessor.byteLength.toLong() != accessor.count.toLong() * 4 || accessor.byteOffset.toLong() + accessor.byteLength > bytes.size) {
		throw SerializationException("the $componentType accessor does not fit the buffer '${accessor.buffer}'")
	}
	return accessor to bytes
}

/**
 * The entry JSON with its bulk array fields bound to buffers: decoding slices them out of [buffers], and
 * encoding appends them to [scratch].
 *
 * @param Function          buffers Resolves a buffer path to its bytes.
 * @param UmaScratchBuffer? scratch The buffer new arrays append to, or null when only decoding.
 * @return Json The configured instance.
 */
internal fun umaEntryJsonWithBuffers(buffers: (String) -> ByteArray?, scratch: UmaScratchBuffer?): Json =
	Json(UmaEntryJson) {
		serializersModule =
			SerializersModule {
				contextual(FloatArray::class, Float32AccessorSerializer(buffers, scratch))
				contextual(IntArray::class, Int32AccessorSerializer(buffers, scratch))
			}
	}