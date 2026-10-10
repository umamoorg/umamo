package org.umamo.geometry.triangulation

/**
 * A growable Int stack for the triangulator's explicit work lists.
 *
 * Every loop in the triangulator is iterative: a recursive legalization would gamble on Kotlin/Native
 * stack depth, and java.util.ArrayDeque is unavailable in commonMain.
 */
internal class IntStack {
	private var storage = IntArray(INITIAL_CAPACITY)

	/** The number of values held. */
	var size: Int = 0
		private set

	/**
	 * Pushes one value.
	 *
	 * @param Int value The value to push.
	 */
	fun push(value: Int) {
		if (size == storage.size) {
			storage = storage.copyOf(storage.size * 2)
		}

		storage[size] = value
		size++
	}

	/**
	 * Removes and returns the top value; the caller guarantees the stack is not empty.
	 *
	 * @return Int The value that was on top.
	 */
	fun pop(): Int {
		size--
		return storage[size]
	}

	/**
	 * Whether any values remain.
	 *
	 * @return Boolean True while the stack holds at least one value.
	 */
	fun isNotEmpty(): Boolean = size > 0

	/** Drops every value, keeping the storage. */
	fun clear() {
		size = 0
	}

	private companion object {
		const val INITIAL_CAPACITY = 64
	}
}