package org.umamo.buildlogic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModuleBoundariesTest {
	private val graph =
		mapOf(
			":runtime" to emptyList(),
			":edit" to listOf(":runtime", ":geometry"),
			":geometry" to emptyList(),
		)

	@Test
	fun aTestConfigurationIsToldByItsName() {
		assertTrue(isTestConfiguration("jvmTestImplementation"))
		assertTrue(isTestConfiguration("commonTestApi"))
		assertTrue(isTestConfiguration("testImplementation"))
		assertTrue(isTestConfiguration("debugUnitTestRuntimeOnly"))
		assertFalse(isTestConfiguration("commonMainApi"))
		assertFalse(isTestConfiguration("jvmAndroidMainImplementation"))
		assertFalse(isTestConfiguration("implementation"))
	}

	@Test
	fun aGraphIsListedSortedOneModulePerLine() {
		assertEquals(listOf(":edit -> :geometry, :runtime", ":geometry ->", ":runtime ->"), renderModuleGraph(graph))
	}

	@Test
	fun theTableReadsBackAsTheGraphItWasWrittenFrom() {
		val lines = listOf("# Which module may see which.", "") + renderModuleGraph(graph)

		assertEquals(graph.mapValues { (_, dependencies) -> dependencies.toSet() }, parseModuleGraph(lines))
	}

	@Test
	fun aLineThatIsNotAModuleLineIsRefused() {
		assertFailsWith<IllegalArgumentException> { parseModuleGraph(listOf(":edit :runtime")) }
	}

	@Test
	fun agreeingGraphsHaveNoDifference() {
		assertEquals(emptyList(), diffModuleGraph(parseModuleGraph(renderModuleGraph(graph)), graph))
	}

	@Test
	fun everyKindOfDifferenceIsNamed() {
		val table = mapOf(":edit" to setOf(":runtime", ":geometry"), ":runtime" to emptySet(), ":geometry" to emptySet(), ":retired" to setOf(":runtime"))
		val built = mapOf(":edit" to listOf(":runtime", ":render"), ":runtime" to emptyList(), ":geometry" to emptyList(), ":testkit" to emptyList())

		assertEquals(
			listOf(
				":edit now depends on :render",
				":edit no longer depends on :geometry",
				":retired is listed, and the build has no such module",
				":testkit is a module the table does not list",
			),
			diffModuleGraph(table, built),
		)
	}
}