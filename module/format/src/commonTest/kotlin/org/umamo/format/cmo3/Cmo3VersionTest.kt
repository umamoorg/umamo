package org.umamo.format.cmo3

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Cmo3VersionTest {
	@Test
	fun everyVersionDescribesItself() {
		// If a new Cmo3Version is added, `describe()` won't compile until handled - this test just
		// confirms the wired-up path produces a non-empty label for each known version.
		val all = listOf(Cmo3Version.V3, Cmo3Version.V4, Cmo3Version.V5)
		all.forEach { version ->
			assertTrue(version.describe().isNotEmpty(), "describe() empty for $version")
		}
	}

	@Test
	fun fileFormatVersionMapsByLeadingGenerationDigit() {
		// Observed corpus values - CMO3.md §3 Document shape.
		assertEquals(Cmo3Version.V4, Cmo3Version.fromFileFormatVersion("401010001"), "Cubism 4.x sample")
		assertEquals(Cmo3Version.V4, Cmo3Version.fromFileFormatVersion("400050002"), "second Cubism 4.x sample")
		assertEquals(Cmo3Version.V5, Cmo3Version.fromFileFormatVersion("501030000"), "Cubism 5.x sample")
		assertEquals(Cmo3Version.V3, Cmo3Version.fromFileFormatVersion("300000000"), "Cubism 3.x generation")
	}

	@Test
	fun fileFormatVersionRejectsUnknownOrMalformedValues() {
		assertNull(Cmo3Version.fromFileFormatVersion(""), "empty string")
		assertNull(Cmo3Version.fromFileFormatVersion("not-a-number"), "garbage text")
		assertNull(Cmo3Version.fromFileFormatVersion("601030000"), "unknown future generation")
		assertNull(Cmo3Version.fromFileFormatVersion("42"), "too small to carry a generation digit")
	}
}
