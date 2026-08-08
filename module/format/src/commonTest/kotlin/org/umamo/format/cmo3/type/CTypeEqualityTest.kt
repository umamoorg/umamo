package org.umamo.format.cmo3.type

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Pins the hand-forwarded equals/hashCode/toString on the CMO3 collection types.  Kotlin's `by`
 * delegation does NOT forward these three, so without the overrides two equal lists would compare
 * by identity - the exact trap the classes document.  This is the load-bearing behavior, not the
 * delegated MutableList/MutableMap surface.
 */
class CTypeEqualityTest {
	/** A CArrayList equals a plain list with the same elements, in both directions. */
	@Test
	fun cArrayListEqualsAPlainListByValue() {
		val cList = CArrayList(listOf(1, 2, 3))
		assertEquals<Any>(listOf(1, 2, 3), cList)
		assertEquals<Any>(cList, listOf(1, 2, 3))
		assertEquals(listOf(1, 2, 3).hashCode(), cList.hashCode())
		assertEquals(listOf(1, 2, 3).toString(), cList.toString())
		assertNotEquals<Any>(listOf(1, 2), cList)
	}

	/** A CHashMap equals a plain map with the same entries and keeps insertion order. */
	@Test
	fun cHashMapEqualsAPlainMapAndPreservesInsertionOrder() {
		val cMap = CHashMap<String, Int>()
		cMap["zulu"] = 1
		cMap["alpha"] = 2
		cMap["mike"] = 3
		assertEquals<Any>(mapOf("zulu" to 1, "alpha" to 2, "mike" to 3), cMap)
		assertEquals(mapOf("zulu" to 1, "alpha" to 2, "mike" to 3).hashCode(), cMap.hashCode())
		assertEquals(listOf("zulu", "alpha", "mike"), cMap.keys.toList(), "iteration follows insertion, not hash order")
		assertNotEquals<Any>(mapOf("zulu" to 1), cMap)
	}

	/** An empty-constructed instance still equals its plain empty counterpart. */
	@Test
	fun emptyInstancesEqualTheirPlainCounterparts() {
		assertEquals<Any>(emptyList<Int>(), CArrayList<Int>())
		assertEquals<Any>(emptyMap<String, Int>(), CHashMap<String, Int>())
	}
}