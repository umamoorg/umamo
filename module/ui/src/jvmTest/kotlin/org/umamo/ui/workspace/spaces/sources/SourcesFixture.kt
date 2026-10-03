package org.umamo.ui.workspace.spaces.sources

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CompletableDeferred
import org.umamo.edit.SelectionTarget
import org.umamo.reimport.LayerMatch
import org.umamo.reimport.MatchSignals
import org.umamo.render.DecodedImage
import org.umamo.render.SourceArtRasters
import org.umamo.runtime.model.ArtSource
import org.umamo.runtime.model.ArtSourceId
import org.umamo.runtime.model.ArtSourceLayer
import org.umamo.runtime.model.AtlasPage
import org.umamo.runtime.model.AtlasPlacement
import org.umamo.runtime.model.AtlasTile
import org.umamo.runtime.model.AtlasTileId
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.PuppetAtlas
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.SourceLayerRef
import org.umamo.ui.model.DrawableThumbnailProvider
import org.umamo.ui.workspace.spaces.outliner.hoverAt
import org.umamo.ui.workspace.spaces.parameters.ControlBox
import org.umamo.ui.workspace.spaces.parameters.PANEL_SOURCES_HEIGHT
import org.umamo.ui.workspace.spaces.parameters.PANEL_SOURCES_TAG
import org.umamo.ui.workspace.spaces.parameters.PANEL_SOURCES_WIDTH
import org.umamo.ui.workspace.spaces.parameters.ParametersPanelHarness
import org.umamo.ui.workspace.spaces.parameters.clickAt
import org.umamo.ui.workspace.spaces.parameters.mountParametersPanel
import org.umamo.ui.workspace.spaces.parameters.panelBoundsOf
import org.umamo.ui.workspace.spaces.parameters.panelFixtureModel

/*
 * What the Sources space's composition tests share: a rig with every kind of row and every status the
 * table draws, a mount beside the Parameters panel in that panel's miniature shell (the Sources space gets
 * the app's file-presence probe, watcher state, published proposals, and artwork commands there), and the
 * geometry that finds a row and the slots on it.
 *
 * The rig, as the table lists it with the files and the unbound group open:
 *
 *   Body.psd (present)
 *     Hair          bound by a stable key     Hair art (placed)       Hair mesh, Hair shadow
 *     Eye           bound by name             Eye art (unplaced)      Eye mesh
 *     Sketch        unbound
 *     Notes         unbound, ignored
 *     Brow          unbound                   the layer the published proposal names
 *     Brow (old)    lost, still bound         Brow art L, Brow art R  Brow mesh L, Brow mesh R
 *   Face.clip (missing)
 *     Mouth         bound by a stable key     Mouth art (placed)      no drawable
 *   Unbound Art
 *     Loose art                                                       Loose mesh
 *
 * Eleven rows show as the fixture opens.  Every label is its own, because a row is found by its label,
 * scoped to the Sources box and kept out of any popup: the panel beside it shows names of its own, and a
 * relink menu lists the layers again.  A slot on a row (its chevron, its status glyph, or its chip) is the
 * node carrying that slot's accessible name whose vertical center falls in the row's band.  Every point is
 * in the panel body's pixels, as the shared gestures expect.
 */

/** The ids of the Sources rig's files, tiles, and drawables. */
internal object SourcesIds {
	val body = ArtSourceId("art-0")
	val face = ArtSourceId("art-1")
	val hairArt = AtlasTileId("tile-hair")
	val eyeArt = AtlasTileId("tile-eye")
	val browArtLeft = AtlasTileId("tile-brow-left")
	val browArtRight = AtlasTileId("tile-brow-right")
	val browArtNew = AtlasTileId("tile-brow-new")
	val mouthArt = AtlasTileId("tile-mouth")
	val looseArt = AtlasTileId("tile-loose")
	val hairMesh = DrawableId("hair_mesh")
	val hairShadow = DrawableId("hair_shadow")
	val eyeMesh = DrawableId("eye_mesh")
	val browMeshLeft = DrawableId("brow_mesh_left")
	val browMeshRight = DrawableId("brow_mesh_right")
	val browMeshNew = DrawableId("brow_mesh_new")
	val looseMesh = DrawableId("loose_mesh")
}

/** The layer keys of the Sources rig, as its files' readers minted them. */
internal object SourcesKeys {
	const val HAIR = "lyid:1"
	const val EYE = "name:Eye"
	const val SKETCH = "lyid:3"
	const val NOTES = "lyid:4"
	const val BROW = "lyid:5"
	const val BROW_OLD = "lyid:9"
	const val MOUTH = "uuid-mouth"
}

/** The paths the Sources rig's files were read from, which is what the presence probe is asked about. */
internal object SourcesPaths {
	const val BODY = "/art/Body.psd"
	const val FACE = "/art/Face.clip"
}

/** The display names of the Sources rig's rows, which is what a test finds them by. */
internal object SourcesNames {
	const val BODY = "Body.psd"
	const val FACE = "Face.clip"
	const val HAIR = "Hair"
	const val EYE = "Eye"
	const val SKETCH = "Sketch"
	const val NOTES = "Notes"
	const val BROW = "Brow"
	const val BROW_OLD = "Brow (old)"
	const val MOUTH = "Mouth"
	const val HAIR_ART = "Hair art"
	const val EYE_ART = "Eye art"
	const val BROW_ART_LEFT = "Brow art L"
	const val BROW_ART_RIGHT = "Brow art R"
	const val BROW_ART_NEW = "Brow art new"
	const val MOUTH_ART = "Mouth art"
	const val LOOSE_ART = "Loose art"
	const val HAIR_MESH = "Hair mesh"
	const val HAIR_SHADOW = "Hair shadow"
	const val EYE_MESH = "Eye mesh"
	const val BROW_MESH_LEFT = "Brow mesh L"
	const val BROW_MESH_RIGHT = "Brow mesh R"
	const val BROW_MESH_NEW = "Brow mesh new"
	const val LOOSE_MESH = "Loose mesh"
}

/** The stable node ids of the rig's rows, which is how a test reads and writes a fold. */
internal object SourcesRowKeys {
	const val BODY = "source:art-0"
	const val FACE = "source:art-1"
	const val HAIR = "layer:art-0/lyid:1"
	const val EYE = "layer:art-0/name:Eye"
	const val SKETCH = "layer:art-0/lyid:3"
	const val BROW = "layer:art-0/lyid:5"
	const val BROW_OLD = "layer:art-0/lyid:9"
	const val MOUTH = "layer:art-1/uuid-mouth"
	const val HAIR_ART = "tile:tile-hair"
	const val EYE_ART = "tile:tile-eye"
	const val MOUTH_ART = "tile:tile-mouth"
	const val LOOSE_ART = "tile:tile-loose"
}

/** How many rows the table shows as the fixture opens: the two files, their seven layers, the group, and its tile. */
internal const val SOURCES_OPEN_ROWS = 11

/**
 * The Sources rig: the panel rig with the files, tiles, and drawables above in place of its one drawable.
 *
 * @return PuppetModel The rig.
 */
internal fun sourcesFixtureModel(): PuppetModel {
	val drawables =
		listOf(
			fixtureDrawable(SourcesIds.hairMesh, SourcesNames.HAIR_MESH, SourcesIds.hairArt),
			fixtureDrawable(SourcesIds.hairShadow, SourcesNames.HAIR_SHADOW, SourcesIds.hairArt),
			fixtureDrawable(SourcesIds.eyeMesh, SourcesNames.EYE_MESH, SourcesIds.eyeArt),
			fixtureDrawable(SourcesIds.browMeshLeft, SourcesNames.BROW_MESH_LEFT, SourcesIds.browArtLeft),
			fixtureDrawable(SourcesIds.browMeshRight, SourcesNames.BROW_MESH_RIGHT, SourcesIds.browArtRight),
			fixtureDrawable(SourcesIds.looseMesh, SourcesNames.LOOSE_MESH, SourcesIds.looseArt),
		)
	return panelFixtureModel().copy(
		drawables = drawables,
		rootChildren = drawables.map { drawable -> OrgChild.Drawable(drawable.id) },
		atlas =
			PuppetAtlas(
				pages = listOf(AtlasPage(64, 64)),
				tiles =
					listOf(
						AtlasTile(SourcesIds.hairArt, SourcesNames.HAIR_ART, 8, 8, placement = AtlasPlacement(0, 1f, 1f, 1f, 1f, 0f), source = SourceLayerRef(SourcesIds.body, SourcesKeys.HAIR, true)),
						AtlasTile(SourcesIds.eyeArt, SourcesNames.EYE_ART, 4, 4, source = SourceLayerRef(SourcesIds.body, SourcesKeys.EYE, false)),
						AtlasTile(SourcesIds.browArtLeft, SourcesNames.BROW_ART_LEFT, 4, 4, source = SourceLayerRef(SourcesIds.body, SourcesKeys.BROW_OLD, true)),
						AtlasTile(SourcesIds.browArtRight, SourcesNames.BROW_ART_RIGHT, 4, 4, source = SourceLayerRef(SourcesIds.body, SourcesKeys.BROW_OLD, true)),
						AtlasTile(SourcesIds.mouthArt, SourcesNames.MOUTH_ART, 8, 4, placement = AtlasPlacement(0, 20f, 1f, 1f, 1f, 0f), source = SourceLayerRef(SourcesIds.face, SourcesKeys.MOUTH, true)),
						AtlasTile(SourcesIds.looseArt, SourcesNames.LOOSE_ART, 4, 4),
					),
			),
		sources =
			listOf(
				ArtSource(
					SourcesIds.body,
					SourcesNames.BODY,
					SourcesPaths.BODY,
					"psd",
					listOf(
						ArtSourceLayer(SourcesKeys.HAIR, SourcesNames.HAIR, "Head", 10, 20, 8, 8, true),
						ArtSourceLayer(SourcesKeys.EYE, SourcesNames.EYE, "Head", 30, 20, 4, 4, true),
						ArtSourceLayer(SourcesKeys.SKETCH, SourcesNames.SKETCH, "", 0, 0, 16, 16, true),
						ArtSourceLayer(SourcesKeys.NOTES, SourcesNames.NOTES, "", 0, 0, 16, 16, true, ignored = true),
						ArtSourceLayer(SourcesKeys.BROW, SourcesNames.BROW, "Head", 12, 22, 4, 4, true),
						ArtSourceLayer(SourcesKeys.BROW_OLD, SourcesNames.BROW_OLD, "Head", 12, 22, 4, 4, true, present = false),
					),
				),
				ArtSource(
					SourcesIds.face,
					SourcesNames.FACE,
					SourcesPaths.FACE,
					"clip",
					listOf(ArtSourceLayer(SourcesKeys.MOUTH, SourcesNames.MOUTH, "", 0, 0, 8, 4, true)),
				),
			),
	)
}

/**
 * The Sources rig with the proposal's layer under a fresh drawable: a reload minted a tile and an untouched
 * birth quad for Brow before the match could take it, so accepting the proposal retires that tile.
 *
 * @return PuppetModel The rig.
 */
internal fun sourcesMergeModel(): PuppetModel {
	val base = sourcesFixtureModel()
	// The quad an import mints over the Brow layer's frame: 4 x 4 at (12, 22), sampling the whole tile.
	val birthQuad = DrawableMesh.withLocalEqualToCanvas(floatArrayOf(12f, 22f, 16f, 22f, 16f, 26f, 12f, 26f), floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f), intArrayOf(0, 1, 2, 0, 2, 3))
	val fresh = fixtureDrawable(SourcesIds.browMeshNew, SourcesNames.BROW_MESH_NEW, SourcesIds.browArtNew).copy(mesh = birthQuad)
	return base.copy(
		drawables = base.drawables + fresh,
		rootChildren = base.rootChildren + OrgChild.Drawable(fresh.id),
		atlas =
			base.atlas.copy(
				tiles = base.atlas.tiles + AtlasTile(SourcesIds.browArtNew, SourcesNames.BROW_ART_NEW, 4, 4, source = SourceLayerRef(SourcesIds.body, SourcesKeys.BROW, true)),
			),
	)
}

/**
 * A drawable over a tile, with no mesh and no keys: the table reads only its name and the tile it samples.
 *
 * @param DrawableId id The drawable's id.
 * @param String name Its display name.
 * @param AtlasTileId tileId The tile it samples.
 * @return Drawable The drawable.
 */
private fun fixtureDrawable(id: DrawableId, name: String, tileId: AtlasTileId): Drawable =
	Drawable(
		id = id,
		name = name,
		parentDeformerId = null,
		blendMode = BlendMode.Normal,
		maskedBy = emptyList(),
		mesh = null,
		geometryGrid = null,
		atlasTileId = tileId,
	)

/**
 * Source art for every tile of the rig, so a rested hover on a tile or a bound layer pops a preview.
 *
 * @return SourceArtRasters The store, handing out one raster per tile for its whole life.
 */
internal fun sourcesFixtureArt(): SourceArtRasters {
	val tileIds =
		listOf(SourcesIds.hairArt, SourcesIds.eyeArt, SourcesIds.browArtLeft, SourcesIds.browArtRight, SourcesIds.browArtNew, SourcesIds.mouthArt, SourcesIds.looseArt)
	val rasterByTile = tileIds.associateWith { DecodedImage(ByteArray(STUB_RASTER_EDGE * STUB_RASTER_EDGE * 4) { STUB_RASTER_VALUE }, STUB_RASTER_EDGE, STUB_RASTER_EDGE) }
	return SourceArtRasters { tileId -> rasterByTile[tileId] }
}

/**
 * Mounts the panel and the Sources space over the Sources rig, with Face.clip gone from the disk and the
 * proposal for the lost Brow layer published.
 *
 * @param PuppetModel model The rig the session opens on.
 * @param DrawableThumbnailProvider? thumbnails The art a drawable row previews, or null for none.
 * @param SourceArtRasters? sourceArt The art a tile or a layer row previews, or null for none.
 * @param Dp height The Sources space's height; a short one makes its list scroll.
 * @param Boolean provideDocument Whether the composition gets an open document at all.
 * @param Function onSourcePresenceAsked Told the path each time the file-presence probe is asked, as it is asked.
 * @param CompletableDeferred? presenceGate Holds the probe's first answers back until it completes, or null.
 * @return ParametersPanelHarness The mounted harness.
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.mountSources(
	model: PuppetModel = sourcesFixtureModel(),
	thumbnails: DrawableThumbnailProvider? = null,
	sourceArt: SourceArtRasters? = null,
	height: Dp = PANEL_SOURCES_HEIGHT,
	provideDocument: Boolean = true,
	onSourcePresenceAsked: (path: String) -> Unit = {},
	presenceGate: CompletableDeferred<Unit>? = null,
): ParametersPanelHarness {
	val harness =
		ParametersPanelHarness(
			showSources = true,
			thumbnails = thumbnails,
			sourceArt = sourceArt,
			model = model,
			provideDocument = provideDocument,
			onSourcePresenceAsked = onSourcePresenceAsked,
		)
	harness.sourcesSize = DpSize(PANEL_SOURCES_WIDTH, height)
	harness.sourcePresenceByPath[SourcesPaths.FACE] = false
	harness.sourcePresenceGate = presenceGate
	harness.publishedSuggestions.value =
		mapOf((SourcesIds.body to SourcesKeys.BROW_OLD) to LayerMatch(SourcesKeys.BROW, PROPOSAL_SCORE, MatchSignals(1f, 1f, 1f, 1f, null, hashEqual = false)))
	mountParametersPanel(harness)
	return harness
}

/** The Sources space's view state, the same instance its body and header read. */
internal val ParametersPanelHarness.sourcesViewState: SourcesViewState
	get() = sourcesScope.spaceState(SOURCES_VIEW_STATE_KEY) { SourcesViewState() }

/** The drawables the session's selection holds now. */
internal val ParametersPanelHarness.selectedDrawables: Set<DrawableId>
	get() = session.selection.value.targets.mapNotNullTo(HashSet()) { target -> (target as? SelectionTarget.Drawable)?.id }

/**
 * A tile of the session's current model.
 *
 * @param ParametersPanelHarness harness The mounted harness.
 * @param AtlasTileId id The tile.
 * @return AtlasTile? The tile as the model holds it now, or null when the model holds none.
 */
internal fun tileOf(harness: ParametersPanelHarness, id: AtlasTileId): AtlasTile? = harness.session.model.value.atlas.tileById[id]

/**
 * Opens the row [rowKey] through the view state, which is what a press on its chevron does.
 *
 * @param ParametersPanelHarness harness The mounted harness.
 * @param String rowKey The row's node id.
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.openRow(harness: ParametersPanelHarness, rowKey: String) {
	runOnIdle { harness.sourcesViewState.expanded[rowKey] = true }
	waitForIdle()
}

/**
 * Restricts a matcher to the Sources box and keeps it out of every popup: the panel beside it shows names
 * of its own, and a relink menu lists the layers again.
 *
 * @return SemanticsMatcher The matcher.
 */
private fun inSources(): SemanticsMatcher = hasAnyAncestor(hasTestTag(PANEL_SOURCES_TAG)) and !hasAnyAncestor(isPopup())

/**
 * The Sources box, in the panel body's pixels.
 *
 * @return ControlBox The box.
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.sourcesBox(): ControlBox = panelBoundsOf(onNodeWithTag(PANEL_SOURCES_TAG))

/**
 * The bounds of the table's node showing exactly [text], in the panel body's pixels.
 *
 * @param String text The text.
 * @return ControlBox The node's bounds.
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.sourcesBoundsOfText(text: String): ControlBox = panelBoundsOf(onNode(hasText(text) and inSources(), useUnmergedTree = true))

/**
 * Whether the table lists a row showing exactly [text], on screen or composed just off it.
 *
 * @param String text The text.
 * @return Boolean True when a row shows it.
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.sourcesShows(text: String): Boolean = onAllNodes(hasText(text) and inSources(), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

/**
 * Whether the table shows a row with exactly [text] inside its visible bounds.
 *
 * @param String text The text.
 * @return Boolean True when the row is listed and on screen.
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.sourcesDisplays(text: String): Boolean = sourcesShows(text) && onNode(hasText(text) and inSources(), useUnmergedTree = true).isDisplayed()

/**
 * The full-width band of the row labelled [name], in the panel body's pixels: the table's width at the
 * label's height.  The band's center lies on the row's own surface, clear of its chevron and its chip.
 *
 * @param String name The row's label.
 * @return ControlBox The row's band.
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.sourcesRowBox(name: String): ControlBox {
	val label = sourcesBoundsOfText(name)
	val table = sourcesBox()
	val halfRow = with(density) { FIXTURE_ROW_HEIGHT.toPx() / 2f }
	return ControlBox(left = table.left, top = label.center.y - halfRow, right = table.right, bottom = label.center.y + halfRow)
}

/**
 * Whether the row labelled [rowName] carries a slot with the accessible name [description]: its chevron,
 * its status glyph, or its chip.
 *
 * @param String description The slot's accessible name.
 * @param String rowName The row's label.
 * @return Boolean True when the row carries one.
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.sourcesRowHasSlot(description: String, rowName: String): Boolean = sourcesSlotBoxOrNull(description, rowName) != null

/**
 * The bounds of the slot carrying the accessible name [description] on the row labelled [rowName].
 *
 * @param String description The slot's accessible name.
 * @param String rowName The row's label.
 * @return ControlBox The slot's bounds, in the panel body's pixels.
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.sourcesSlotBox(description: String, rowName: String): ControlBox =
	sourcesSlotBoxOrNull(description, rowName) ?: error("no slot named $description on the row $rowName")

/**
 * The center of the slot carrying the accessible name [description] on the row labelled [rowName].
 *
 * @param String description The slot's accessible name.
 * @param String rowName The row's label.
 * @return Offset The slot's center, in the panel body's pixels.
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.sourcesSlotPoint(description: String, rowName: String): Offset = sourcesSlotBox(description, rowName).center

/**
 * The bounds of a row's slot, or null when the row carries none by that name.
 *
 * @param String description The slot's accessible name.
 * @param String rowName The row's label.
 * @return ControlBox? The slot's bounds, or null.
 */
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.sourcesSlotBoxOrNull(description: String, rowName: String): ControlBox? {
	val row = sourcesRowBox(rowName)
	val slots = onAllNodes(hasContentDescription(description) and inSources(), useUnmergedTree = true)
	val count = slots.fetchSemanticsNodes().size
	for (slotIndex in 0 until count) {
		val bounds = panelBoundsOf(slots[slotIndex])
		if (bounds.center.y >= row.top && bounds.center.y < row.bottom) {
			return bounds
		}
	}
	return null
}

/**
 * Rests the pointer at [point] until a row's preview has had its time to pop.  The preview waits out a
 * short rest before it shows, and nothing else in the table asks for the frames that would carry the
 * clock past it.
 *
 * @param Offset point Where to rest, in the panel body's pixels.
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.restAt(point: Offset) {
	hoverAt(point)
	mainClock.advanceTimeBy(HOVER_REST_MILLIS)
	waitForIdle()
}

/**
 * Closes whichever menu is open by a press outside it, on the bare table under the last row.
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.pressOutsideTheMenu() {
	val table = sourcesBox()
	val inset = with(density) { BARE_TABLE_INSET.toPx() }
	clickAt(Offset(table.left + inset, table.bottom - inset))
}

/**
 * Types into the search box of whichever menu is open.
 *
 * @param String text The search text.
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.typeIntoMenuSearch(text: String) {
	onNode(hasSetTextAction() and hasAnyAncestor(isPopup())).performTextReplacement(text)
	waitForIdle()
}

/**
 * Whether the search box of the open menu holds exactly [text].
 *
 * @param String text The search text to look for.
 * @return Boolean True when the open menu's search box shows it.
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.menuSearchShows(text: String): Boolean =
	onAllNodes(hasSetTextAction() and hasText(text) and hasAnyAncestor(isPopup())).fetchSemanticsNodes().isNotEmpty()

/** How far inside the table's bottom left corner a press lands on bare table, clear of every row and menu. */
private val BARE_TABLE_INSET: Dp = 12.dp

/** The height of a table row, which the fixture measures a row's band by. */
private val FIXTURE_ROW_HEIGHT: Dp = 22.dp

/** The confidence of the published proposal, which the Accept entry shows as 92%. */
private const val PROPOSAL_SCORE = 0.92f

/** Longer than the rest a row's preview waits out before it shows. */
private const val HOVER_REST_MILLIS = 100L

/** The edge of the stub source art, in pixels. */
private const val STUB_RASTER_EDGE = 4

/** The value every channel of the stub source art holds: opaque white. */
private const val STUB_RASTER_VALUE: Byte = -1