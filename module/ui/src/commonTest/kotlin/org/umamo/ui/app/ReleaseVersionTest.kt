package org.umamo.ui.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins how the update check orders versions: by number, not as text, with a `-dev` build before the release it
 * precedes - the order RELEASING.md's version rule gives master and the tags.
 */
class ReleaseVersionTest {
	/**
	 * Reads a version the test knows is valid.
	 *
	 * @param String text The version or tag.
	 * @return ReleaseVersion The version.
	 */
	private fun version(text: String): ReleaseVersion = checkNotNull(ReleaseVersion.parse(text)) { "$text did not parse" }

	@Test
	fun aTagAndAVersionReadTheSame() {
		assertEquals(version("0.4.0"), version("v0.4.0"))
		assertEquals(ReleaseVersion(0, 4, 1, "dev"), version("0.4.1-dev"))
		assertEquals("0.4.1-dev", version("v0.4.1-dev").toString())
		assertEquals("1.2.3", version("v1.2.3").toString())
	}

	@Test
	fun aDevelopmentBuildComesBeforeItsRelease() {
		assertTrue(version("0.4.0-dev") < version("0.4.0"))
		assertTrue(version("0.4.0") < version("0.4.1-dev"))
		assertTrue(version("0.4.1-dev") < version("0.4.1"))
		assertEquals(0, version("0.4.0").compareTo(version("v0.4.0")))
	}

	@Test
	fun numbersCompareAsNumbers() {
		assertTrue(version("0.10.0") > version("0.9.0"))
		assertTrue(version("1.0.0") > version("0.99.99"))
		assertTrue(version("0.4.10") > version("0.4.9"))
	}

	@Test
	fun anythingElseIsUnreadable() {
		for (text in listOf("", "latest", "0.4", "0.4.0.1", "v0.4.x", "0.4.0+build", "99999999999.0.0")) {
			assertNull(ReleaseVersion.parse(text), "'$text'")
		}
	}
}