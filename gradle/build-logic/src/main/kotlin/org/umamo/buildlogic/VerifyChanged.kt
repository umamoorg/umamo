package org.umamo.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/**
 * What choosing a module's verification tasks needs to know about it.
 *
 * @property String path The Gradle path, such as `:format`.
 * @property String directory The module's directory from the repository root, with forward slashes.
 * @property Set<String> taskNames The names of the tasks the module has.
 * @property Set<String> dependencies The paths of the modules it depends on directly, its tests' included.
 */
data class ModuleFacts(val path: String, val directory: String, val taskNames: Set<String>, val dependencies: Set<String>)

/**
 * What `verifyChanged` chose to run.
 *
 * @property List<String> taskPaths The root build's tasks to run, sorted.
 * @property Boolean ruleSetTests Whether the ktlint rule set's own tests run, which also lint both included builds.
 * @property Boolean buildLogicTests Whether the build logic's own tests run.
 * @property List<String> report The lines that say what changed and what was chosen for it.
 */
data class VerificationPlan(val taskPaths: List<String>, val ruleSetTests: Boolean, val buildLogicTests: Boolean, val report: List<String>)

/** Where the ktlint rule set lives, an included build the root build's tasks do not reach. */
private const val RULE_SET_DIRECTORY = "gradle/ktlint-rules/"

/** Where the convention plugins live, the other included build. */
private const val BUILD_LOGIC_DIRECTORY = "gradle/build-logic/"

/** The files that configure every module at once, so a change to one can break any of them. */
private val BUILD_WIDE_FILES = setOf("build.gradle.kts", "settings.gradle.kts", "gradle.properties", "gradle/libs.versions.toml", "gradle/gradle-daemon-jvm.properties")

/** The root project's own Kotlin scripts, which its ktlintCheck lints. */
private val ROOT_SCRIPTS = setOf("build.gradle.kts", "settings.gradle.kts")

/** The table the dependencies between modules are held to, which `checkModuleBoundaries` reads. */
private const val MODULE_GRAPH_TABLE = "gradle/module-dependencies.txt"

/** How many uncovered files the report names before it only counts the rest. */
private const val MAX_NAMED_UNCOVERED_FILES = 8

/**
 * Names the source set a changed file belongs to.
 *
 * @param String changedFile The file's path from the repository root.
 * @param ModuleFacts module The module whose directory holds it.
 * @return String? The source set, such as `commonMain`, or null for a file outside `src`, which is the
 *                 module's build script or something else that shapes the whole module.
 */
private fun sourceSetOf(changedFile: String, module: ModuleFacts): String? {
	val segments = changedFile.removePrefix(module.directory + "/").split('/')

	return if (segments.size > 2 && segments[0] == "src") segments[1] else null
}

/**
 * Tells whether a source set is compiled for more than the JVM and Android.
 *
 * Those are the ones the non-JVM gates exist for: a JVM-only source set cannot leak a JVM API into a
 * target that lacks it.
 *
 * @param String sourceSet The source set name.
 * @return Boolean True for `commonMain`, `commonTest`, and the native source sets.
 */
private fun isSharedSourceSet(sourceSet: String): Boolean = !sourceSet.startsWith("jvm") && !sourceSet.startsWith("android") && sourceSet != "main" && sourceSet != "test"

/**
 * Chooses the tasks that cover a set of changed files.
 *
 * A changed module runs its tests; a module that depends on one whose main sources changed is compiled,
 * tests included, since that is where a changed signature breaks.  The non-JVM gates (the metadata and
 * iOS compiles, the native tests) are chosen only for a module that has a native target and only when a
 * shared source set changed, and the Android compile only when an Android source set did, so a JVM-only
 * edit pays for neither.  A change to the build itself counts as a change to every module.
 *
 * @param List<String> changedFiles The changed and untracked files, as paths from the repository root.
 * @param List<ModuleFacts> modules Every module of the root build.
 * @return VerificationPlan The tasks to run and the report of why.
 */
fun planVerification(changedFiles: List<String>, modules: List<ModuleFacts>): VerificationPlan {
	if (changedFiles.isEmpty()) {
		return VerificationPlan(emptyList(), ruleSetTests = false, buildLogicTests = false, report = listOf("verifyChanged: no changed or untracked files, so there is nothing to verify"))
	}

	val ruleSetChanged = changedFiles.any { changedFile -> changedFile.startsWith(RULE_SET_DIRECTORY) }
	val buildLogicChanged = changedFiles.any { changedFile -> changedFile.startsWith(BUILD_LOGIC_DIRECTORY) }
	val buildChanged = buildLogicChanged || changedFiles.any { changedFile -> changedFile in BUILD_WIDE_FILES }
	val lintEverywhere = ruleSetChanged || ".editorconfig" in changedFiles
	val taskPaths = sortedSetOf<String>()
	val report = ArrayList<String>()
	val verifiedModules = HashSet<String>()
	var moduleGraphTouched = buildChanged || MODULE_GRAPH_TABLE in changedFiles
	val mainChangedModules = LinkedHashSet<String>()
	val coveredFiles = HashSet<String>()

	report.add("verifyChanged: ${changedFiles.size} changed or untracked files")

	if (buildChanged) {
		report.add("  the build itself changed, so every module is verified")
	}

	for (module in modules.sortedBy { module -> module.path }) {
		val moduleFiles = changedFiles.filter { changedFile -> changedFile.startsWith(module.directory + "/") }
		coveredFiles.addAll(moduleFiles)

		if (lintEverywhere || moduleFiles.any { moduleFile -> moduleFile.endsWith(".kt") || moduleFile.endsWith(".kts") }) {
			addTask(taskPaths, module, "ktlintCheck")
		}

		if (moduleFiles.isEmpty() && !buildChanged) {
			continue
		}

		verifiedModules.add(module.path)

		val sourceSets = moduleFiles.mapNotNull { moduleFile -> sourceSetOf(moduleFile, module) }.toSortedSet()
		val wholeModule = buildChanged || moduleFiles.any { moduleFile -> sourceSetOf(moduleFile, module) == null }
		val chosen = ArrayList<String>()

		// An application without tests of its own is verified by compiling it.
		chosen.addAll(listOfNotNull(addTask(taskPaths, module, "jvmTest") ?: addTask(taskPaths, module, "compileDebugKotlin")))

		if ((wholeModule || sourceSets.any { sourceSet -> isSharedSourceSet(sourceSet) }) && "compileKotlinIosArm64" in module.taskNames) {
			for (taskName in listOf("compileCommonMainKotlinMetadata", "compileKotlinIosArm64", "compileTestKotlinIosArm64", "linuxX64Test")) {
				chosen.addAll(listOfNotNull(addTask(taskPaths, module, taskName)))
			}
		}

		if (wholeModule || sourceSets.any { sourceSet -> sourceSet.startsWith("android") || sourceSet == "jvmAndroidMain" }) {
			chosen.addAll(listOfNotNull(addTask(taskPaths, module, "compileAndroidMain")))
		}

		if (wholeModule || sourceSets.any { sourceSet -> !sourceSet.endsWith("Test") && sourceSet != "test" }) {
			mainChangedModules.add(module.path)
		}

		// A build script is where a dependency between modules is declared.
		moduleGraphTouched = moduleGraphTouched || wholeModule

		if (moduleFiles.isNotEmpty()) {
			val changedParts = (sourceSets + if (moduleFiles.any { moduleFile -> sourceSetOf(moduleFile, module) == null }) listOf("build") else emptyList()).joinToString(", ")
			report.add("  ${module.path} ($changedParts): ${chosen.joinToString(", ")}")
		}
	}

	// A module verified in its own right is compiled on the way to its tests.
	val compiledDependents =
		dependentsOf(mainChangedModules, modules)
			.filter { dependent -> dependent.path !in verifiedModules }
			.filter { dependent -> (addTask(taskPaths, dependent, "compileTestKotlinJvm") ?: addTask(taskPaths, dependent, "compileDebugKotlin")) != null }

	if (compiledDependents.isNotEmpty()) {
		report.add("  compiled because they depend on changed main sources: " + compiledDependents.joinToString(", ") { dependent -> dependent.path })
	}

	if (coveredFiles.isNotEmpty() || buildChanged) {
		taskPaths.add(":checkCommonSourcePurity")
	}

	if (moduleGraphTouched) {
		taskPaths.add(":checkModuleBoundaries")
	}

	if (lintEverywhere || changedFiles.any { changedFile -> changedFile in ROOT_SCRIPTS }) {
		taskPaths.add(":ktlintCheck")
	}

	if (lintEverywhere) {
		report.add("  the lint rules changed, so ktlintCheck runs over every module")
	}

	// The rule set's test is what lints an included build, which no ktlint task of the root build reads.
	if (ruleSetChanged || buildLogicChanged) {
		report.add("  an included build changed, so the rule set's tests run, which lint both of them")
	}

	if (buildLogicChanged) {
		report.add("  the build logic's own tests run")
	}

	val uncoveredFiles = changedFiles.filter { changedFile -> changedFile !in coveredFiles && changedFile !in BUILD_WIDE_FILES && changedFile != ".editorconfig" && changedFile != MODULE_GRAPH_TABLE && !changedFile.startsWith(RULE_SET_DIRECTORY) && !changedFile.startsWith(BUILD_LOGIC_DIRECTORY) }

	if (uncoveredFiles.isNotEmpty()) {
		val unnamed = if (uncoveredFiles.size > MAX_NAMED_UNCOVERED_FILES) ", and ${uncoveredFiles.size - MAX_NAMED_UNCOVERED_FILES} more" else ""
		report.add("  no task covers: " + uncoveredFiles.take(MAX_NAMED_UNCOVERED_FILES).joinToString(", ") + unnamed)
	}

	return VerificationPlan(taskPaths.toList(), ruleSetTests = ruleSetChanged || buildLogicChanged, buildLogicTests = buildLogicChanged, report = report)
}

/**
 * Adds one of a module's tasks to the chosen set, when the module has it.
 *
 * @param MutableSet<String> taskPaths The chosen task paths, added to.
 * @param ModuleFacts module The module.
 * @param String taskName The task's name within the module.
 * @return String? The task name when the module has that task, else null.
 */
private fun addTask(taskPaths: MutableSet<String>, module: ModuleFacts, taskName: String): String? {
	if (taskName !in module.taskNames) {
		return null
	}

	taskPaths.add(module.path + ":" + taskName)

	return taskName
}

/**
 * Finds every module that depends on one of a set of modules, directly or through others.
 *
 * @param Set<String> changedPaths The paths of the modules whose main sources changed.
 * @param List<ModuleFacts> modules Every module of the root build.
 * @return List<ModuleFacts> The dependents, sorted by path.  A changed module is among them when it
 *                           depends on another changed one.
 */
private fun dependentsOf(changedPaths: Set<String>, modules: List<ModuleFacts>): List<ModuleFacts> {
	val reached = LinkedHashSet<String>()
	var frontier = changedPaths

	while (frontier.isNotEmpty()) {
		val next = modules.filter { module -> module.path !in reached && module.dependencies.any { dependency -> dependency in frontier } }.map { module -> module.path }.toSet()
		reached.addAll(next)
		frontier = next
	}

	return modules.filter { module -> module.path in reached }.sortedBy { module -> module.path }
}

/**
 * Runs what covers the files changed in the working tree, and says what it chose.
 *
 * The choosing happens while the build is configured, where task dependencies can still be added, so the
 * task itself only prints the report.
 */
@DisableCachingByDefault(because = "It only reports what its dependencies were chosen for.")
abstract class VerifyChangedTask : DefaultTask() {
	/** Whether the plan was made, which it only is when the task is asked for by its full name. */
	@get:Input
	abstract val planned: Property<Boolean>

	/** The plan's report. */
	@get:Input
	abstract val report: ListProperty<String>

	/** Prints the plan's report, or fails when no plan was made. */
	@TaskAction
	fun printReport() {
		if (!planned.get()) {
			throw GradleException("verifyChanged chooses its tasks while the build is configured, so it must be asked for by its full name: ./gradlew verifyChanged")
		}

		for (line in report.get()) {
			logger.lifecycle(line)
		}
	}
}