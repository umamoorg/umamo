package org.umamo.ui.viewport.uv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.umamo.edit.EditorSession
import org.umamo.edit.UvSnapKind
import org.umamo.edit.atlas.setAtlasPins
import org.umamo.edit.atlas.setAtlasPlacements
import org.umamo.runtime.model.AtlasTileId
import org.umamo.runtime.model.DrawableId
import org.umamo.ui.viewport.GridConfig
import kotlin.math.round
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The UV editor's Shift+S snaps in Object mode ([handleUvObjectSnapRequest]): they move and read placed art
 * tiles by their origin, the footprint center a placement drag turns them about.  Expected origins come from
 * the same footprint lookup the handler uses, so each case pins where an origin lands, not the rig's numbers.
 */
class UvObjectSnapTest {
	/** Cursor to Selected puts the UV cursor on the selected tile's origin. */
	@Test
	fun cursorToSelectedLandsOnTheTilesOrigin() =
		runTest {
			val session = placedSession()

			snap(session, UvSnapKind.CursorToSelected)

			assertPoint(originOf(session, UV_RIG_QUAD_TILE), cursorDisplayOf(session), "the cursor sits on the origin")
		}

	/** Selection to Cursor lands the tile's origin on the cursor, moving the page position by the flipped delta, as one undo step. */
	@Test
	fun selectionToCursorLandsTheOriginOnTheCursor() =
		runTest {
			val session = placedSession()
			placeCursorAt(session, CURSOR_X, CURSOR_Y)
			val before = assertNotNull(uvRigPlacementOf(session, UV_RIG_QUAD_TILE))
			val (originX, originY) = originOf(session, UV_RIG_QUAD_TILE)
			val other = uvRigPlacementOf(session, UV_RIG_OTHER_TILE)
			val stepsBefore = session.historyView.value.steps.size

			snap(session, UvSnapKind.SelectionToCursor)

			assertPoint(CURSOR_X to CURSOR_Y, originOf(session, UV_RIG_QUAD_TILE), "the origin sits on the cursor")
			val after = assertNotNull(uvRigPlacementOf(session, UV_RIG_QUAD_TILE))
			assertEquals(before.positionX + (CURSOR_X - originX), after.positionX, TOLERANCE, "display x is page x")
			assertEquals(before.positionY - (CURSOR_Y - originY), after.positionY, TOLERANCE, "display y up is page y down")
			assertEquals(other, uvRigPlacementOf(session, UV_RIG_OTHER_TILE), "an unselected tile stays put")
			assertEquals(stepsBefore + 1, session.historyView.value.steps.size, "one step")
			session.undo()
			assertEquals(before, uvRigPlacementOf(session, UV_RIG_QUAD_TILE), "which one undo takes back")
		}

	/** Cursor to Selected then Selection to Cursor moves nothing: both read the same origin. */
	@Test
	fun cursorToSelectedThenSelectionToCursorMovesNothing() =
		runTest {
			val session = placedSession()
			val before = uvRigPlacementOf(session, UV_RIG_QUAD_TILE)
			val stepsBefore = session.historyView.value.steps.size

			snap(session, UvSnapKind.CursorToSelected)
			snap(session, UvSnapKind.SelectionToCursor)

			assertEquals(before, uvRigPlacementOf(session, UV_RIG_QUAD_TILE))
			assertEquals(stepsBefore, session.historyView.value.steps.size, "and records nothing")
		}

	/** Keep Offset moves both tiles by one delta: their mean origin lands on the cursor and their spacing holds. */
	@Test
	fun keepOffsetMovesTheTilesTogether() =
		runTest {
			val session = placedSession(selected = listOf(UV_RIG_QUAD, UV_RIG_OTHER))
			placeCursorAt(session, CURSOR_X, CURSOR_Y)
			val (quadX, quadY) = originOf(session, UV_RIG_QUAD_TILE)
			val (otherX, otherY) = originOf(session, UV_RIG_OTHER_TILE)

			snap(session, UvSnapKind.SelectionToCursorOffset)

			val (quadAfterX, quadAfterY) = originOf(session, UV_RIG_QUAD_TILE)
			val (otherAfterX, otherAfterY) = originOf(session, UV_RIG_OTHER_TILE)
			assertPoint(CURSOR_X to CURSOR_Y, (quadAfterX + otherAfterX) / 2f to (quadAfterY + otherAfterY) / 2f, "the mean origin sits on the cursor")
			assertPoint(otherX - quadX to otherY - quadY, otherAfterX - quadAfterX to otherAfterY - quadAfterY, "the spacing holds")
		}

	/** Selection to Grid rounds the origin to the area's grid step in display space. */
	@Test
	fun selectionToGridRoundsTheOriginToTheGrid() =
		runTest {
			val session = placedSession()
			val (originX, originY) = originOf(session, UV_RIG_QUAD_TILE)
			assertNotEquals(0f, originX % GRID_STEP, "the rig's origin starts off the grid, so the snap has to move it")

			snap(session, UvSnapKind.SelectionToGrid, grid = GridConfig(GRID_STEP * 4f, 4))

			val (snappedX, snappedY) = originOf(session, UV_RIG_QUAD_TILE)
			assertPoint(round(originX / GRID_STEP) * GRID_STEP to round(originY / GRID_STEP) * GRID_STEP, snappedX to snappedY, "the nearest grid intersection")
		}

	/** Selection to Pixels rounds a fractional placement position to whole page pixels. */
	@Test
	fun selectionToPixelsRoundsThePosition() =
		runTest {
			val session = placedSession()
			session.setAtlasPlacements(mapOf(UV_RIG_QUAD_TILE to uvRigTilePlacement(100.4f).copy(positionY = 136.6f)))

			snap(session, UvSnapKind.SelectionToPixels)

			val after = assertNotNull(uvRigPlacementOf(session, UV_RIG_QUAD_TILE))
			assertEquals(100f, after.positionX)
			assertEquals(137f, after.positionY)
		}

	/** Pinned art does not move: a move answers with the drag's notice, while Cursor to Selected still reads it. */
	@Test
	fun pinnedArtAnswersWithTheNoticeButStillAnchorsTheCursor() =
		runTest {
			val session = placedSession()
			session.setAtlasPins(setOf(UV_RIG_QUAD_TILE), pinned = true)
			placeCursorAt(session, CURSOR_X, CURSOR_Y)
			val before = uvRigPlacementOf(session, UV_RIG_QUAD_TILE)

			snap(session, UvSnapKind.SelectionToCursor)

			assertEquals(before, uvRigPlacementOf(session, UV_RIG_QUAD_TILE))
			assertEquals("notice.uv.placement.pinned", session.notice.value?.messageKey)
			snap(session, UvSnapKind.CursorToSelected)
			assertPoint(originOf(session, UV_RIG_QUAD_TILE), cursorDisplayOf(session), "the pinned tile still anchors the cursor")
		}

	/** With nothing selected, a move answers that there is no placed art. */
	@Test
	fun anEmptySelectionAnswersNoPlacedArt() =
		runTest {
			val session = placedSession(selected = emptyList())

			snap(session, UvSnapKind.SelectionToCursor)

			assertEquals("notice.uv.placement.noPlacedArt", session.notice.value?.messageKey)
		}

	/** Over a source layer the selection snaps answer that they need the page view, while the cursor-only snaps still run. */
	@Test
	fun overALayerOnlyTheCursorSnapsRun() =
		runTest {
			val session = placedSession()
			val before = uvRigPlacementOf(session, UV_RIG_QUAD_TILE)

			snap(session, UvSnapKind.SelectionToCursor, surface = null)

			assertEquals(before, uvRigPlacementOf(session, UV_RIG_QUAD_TILE))
			assertEquals("notice.uv.placement.pageViewOnly", session.notice.value?.messageKey)
			assertNull(session.uvCursor.value)
			snap(session, UvSnapKind.CursorToGrid, surface = null)
			assertNotNull(session.uvCursor.value, "Cursor to Grid placed the cursor")
		}

	/**
	 * Runs one Object-mode snap over the rig's page as the overlay would, decoding on an unconfined dispatcher.
	 *
	 * @param EditorSession session The session.
	 * @param UvSnapKind kind The snap.
	 * @param UvPlacementSurface? surface The shown page, or null for a source layer.
	 * @param GridConfig grid The area's grid.
	 */
	private suspend fun snap(session: EditorSession, kind: UvSnapKind, surface: UvPlacementSurface? = uvRigPlacementSurface(), grid: GridConfig = GridConfig()) {
		val frame = uvRigPageFrame()
		handleUvObjectSnapRequest(session, surface, uvRigGeometries(session.model.value, frame), frame, kind, grid, Dispatchers.Unconfined)
	}

	/**
	 * An Object-mode session over the placed rig.
	 *
	 * @param List selected The drawables to select.
	 * @return EditorSession The session.
	 */
	private fun placedSession(selected: List<DrawableId> = listOf(UV_RIG_QUAD)): EditorSession = uvObjectSession(selected, uvRigPlacedModel())

	/**
	 * A tile's origin in display space, from the footprint lookup the handler uses.
	 *
	 * @param EditorSession session The session.
	 * @param AtlasTileId tileId The tile.
	 * @return Pair<Float, Float> The origin.
	 */
	private fun originOf(session: EditorSession, tileId: AtlasTileId): Pair<Float, Float> =
		assertNotNull(placementFootprintCenters(session.model.value, uvRigPlacementSurface(), listOf(tileId))).getValue(tileId)

	/**
	 * The UV cursor in the page's display space.
	 *
	 * @param EditorSession session The session.
	 * @return Pair<Float, Float> The cursor.
	 */
	private fun cursorDisplayOf(session: EditorSession): Pair<Float, Float> {
		val cursor = assertNotNull(session.uvCursor.value, "the cursor is placed")
		return uvRigPageFrame().displayAt(cursor.u, cursor.v)
	}

	/**
	 * Places the UV cursor at a display-space point on the page.
	 *
	 * @param EditorSession session The session.
	 * @param Float displayX The point's display x.
	 * @param Float displayY The point's display y.
	 */
	private fun placeCursorAt(session: EditorSession, displayX: Float, displayY: Float) {
		val (cursorU, cursorV) = uvRigPageFrame().storedUvAt(displayX, displayY)
		session.setUvCursor(cursorU, cursorV)
	}

	/**
	 * Asserts two display points agree within the float tolerance.
	 *
	 * @param Pair expected The expected point.
	 * @param Pair actual The actual point.
	 * @param String message What the point is.
	 */
	private fun assertPoint(expected: Pair<Float, Float>, actual: Pair<Float, Float>, message: String) {
		assertEquals(expected.first, actual.first, TOLERANCE, "$message (x)")
		assertEquals(expected.second, actual.second, TOLERANCE, "$message (y)")
	}

	private companion object {
		/** The UV cursor's display x in the cases that place it, clear of both tiles. */
		const val CURSOR_X = 60f

		/** The UV cursor's display y in the cases that place it. */
		const val CURSOR_Y = 50f

		/** The grid step the grid case snaps to, in texels. */
		const val GRID_STEP = 8f

		/** Float agreement for display coordinates. */
		const val TOLERANCE = 1e-3f
	}
}