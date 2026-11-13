// Convention plugin for the root project: says honestly how a build went, and runs what covers a change.
//
// Two things, both about reading a Gradle run correctly:
//
//   1. A summary when any build ends: the failed tasks, the tasks asked for by name that did not run,
//      what each test task found with its skipped tests named, and a last line that opens with
//      `VERDICT:`.  Gradle's own closing line cannot do that job: "BUILD SUCCESSFUL" is as true of a run
//      whose gated tests all sat out as of one that exercised them.  See
//      org.umamo.buildlogic.VerificationSummaryService.
//   2. `./gradlew verifyChanged`, which maps the files changed in the working tree to the modules they
//      belong to and runs the tests, compile gates, and lint that cover them.  Pass `-PverifyBase=<ref>`
//      to count everything since the branch left that ref, committed work included.  See
//      org.umamo.buildlogic.planVerification for the rules.

import org.umamo.buildlogic.BuildEventServices
import org.umamo.buildlogic.ModuleFacts
import org.umamo.buildlogic.VerificationSummaryService
import org.umamo.buildlogic.VerifyChangedTask
import org.umamo.buildlogic.planVerification

// Plain strings, so the configuration cache stores values and no project reference.
val projectDirectories = allprojects.associate { module -> module.path to module.projectDir.absolutePath } + gradle.includedBuilds.associate { includedBuild -> ":" + includedBuild.name to includedBuild.projectDir.absolutePath }
val requestedTaskNames = gradle.startParameter.taskNames
val dryRunRequested = gradle.startParameter.isDryRun

val verificationSummary =
	gradle.sharedServices.registerIfAbsent("umamoVerificationSummary", VerificationSummaryService::class) {
		parameters.projectDirectories.set(projectDirectories)
		parameters.requestedTasks.set(requestedTaskNames)
		parameters.dryRun.set(dryRunRequested)
	}

objects.newInstance<BuildEventServices>().listenerRegistry.onTaskCompletion(verificationSummary)

// The plan is made only when the task is asked for.  Git's answer becomes an input of the configuration
// cache, so asking on every build would throw the cache away each time a file was first touched.
val verifyChangedRequested = requestedTaskNames.any { taskName -> taskName.trimStart(':') == "verifyChanged" }

val verifyChanged =
	tasks.register<VerifyChangedTask>("verifyChanged") {
		group = "verification"
		description = "Runs the tests, compile gates, and lint that cover the files changed in the working tree."
		planned.set(verifyChangedRequested)
		report.set(emptyList())
	}

/**
 * Asks git for a list of paths.
 *
 * @param String arguments The git arguments, after the options that keep its output plain.
 * @return List<String> The paths git printed, from the repository root.
 */
fun gitPaths(vararg arguments: String): List<String> =
	providers
		.exec {
			workingDir = rootDir
			// quotePath off, so a path with a non-ASCII character comes back as itself and not as an escape.
			commandLine(listOf("git", "-c", "core.quotePath=false") + arguments)
		}.standardOutput.asText
		.get()
		.lines()
		.filter { line -> line.isNotBlank() }

if (verifyChangedRequested) {
	val baseRef = providers.gradleProperty("verifyBase").orNull
	// With a base, everything since the branch left it, the uncommitted work included; without, the
	// working tree against HEAD.
	val trackedChanges = if (baseRef == null) gitPaths("diff", "--name-only", "HEAD") else gitPaths("diff", "--name-only", "--merge-base", baseRef)
	val changedFiles = (trackedChanges + gitPaths("ls-files", "--others", "--exclude-standard")).distinct().sorted()
	val repositoryRoot = rootDir

	// Which tasks a module has, and what it depends on, is only known once every module is configured.
	gradle.projectsEvaluated {
		val modules =
			subprojects.map { module ->
				ModuleFacts(
					path = module.path,
					directory = module.projectDir.relativeTo(repositoryRoot).invariantSeparatorsPath,
					taskNames = module.tasks.names.toSet(),
					dependencies =
						module.configurations
							.flatMap { configuration -> configuration.dependencies.withType<ProjectDependency>().map { dependency -> dependency.path } }
							.toSet() - module.path,
				)
			}
		val plan = planVerification(changedFiles, modules)

		verifyChanged.configure {
			dependsOn(plan.taskPaths)

			if (plan.ruleSetTests) {
				dependsOn(gradle.includedBuild("ktlint-rules").task(":test"))
			}

			if (plan.buildLogicTests) {
				dependsOn(gradle.includedBuild("build-logic").task(":test"))
			}

			report.set(plan.report)
		}
	}
}