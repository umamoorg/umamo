package org.umamo.ui.tracks

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.umamo.ui.kit.menu.MenuItem

/*
 * The track sheet test rig: a harness recording every callback a mounted sheet fires, a mount over a
 * fixed-size box, and the sheet's geometry derived from its public constants (never from literals), so a
 * test that aims at a row, a mark, or the chevron reads the same numbers the sheet lays out with.
 * Density is 1 under the test runner, so a dp is a pixel.
 */

/** The test tag of the box the sheet fills. */
internal const val SHEET_TAG = "sheet"

/** The mounted sheet's size. */
internal val SHEET_WIDTH: Dp = 600.dp
internal val SHEET_HEIGHT: Dp = 200.dp

/** The x where the track region starts: the label column plus its one-pixel divider. */
internal val LANE_LEFT: Float = TRACK_LABEL_COLUMN_DEFAULT_WIDTH.value + 1f

/** The x of a depth-zero row's chevron: the label cell's 4 dp start pad plus half the 16 dp slot. */
internal const val CHEVRON_X: Float = 4f + 8f

/**
 * What a mounted sheet reported through each of its callbacks, in order, plus the one piece of state the
 * sheet is driven with (which rows are expanded), so a test asserts on the record rather than on a
 * handful of captured vars.
 */
internal class TrackSheetHarness {
	val markClicks = mutableListOf<Triple<TrackRow, TrackKeyMark, Boolean>>()
	val scrubs = mutableListOf<Triple<TrackRow, Float, Boolean>>()
	val scrubEnds = mutableListOf<Pair<TrackRow, Float>>()
	val markDrags = mutableListOf<Triple<TrackRow, TrackKeyMark, Float>>()
	val markDragEnds = mutableListOf<Triple<TrackRow, TrackKeyMark, Float>>()
	val hovers = mutableListOf<Pair<TrackRow, TrackLaneHit?>>()
	val toggles = mutableListOf<TrackRow>()
	val laneBounds = mutableMapOf<String, Rect>()
	val labelMenuRows = mutableListOf<TrackRow>()
	val laneMenuHits = mutableListOf<TrackLaneHit>()

	/** The keys of the rows whose children the sheet shows; a test changes it in runOnIdle. */
	var expandedKeys: Set<String> by mutableStateOf(setOf(FixtureRows.OWNER))
}

/** The row keys the fixture rows carry. */
internal object FixtureRows {
	const val OWNER = "owner"
	const val OPACITY = "owner/opacity"
	const val ANGLE = "owner/angle"
}

/**
 * One group over two tracks: Opacity with marks at both ends and the middle (and a detail line), Angle with
 * a selected mark and a plain one.  The group is row 0, Opacity row 1, Angle row 2 while expanded.
 *
 * @return List<TrackRow> The rows.
 */
internal fun trackSheetFixtureRows(): List<TrackRow> =
	listOf(
		TrackRow(
			key = FixtureRows.OWNER,
			label = "Owner",
			tone = TrackRowTone.Group,
			children =
				listOf(
					TrackRow(
						key = FixtureRows.OPACITY,
						label = "Opacity",
						detail = "Opacity channel",
						marks = listOf(TrackKeyMark(0, -30f), TrackKeyMark(1, 0f), TrackKeyMark(2, 30f)),
					),
					TrackRow(
						key = FixtureRows.ANGLE,
						label = "Angle",
						marks = listOf(TrackKeyMark(0, -10f, selected = true), TrackKeyMark(1, 20f)),
					),
				),
		),
	)

/**
 * Mounts a sheet over the fixture rows in a [SHEET_WIDTH] x [SHEET_HEIGHT] box, every callback wired to
 * [harness].
 *
 * @param TrackSheetHarness harness Records what the sheet reports.
 * @param List<TrackRow> rows The rows to show.
 * @param TrackAxis axis The domain.
 * @param Float? playhead The playhead value, or null.
 * @param Function? labelMenuItems The label-cell menu builder, or null for no label menus.
 * @param Function? laneMenuItems The lane menu builder, or null for no lane menus.
 * @param Function? formatTick The ruler's tick formatter, or null for the sheet's default.
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.mountTrackSheet(
	harness: TrackSheetHarness,
	rows: List<TrackRow> = trackSheetFixtureRows(),
	axis: TrackAxis = TrackAxis(-30f, 30f),
	playhead: Float? = null,
	labelMenuItems: ((TrackRow) -> List<MenuItem>)? = null,
	laneMenuItems: ((TrackLaneHit) -> List<MenuItem>)? = null,
	formatTick: ((Float) -> String)? = null,
) {
	setContent {
		Box(modifier = Modifier.size(width = SHEET_WIDTH, height = SHEET_HEIGHT).testTag(SHEET_TAG)) {
			TrackSheet(
				rows = rows,
				axis = axis,
				playhead = playhead,
				modifier = Modifier.fillMaxSize(),
				expandedKeys = harness.expandedKeys,
				onToggleExpanded = { row -> harness.toggles += row },
				formatTick = formatTick ?: ::defaultTickLabel,
				onMarkClick = { row, mark, additive -> harness.markClicks += Triple(row, mark, additive) },
				onTrackScrub = { row, value, additive -> harness.scrubs += Triple(row, value, additive) },
				onTrackScrubEnd = { row, value -> harness.scrubEnds += row to value },
				onMarkDrag = { row, mark, value -> harness.markDrags += Triple(row, mark, value) },
				onMarkDragEnd = { row, mark, value -> harness.markDragEnds += Triple(row, mark, value) },
				laneMenuItems =
					laneMenuItems?.let { build ->
						{ hit ->
							harness.laneMenuHits += hit
							build(hit)
						}
					},
				labelMenuItems =
					labelMenuItems?.let { build ->
						{ row ->
							harness.labelMenuRows += row
							build(row)
						}
					},
				onLaneHover = { row, hit -> harness.hovers += row to hit },
				onLaneBounds = { row, bounds -> harness.laneBounds[row.key] = bounds },
			)
		}
	}
}

/**
 * The y through the middle of the ruler strip.
 *
 * @return Float The y in sheet pixels.
 */
internal fun rulerCenterY(): Float = TRACK_RULER_HEIGHT.value / 2f

/**
 * The y where the display line at [index] starts: the ruler, then that many rows with their gaps.
 *
 * @param Int index The line's position among the shown rows, from zero.
 * @return Float The y in sheet pixels.
 */
internal fun rowTop(index: Int): Float = TRACK_RULER_HEIGHT.value + index * (TRACK_ROW_HEIGHT.value + TRACK_ROW_GAP.value)

/**
 * The y through the middle of the display line at [index].
 *
 * @param Int index The line's position among the shown rows, from zero.
 * @return Float The y in sheet pixels.
 */
internal fun rowCenterY(index: Int): Float = rowTop(index) + TRACK_ROW_HEIGHT.value / 2f

/**
 * The track region's width in a sheet [sheetWidth] wide.
 *
 * @param Float sheetWidth The sheet's width in pixels.
 * @return Float The lane width.
 */
internal fun laneWidth(sheetWidth: Float = SHEET_WIDTH.value): Float = sheetWidth - LANE_LEFT

/**
 * The x where a lane draws [value], through the same mapping the marks go through.
 *
 * @param TrackAxis axis The domain.
 * @param Float value The domain value.
 * @return Float The x in sheet pixels.
 */
internal fun laneXOf(axis: TrackAxis, value: Float): Float =
	LANE_LEFT + laneMarkOffsetX(axis, value, laneWidth(), TRACK_MARK_RADIUS.value)