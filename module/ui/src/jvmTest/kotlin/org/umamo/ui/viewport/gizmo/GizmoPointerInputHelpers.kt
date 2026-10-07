package org.umamo.ui.viewport.gizmo

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.ScrollWheel
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import org.umamo.ui.workspace.spaces.parameters.GESTURE_GAP_MILLIS
import org.umamo.ui.workspace.spaces.parameters.GESTURE_STEP_MILLIS

/*
 * The pointer and key input the gizmo overlay fixtures send, shared by the 2D viewport's fixture and the
 * UV editor's.  Each fixture mounts its overlays twice, in two areas side by side (LEFT_AREA and RIGHT_AREA,
 * gizmo/GizmoTestAreas.kt), and tags each area's box with gizmoAreaTag, so these helpers address an area by
 * its id.  Every point is area-local.
 */

/**
 * The test tag of one area's box.
 *
 * @param String areaId The area.
 * @return String The tag.
 */
internal fun gizmoAreaTag(areaId: String): String = "gizmo-area-$areaId"

/**
 * Moves the pointer through [points] in one area, with whatever buttons a [pressIn] holds.
 *
 * @param String areaId The area.
 * @param List<Offset> points The area-local points, in order.
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.moveIn(areaId: String, points: List<Offset>) {
	onNodeWithTag(gizmoAreaTag(areaId)).performMouseInput {
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
	onNodeWithTag(gizmoAreaTag(areaId)).performMouseInput {
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
	onNodeWithTag(gizmoAreaTag(areaId)).performMouseInput {
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
	onNodeWithTag(gizmoAreaTag(areaId)).performMouseInput {
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
	onNodeWithTag(gizmoAreaTag(areaId)).performMouseInput {
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
	onNodeWithTag(gizmoAreaTag(areaId)).performMouseInput {
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
	onNodeWithTag(gizmoAreaTag(areaId)).performKeyInput { keyDown(key) }
	action()
	onNodeWithTag(gizmoAreaTag(areaId)).performKeyInput { keyUp(key) }
	waitForIdle()
}