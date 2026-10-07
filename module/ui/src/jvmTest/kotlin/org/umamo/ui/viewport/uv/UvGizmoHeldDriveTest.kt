package org.umamo.ui.viewport.uv

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.v2.runComposeUiTest
import org.umamo.edit.MeshElement
import org.umamo.edit.MeshOperatorKind
import org.umamo.edit.transform.beginUvOperator
import org.umamo.ui.viewport.gizmo.LEFT_AREA
import org.umamo.ui.viewport.gizmo.RIGHT_AREA
import org.umamo.ui.viewport.gizmo.clickIn
import org.umamo.ui.viewport.gizmo.moveIn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Pins the UV overlays' modal drive through the overlays themselves while the drive dispatcher is held: a
 * confirm commits where the pointer is whether or not the drive caught up, and a drive
 * still in flight when the gesture is cancelled or its overlay leaves publishes nothing over the teardown.
 */
@OptIn(ExperimentalTestApi::class)
class UvGizmoHeldDriveTest {
	/** Sixty pixels right of [UV_RIG_GESTURE_START]: fifteen page texels at the rig's zoom. */
	private val fifteenTexelsRight = Offset(260f, 150f)

	/**
	 * Latches a UV Edit Grab in the left area with the pointer at [UV_RIG_GESTURE_START].
	 *
	 * @param UvGizmoOverlayFixture fixture The mounted fixture.
	 */
	private fun ComposeUiTest.latchEditGrab(fixture: UvGizmoOverlayFixture) {
		moveIn(LEFT_AREA, listOf(UV_RIG_GESTURE_START))
		fixture.session.beginUvOperator(MeshOperatorKind.Grab, LEFT_AREA)
		waitForIdle()
	}

	/**
	 * Mounts the overlays over the placed model in Object mode and latches a placement Grab in the left area,
	 * waiting for its capture to land.
	 *
	 * @return UvGizmoOverlayFixture The fixture.
	 */
	private fun ComposeUiTest.mountPlacementGrab(): UvGizmoOverlayFixture {
		val fixture = mountUvGizmoOverlays(uvObjectSession(listOf(UV_RIG_QUAD), uvRigPlacedModel()), uvRigPlacementSurface())
		moveIn(LEFT_AREA, listOf(UV_RIG_GESTURE_START))
		fixture.session.beginUvOperator(MeshOperatorKind.Grab, LEFT_AREA)
		waitForIdle()
		awaitPlacementCapture(fixture, LEFT_AREA, UV_RIG_GESTURE_START)
		return fixture
	}

	/** A right-click while a UV Edit drive is held cancels, and the held drive previews nothing after the resync. */
	@Test
	fun aCancelWhileHeldPreviewsNothingAfterTheResync() =
		runComposeUiTest {
			val fixture = mountUvGizmoOverlays(uvEditSession(elements = listOf(MeshElement.Vertex(0))))
			latchEditGrab(fixture)
			fixture.driveDispatcher.hold()
			moveIn(LEFT_AREA, listOf(UV_RIG_TEN_TEXELS_RIGHT))
			assertEquals(1, fixture.driveDispatcher.queuedCount, "the drive is in flight on the drive dispatcher")

			clickIn(LEFT_AREA, UV_RIG_TEN_TEXELS_RIGHT, MouseButton.Secondary)
			assertNull(fixture.session.activeUvOperator.value)
			assertEquals(1, fixture.renderSync.resyncs, "the teardown resynced the renderer")
			val previewsAfterResync = fixture.renderSync.previewed.size
			fixture.driveDispatcher.release()
			waitForIdle()

			assertEquals(previewsAfterResync, fixture.renderSync.previewed.size, "the held drive previewed nothing after the resync")
			assertNull(fixture.renderSync.preview.value, "the renderer stays on the committed model")
			assertEquals(UV_RIG_QUAD_UVS, uvRigUvsOf(fixture.session, UV_RIG_QUAD), "nothing committed")
		}

	/** A click while a UV Edit drive is held commits where the click landed. */
	@Test
	fun aClickWhileHeldCommitsTheClickedPointer() =
		runComposeUiTest {
			val fixture = mountUvGizmoOverlays(uvEditSession(elements = listOf(MeshElement.Vertex(0))))
			latchEditGrab(fixture)
			moveIn(LEFT_AREA, listOf(UV_RIG_TEN_TEXELS_RIGHT))
			fixture.driveDispatcher.hold()

			clickIn(LEFT_AREA, fifteenTexelsRight)

			assertNull(fixture.session.activeUvOperator.value, "the click confirmed")
			assertEquals(listOf(115f / 256) + UV_RIG_QUAD_UVS.drop(1), uvRigUvsOf(fixture.session, UV_RIG_QUAD), "at the clicked pointer")
			val previewsAfterConfirm = fixture.renderSync.previewed.size
			fixture.driveDispatcher.release()
			waitForIdle()
			assertEquals(previewsAfterConfirm, fixture.renderSync.previewed.size, "the held drive previewed nothing after the confirm")
		}

	/** A UV Edit overlay leaving mid-gesture while a drive is held resyncs, and the held drive previews nothing after. */
	@Test
	fun anUnmountWhileHeldPreviewsNothingAfterTheResync() =
		runComposeUiTest {
			val fixture = mountUvGizmoOverlays(uvEditSession(elements = listOf(MeshElement.Vertex(0))))
			latchEditGrab(fixture)
			fixture.driveDispatcher.hold()
			moveIn(LEFT_AREA, listOf(UV_RIG_TEN_TEXELS_RIGHT))

			fixture.mountedAreas.value = setOf(RIGHT_AREA)
			waitForIdle()
			assertNull(fixture.session.activeUvOperator.value, "the abandon cleared the latch")
			assertEquals(1, fixture.renderSync.resyncs, "the abandon resynced the renderer")
			val previewsAfterResync = fixture.renderSync.previewed.size
			fixture.driveDispatcher.release()
			waitForIdle()

			assertEquals(previewsAfterResync, fixture.renderSync.previewed.size, "the held drive previewed nothing after the resync")
			assertNull(fixture.renderSync.preview.value)
		}

	/** A right-click while a placement drive is held cancels, and the held drive leaves the readout down. */
	@Test
	fun aPlacementCancelWhileHeldLeavesTheReadoutDown() =
		runComposeUiTest {
			val fixture = mountPlacementGrab()
			fixture.driveDispatcher.hold()
			moveIn(LEFT_AREA, listOf(UV_RIG_TEN_TEXELS_RIGHT))
			assertEquals(1, fixture.driveDispatcher.queuedCount, "the drive is in flight on the drive dispatcher")

			clickIn(LEFT_AREA, UV_RIG_TEN_TEXELS_RIGHT, MouseButton.Secondary)
			fixture.driveDispatcher.release()
			waitForIdle()

			assertNull(fixture.session.activeUvOperator.value)
			assertNull(fixture.placementDragStatusByArea.getValue(LEFT_AREA).value, "the held drive left the readout down")
			assertEquals(uvRigTilePlacement(100f), uvRigPlacementOf(fixture.session, UV_RIG_QUAD_TILE), "nothing committed")
		}

	/** A click while a placement drive is held moves the tile to where the click landed. */
	@Test
	fun aPlacementClickWhileHeldCommitsTheClickedPointer() =
		runComposeUiTest {
			val fixture = mountPlacementGrab()
			moveIn(LEFT_AREA, listOf(UV_RIG_TEN_TEXELS_RIGHT))
			assertEquals(10, assertNotNull(fixture.placementDragStatusByArea.getValue(LEFT_AREA).value).deltaX)
			fixture.driveDispatcher.hold()

			clickIn(LEFT_AREA, fifteenTexelsRight)

			assertNull(fixture.session.activeUvOperator.value, "the click confirmed")
			assertEquals(uvRigTilePlacement(115f), uvRigPlacementOf(fixture.session, UV_RIG_QUAD_TILE), "at the clicked pointer")
			fixture.driveDispatcher.release()
			waitForIdle()
			assertNull(fixture.placementDragStatusByArea.getValue(LEFT_AREA).value, "and the readout stays down")
		}

	/** The placement capture builds on the drive dispatcher, so holding it holds the build until it is let go. */
	@Test
	fun aHeldDispatcherHoldsThePlacementCapture() =
		runComposeUiTest {
			val fixture = mountUvGizmoOverlays(uvObjectSession(listOf(UV_RIG_QUAD), uvRigPlacedModel()), uvRigPlacementSurface())
			moveIn(LEFT_AREA, listOf(UV_RIG_GESTURE_START))
			fixture.driveDispatcher.hold()

			fixture.session.beginUvOperator(MeshOperatorKind.Grab, LEFT_AREA)
			waitForIdle()
			assertEquals(1, fixture.driveDispatcher.queuedCount, "the capture build waits on the drive dispatcher")

			fixture.driveDispatcher.release()
			waitForIdle()
			moveIn(LEFT_AREA, listOf(UV_RIG_TEN_TEXELS_RIGHT))
			assertEquals(10, assertNotNull(fixture.placementDragStatusByArea.getValue(LEFT_AREA).value).deltaX, "the released capture drives")
		}
}