package org.umamo.ui.viewport.viewport2d

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import org.umamo.ui.viewport.OverlaySurface
import org.umamo.ui.viewport.ViewportOverlayState
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The publisher tells the demand register whether its area asks for the wireframe: the row under the master,
 * re-sent on a flip, never for a UV editor or a missing state, and withdrawn as the area leaves.
 */
@OptIn(ExperimentalTestApi::class)
class WireframeDemandPublisherTest {
	@Test
	fun theAreasRowAndMasterDriveItsAsk() =
		runComposeUiTest {
			val demand = WireframeDemand()
			val state = ViewportOverlayState(OverlaySurface.Viewport2D)
			setContent { WireframeDemandPublisher(demand, "area", state) }
			waitForIdle()
			assertFalse(demand.wanted.value, "the row is off by default")

			state.showWireframe = true
			waitForIdle()
			assertTrue(demand.wanted.value, "the row on asks")

			state.showOverlays = false
			waitForIdle()
			assertFalse(demand.wanted.value, "the master off withdraws the ask")
		}

	@Test
	fun aUvAreaAndAMissingStateNeverAsk() =
		runComposeUiTest {
			val demand = WireframeDemand()
			val uvState = ViewportOverlayState(OverlaySurface.UvEditor).apply { showWireframe = true }
			setContent {
				WireframeDemandPublisher(demand, "uv", uvState)
				WireframeDemandPublisher(demand, "bare", null)
			}
			waitForIdle()

			assertFalse(demand.wanted.value)
		}

	@Test
	fun leavingTheCompositionWithdrawsTheAsk() =
		runComposeUiTest {
			val demand = WireframeDemand()
			val state = ViewportOverlayState(OverlaySurface.Viewport2D).apply { showWireframe = true }
			var mounted by mutableStateOf(true)
			setContent {
				if (mounted) {
					WireframeDemandPublisher(demand, "area", state)
				}
			}
			waitForIdle()
			assertTrue(demand.wanted.value)

			mounted = false
			waitForIdle()

			assertFalse(demand.wanted.value, "an area that left no longer asks")
		}
}