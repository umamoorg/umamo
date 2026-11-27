package org.umamo.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Tells whether a configuration belongs to a test source set, by its name.
 *
 * @param String configurationName The configuration's name, such as `jvmTestImplementation`.
 * @return Boolean True for a configuration of any test source set.
 */
fun isTestConfiguration(configurationName: String): Boolean = configurationName.contains("test", ignoreCase = true)

/**
 * Writes a module graph as the lines of the dependency table, one module per line.
 *
 * @param Map<String, Collection<String>> edges Each module's path with the paths of the modules it depends on.
 * @return List<String> The lines, sorted by module, each `:module -> :first, :second` with the dependencies sorted.
 */
fun renderModuleGraph(edges: Map<String, Collection<String>>): List<String> = edges.toSortedMap().map { (module, dependencies) -> "$module -> ${dependencies.sorted().joinToString(", ")}".trimEnd() }

/**
 * Reads the dependency table back into a module graph.
 *
 * @param List<String> lines The table's lines; a blank line or one that opens with `#` is passed over.
 * @return Map<String, Set<String>> Each listed module with its listed dependencies.
 * @throws IllegalArgumentException When a line is not `:module -> :dependency, ...`.
 */
fun parseModuleGraph(lines: List<String>): Map<String, Set<String>> {
	val edges = LinkedHashMap<String, Set<String>>()

	for (line in lines.map { line -> line.trim() }.filter { line -> line.isNotEmpty() && !line.startsWith("#") }) {
		require(line.contains("->")) { "not a module line (`:module -> :dependency, ...`): $line" }
		val module = line.substringBefore("->").trim()
		edges[module] = line.substringAfter("->").split(',').map { dependency -> dependency.trim() }.filter { dependency -> dependency.isNotEmpty() }.toSet()
	}

	return edges
}

/**
 * Lists where the build's module graph and the dependency table disagree.
 *
 * @param Map<String, Collection<String>> expected The table's graph.
 * @param Map<String, Collection<String>> actual The build's graph.
 * @return List<String> One line per difference, empty when the two agree.
 */
fun diffModuleGraph(expected: Map<String, Collection<String>>, actual: Map<String, Collection<String>>): List<String> {
	val differences = ArrayList<String>()

	for (module in (expected.keys + actual.keys).toSortedSet()) {
		val listed = expected[module]?.toSet()
		val built = actual[module]?.toSet()

		when {
			listed == null -> {
				differences.add("$module is a module the table does not list")
			}

			built == null -> {
				differences.add("$module is listed, and the build has no such module")
			}

			else -> {
				differences.addAll((built - listed).sorted().map { dependency -> "$module now depends on $dependency" })
				differences.addAll((listed - built).sorted().map { dependency -> "$module no longer depends on $dependency" })
			}
		}
	}

	return differences
}

/**
 * Holds the dependencies between modules to a table that is checked in.
 *
 * The compiler takes any dependency a build script declares, so a new edge between modules arrives as
 * one quiet line in one script.  With the table, it is also a changed line in a file whose whole subject
 * is which module may see which, and that is where a reviewer meets it.  Only shipping source sets are
 * read: a test may reach further than the code it tests.
 */
@CacheableTask
abstract class CheckModuleBoundariesTask : DefaultTask() {
	/** The build's graph: each module's path with the modules its shipping source sets depend on. */
	@get:Input
	abstract val actualEdges: MapProperty<String, List<String>>

	/** The dependency table. */
	@get:InputFile
	@get:PathSensitive(PathSensitivity.NONE)
	abstract val table: RegularFileProperty

	/** Where the build's graph is written, as the table would list it. */
	@get:OutputFile
	abstract val report: RegularFileProperty

	/** Compares the build's graph with the table and fails on any difference. */
	@TaskAction
	fun check() {
		val actual = actualEdges.get()
		val reportFile = report.get().asFile
		reportFile.parentFile.mkdirs()
		reportFile.writeText(renderModuleGraph(actual).joinToString("\n") + "\n")
		val differences = diffModuleGraph(parseModuleGraph(table.get().asFile.readLines()), actual)

		if (differences.isNotEmpty()) {
			throw GradleException(
				"The dependencies between modules differ from ${table.get().asFile.name}:\n" +
					differences.joinToString("\n") { difference -> "  $difference" } +
					"\nIf the change is meant, make the same change to the table, so it is reviewed as a change to the module graph." +
					"\nThe build's graph, as the table would list it, is in ${reportFile.path}.",
			)
		}
	}
}