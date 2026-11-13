package org.umamo.buildlogic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VerifyChangedTest {
	private val jvmTasks = setOf("jvmTest", "compileTestKotlinJvm", "compileKotlinJvm", "compileCommonMainKotlinMetadata", "ktlintCheck")
	private val androidTasks = jvmTasks + "compileAndroidMain"
	private val nativeTasks = androidTasks + setOf("compileKotlinIosArm64", "compileTestKotlinIosArm64")

	// The shape of the real graph, cut down: two native-gated libraries, two that are not, and two applications.
	private val modules =
		listOf(
			ModuleFacts(":geometry", "module/geometry", nativeTasks + "linuxX64Test", emptySet()),
			ModuleFacts(":runtime", "module/runtime", nativeTasks, emptySet()),
			ModuleFacts(":edit", "module/edit", androidTasks, setOf(":runtime", ":geometry")),
			ModuleFacts(":ui", "module/ui", androidTasks, setOf(":runtime", ":edit")),
			ModuleFacts(":desktop", "app/desktop", jvmTasks, setOf(":ui", ":edit", ":runtime")),
			ModuleFacts(":android", "app/android", setOf("compileDebugKotlin", "testDebugUnitTest", "ktlintCheck"), setOf(":ui", ":edit", ":runtime")),
		)

	@Test
	fun nothingChangedMeansNothingRuns() {
		val plan = planVerification(emptyList(), modules)

		assertEquals(emptyList(), plan.taskPaths)
		assertFalse(plan.ruleSetTests)
		assertFalse(plan.buildLogicTests)
		assertEquals(listOf("verifyChanged: no changed or untracked files, so there is nothing to verify"), plan.report)
	}

	@Test
	fun aTestOnlyChangeRunsThatModulesTestsAndCompilesNoDependent() {
		val plan = planVerification(listOf("module/edit/src/jvmTest/kotlin/org/umamo/edit/SessionTest.kt"), modules)

		assertEquals(listOf(":checkCommonSourcePurity", ":edit:jvmTest", ":edit:ktlintCheck"), plan.taskPaths)
		assertEquals(listOf("verifyChanged: 1 changed or untracked files", "  :edit (jvmTest): jvmTest"), plan.report)
	}

	@Test
	fun aMainChangeCompilesEveryDependentAndItsTests() {
		val plan = planVerification(listOf("module/edit/src/commonMain/kotlin/org/umamo/edit/EditorSession.kt"), modules)

		// :edit has no native target, so its commonMain has no metadata or iOS compile worth running.
		assertEquals(
			listOf(":android:compileDebugKotlin", ":checkCommonSourcePurity", ":desktop:compileTestKotlinJvm", ":edit:jvmTest", ":edit:ktlintCheck", ":ui:compileTestKotlinJvm"),
			plan.taskPaths,
		)
		assertEquals("  compiled because they depend on changed main sources: :android, :desktop, :ui", plan.report.last())
	}

	@Test
	fun aSharedSourceSetInANativeModuleRunsTheNonJvmGates() {
		val plan = planVerification(listOf("module/geometry/src/commonTest/kotlin/org/umamo/geometry/PredicateTest.kt"), modules)

		assertEquals(
			listOf(
				":checkCommonSourcePurity",
				":geometry:compileCommonMainKotlinMetadata",
				":geometry:compileKotlinIosArm64",
				":geometry:compileTestKotlinIosArm64",
				":geometry:jvmTest",
				":geometry:ktlintCheck",
				":geometry:linuxX64Test",
			),
			plan.taskPaths,
		)
	}

	@Test
	fun aJvmOnlySourceSetInANativeModulePaysForNoNonJvmGate() {
		val plan = planVerification(listOf("module/runtime/src/jvmMain/kotlin/org/umamo/runtime/Clock.kt"), modules)

		assertFalse(plan.taskPaths.any { taskPath -> taskPath.contains("Ios") || taskPath.contains("Metadata") })
		assertTrue(":runtime:jvmTest" in plan.taskPaths)
		// The dependents of a changed dependent are reached too.
		assertTrue(":desktop:compileTestKotlinJvm" in plan.taskPaths)
		assertTrue(":edit:compileTestKotlinJvm" in plan.taskPaths)
	}

	@Test
	fun anAndroidSourceSetRunsTheAndroidCompile() {
		assertTrue(":ui:compileAndroidMain" in planVerification(listOf("module/ui/src/jvmAndroidMain/kotlin/org/umamo/ui/Clipboard.kt"), modules).taskPaths)
		assertTrue(":ui:compileAndroidMain" in planVerification(listOf("module/ui/src/androidMain/kotlin/org/umamo/ui/Pen.kt"), modules).taskPaths)
		assertFalse(":ui:compileAndroidMain" in planVerification(listOf("module/ui/src/jvmMain/kotlin/org/umamo/ui/Window.kt"), modules).taskPaths)
	}

	@Test
	fun aModulesBuildScriptCountsAsTheWholeModule() {
		val plan = planVerification(listOf("module/runtime/build.gradle.kts"), modules)

		assertTrue(plan.taskPaths.containsAll(listOf(":runtime:jvmTest", ":runtime:compileCommonMainKotlinMetadata", ":runtime:compileKotlinIosArm64", ":runtime:compileAndroidMain", ":runtime:ktlintCheck")))
		assertTrue(":ui:compileTestKotlinJvm" in plan.taskPaths)
		assertEquals("  :runtime (build): jvmTest, compileCommonMainKotlinMetadata, compileKotlinIosArm64, compileTestKotlinIosArm64, compileAndroidMain", plan.report[1])
	}

	@Test
	fun anApplicationWithoutTestsIsCompiled() {
		val plan = planVerification(listOf("app/android/src/main/kotlin/org/umamo/editor/android/MainActivity.kt"), modules)

		assertEquals(listOf(":android:compileDebugKotlin", ":android:ktlintCheck", ":checkCommonSourcePurity"), plan.taskPaths)
	}

	@Test
	fun aRuleSetChangeLintsEverythingAndRunsTheRuleSetsTests() {
		val plan = planVerification(listOf("gradle/ktlint-rules/src/main/kotlin/org/umamo/ktlint/StatementIfBracesRule.kt"), modules)

		assertEquals(listOf(":android:ktlintCheck", ":desktop:ktlintCheck", ":edit:ktlintCheck", ":geometry:ktlintCheck", ":ktlintCheck", ":runtime:ktlintCheck", ":ui:ktlintCheck"), plan.taskPaths)
		assertTrue(plan.ruleSetTests)
		assertFalse(plan.buildLogicTests)
	}

	@Test
	fun aBuildLogicChangeVerifiesEveryModule() {
		val plan = planVerification(listOf("gradle/build-logic/src/main/kotlin/umamo.test-corpus.gradle.kts"), modules)

		assertTrue(plan.taskPaths.containsAll(listOf(":geometry:jvmTest", ":runtime:jvmTest", ":edit:jvmTest", ":ui:jvmTest", ":desktop:jvmTest", ":android:compileDebugKotlin", ":geometry:linuxX64Test", ":checkCommonSourcePurity")))
		// The rule set's tests are what lint the build logic, so both sets of tests run.
		assertTrue(plan.ruleSetTests)
		assertTrue(plan.buildLogicTests)
		assertEquals("  the build itself changed, so every module is verified", plan.report[1])
	}

	@Test
	fun aRootScriptChangeLintsTheRootAndVerifiesEveryModule() {
		val plan = planVerification(listOf("settings.gradle.kts"), modules)

		assertTrue(":ktlintCheck" in plan.taskPaths)
		assertTrue(":ui:jvmTest" in plan.taskPaths)
		assertFalse(plan.buildLogicTests)
	}

	@Test
	fun aFileNoTaskCoversIsNamed() {
		val plan = planVerification(listOf("docs/format/UMA.md", "module/edit/src/jvmTest/kotlin/org/umamo/edit/SessionTest.kt"), modules)

		assertEquals("  no task covers: docs/format/UMA.md", plan.report.last())
		assertEquals(emptyList(), planVerification(listOf("README.md"), modules).taskPaths)
	}
}