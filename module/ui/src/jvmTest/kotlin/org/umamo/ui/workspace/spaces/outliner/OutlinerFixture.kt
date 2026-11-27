package org.umamo.ui.workspace.spaces.outliner

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.Part
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.PuppetModel
import org.umamo.ui.model.DrawableThumbnailProvider
import org.umamo.ui.workspace.spaces.parameters.ControlBox
import org.umamo.ui.workspace.spaces.parameters.GESTURE_GAP_MILLIS
import org.umamo.ui.workspace.spaces.parameters.GESTURE_STEP_MILLIS
import org.umamo.ui.workspace.spaces.parameters.PANEL_BODY_TAG
import org.umamo.ui.workspace.spaces.parameters.PANEL_OUTLINER_HEIGHT
import org.umamo.ui.workspace.spaces.parameters.PANEL_OUTLINER_TAG
import org.umamo.ui.workspace.spaces.parameters.PANEL_OUTLINER_WIDTH
import org.umamo.ui.workspace.spaces.parameters.PANEL_ROOT_TAG
import org.umamo.ui.workspace.spaces.parameters.ParametersPanelHarness
import org.umamo.ui.workspace.spaces.parameters.clickAt
import org.umamo.ui.workspace.spaces.parameters.mountParametersPanel
import org.umamo.ui.workspace.spaces.parameters.panelBoundsOf
import org.umamo.ui.workspace.spaces.parameters.panelFixtureModel

/*
 * What the outliner's composition tests share: a rig with every row kind the tree draws, a mount beside the
 * Parameters panel in that panel's miniature shell (the outliner gets the shell's relation pick, thumbnail
 * provider, and object commands there), and the geometry that finds a row and the slots on it.
 *
 * The rig, as the outliner lists it with only the root open:
 *
 *   Puppet
 *     Armature             Root Warp (a warp deformer) holding Neck (a rotation deformer)
 *     Head (a part)        Eye (a drawable), then Hair (a part) holding Bang (a drawable)
 *     Loose (a drawable)   a root-level mesh sitting between two parts
 *     Limbs (a part)       Chest (a drawable, hidden) and Hand (a drawable, unselectable)
 *
 * Five rows show as the fixture opens, eleven once every branch is open.  A row is found by its label,
 * scoped to the outliner's box because the panel beside it shows names of its own; a slot on a row (its
 * chevron, eye, or pointer) is the node carrying that slot's accessible name whose vertical center falls
 * in the row's band.  Every point is in the panel body's pixels, as the shared gestures expect.
 */

/** The ids of the outliner rig's parts, drawables, and deformers. */
internal object OutlinerIds {
	val head = PartId("head")
	val hair = PartId("hair")
	val limbs = PartId("limbs")
	val eye = DrawableId("eye")
	val bang = DrawableId("bang")
	val loose = DrawableId("loose")
	val chest = DrawableId("chest")
	val hand = DrawableId("hand")
	val rootWarp = DeformerId("root_warp")
	val neck = DeformerId("neck")
}

/** The display names of the outliner rig's rows, which is what a test finds them by. */
internal object OutlinerNames {
	const val HEAD = "Head"
	const val HAIR = "Hair"
	const val LIMBS = "Limbs"
	const val EYE = "Eye"
	const val BANG = "Bang"
	const val LOOSE = "Loose"
	const val CHEST = "Chest"
	const val HAND = "Hand"
	const val ROOT_WARP = "Root Warp"
	const val NECK = "Neck"
}

/** The stable node ids of the rig's branches, which is how a test reads a fold. */
internal object OutlinerRowKeys {
	const val ARMATURE = "armature"
	const val HEAD = "part:head"
	const val HAIR = "part:hair"
	const val LIMBS = "part:limbs"
}

/**
 * The outliner rig: the panel rig with the parts, drawables, and deformers above in place of its one
 * loose drawable.
 *
 * @return PuppetModel The rig.
 */
internal fun outlinerFixtureModel(): PuppetModel =
	panelFixtureModel().copy(
		parts =
			listOf(
				Part(OutlinerIds.head, OutlinerNames.HEAD, listOf(OrgChild.Drawable(OutlinerIds.eye), OrgChild.Part(OutlinerIds.hair))),
				Part(OutlinerIds.hair, OutlinerNames.HAIR, listOf(OrgChild.Drawable(OutlinerIds.bang))),
				Part(OutlinerIds.limbs, OutlinerNames.LIMBS, listOf(OrgChild.Drawable(OutlinerIds.chest), OrgChild.Drawable(OutlinerIds.hand))),
			),
		deformers =
			listOf(
				Deformer.Warp(OutlinerIds.rootWarp, OutlinerNames.ROOT_WARP, parent = null, partId = null, rows = 2, columns = 2, isQuadTransform = true, geometryGrid = null),
				Deformer.Rotation(OutlinerIds.neck, OutlinerNames.NECK, OutlinerIds.rootWarp, null, 0f, null),
			),
		drawables =
			listOf(
				fixtureDrawable(OutlinerIds.eye, OutlinerNames.EYE),
				fixtureDrawable(OutlinerIds.bang, OutlinerNames.BANG),
				fixtureDrawable(OutlinerIds.loose, OutlinerNames.LOOSE),
				fixtureDrawable(OutlinerIds.chest, OutlinerNames.CHEST, visible = false),
				fixtureDrawable(OutlinerIds.hand, OutlinerNames.HAND, selectable = false),
			),
		rootChildren = listOf(OrgChild.Part(OutlinerIds.head), OrgChild.Drawable(OutlinerIds.loose), OrgChild.Part(OutlinerIds.limbs)),
	)

/**
 * A drawable with no mesh and no keys: the outliner reads only its name and its two flags.
 *
 * @param DrawableId id The drawable's id.
 * @param String name Its display name.
 * @param Boolean visible Whether its own eyeball is on.
 * @param Boolean selectable Whether it can be picked in the viewport.
 * @return Drawable The drawable.
 */
private fun fixtureDrawable(id: DrawableId, name: String, visible: Boolean = true, selectable: Boolean = true): Drawable =
	Drawable(
		id = id,
		name = name,
		parentDeformerId = null,
		blendMode = BlendMode.Normal,
		maskedBy = emptyList(),
		mesh = null,
		geometryGrid = null,
		isVisible = visible,
		isSelectable = selectable,
	)

/**
 * Mounts the panel and the outliner over the outliner rig.
 *
 * @param PuppetModel model The rig the session opens on.
 * @param DrawableThumbnailProvider? thumbnails The art the outliner previews, or null for no previews.
 * @param Dp height The outliner's height; a short one makes its list scroll.
 * @return ParametersPanelHarness The mounted harness.
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.mountOutliner(
	model: PuppetModel = outlinerFixtureModel(),
	thumbnails: DrawableThumbnailProvider? = null,
	height: Dp = PANEL_OUTLINER_HEIGHT,
): ParametersPanelHarness {
	val harness = ParametersPanelHarness(showOutliner = true, thumbnails = thumbnails, model = model)
	harness.outlinerSize = DpSize(PANEL_OUTLINER_WIDTH, height)
	mountParametersPanel(harness)

	return harness
}

/** The outliner's view state, the same instance its body and header read. */
internal val ParametersPanelHarness.outlinerViewState: OutlinerViewState
	get() = outlinerScope.spaceState(OUTLINER_VIEW_STATE_KEY) { OutlinerViewState() }

/**
 * A part of the session's current model.
 *
 * @param ParametersPanelHarness harness The mounted harness.
 * @param PartId id The part.
 * @return Part The part as the model holds it now.
 */
internal fun partOf(harness: ParametersPanelHarness, id: PartId): Part = harness.session.model.value.parts.first { part -> part.id == id }

/**
 * A drawable of the session's current model.
 *
 * @param ParametersPanelHarness harness The mounted harness.
 * @param DrawableId id The drawable.
 * @return Drawable The drawable as the model holds it now.
 */
internal fun drawableOf(harness: ParametersPanelHarness, id: DrawableId): Drawable =
	harness.session.model.value.drawables.first { drawable -> drawable.id == id }

/**
 * A deformer of the session's current model.
 *
 * @param ParametersPanelHarness harness The mounted harness.
 * @param DeformerId id The deformer.
 * @return Deformer The deformer as the model holds it now.
 */
internal fun deformerOf(harness: ParametersPanelHarness, id: DeformerId): Deformer =
	harness.session.model.value.deformers.first { deformer -> deformer.id == id }

/**
 * Restricts a matcher to the outliner's box, because the panel beside it shows names of its own.
 *
 * @return SemanticsMatcher The matcher.
 */
private fun inOutliner(): SemanticsMatcher = hasAnyAncestor(hasTestTag(PANEL_OUTLINER_TAG))

/**
 * The outliner's box, in the panel body's pixels.
 *
 * @return ControlBox The box.
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.outlinerBox(): ControlBox = panelBoundsOf(onNodeWithTag(PANEL_OUTLINER_TAG))

/**
 * The bounds of the outliner's node showing exactly [text], in the panel body's pixels.
 *
 * @param String text The text.
 * @return ControlBox The node's bounds.
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.outlinerBoundsOfText(text: String): ControlBox = panelBoundsOf(onNode(hasText(text) and inOutliner(), useUnmergedTree = true))

/**
 * Whether the outliner lists a row showing exactly [text], on screen or composed just off it.
 *
 * @param String text The text.
 * @return Boolean True when a row shows it.
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.outlinerShows(text: String): Boolean = onAllNodes(hasText(text) and inOutliner(), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

/**
 * Whether the outliner shows a row with exactly [text] inside its visible bounds.
 *
 * @param String text The text.
 * @return Boolean True when the row is listed and on screen.
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.outlinerDisplays(text: String): Boolean = outlinerShows(text) && onNode(hasText(text) and inOutliner(), useUnmergedTree = true).isDisplayed()

/**
 * The full-width band of the row labelled [name], in the panel body's pixels: the outliner's width at the
 * label's height.  The band's center lies on blank row space, which is where a click selects a row.
 *
 * @param String name The row's label.
 * @return ControlBox The row's band.
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.rowBox(name: String): ControlBox {
	val label = outlinerBoundsOfText(name)
	val outliner = outlinerBox()
	val halfRow = with(density) { OUTLINER_ROW_HEIGHT.toPx() / 2f }

	return ControlBox(left = outliner.left, top = label.center.y - halfRow, right = outliner.right, bottom = label.center.y + halfRow)
}

/**
 * A point on the row labelled [name], by how far down its band it sits: the drop bands a drag reads.
 *
 * @param String name The row's label.
 * @param Float down The fraction of the row's height, 0 at its top edge.
 * @return Offset The point, at the row's horizontal center.
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.rowBandPoint(name: String, down: Float): Offset = rowBox(name).at(0.5f, down)

/**
 * The center of the slot carrying the accessible name [description] on the row labelled [rowName]: its
 * chevron, its eye, or its pointer.
 *
 * @param String description The slot's accessible name.
 * @param String rowName The row's label.
 * @return Offset The slot's center, in the panel body's pixels.
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.slotPoint(description: String, rowName: String): Offset {
	val row = rowBox(rowName)
	val slots = onAllNodes(hasContentDescription(description) and inOutliner(), useUnmergedTree = true)
	val count = slots.fetchSemanticsNodes().size

	for (slotIndex in 0 until count) {
		val bounds = panelBoundsOf(slots[slotIndex])

		if (bounds.center.y >= row.top && bounds.center.y < row.bottom) {
			return bounds.center
		}
	}

	error("no slot named $description on the row $rowName")
}

/**
 * A point given in the panel body's pixels, in the window's.  A popup is placed in the window, so a
 * case comparing one with a row or with the pointer compares them there.
 *
 * @param Offset point The point, in the panel body's pixels.
 * @return Offset The point, in window pixels.
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.pointInWindow(point: Offset): Offset = onNodeWithTag(PANEL_BODY_TAG).fetchSemanticsNode().positionInWindow + point

/**
 * The hover art preview's card for the row named [name], in window pixels.
 *
 * A popup's own node is its layer, which spans the window, so the card is worked out from the one node
 * inside it that has semantics: the name under the art.  The card pads its content by 6 dp and stacks
 * the 120 dp art and a 4 dp gap over the name.
 *
 * @param String name The previewed row's name, which the card shows under its art.
 * @return Rect The card's bounds.
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.previewCardInWindow(name: String): Rect {
	val label = onNode(hasText(name) and hasAnyAncestor(isPopup()), useUnmergedTree = true).fetchSemanticsNode().boundsInWindow
	val padding = with(density) { PREVIEW_CARD_PADDING.toPx() }
	val art = with(density) { PREVIEW_CARD_ART.toPx() }
	val artGap = with(density) { PREVIEW_CARD_ART_GAP.toPx() }
	val left = label.left - padding

	return Rect(left = left, top = label.top - artGap - art - padding, right = left + art + padding * 2f, bottom = label.bottom + padding)
}

/**
 * Rests the pointer at [point], which is what hovers a row.
 *
 * @param Offset point Where to rest, in the panel body's pixels.
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.hoverAt(point: Offset) {
	onNodeWithTag(PANEL_BODY_TAG).performMouseInput {
		advanceEventTime(GESTURE_GAP_MILLIS)
		moveTo(point)
	}
	waitForIdle()
}

/**
 * A click at [point] with Ctrl held, in the panel body's pixels.
 *
 * @param Offset point Where to click.
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.ctrlClickAt(point: Offset) {
	onNodeWithTag(PANEL_ROOT_TAG).performKeyInput { keyDown(Key.CtrlLeft) }
	clickAt(point)
	onNodeWithTag(PANEL_ROOT_TAG).performKeyInput { keyUp(Key.CtrlLeft) }
	waitForIdle()
}

/**
 * Presses at [from], holds still for the long-press timeout, then moves through [through] with the
 * button held: the gesture that picks a row up.  No movement before the hold, which would cancel the
 * long press.
 *
 * @param Offset from Where the press lands.
 * @param List through The points the pointer then moves through, in order.
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.longPressAndMove(from: Offset, through: List<Offset>) {
	onNodeWithTag(PANEL_BODY_TAG).performMouseInput {
		advanceEventTime(GESTURE_GAP_MILLIS)
		moveTo(from)
		press()
		advanceEventTime(viewConfiguration.longPressTimeoutMillis + LONG_PRESS_MARGIN_MILLIS)

		for (point in through) {
			advanceEventTime(GESTURE_STEP_MILLIS)
			moveTo(point)
		}
	}
	waitForIdle()
}

/** A thumbnail provider with art for every drawable and part, so a rested hover pops a preview. */
internal object StubThumbnails : DrawableThumbnailProvider {
	private val art: ImageBitmap by lazy { ImageBitmap(STUB_ART_SIZE, STUB_ART_SIZE) }

	override fun thumbnailFor(id: DrawableId): ImageBitmap = art

	override fun partThumbnailFor(id: PartId): ImageBitmap = art
}

/** The padding RowThumbnailPreview's card puts around its content. */
private val PREVIEW_CARD_PADDING: Dp = 6.dp

/** The edge of the square art in RowThumbnailPreview's card. */
private val PREVIEW_CARD_ART: Dp = 120.dp

/** The gap between the art and the name in RowThumbnailPreview's card. */
private val PREVIEW_CARD_ART_GAP: Dp = 4.dp

/** How much longer than the long-press timeout a press is held, so the timeout has surely elapsed. */
private const val LONG_PRESS_MARGIN_MILLIS = 100L

/** The side of the stub art, in pixels. */
private const val STUB_ART_SIZE = 4