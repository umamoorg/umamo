package org.umamo.format.cmo3.serialize

import org.umamo.format.cmo3.serialize.annotations.SerialTag
import org.umamo.format.cmo3.type.CArrayList
import org.umamo.format.cmo3.type.CHashMap
import org.umamo.format.cmo3.xml.XmlCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@SerialTag("Crate")
class Crate {
	var items: List<String> = emptyList()
	var lookup: Map<String, Int> = emptyMap()
}

/**
 * Pins the on-disk contract of the two CMO3 collection types, corpus-free.
 *
 * These carry real weight: CArrayList must emit <carray_list> (not <array_list>) and CHashMap must
 * emit <hash_map> preserving insertion order — SerializeEngine picks both by an `is` check ordered
 * subtype-before-supertype, and CMO3 byte-identity depends on the tag and the entry order.  Nothing
 * else covers this without a corpus sample, so a change to how these types are declared could
 * otherwise break round-trip with every gate still green.
 */
class CTypeRoundTripTest {
	private fun engine(): SerializeEngine {
		val registry = SerializerRegistry()
		registry.register(Crate::class)
		return SerializeEngine(registry, SerializeDiagnostics.None)
	}

	@Test
	fun cArrayListAndCHashMapRoundTripWithTagsAndOrder() {
		val crate =
			Crate().apply {
				items = CArrayList(listOf("first", "second", "third"))
				// Deliberately NOT alphabetical: proves order is preserved, not re-hashed.
				lookup =
					CHashMap<String, Int>().apply {
						put("zulu", 26)
						put("alpha", 1)
						put("mike", 13)
					}
			}

		val engine = engine()
		val xml = XmlCodec.write(engine.writeRoot(crate)).decodeToString()
		assertTrue("<carray_list" in xml, "CArrayList must emit <carray_list>, got: $xml")
		assertTrue("<hash_map" in xml, "CHashMap must emit <hash_map>, got: $xml")

		val restored = engine.readRoot(XmlCodec.parse(xml.encodeToByteArray())) as Crate
		assertEquals(listOf("first", "second", "third"), restored.items, "list contents round-trip")
		assertTrue(restored.items is CArrayList<*>, "carray_list must read back as CArrayList, got ${restored.items::class.simpleName}")
		assertTrue(restored.lookup is CHashMap<*, *>, "hash_map must read back as CHashMap, got ${restored.lookup::class.simpleName}")
		assertEquals(listOf("zulu", "alpha", "mike"), restored.lookup.keys.toList(), "hash_map entry ORDER must survive (CMO3 byte-identity depends on it)")
		assertEquals(mapOf("zulu" to 26, "alpha" to 1, "mike" to 13), restored.lookup, "map contents round-trip")
	}
}
