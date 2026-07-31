package org.umamo.format.cmo3

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TargetVersionNoTest {
	@Test
	fun decodesEveryObservedLiteral() {
		// CMO3: CModelSource field targetVersionNo - the corpus-observed per-era literals.
		assertEquals(Cmo3TargetVersion.V30, Cmo3TargetVersion.fromVersionNo(3_000), "Cubism 3.0")
		assertEquals(Cmo3TargetVersion.V33, Cmo3TargetVersion.fromVersionNo(3_030), "Cubism 3.3")
		assertEquals(Cmo3TargetVersion.V40, Cmo3TargetVersion.fromVersionNo(400_000), "Cubism 4.0")
		assertEquals(Cmo3TargetVersion.V42, Cmo3TargetVersion.fromVersionNo(4_020_000), "Cubism 4.2")
		assertEquals(Cmo3TargetVersion.V50, Cmo3TargetVersion.fromVersionNo(5_000_000), "Cubism 5.0")
		assertEquals(Cmo3TargetVersion.V53, Cmo3TargetVersion.fromVersionNo(5_030_000), "Cubism 5.3")
	}

	@Test
	fun decodesModernSchemeAliasesForTheLegacyVersions() {
		// The 4.2+ scheme applied to the older versions is accepted on read even though the editor
		// has not been observed writing it - a tolerant-read fallback only.
		assertEquals(Cmo3TargetVersion.V30, Cmo3TargetVersion.fromVersionNo(3_000_000), "modern 3.0 alias")
		assertEquals(Cmo3TargetVersion.V33, Cmo3TargetVersion.fromVersionNo(3_030_000), "modern 3.3 alias")
		assertEquals(Cmo3TargetVersion.V40, Cmo3TargetVersion.fromVersionNo(4_000_000), "modern 4.0 alias")
	}

	@Test
	fun unknownValuesDecodeToNull() {
		assertNull(Cmo3TargetVersion.fromVersionNo(null), "absent field")
		assertNull(Cmo3TargetVersion.fromVersionNo(0), "zero")
		assertNull(Cmo3TargetVersion.fromVersionNo(9_000_000), "the unconfirmed haruto sentinel")
		assertNull(Cmo3TargetVersion.fromVersionNo(42), "garbage")
		assertNull(Cmo3TargetVersion.fromVersionNo(6_000_000), "unknown future version")
		assertNull(Cmo3TargetVersion.fromVersionNo(-3_000), "negative")
	}

	@Test
	fun everyVersionRoundTripsThroughItsLiteral() {
		Cmo3TargetVersion.entries.forEach { version ->
			assertEquals(version, Cmo3TargetVersion.fromVersionNo(version.versionNo), "round trip for $version")
		}
	}
}
