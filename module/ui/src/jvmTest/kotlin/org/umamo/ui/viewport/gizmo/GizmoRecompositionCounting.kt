package org.umamo.ui.viewport.gizmo

import androidx.compose.runtime.Composer
import androidx.compose.runtime.InternalComposeTracingApi
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import org.umamo.ui.workspace.spaces.parameters.ComposableRunCounter
import kotlin.test.assertEquals

/** The 2D viewport's package, whose composables the viewport gizmo recomposition tests count. */
internal const val VIEWPORT2D_PACKAGE_PREFIX = "org.umamo.ui.viewport.viewport2d."

/** The 2D viewport fixture's function, whose own body and lambdas are not counted. */
internal const val VIEWPORT2D_FIXTURE_FUNCTION = "mountGizmoOverlays"

/**
 * Runs [body] with one package's composable runs counted, and takes the counter off again whatever
 * happens: the tracer is one per process.
 *
 * @param String packagePrefix The package whose composables are counted, with its trailing dot.
 * @param String fixtureFunction The fixture function whose own body and lambdas are not counted.
 * @param Function body The case, handed the counter.
 */
@OptIn(ExperimentalTestApi::class, InternalComposeTracingApi::class)
internal fun countingGizmoRuns(
	packagePrefix: String = VIEWPORT2D_PACKAGE_PREFIX,
	fixtureFunction: String = VIEWPORT2D_FIXTURE_FUNCTION,
	body: ComposeUiTest.(ComposableRunCounter) -> Unit,
) {
	val counter = ComposableRunCounter(packagePrefix, fixtureFunction)
	Composer.setTracer(counter)
	try {
		runComposeUiTest { body(counter) }
	} finally {
		Composer.setTracer(null)
	}
}

/**
 * Asserts nothing of the counted package ran since the counter was last reset.
 *
 * @param ComposableRunCounter counter The counter.
 * @param String what What the case did, for the message.
 */
internal fun assertNothingRan(counter: ComposableRunCounter, what: String) {
	assertEquals(emptyMap(), counter.namedRuns(), "$what ran no composable")
	assertEquals(0, counter.lambdaRuns(), "$what ran no composable lambda")
}