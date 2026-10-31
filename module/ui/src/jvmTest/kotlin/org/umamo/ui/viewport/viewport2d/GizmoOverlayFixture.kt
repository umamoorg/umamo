package org.umamo.ui.viewport.viewport2d

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.umamo.edit.EditorSession
import org.umamo.settings.Settings
import org.umamo.ui.LocalSettings
import org.umamo.ui.theme.UmamoTheme
import org.umamo.ui.viewport.LiveMeshPreview
import org.umamo.ui.viewport.LocalLiveMeshPreview
import org.umamo.ui.viewport.ObjectPickCandidate
import org.umamo.ui.viewport.StubPuppetViewportService
import org.umamo.ui.viewport.ViewportRegionOverlay
import org.umamo.ui.viewport.asObjectPickCandidates
import org.umamo.ui.viewport.gizmo.HeldDriveDispatcher
import org.umamo.ui.viewport.gizmo.LEFT_AREA
import org.umamo.ui.viewport.gizmo.LocalModalDriveDispatcher
import org.umamo.ui.viewport.gizmo.RIGHT_AREA
import org.umamo.ui.viewport.gizmo.gizmoAreaTag
import org.umamo.ui.viewport.tracksAreaPointer
import org.umamo.ui.workspace.commands.inMemorySettings

/*
 * The viewport gizmo overlays, with the Zoom Region overlay above them, mounted the way
 * PuppetViewportBinding mounts them, twice: two areas side by side over ONE session and ONE render
 * service, so a case can check that a gesture belongs to the area it started in.  The render service is
 * the stub, which records what the overlays push and never renders, and the camera is the rig's.  Density
 * is one, so a dp is a pixel and the rig's screen coordinates are the pointer coordinates the input
 * helpers take (gizmo/GizmoPointerInputHelpers.kt, which address an area by its tag).
 *
 * Pointer moves stay well inside an area and never cross into the other: a modal overlay drives on Exit
 * as well as Move, and a move within WRAP_MARGIN_PX of an edge would warp the real cursor.
 */

/** What the fixture holds for a case to inspect. */
internal class GizmoOverlayFixture(
	/** The session both areas run over. */
	val session: EditorSession,
) {
	/** The render service both areas share. */
	val service = StubPuppetViewportService()

	/** The settings the gizmo colors read. */
	val settings: Settings = inMemorySettings()

	/** The drive dispatcher every overlay computes on: open (in place) unless a case holds it. */
	val driveDispatcher = HeldDriveDispatcher()

	/** The live mesh preview both areas' Edit transforms publish to, as the shell provides one per window. */
	val livePreview = LiveMeshPreview()

	/** Every overlap picker an overlay asked for, as the area, the anchor, and the candidates. */
	val overlapRequests = ArrayList<Triple<String, Offset, List<ObjectPickCandidate>>>()

	/**
	 * The areas whose overlays are mounted.  Taking an area out unmounts its overlays mid-gesture the way
	 * a mode switch or a closing area does, while the area's box, its tag, and the host's pointer record
	 * stay, so input can go on after it.
	 */
	val mountedAreas = mutableStateOf(setOf(LEFT_AREA, RIGHT_AREA))
}

/**
 * Mounts the Edit and Object gizmo overlays in the two areas over [session].
 *
 * @param EditorSession session The session to run the overlays over.
 * @param Function? beside A surface to mount to the right of the two areas, under the same locals, for a case
 *   that watches the overlays from outside them; null for none.
 * @return GizmoOverlayFixture What the case inspects.
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.mountGizmoOverlays(session: EditorSession, beside: (@Composable () -> Unit)? = null): GizmoOverlayFixture {
	val fixture = GizmoOverlayFixture(session)
	setContent {
		CompositionLocalProvider(
			LocalSettings provides fixture.settings,
			LocalModalDriveDispatcher provides fixture.driveDispatcher,
			LocalLiveMeshPreview provides fixture.livePreview,
		) {
			UmamoTheme {
				CompositionLocalProvider(LocalDensity provides Density(1f)) {
					Row {
						for (areaId in listOf(LEFT_AREA, RIGHT_AREA)) {
							key(areaId) {
								// The host's own pointer record, kept the way PuppetViewportBinding keeps it.
								val areaPointer = remember { mutableStateOf(Offset.Zero) }
								Box(
									modifier =
										Modifier
											.size(RIG_AREA_WIDTH.dp, RIG_AREA_HEIGHT.dp)
											.testTag(gizmoAreaTag(areaId))
											.tracksAreaPointer(areaId, areaPointer),
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
											onOverlapRequest = { anchor, candidates -> fixture.overlapRequests.add(Triple(areaId, anchor, candidates.asObjectPickCandidates())) },
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

							beside?.invoke()
						}
					}
				}
			}
		}
	}
	waitForIdle()

	return fixture
}