package org.umamo.ui.sidebar

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.umamo.edit.EditorSession
import org.umamo.edit.MeshElement
import org.umamo.edit.OperatorParameter
import org.umamo.edit.UvCursor
import org.umamo.edit.transform.TransformParameterKeys
import org.umamo.runtime.model.AtlasPlacement
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.ElementOwner
import org.umamo.runtime.model.PuppetModel
import org.umamo.ui.LocalSettings
import org.umamo.ui.kit.field.LocalScrubCancel
import org.umamo.ui.kit.field.ScrubCancelController
import org.umamo.ui.kit.field.formatDecimals
import org.umamo.ui.model.LocalPuppetRenderSync
import org.umamo.ui.theme.UmamoTheme
import org.umamo.ui.viewport.uv.LocalUvSidebarSurface
import org.umamo.ui.viewport.uv.RecordingPuppetRenderSync
import org.umamo.ui.viewport.uv.UV_RIG_OTHER
import org.umamo.ui.viewport.uv.UV_RIG_PAGE_SIDE
import org.umamo.ui.viewport.uv.UV_RIG_QUAD
import org.umamo.ui.viewport.uv.UV_RIG_QUAD_TILE
import org.umamo.ui.viewport.uv.UvEditFrame
import org.umamo.ui.viewport.uv.UvPlacementSceneState
import org.umamo.ui.viewport.uv.UvPlacementSurface
import org.umamo.ui.viewport.uv.UvSidebarSurface
import org.umamo.ui.viewport.uv.displayToUv
import org.umamo.ui.viewport.uv.uvEditSession
import org.umamo.ui.viewport.uv.uvObjectSession
import org.umamo.ui.viewport.uv.uvRigGeometries
import org.umamo.ui.viewport.uv.uvRigLayerFrame
import org.umamo.ui.viewport.uv.uvRigPageFrame
import org.umamo.ui.viewport.uv.uvRigPlacedModel
import org.umamo.ui.viewport.uv.uvRigPlacementOf
import org.umamo.ui.viewport.uv.uvRigPlacementSurface
import org.umamo.ui.viewport.uv.uvRigUvsOf
import org.umamo.ui.workspace.SpaceKind
import org.umamo.ui.workspace.commands.inMemorySettings
import org.umamo.ui.workspace.sidebar.SidebarState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The UV editor area the sidebar belongs to. */
private const val UV_AREA = "uv-area"

/**
 * The UV editor's sidebar panels over the UV rig: the View tab's UV cursor in y-down texels, the Item tab's UV
 * Vertex / Median in Edit mode, and its tile placement in Object mode.  The rig's quad has display corners
 * (100, 100), (120, 100), (120, 120), (100, 120) on a 256-texel page, so its vertex 0 reads (100, 156) in y-down
 * texels, and its tile is placed at (100, 136) on that page.
 *
 * The surface is rebuilt from the render sync's preview each composition, as the UV editor rebuilds it, so a
 * panel's own scrub reaches its readout the way it does in the editor.
 */
@OptIn(ExperimentalTestApi::class)
class UvSidebarPanelsTest {
	/**
	 * Mounts an open UV editor sidebar on [tab] over [session], its surface built from the session's model (or the
	 * render sync's preview) through [frame], showing [shown].
	 *
	 * @param EditorSession session The session.
	 * @param String tab The tab to open on.
	 * @param UvEditFrame frame The shown surface's frame.
	 * @param List<DrawableId> shown The drawables over the surface.
	 * @param UvPlacementSurface? placementSurface The page a placement moves on, or null where none can.
	 * @param RecordingPuppetRenderSync? renderSync The render sync a scrub previews through, or null for none.
	 * @param ScrubCancelController scrubCancel The shell's scrub seam.
	 */
	private fun ComposeUiTest.mountUvSidebar(
		session: EditorSession,
		tab: String,
		frame: UvEditFrame = uvRigPageFrame(),
		shown: List<DrawableId> = listOf(UV_RIG_QUAD),
		placementSurface: UvPlacementSurface? = null,
		renderSync: RecordingPuppetRenderSync? = null,
		scrubCancel: ScrubCancelController = ScrubCancelController(),
	) {
		val registry = builtInSidebarRegistry()
		val settings = inMemorySettings()
		val sidebarState = SidebarState().also { state -> state.activeTabId = tab }
		val radiusState = mutableStateOf<Float?>(null)
		val placementScene = UvPlacementSceneState()
		setContent {
			UmamoTheme {
				ProvideSidebarLocals(
					session,
					registry,
					LocalDensity provides Density(1f),
					LocalSettings provides settings,
					LocalPuppetRenderSync provides renderSync,
					LocalScrubCancel provides scrubCancel,
				) {
					val committed = session.model.collectAsState().value
					val model: PuppetModel = renderSync?.preview?.value ?: committed
					val geometries = remember(model) { uvRigGeometries(model, frame, shown) }
					val surface = remember(geometries) { UvSidebarSurface(frame, geometries, radiusState, placementSurface, placementScene) }
					CompositionLocalProvider(LocalUvSidebarSurface provides surface) {
						SidebarDrawerInArea(UV_AREA, sidebarState, width = 420.dp, height = 500.dp, space = SpaceKind.UvEditor)
					}
				}
			}
		}
		waitForIdle()
	}

	/**
	 * Types [typed] into the [index]th field showing [shown].
	 *
	 * @param String shown The text the field shows.
	 * @param Int index Which of the fields showing it.
	 * @param String typed What to type.
	 */
	private fun ComposeUiTest.typeInto(shown: String, index: Int, typed: String) {
		onAllNodesWithText(shown)[index].performMouseInput {
			advanceEventTime(GESTURE_GAP_MILLIS)
			click()
		}
		waitForIdle()
		onNode(hasSetTextAction() and isFocused()).performTextReplacement(typed)
		onNode(hasSetTextAction() and isFocused()).performKeyInput { pressKey(Key.Enter) }
		waitForIdle()
	}

	/**
	 * Presses on the [index]th field showing [shown] and drags it right, leaving the button held.
	 *
	 * @param String shown The text the field shows.
	 * @param Int index Which of the fields showing it.
	 */
	private fun ComposeUiTest.pressAndScrub(shown: String, index: Int = 0) {
		onAllNodesWithText(shown)[index].performMouseInput {
			advanceEventTime(GESTURE_GAP_MILLIS)
			moveTo(center)
			press()

			for (stepIndex in 1..SCRUB_STEPS) {
				advanceEventTime(GESTURE_STEP_MILLIS)
				moveTo(center + Offset(SCRUB_PIXELS * stepIndex / SCRUB_STEPS, 0f))
			}
		}
		waitForIdle()
	}

	/**
	 * Asserts that clicking the [index]th field showing [shown] opens no text entry: the field is read-only.
	 *
	 * @param String shown The text the field shows.
	 * @param Int index Which of the fields showing it.
	 */
	private fun ComposeUiTest.assertReadOnly(shown: String, index: Int = 0) {
		onAllNodesWithText(shown)[index].performMouseInput {
			advanceEventTime(GESTURE_GAP_MILLIS)
			click()
		}
		waitForIdle()
		onAllNodes(hasSetTextAction() and isFocused()).assertCountEquals(0)
	}

	/** An unplaced cursor reads the stored origin: the page's top-left. */
	@Test
	fun anUnplacedCursorReadsThePageOrigin() =
		runComposeUiTest {
			mountUvSidebar(uvEditSession(), VIEW_SIDEBAR_TAB_ID)
			onAllNodesWithText("0.0").assertCountEquals(2)
		}

	/** Over a source layer an unplaced cursor reads where that layer's mapping puts the stored origin. */
	@Test
	fun overALayerTheUnplacedCursorReadsThroughTheLayer() =
		runComposeUiTest {
			val frame = uvRigLayerFrame()
			mountUvSidebar(uvEditSession(), VIEW_SIDEBAR_TAB_ID, frame = frame)
			val (texelX, texelY) = frame.texelAt(0f, 0f)
			assertEquals(-96f, texelX)
			assertEquals(-96f, texelY)
			onAllNodesWithText(formatDecimals(texelX, 1)).assertCountEquals(2)
		}

	/** A typed X and Y place the cursor on the page, each keeping the other coordinate, and neither is an undo step. */
	@Test
	fun typedValuesPlaceTheCursor() =
		runComposeUiTest {
			val session = uvEditSession()
			val stepsBefore = session.historyView.value.steps.size
			mountUvSidebar(session, VIEW_SIDEBAR_TAB_ID)

			typeInto("0.0", index = 0, typed = "64")
			assertEquals(UvCursor(0.25f, 0f), session.uvCursor.value)
			typeInto("0.0", index = 0, typed = "192")

			assertEquals(UvCursor(0.25f, 0.75f), session.uvCursor.value)
			assertEquals(stepsBefore, session.historyView.value.steps.size)
		}

	/** A cancelled scrub takes the cursor back off the surface, as it was. */
	@Test
	fun aCancelledScrubLeavesTheCursorUnplaced() =
		runComposeUiTest {
			val session = uvEditSession()
			val scrubCancel = ScrubCancelController()
			mountUvSidebar(session, VIEW_SIDEBAR_TAB_ID, scrubCancel = scrubCancel)

			pressAndScrub("0.0")
			assertNotNull(session.uvCursor.value, "precondition: the scrub placed the cursor")
			val parked = assertNotNull(scrubCancel.cancel)
			runOnIdle { parked() }
			waitForIdle()

			assertNull(session.uvCursor.value)
		}

	/** One covered vertex reads as the vertex, several as their median, and none as nothing selected. */
	@Test
	fun theLabelNamesTheVertexOrTheMedian() =
		runComposeUiTest {
			val session = uvEditSession(elements = listOf(MeshElement.Vertex(1)))
			mountUvSidebar(session, ITEM_SIDEBAR_TAB_ID)
			onNodeWithText("Vertex:").assertExists()
			onNodeWithText("120.0").assertExists()

			runOnIdle {
				session.setMeshSelection(
					session.meshSelection.value.copy(
						elementsByOwner = mapOf(ElementOwner.Drawable(UV_RIG_QUAD) to setOf(MeshElement.Vertex(0), MeshElement.Vertex(1))),
						activeElement = null,
					),
				)
			}
			waitForIdle()
			onNodeWithText("Median:").assertExists()
			onNodeWithText("110.0").assertExists()
			onNodeWithText("156.0").assertExists()

			runOnIdle { session.setMeshSelection(session.meshSelection.value.copy(elementsByOwner = emptyMap(), activeElement = null)) }
			waitForIdle()
			onNodeWithText("Nothing selected.").assertExists()
		}

	/** A typed Y moves the covered coordinates, read down from the top row, and registers the strip in the area. */
	@Test
	fun aTypedYMovesTheCoveredCoordinates() =
		runComposeUiTest {
			val session = uvEditSession(elements = listOf(MeshElement.Vertex(0), MeshElement.Vertex(1)))
			mountUvSidebar(session, ITEM_SIDEBAR_TAB_ID)

			typeInto("156.0", index = 0, typed = "136")

			val expected = displayToUv(floatArrayOf(100f, 120f, 120f, 120f, 120f, 120f, 100f, 120f), UV_RIG_PAGE_SIDE, UV_RIG_PAGE_SIDE).toList()
			assertEquals(expected, uvRigUvsOf(session, UV_RIG_QUAD))
			assertEquals(UV_AREA, assertNotNull(session.adjustableOperation.value).areaId)
		}

	/**
	 * A scrub previews each frame through the render sync, which rebuilds the surface the readout comes from, and
	 * its release still lands the whole move from where the coordinates started.
	 */
	@Test
	fun aScrubReleaseLandsTheWholeMove() =
		runComposeUiTest {
			val session = uvEditSession(elements = listOf(MeshElement.Vertex(0), MeshElement.Vertex(1)))
			val renderSync = RecordingPuppetRenderSync()
			mountUvSidebar(session, ITEM_SIDEBAR_TAB_ID, renderSync = renderSync)
			val before = session.model.value
			val stepsBefore = session.historyView.value.steps.size

			pressAndScrub("110.0")
			assertTrue(renderSync.previewed.isNotEmpty(), "precondition: the scrub previewed")
			assertSame(before, session.model.value, "a preview commits nothing")
			// The field shows the scrub's draft now, so the release goes through the root, where the pointer is.
			onRoot().performMouseInput {
				advanceEventTime(GESTURE_STEP_MILLIS)
				release()
			}
			waitForIdle()

			assertNotEquals(uvRigUvsOf(session, UV_RIG_QUAD), before.drawables.first { drawable -> drawable.id == UV_RIG_QUAD }.mesh!!.uvs.toList())
			assertEquals(stepsBefore + 1, session.historyView.value.steps.size, "the release is one step")
			val moveX = assertNotNull(session.adjustableOperation.value).parameters.first { parameter -> parameter.key == TransformParameterKeys.MOVE_X }
			assertTrue((moveX as OperatorParameter.FloatParameter).value > 0f, "the strip's Move X is the whole drag, measured from the start")
		}

	/** A scrub cancelled through the shell's seam hands the renderer back and commits nothing. */
	@Test
	fun aCancelledScrubResyncsAndCommitsNothing() =
		runComposeUiTest {
			val session = uvEditSession(elements = listOf(MeshElement.Vertex(0), MeshElement.Vertex(1)))
			val renderSync = RecordingPuppetRenderSync()
			val scrubCancel = ScrubCancelController()
			mountUvSidebar(session, ITEM_SIDEBAR_TAB_ID, renderSync = renderSync, scrubCancel = scrubCancel)
			val before = session.model.value

			pressAndScrub("110.0")
			val parked = assertNotNull(scrubCancel.cancel)
			runOnIdle { parked() }
			waitForIdle()

			assertEquals(1, renderSync.resyncs)
			assertSame(before, session.model.value)
		}

	/** In Object mode the Item tab shows the shown drawable's placement on its page. */
	@Test
	fun objectModeShowsThePlacement() =
		runComposeUiTest {
			mountUvSidebar(
				uvObjectSession(model = uvRigPlacedModel()),
				ITEM_SIDEBAR_TAB_ID,
				shown = listOf(UV_RIG_QUAD, UV_RIG_OTHER),
				placementSurface = uvRigPlacementSurface(),
			)
			onNodeWithText("100.0").assertExists()
			onNodeWithText("136.0").assertExists()
			onAllNodesWithText("1.00").assertCountEquals(2)
		}

	/** A typed Position X moves the tile on its page as one undo step, and a typed scale of 0 is refused. */
	@Test
	fun aTypedPlacementLandsAndAZeroScaleIsRefused() =
		runComposeUiTest {
			val session = uvObjectSession(model = uvRigPlacedModel())
			val stepsBefore = session.historyView.value.steps.size
			mountUvSidebar(session, ITEM_SIDEBAR_TAB_ID, shown = listOf(UV_RIG_QUAD, UV_RIG_OTHER), placementSurface = uvRigPlacementSurface())

			typeInto("100.0", index = 0, typed = "110")
			assertEquals(110f, assertNotNull(uvRigPlacementOf(session, UV_RIG_QUAD_TILE)).positionX)
			assertEquals(stepsBefore + 1, session.historyView.value.steps.size)

			typeInto("1.00", index = 0, typed = "0")
			assertEquals(1f, assertNotNull(uvRigPlacementOf(session, UV_RIG_QUAD_TILE)).scaleX)
			assertEquals(stepsBefore + 1, session.historyView.value.steps.size)
		}

	/** With the lock on, a typed Scale X scales Scale Y by the same factor as one undo step, and a zero is still refused. */
	@Test
	fun aLockedScaleKeepsItsProportions() =
		runComposeUiTest {
			val session = uvObjectSession(model = uvRigPlacedModel())
			val stepsBefore = session.historyView.value.steps.size
			mountUvSidebar(session, ITEM_SIDEBAR_TAB_ID, shown = listOf(UV_RIG_QUAD, UV_RIG_OTHER), placementSurface = uvRigPlacementSurface())
			onNodeWithContentDescription(LOCK_ASPECT).performClick()
			waitForIdle()

			typeInto("1.00", index = 0, typed = "0")
			assertEquals(stepsBefore, session.historyView.value.steps.size, "a locked zero collapses both axes, so it is refused")

			typeInto("1.00", index = 0, typed = "2")
			val scaled = assertNotNull(uvRigPlacementOf(session, UV_RIG_QUAD_TILE))
			assertEquals(2f, scaled.scaleX)
			assertEquals(2f, scaled.scaleY, "the locked partner scales with it")
			assertEquals(stepsBefore + 1, session.historyView.value.steps.size, "both axes land as one undo step")
			onAllNodesWithText("2.00").assertCountEquals(2)
		}

	/** A pinned tile shows its placement read-only. */
	@Test
	fun aPinnedTileIsReadOnly() =
		runComposeUiTest {
			val placed = uvRigPlacedModel()
			val pinned = placed.copy(atlas = placed.atlas.copy(tiles = placed.atlas.tiles.map { tile -> tile.copy(pinned = tile.id == UV_RIG_QUAD_TILE) }))
			mountUvSidebar(uvObjectSession(model = pinned), ITEM_SIDEBAR_TAB_ID, shown = listOf(UV_RIG_QUAD, UV_RIG_OTHER), placementSurface = uvRigPlacementSurface())
			assertReadOnly("100.0")
		}

	/** A document whose coordinates address the source layers shows the placement read-only. */
	@Test
	fun aLayerAddressedDocumentIsReadOnly() =
		runComposeUiTest {
			val placed = uvRigPlacedModel()
			val layerAddressed = placed.copy(atlas = placed.atlas.copy(storedUvsAddressPages = false))
			mountUvSidebar(uvObjectSession(model = layerAddressed), ITEM_SIDEBAR_TAB_ID, shown = listOf(UV_RIG_QUAD, UV_RIG_OTHER), placementSurface = uvRigPlacementSurface())
			assertReadOnly("100.0")
		}

	/** With no source art to recompose the page from, the placement is read-only. */
	@Test
	fun noSourceArtIsReadOnly() =
		runComposeUiTest {
			mountUvSidebar(uvObjectSession(model = uvRigPlacedModel()), ITEM_SIDEBAR_TAB_ID, shown = listOf(UV_RIG_QUAD, UV_RIG_OTHER), placementSurface = null)
			assertReadOnly("100.0")
		}

	/** A tile that is not on the shown page shows its placement read-only. */
	@Test
	fun aTileOffTheShownPageIsReadOnly() =
		runComposeUiTest {
			mountUvSidebar(uvObjectSession(model = uvRigPlacedModel()), ITEM_SIDEBAR_TAB_ID, shown = listOf(UV_RIG_OTHER), placementSurface = uvRigPlacementSurface())
			assertReadOnly("100.0")
		}

	private companion object {
		const val GESTURE_GAP_MILLIS = 1000L
		const val LOCK_ASPECT = "Lock Aspect Ratio"
		const val GESTURE_STEP_MILLIS = 16L
		const val SCRUB_PIXELS = 90f
		const val SCRUB_STEPS = 6
	}
}