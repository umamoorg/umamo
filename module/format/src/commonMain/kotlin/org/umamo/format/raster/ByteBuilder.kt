package org.umamo.format.raster

/**
 * A growable byte buffer for assembling encoded output.
 *
 * Kotlin's common stdlib has no `java.io.ByteArrayOutputStream` equivalent, and the raster encoders
 * need one to build a datastream whose final length is not known up front.  This is that, and nothing
 * more: append-only, no streams, no IO, so the encoders stay in commonMain and compile for every
 * target rather than being pinned to the JVM by their output buffer.
 */
internal class ByteBuilder(initialCapacity: Int = 32) {
	private var buffer = ByteArray(if (initialCapacity < 16) 16 else initialCapacity)
	private var count = 0

	/** The number of bytes written so far. */
	val size: Int
		get() = count

	/**
	 * Appends the low 8 bits of [value].
	 *
	 * @param Int value The byte to append; only the low 8 bits are used.
	 */
	fun writeByte(value: Int) {
		ensureCapacity(1)
		buffer[count] = value.toByte()
		count++
	}

	/**
	 * Appends every byte of [source].
	 *
	 * @param ByteArray source The bytes to append.
	 */
	fun writeBytes(source: ByteArray) {
		writeBytes(source, 0, source.size)
	}

	/**
	 * Appends [length] bytes of [source] starting at [offset].
	 *
	 * @param ByteArray source The bytes to append from.
	 * @param Int offset       The first byte to append.
	 * @param Int length       How many bytes to append.
	 */
	fun writeBytes(source: ByteArray, offset: Int, length: Int) {
		if (length <= 0) {
			return
		}
		ensureCapacity(length)
		source.copyInto(buffer, count, offset, offset + length)
		count += length
	}

	/**
	 * Copies out the bytes written so far.
	 *
	 * @return ByteArray A right-sized copy of the contents.
	 */
	fun toByteArray(): ByteArray = buffer.copyOf(count)

	/**
	 * Grows the backing array so at least [additional] more bytes fit, doubling to keep appends
	 * amortized constant.
	 *
	 * The size arithmetic is deliberately Long.  In Int it wraps: once the buffer reaches 1 GiB,
	 * `buffer.size * 2` overflows to Int.MIN_VALUE and the next doubling lands on 0, so the search
	 * loop spins on 0 forever instead of terminating.  A caller that inflates without a real bound
	 * (see [inflateZlib]'s maximumSize) can reach that size on a big or malicious image, so this
	 * must fail loudly rather than hang.
	 *
	 * @param Int additional The number of bytes about to be written.
	 * @throws IllegalArgumentException When the content would exceed [MAXIMUM_CAPACITY].
	 */
	private fun ensureCapacity(additional: Int) {
		val required = count.toLong() + additional
		if (required <= buffer.size) {
			return
		}
		require(required <= MAXIMUM_CAPACITY) { "ByteBuilder cannot hold $required bytes (maximum is $MAXIMUM_CAPACITY)" }
		var grown = buffer.size.toLong() * 2
		while (grown < required) {
			grown *= 2
		}
		buffer = buffer.copyOf(minOf(grown, MAXIMUM_CAPACITY).toInt())
	}
}

/**
 * The largest array [ByteBuilder] will allocate: Int.MAX_VALUE less a small header margin, the
 * conventional JVM ceiling (java.util.ArrayList bounds itself the same way) since some VMs refuse
 * the last few elements.
 */
private const val MAXIMUM_CAPACITY: Long = Int.MAX_VALUE - 8L
