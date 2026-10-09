package org.umamo.ui.workspace

/**
 * A registry from an area id to the live value one of its spaces registered for the area's lifetime, read
 * by the shell's hovered-area commands at dispatch time.  One lifecycle rule for every per-area register:
 * a space registers as it mounts, a re-registration under the same id replaces the earlier one, and the
 * space withdraws as it leaves; a lookup under an id nobody registered resolves null, which the commands
 * treat as "the pointer is over no such area" and do nothing.  The value type is the register's: the
 * camera-bearing areas keep their controllers in one, the work surfaces their overlay states in another.
 *
 * @param T The value a space registers for its area.
 */
internal class AreaHub<T : Any> {
	private val valuesByArea = mutableMapOf<String, T>()

	/**
	 * Registers one area's value (replacing any prior registration for the id).
	 *
	 * @param String areaId The leaf's area id.
	 * @param T value The area's live value.
	 */
	fun register(areaId: String, value: T) {
		valuesByArea[areaId] = value
	}

	/**
	 * Removes one area's registration (the area died or switched space).
	 *
	 * @param String areaId The leaf's area id.
	 */
	fun unregister(areaId: String) {
		valuesByArea.remove(areaId)
	}

	/**
	 * The live value of an area, or null when none is registered under the id.
	 *
	 * @param String areaId The leaf's area id.
	 * @return T? The area's value, or null.
	 */
	fun forArea(areaId: String): T? = valuesByArea[areaId]
}