package org.umamo.ui.workspace.spaces.keyformsheet

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import org.umamo.edit.ParameterSelection
import org.umamo.runtime.model.ChannelGrids
import org.umamo.runtime.model.ChannelValue
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.FormChannel
import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.MeshDeltaForm
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.Part
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.PuppetModel
import org.umamo.ui.tracks.TRACK_MARK_RADIUS
import org.umamo.ui.tracks.TrackAxis
import org.umamo.ui.tracks.laneMarkOffsetX
import org.umamo.ui.workspace.spaces.parameters.ControlBox
import org.umamo.ui.workspace.spaces.parameters.PANEL_BODY_TAG
import org.umamo.ui.workspace.spaces.parameters.PANEL_HEIGHT_SCROLLING
import org.umamo.ui.workspace.spaces.parameters.PANEL_ROOT_TAG
import org.umamo.ui.workspace.spaces.parameters.PANEL_SHEET_TAG
import org.umamo.ui.workspace.spaces.parameters.PANEL_WIDTH
import org.umamo.ui.workspace.spaces.parameters.PanelIds
import org.umamo.ui.workspace.spaces.parameters.ParametersPanelHarness
import org.umamo.ui.workspace.spaces.parameters.clickAt
import org.umamo.ui.workspace.spaces.parameters.mountParametersPanel
import org.umamo.ui.workspace.spaces.parameters.panelBoundsOf
import org.umamo.ui.workspace.spaces.parameters.panelFixtureModel
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/*
 * What the keyform sheet's composition tests share: a rig with enough keys to reach every gesture, a mount
 * beside the Parameters panel in that panel's miniature shell (the sheet gets the shell's keyable hover,
 * open-sheet registry, and keyform and select commands there), and the geometry that finds a mark, a label,
 * and a chevron.  A lane is a bare Canvas, so a point on it is found the way the lane draws: from the bounds
 * it reports and the same domain-to-pixel mapping.
 *
 * The rig, as the sheet lists it for Body X (-10 to 10):
 *
 *   Head (a part)        Opacity keyed at -8 and 8
 *   a (a drawable)       Geometry keyed at -5, 0, and 5; Opacity keyed at 0, 5, and 8
 *
 * and for the linked pad, Angle X over Angle Y (each -30 to 30):
 *
 *   b (a drawable)       Geometry keyed on Angle X at -15 and 15; Opacity keyed on Angle Y at -15 and 15
 *
 * Folded, row a's summary marks stand at -5 (the geometry key alone), 0 and 5 (a geometry and an opacity
 * key each), and 8 (the opacity key alone).  Every track has keys to spare, because a track left with one
 * key folds into its static value and leaves the sheet.
 */

/** The ids the sheet rig adds to the panel rig's. */
internal object SheetIds {
	val part = PartId("head")
	val padDrawable = DrawableId("b")
}

/** The row keys of the sheet rig's rows, which is how a test finds a lane. */
internal object SheetRows {
	const val PART = "part:head"
	const val PART_OPACITY = "part:head/OPACITY"
	const val DRAWABLE = "drawable:a"
	const val GEOMETRY = "drawable:a/geometry"
	const val OPACITY = "drawable:a/OPACITY"
	const val PAD_GEOMETRY = "drawable:b/geometry"
	const val PAD_OPACITY = "drawable:b/OPACITY"
}

/**
 * The sheet rig: the panel rig with its drawable keyed more densely, a part, and a drawable on the pad.
 *
 * @return PuppetModel The rig.
 */
internal fun sheetFixtureModel(): PuppetModel {
	val base = panelFixtureModel()
	val keyedDrawable =
		base.drawables.single().copy(
			geometryGrid = geometryGridOn(PanelIds.bodyX, -5f, 0f, 5f),
			channelGrids = ChannelGrids(mapOf(FormChannel.OPACITY to opacityGridOn(PanelIds.bodyX, 0f, 5f, 8f))),
		)
	val padDrawable =
		keyedDrawable.copy(
			id = SheetIds.padDrawable,
			name = "b",
			geometryGrid = geometryGridOn(PanelIds.angleX, -15f, 15f),
			channelGrids = ChannelGrids(mapOf(FormChannel.OPACITY to opacityGridOn(PanelIds.angleY, -15f, 15f))),
		)
	val part =
		Part(
			id = SheetIds.part,
			name = "Head",
			children = emptyList(),
			channelGrids = ChannelGrids(mapOf(FormChannel.OPACITY to opacityGridOn(PanelIds.bodyX, -8f, 8f))),
		)
	return base.copy(parts = listOf(part), drawables = listOf(keyedDrawable, padDrawable))
}

/**
 * A geometry grid over one parameter, with a flat form at every key.
 *
 * @param ParameterId parameterId The parameter it keys on.
 * @param FloatArray keys The key positions, ascending.
 * @return KeyformGrid The grid.
 */
private fun geometryGridOn(parameterId: ParameterId, vararg keys: Float): KeyformGrid<MeshDeltaForm> =
	KeyformGrid(
		listOf(KeyformAxis(parameterId, keys)),
		keys.indices.map { keyIndex -> KeyformCell(intArrayOf(keyIndex), MeshDeltaForm(DoubleArray(6))) },
	)

/**
 * An opacity grid over one parameter, fading out along the keys.
 *
 * @param ParameterId parameterId The parameter it keys on.
 * @param FloatArray keys The key positions, ascending.
 * @return KeyformGrid The grid.
 */
private fun opacityGridOn(parameterId: ParameterId, vararg keys: Float): KeyformGrid<ChannelValue> =
	KeyformGrid(
		listOf(KeyformAxis(parameterId, keys)),
		keys.indices.map { keyIndex -> KeyformCell<ChannelValue>(intArrayOf(keyIndex), ChannelValue.Scalar(1f - keyIndex * 0.25f)) },
	)

/**
 * Mounts the panel and the sheet over the sheet rig, with [targets] targeted so the sheet has sections.
 *
 * @param List<ParameterId> targets The parameters to target, the first of them active.
 * @return ParametersPanelHarness The mounted harness.
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.mountSheet(targets: List<ParameterId>): ParametersPanelHarness {
	val harness = ParametersPanelHarness(showKeyformSheet = true, model = sheetFixtureModel())
	harness.panelSize = DpSize(PANEL_WIDTH, PANEL_HEIGHT_SCROLLING)
	mountParametersPanel(harness)
	runOnIdle { harness.session.setParameterSelection(ParameterSelection(targets.toSet(), targets.firstOrNull())) }
	waitForIdle()
	return harness
}

/** The sheet's view state, the same instance its body and header read. */
internal val ParametersPanelHarness.sheetViewState: KeyformSheetViewState
	get() = sheetScope.spaceState(KEYFORM_SHEET_VIEW_STATE_KEY) { KeyformSheetViewState() }

/**
 * The lane of the row keyed [rowKey], in the panel body's pixels, as the lane last reported itself.
 *
 * @param ParametersPanelHarness harness The mounted harness.
 * @param String rowKey The row.
 * @return ControlBox The lane's box.
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.laneBox(harness: ParametersPanelHarness, rowKey: String): ControlBox {
	val lane = harness.sheetViewState.laneBounds[rowKey] ?: error("row $rowKey has no lane on screen")
	val body = onNodeWithTag(PANEL_BODY_TAG).getUnclippedBoundsInRoot()
	val originX = with(density) { body.left.toPx() }
	val originY = with(density) { body.top.toPx() }
	return ControlBox(lane.left - originX, lane.top - originY, lane.right - originX, lane.bottom - originY)
}

/**
 * The point on the row keyed [rowKey] where the lane draws [value], in the panel body's pixels.
 *
 * @param ParametersPanelHarness harness The mounted harness.
 * @param String rowKey The row.
 * @param ParameterId parameterId The parameter of the row's section.
 * @param Float value The domain value.
 * @return Offset The point, at the lane's vertical center.
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.lanePoint(harness: ParametersPanelHarness, rowKey: String, parameterId: ParameterId, value: Float): Offset {
	val lane = laneBox(harness, rowKey)
	val parameter = harness.session.model.value.parameters.first { candidate -> candidate.id == parameterId }
	val (domainStart, domainEnd) = parameterDomain(parameter)
	val axis = harness.sheetViewState.window.axisOver(TrackAxis(domainStart, domainEnd))
	val markRadiusPx = with(density) { TRACK_MARK_RADIUS.toPx() }
	return Offset(lane.left + laneMarkOffsetX(axis, value, lane.width, markRadiusPx), lane.center.y)
}

/**
 * The middle of the label cell of the row keyed [rowKey], in the panel body's pixels.  The cell ends one
 * divider short of the lane and is as wide as the label column.
 *
 * @param ParametersPanelHarness harness The mounted harness.
 * @param String rowKey The row.
 * @return Offset The point.
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.labelPoint(harness: ParametersPanelHarness, rowKey: String): Offset {
	val lane = laneBox(harness, rowKey)
	val cellRight = lane.left - with(density) { 1.dp.toPx() }
	val cellWidth = with(density) { harness.sheetViewState.labelColumnWidth.toPx() }
	return Offset(cellRight - cellWidth / 2f, lane.center.y)
}

/**
 * The chevron of the group row keyed [rowKey], in the panel body's pixels.  A group row sits at depth
 * zero, so its label cell pads its start by 4 dp and the chevron is the 16 dp slot after that.
 *
 * @param ParametersPanelHarness harness The mounted harness.
 * @param String rowKey The group row.
 * @return Offset The chevron's center.
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.chevronPoint(harness: ParametersPanelHarness, rowKey: String): Offset {
	val lane = laneBox(harness, rowKey)
	val cellLeft = lane.left - with(density) { 1.dp.toPx() + harness.sheetViewState.labelColumnWidth.toPx() }
	return Offset(cellLeft + with(density) { 12.dp.toPx() }, lane.center.y)
}

/**
 * The bounds of the sheet's node showing exactly [text], in the panel body's pixels.  Scoped to the sheet,
 * because the panel beside it shows the same parameter names.
 *
 * @param String text The text.
 * @return ControlBox The node's bounds.
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.sheetBoundsOfText(text: String): ControlBox =
	panelBoundsOf(onNode(hasText(text) and hasAnyAncestor(hasTestTag(PANEL_SHEET_TAG)), useUnmergedTree = true))

/**
 * How many of the sheet's nodes show exactly [text].
 *
 * @param String text The text.
 * @return Int The count.
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.sheetCountOfText(text: String): Int =
	onAllNodes(hasText(text) and hasAnyAncestor(hasTestTag(PANEL_SHEET_TAG)), useUnmergedTree = true).fetchSemanticsNodes().size

/**
 * Whether any popup is open.
 *
 * @return Boolean True when one is.
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.anyPopupOpen(): Boolean = onAllNodes(isPopup()).fetchSemanticsNodes().isNotEmpty()

/**
 * A click at [point] with Shift held, in the panel body's pixels.
 *
 * @param Offset point Where to click.
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.shiftClickAt(point: Offset) {
	onNodeWithTag(PANEL_ROOT_TAG).performKeyInput { keyDown(Key.ShiftLeft) }
	clickAt(point)
	onNodeWithTag(PANEL_ROOT_TAG).performKeyInput { keyUp(Key.ShiftLeft) }
	waitForIdle()
}

/**
 * The key positions of a drawable's geometry grid on one parameter.
 *
 * @param ParametersPanelHarness harness The mounted harness.
 * @param DrawableId drawableId The drawable.
 * @param ParameterId parameterId The parameter.
 * @return List<Float> The positions, ascending.
 */
internal fun geometryKeysOf(harness: ParametersPanelHarness, drawableId: DrawableId, parameterId: ParameterId): List<Float> =
	harness.session.model.value.drawables
		.first { drawable -> drawable.id == drawableId }
		.geometryGrid!!
		.axes
		.first { axis -> axis.parameterId == parameterId }
		.keys
		.toList()

/**
 * The key positions of a drawable's opacity grid on one parameter.
 *
 * @param ParametersPanelHarness harness The mounted harness.
 * @param DrawableId drawableId The drawable.
 * @param ParameterId parameterId The parameter.
 * @return List<Float> The positions, ascending.
 */
internal fun opacityKeysOf(harness: ParametersPanelHarness, drawableId: DrawableId, parameterId: ParameterId): List<Float> =
	harness.session.model.value.drawables
		.first { drawable -> drawable.id == drawableId }
		.channelGrids
		.gridsByChannel
		.getValue(FormChannel.OPACITY)
		.axes
		.first { axis -> axis.parameterId == parameterId }
		.keys
		.toList()

/**
 * The key positions of the part's opacity grid on Body X.
 *
 * @param ParametersPanelHarness harness The mounted harness.
 * @return List<Float> The positions, ascending.
 */
internal fun partOpacityKeysOf(harness: ParametersPanelHarness): List<Float> =
	harness.session.model.value.parts
		.first { part -> part.id == SheetIds.part }
		.channelGrids
		.gridsByChannel
		.getValue(FormChannel.OPACITY)
		.axes
		.first { axis -> axis.parameterId == PanelIds.bodyX }
		.keys
		.toList()

/**
 * Asserts key positions a gesture produced, each to within what a pixel of pointer travel can resolve.
 *
 * @param List expected The positions the gesture should have produced.
 * @param List actual The positions it produced.
 * @param String message What the assertion is about.
 */
internal fun assertKeysNear(expected: List<Float>, actual: List<Float>, message: String) {
	assertEquals(expected.size, actual.size, "$message: expected keys $expected, got $actual")
	assertTrue(expected.zip(actual).all { (want, got) -> abs(want - got) <= SHEET_KEY_TOLERANCE }, "$message: expected keys $expected, got $actual")
}

/** How far a key a gesture placed may land from the value its point names: the lane is inset at both ends. */
internal const val SHEET_KEY_TOLERANCE = 0.5f