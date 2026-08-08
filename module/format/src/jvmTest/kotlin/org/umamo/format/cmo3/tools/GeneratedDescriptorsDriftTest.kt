package org.umamo.format.cmo3.tools

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The descriptor drift gate: regenerates the descriptor source in memory and byte-compares it
 * against the checked-in file, so any model-class or registration edit that forgot to re-run
 * DescriptorGenerator fails here with the exact command to fix it.  Needs no corpus — the inputs
 * are the checked-in GeneratedModel.kt and the registration lists — so unlike the corpus gates it
 * HARD-FAILS when its input is missing rather than self-skipping.
 */
class GeneratedDescriptorsDriftTest {
	@Test
	fun checkedInDescriptorsMatchRegeneration() {
		val rootDir = DescriptorSource.repositoryRoot()
		val descriptorsFile = File(rootDir, DescriptorSource.DESCRIPTORS_PATH)
		assertTrue(
			descriptorsFile.isFile,
			"GeneratedDescriptors.kt is missing; generate it with: ${DescriptorSource.REGENERATION_COMMAND}",
		)
		val input = DescriptorSource.input(rootDir)
		assertEquals(
			DescriptorSource.descriptorsSource(input),
			descriptorsFile.readText(),
			"GeneratedDescriptors.kt is stale; regenerate with: ${DescriptorSource.REGENERATION_COMMAND}",
		)

		val registrationFile = File(rootDir, DescriptorSource.REGISTRATION_PATH)
		assertTrue(
			registrationFile.isFile,
			"GeneratedRegistration.kt is missing; generate it with: ${DescriptorSource.REGENERATION_COMMAND}",
		)
		assertEquals(
			DescriptorSource.registrationSource(input),
			registrationFile.readText(),
			"GeneratedRegistration.kt is stale; regenerate with: ${DescriptorSource.REGENERATION_COMMAND}",
		)
	}
}