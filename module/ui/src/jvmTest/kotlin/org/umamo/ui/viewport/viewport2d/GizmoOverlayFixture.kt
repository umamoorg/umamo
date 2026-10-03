package org.umamo.ui.viewport.viewport2d

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.ScrollWheel
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.umamo.edit.EditorSession
import org.umamo.render.pick.PickCandidate
import org.umamo.settings.Settings
import org.umamo.ui.LocalSettings
import org.umamo.ui.theme.UmamoTheme
import org.umamo.ui.viewport.StubPuppetViewportService
import org.umamo.ui.viewport.ViewportRegionOverlay
import org.umamo.ui.workspace.commands.inMemorySettings
import org.umamo.ui.workspace.spaces.parameters.GESTURE_GAP_MILLIS
import org.umamo.ui.workspace.spaces.parameters.GESTURE_STEP_MILLIS

/*
 * The viewport gizmo overlays, with the Zoom Region overlay above them, mounted the way
 * PuppetViewportBinding mounts them, twice: two areas side by side over ONE session and ONE render
 * service, so a case can check that a gesture belongs to the area it started in.  The render service is
 * the stub, which records what the overlays push and never renders, and the camera is the rig's.  Density
 * is one, so a dp is a pixel and the rig's screen coordinates are the pointer coordinates the helpers
 * below take.
 *
 * Pointer moves stay well inside an area and never cross into the other: a modal overlay drives on Exit
 * as well as Move, and a move within WRAP_MARGIN_PX of an edge would warp the real cursor.
 */

/** The left area's id. */
internal const val LEFT_AREA = "left"

/** The right area's id. */
internal const val RIGHT_AREA = "right"

/** What the fixture holds for a case to inspect. */
internal class GizmoOverlayFixture(
	/** The session both areas run over. */
	val session: EditorSession,
) {
	/** The render service both areas share. */
	val service = StubPuppetViewportService()

	/** The settings the gizmo colors read. */
	val settings: Settings = inMemorySettings()

	/** Every overlap picker an overlay asked for, as the area, the anchor, and the candidates. */
	val overlapRequests = ArrayList<Triple<String, Offset, List<PickCandidate>>>()

	/**
	 * The areas whose overlays are mounted.  Taking an area out unmounts its overlays mid-gesture the way
	 * a mode switch or a closing area does, while the area's box, its tag, and the host's pointer record
	 * stay, so input can go on after it.
	 */
	val mountedAreas = mutableStateOf(setOf(LEFT_AREA, RIGHT_AREA))
}

/**
 * The test tag of one area's box.
 *
 * @param String areaId The area.
 * @return String The tag.
 */
private fun areaTag(areaId: String): String = "gizmo-area-$areaId"

/**
 * Mounts the Edit and Object gizmo overlays in the two areas over [session].
 *
 * @param EditorSession session The session to run the overlays over.
 * @return GizmoOverlayFixture What the case inspects.
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.mountGizmoOverlays(session: EditorSession): GizmoOverlayFixture {
	val fixture = GizmoOverlayFixture(session)
	setContent {
		CompositionLocalProvider(LocalSettings provides fixture.settings) {
			UmamoTheme {
				CompositionLocalProvider(LocalDensity provides Density(1f)) {
					Row {
						for (areaId in listOf(LEFT_AREA, RIGHT_AREA)) {
							key(areaId) {
								// The host's own pointer record, as PuppetViewportBinding keeps it: watch-only, on
								// the Initial pass, so it is current even while an overlay owns the gesture.
								val areaPointer = remember { mutableStateOf(Offset.Zero) }
								Box(
									modifier =
										Modifier
											.size(RIG_AREA_WIDTH.dp, RIG_AREA_HEIGHT.dp)
											.testTag(areaTag(areaId))
											.pointerInput(areaId) {
												awaitPointerEventScope {
													while (true) {
														val event = awaitPointerEvent(PointerEventPass.Initial)
														event.changes.lastOrNull()?.let { change -> areaPointer.value = change.position }
													}
												}
											},
								) {
									if (areaId in fixture.mountedAreas.value) {
										ViewportEditGizmoOverlay(
											areaId = areaId,
											service = fixture.service,
											session = session,
											camera = RIG_CAMERA,
											widthPx = RIG_AREA_WIDTH,
											heightPx = RIG_AREA_HEIGHT,
											areaPointer = areaPointer,
											onOverlapRequest = { anchor, candidates -> fixture.overlapRequests.add(Triple(areaId, anchor, candidates)) },
										)
										ViewportObjectGizmoOverlay(
											areaId = areaId,
											service = fixture.service,
											session = session,
											camera = RIG_CAMERA,
											widthPx = RIG_AREA_WIDTH,
											heightPx = RIG_AREA_HEIGHT,
											onOverlapRequest = { anchor, candidates -> fixture.overlapRequests.add(Triple(areaId, anchor, candidates)) },
										)
										// Above the gizmos, as the binding mounts it; inert unless Zoom Region is armed here.
										ViewportRegionOverlay(
											areaId = areaId,
											service = fixture.service,
											session = session,
											camera = RIG_CAMERA,
											widthPx = RIG_AREA_WIDTH,
											heightPx = RIG_AREA_HEIGHT,
										)
									}
								}
							}
						}
					}
				}
			}
		}
	}
	waitForIdle()
	return fixture
}

/**
 * Moves the pointer through [points] in one area, with whatever buttons a [pressIn] holds.
 *
 * @param String areaId The area.
 * @param List<Offset> points The area-local points, in order.
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.moveIn(areaId: String, points: List<Offset>) {
	onNodeWithTag(areaTag(areaId)).performMouseInput {
		for (point in points) {
			advanceEventTime(GESTURE_STEP_MILLIS)
			moveTo(point)
		}
	}
	waitForIdle()
}

/**
 * One click at a point in an area.
 *
 * @param String areaId The area.
 * @param Offset point The area-local point.
 * @param MouseButton button The button.
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.clickIn(areaId: String, point: Offset, button: MouseButton = MouseButton.Primary) {
	onNodeWithTag(areaTag(areaId)).performMouseInput {
		advanceEventTime(GESTURE_GAP_MILLIS)
		moveTo(point)
		press(button)
		advanceEventTime(GESTURE_STEP_MILLIS)
		release(button)
	}
	waitForIdle()
}

/**
 * A primary drag in an area: press at [from], move through [through], release.
 *
 * @param String areaId The area.
 * @param Offset from Where the press lands.
 * @param List<Offset> through The points the pointer moves through, in order.
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.dragIn(areaId: String, from: Offset, through: List<Offset>) {
	onNodeWithTag(areaTag(areaId)).performMouseInput {
		advanceEventTime(GESTURE_GAP_MILLIS)
		moveTo(from)
		press()
		for (point in through) {
			advanceEventTime(GESTURE_STEP_MILLIS)
			moveTo(point)
		}
		advanceEventTime(GESTURE_STEP_MILLIS)
		release()
	}
	waitForIdle()
}

/**
 * One wheel scroll where the pointer already is, with no move first, so nothing but the scroll reaches
 * the overlay.
 *
 * @param String areaId The area the pointer is in.
 * @param Float steps The scroll in wheel steps; negative is wheel-up.
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.scrollIn(areaId: String, steps: Float) {
	onNodeWithTag(areaTag(areaId)).performMouseInput {
		advanceEventTime(GESTURE_STEP_MILLIS)
		scroll(steps, ScrollWheel.Vertical)
	}
	waitForIdle()
}

/**
 * Presses a button at a point in an area and holds it.
 *
 * @param String areaId The area.
 * @param Offset point The area-local point.
 * @param MouseButton button The button.
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.pressIn(areaId: String, point: Offset, button: MouseButton = MouseButton.Primary) {
	onNodeWithTag(areaTag(areaId)).performMouseInput {
		advanceEventTime(GESTURE_GAP_MILLIS)
		moveTo(point)
		press(button)
	}
	waitForIdle()
}

/**
 * Releases a button a [pressIn] held, where the pointer is.
 *
 * @param String areaId The area the pointer is in.
 * @param MouseButton button The button.
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.releaseIn(areaId: String, button: MouseButton = MouseButton.Primary) {
	onNodeWithTag(areaTag(areaId)).performMouseInput {
		advanceEventTime(GESTURE_STEP_MILLIS)
		release(button)
	}
	waitForIdle()
}

/**
 * Runs [action] with a key held, so the pointer events it sends carry that modifier.
 *
 * @param String areaId The area the key goes to.
 * @param Key key The key to hold (for example Key.ShiftLeft).
 * @param Function action The input sent while the key is down.
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.withKeyHeld(areaId: String, key: Key, action: () -> Unit) {
	onNodeWithTag(areaTag(areaId)).performKeyInput { keyDown(key) }
	action()
	onNodeWithTag(areaTag(areaId)).performKeyInput { keyUp(key) }
	waitForIdle()
}