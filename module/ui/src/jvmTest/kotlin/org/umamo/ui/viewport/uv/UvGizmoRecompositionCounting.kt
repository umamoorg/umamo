package org.umamo.ui.viewport.uv

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import org.umamo.ui.viewport.gizmo.countingGizmoRuns
import org.umamo.ui.workspace.spaces.parameters.ComposableRunCounter

/** The UV overlays' package, whose composables the UV recomposition cases count. */
private const val UV_PACKAGE_PREFIX = "org.umamo.ui.viewport.uv."

/** The UV fixture's function, whose own body and lambdas are not counted. */
private const val UV_FIXTURE_FUNCTION = "mountUvGizmoOverlays"

/**
 * Runs [body] with the UV overlays' composable runs counted.
 *
 * @param Function body The case, handed the counter.
 */
@OptIn(ExperimentalTestApi::class)
internal fun countingUvGizmoRuns(body: ComposeUiTest.(ComposableRunCounter) -> Unit) = countingGizmoRuns(UV_PACKAGE_PREFIX, UV_FIXTURE_FUNCTION, body)