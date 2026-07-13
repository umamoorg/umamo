package org.umamo.format.cmo3

import org.umamo.format.cmo3.caff.CaffArchive
import kotlin.test.Test
import kotlin.test.assertEquals

class SanityTest {
	@Test
	fun caffMagicIsStable() {
		assertEquals("CAFF", CaffArchive.MAGIC)
	}
}
