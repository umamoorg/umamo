package org.umamo.testkit

import org.junit.AssumptionViolatedException
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TestSkipsTest {
	// A name no build forwards, so the test owns it.
	private val property = "testkit.requireSampleProbe"

	/**
	 * Runs a block with the probe property set, and clears the property afterwards.
	 *
	 * @param String? value The value to set, or null to leave the property unset.
	 * @param Function block What to run.
	 */
	private fun withProperty(value: String?, block: () -> Unit) {
		if (value != null) {
			System.setProperty(property, value)
		}

		try {
			block()
		} finally {
			System.clearProperty(property)
		}
	}

	@Test
	fun aSkipIsTheAssumptionFailureTheRunnerReportsAsSkipped() {
		val skip = assertFailsWith<AssumptionViolatedException> { skipTest("the sample is absent") }

		assertEquals("the sample is absent", skip.message)
	}

	@Test
	fun anExistingFileOrDirectoryIsReturned() {
		val directory = createTempDirectory("testkit").toFile()

		try {
			val file = File(directory, "sample.bin").also { created -> created.writeBytes(byteArrayOf(1)) }

			withProperty(file.path) { assertEquals(file, requireSample(property)) }
			withProperty(directory.path) { assertEquals(directory, requireSample(property)) }
		} finally {
			directory.deleteRecursively()
		}
	}

	@Test
	fun anUnsetPropertySkipsAndNamesItself() {
		val skip = assertFailsWith<AssumptionViolatedException> { withProperty(null) { requireSample(property) } }

		assertEquals("absent -D$property", skip.message)
	}

	@Test
	fun aPathThatNamesNothingSkips() {
		assertFailsWith<AssumptionViolatedException> { withProperty("/nowhere/at/all.cmo3") { requireSample(property) } }
	}
}