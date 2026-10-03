package org.umamo.ui.workspace.spaces.parameters

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import org.umamo.edit.EditorMode
import org.umamo.edit.EditorSession
import org.umamo.edit.Selection
import org.umamo.edit.SelectionTarget
import org.umamo.edit.deleteTile
import org.umamo.edit.setLayerIgnored
import org.umamo.edit.setTileSources
import org.umamo.reimport.LayerMatch
import org.umamo.render.SourceArtRasters
import org.umamo.runtime.model.ArtSourceId
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.MeshDeltaForm
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterGroupId
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.ParameterKind
import org.umamo.runtime.model.ParameterLink
import org.umamo.runtime.model.ParameterNode
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.RuntimeTarget
import org.umamo.settings.Settings
import org.umamo.ui.LocalSettings
import org.umamo.ui.action.Command
import org.umamo.ui.action.CommandRegistry
import org.umamo.ui.action.Keymap
import org.umamo.ui.action.LocalCommands
import org.umamo.ui.action.LocalKeymap
import org.umamo.ui.action.parseKeyChord
import org.umamo.ui.kit.container.OverflowRow
import org.umamo.ui.kit.textentry.InlineEditController
import org.umamo.ui.kit.textentry.LocalInlineEditController
import org.umamo.ui.model.DrawableThumbnailProvider
import org.umamo.ui.model.LocalDrawableThumbnails
import org.umamo.ui.model.LocalEditorSession
import org.umamo.ui.model.LocalLiveParams
import org.umamo.ui.model.LocalPuppet
import org.umamo.ui.model.LocalSelection
import org.umamo.ui.model.LocalSourceArtRasters
import org.umamo.ui.model.artwork.LocalSourceFilePresence
import org.umamo.ui.model.artwork.LocalSourceSuggestions
import org.umamo.ui.model.artwork.LocalSourceWatch
import org.umamo.ui.model.artwork.SourceFilePresence
import org.umamo.ui.model.artwork.SourceSuggestionState
import org.umamo.ui.model.artwork.SourceWatchState
import org.umamo.ui.model.rememberSessionEditorState
import org.umamo.ui.resources.*
import org.umamo.ui.theme.UmamoTheme
import org.umamo.ui.viewport.LiveParams
import org.umamo.ui.viewport.LiveParamsAdapter
import org.umamo.ui.viewport.initialLiveParams
import org.umamo.ui.workspace.AreaScope
import org.umamo.ui.workspace.HoveredSurface
import org.umamo.ui.workspace.KeyableHover
import org.umamo.ui.workspace.KeyformSheetViews
import org.umamo.ui.workspace.LocalKeyableHover
import org.umamo.ui.workspace.LocalKeyformSheetViews
import org.umamo.ui.workspace.LocalRelationPick
import org.umamo.ui.workspace.RelationPickController
import org.umamo.ui.workspace.ShellOverlayState
import org.umamo.ui.workspace.SpaceKind
import org.umamo.ui.workspace.area.AreaDragController
import org.umamo.ui.workspace.area.SplitterDragCancelController
import org.umamo.ui.workspace.commands.ArtworkOperations
import org.umamo.ui.workspace.commands.CommandRouting
import org.umamo.ui.workspace.commands.DeleteArtRequest
import org.umamo.ui.workspace.commands.IgnoreLayerRequest
import org.umamo.ui.workspace.commands.RelinkRequest
import org.umamo.ui.workspace.commands.ReloadScope
import org.umamo.ui.workspace.commands.ReplaceRequest
import org.umamo.ui.workspace.commands.SessionAvailability
import org.umamo.ui.workspace.commands.chromeCommands
import org.umamo.ui.workspace.commands.fileArtworkCommands
import org.umamo.ui.workspace.commands.inMemorySettings
import org.umamo.ui.workspace.commands.keyformCommands
import org.umamo.ui.workspace.commands.objectCommands
import org.umamo.ui.workspace.commands.registerAll
import org.umamo.ui.workspace.commands.selectCommands
import org.umamo.ui.workspace.layout.WorkspaceLayoutController
import org.umamo.ui.workspace.layout.defaultLayout
import org.umamo.ui.workspace.rowdrag.LocalRowDragCancel
import org.umamo.ui.workspace.rowdrag.RowDragCancelController
import org.umamo.ui.workspace.shell.ShellModalState
import org.umamo.ui.workspace.shell.handleModalKeyLadder
import org.umamo.ui.workspace.shell.releaseTextEntryOnPress
import org.umamo.ui.workspace.shell.toShellKeyStroke
import org.umamo.ui.workspace.spaces.keyformsheet.KeyformSheetSpace
import org.umamo.ui.workspace.spaces.outliner.OutlinerSpace
import org.umamo.ui.workspace.spaces.sources.SourcesSpace
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/*
 * What the Parameters panel's composition tests share: one small rig that has every row kind the panel
 * draws, a miniature shell to mount the panel under, and the geometry that finds the two controls with
 * no semantics of their own (the slider and the pad are each a bare Canvas).
 *
 * The rig, in panel order:
 *
 *   Group "Face" (open)      Eye Open, Smile Shape, Smile (a blend-shape parameter)
 *   Pad                      Angle X over Angle Y, linked
 *   Slider                   Body X, whose link candidate is Breath
 *   Slider                   Breath, the last row of its run, so it has no candidate
 *   (no row)                 Fixed, whose range is empty
 *   Group "Body" (closed)    Arm
 *
 * Every row starts on a value no other row shows, so a row's number field is found by its text.
 */

/** The ids of the fixture rig's parameters, groups, and its one drawable. */
internal object PanelIds {
	val eyeOpen = ParameterId("ParamEyeOpen")
	val smileShape = ParameterId("ParamMouthForm")
	val smile = ParameterId("ParamSmileBlend")
	val angleX = ParameterId("ParamAngleX")
	val angleY = ParameterId("ParamAngleY")
	val bodyX = ParameterId("ParamBodyX")
	val breath = ParameterId("ParamBreath")
	val fixed = ParameterId("ParamFixed")
	val arm = ParameterId("ParamArm")
	val face = ParameterGroupId("GroupFace")
	val body = ParameterGroupId("GroupBody")
	val drawable = DrawableId("a")
}

/** The display names of the fixture rig's rows, which is what a test finds them by. */
internal object PanelNames {
	const val EYE_OPEN = "Eye Open"
	const val SMILE_SHAPE = "Smile Shape"
	const val SMILE = "Smile"
	const val ANGLE_X = "Angle X"
	const val ANGLE_Y = "Angle Y"
	const val BODY_X = "Body X"
	const val BREATH = "Breath"
	const val ARM = "Arm"
	const val FACE = "Face"
	const val BODY = "Body"
}

/** Where each row sits in the list as the fixture opens, which is how a row's grip is found. */
internal object PanelRows {
	const val FACE = 0
	const val EYE_OPEN = 1
	const val SMILE_SHAPE = 2
	const val SMILE = 3
	const val ANGLE_PAD = 4
	const val BODY_X = 5
	const val BREATH = 6
	const val BODY = 7

	/** How many rows the list shows as the fixture opens. */
	const val COUNT = 8
}

/** The text each row's number field opens on. */
internal object PanelValues {
	const val EYE_OPEN = "1.00"
	const val SMILE_SHAPE = "0.50"
	const val SMILE = "0.75"
	const val ANGLE_X = "3.00"
	const val ANGLE_Y = "-4.00"
	const val BODY_X = "2.00"
	const val BREATH = "0.25"
}

/** The pose the fixture opens on: a distinct value per row, with Eye Open alone sitting on its default. */
internal val PANEL_FIXTURE_POSE: Map<ParameterId, Float> =
	mapOf(
		PanelIds.eyeOpen to 1f,
		PanelIds.smileShape to 0.5f,
		PanelIds.smile to 0.75f,
		PanelIds.angleX to 3f,
		PanelIds.angleY to -4f,
		PanelIds.bodyX to 2f,
		PanelIds.breath to 0.25f,
		PanelIds.fixed to 0f,
		PanelIds.arm to 0f,
	)

/**
 * The fixture rig.
 *
 * @param RuntimeTarget runtimeTarget The runtime the rig targets, which decides whether a blend-shape
 *   parameter may be created.
 * @return PuppetModel The rig.
 */
internal fun panelFixtureModel(runtimeTarget: RuntimeTarget = RuntimeTarget.NoTarget): PuppetModel {
	val parameters =
		listOf(
			Parameter(PanelIds.eyeOpen, PanelNames.EYE_OPEN, min = 0f, max = 1f, default = 1f),
			Parameter(PanelIds.smileShape, PanelNames.SMILE_SHAPE, min = -1f, max = 1f, default = 0f),
			Parameter(PanelIds.smile, PanelNames.SMILE, min = 0f, max = 1f, default = 0f, kind = ParameterKind.BLEND_SHAPE),
			Parameter(PanelIds.angleX, PanelNames.ANGLE_X, min = -30f, max = 30f, default = 0f),
			Parameter(PanelIds.angleY, PanelNames.ANGLE_Y, min = -30f, max = 30f, default = 0f),
			Parameter(PanelIds.bodyX, PanelNames.BODY_X, min = -10f, max = 10f, default = 0f),
			Parameter(PanelIds.breath, PanelNames.BREATH, min = 0f, max = 1f, default = 0f),
			Parameter(PanelIds.fixed, "Fixed", min = 0f, max = 0f, default = 0f),
			Parameter(PanelIds.arm, PanelNames.ARM, min = -1f, max = 1f, default = 0f),
		)
	// Keyed on Body X, so the selected-object filter has exactly one parameter to keep.
	val drawable =
		Drawable(
			id = PanelIds.drawable,
			name = "a",
			parentDeformerId = null,
			blendMode = BlendMode.Normal,
			maskedBy = emptyList(),
			mesh = DrawableMesh.withLocalEqualToCanvas(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f), floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f), intArrayOf(0, 1, 2)),
			geometryGrid =
				KeyformGrid(
					listOf(KeyformAxis(PanelIds.bodyX, floatArrayOf(0f))),
					listOf(KeyformCell(intArrayOf(0), MeshDeltaForm(FloatArray(6)))),
				),
		)
	return PuppetModel(
		parameters = parameters,
		parts = emptyList(),
		deformers = emptyList(),
		drawables = listOf(drawable),
		rootChildren = emptyList(),
		rootPartId = null,
		parameterLinks = listOf(ParameterLink(PanelIds.angleX, PanelIds.angleY)),
		parameterTree =
			listOf(
				ParameterNode.Group(
					PanelIds.face,
					PanelNames.FACE,
					initiallyOpen = true,
					children =
						listOf(
							ParameterNode.Param(PanelIds.eyeOpen),
							ParameterNode.Param(PanelIds.smileShape),
							ParameterNode.Param(PanelIds.smile),
						),
				),
				ParameterNode.Param(PanelIds.angleX),
				ParameterNode.Param(PanelIds.angleY),
				ParameterNode.Param(PanelIds.bodyX),
				ParameterNode.Param(PanelIds.breath),
				ParameterNode.Param(PanelIds.fixed),
				ParameterNode.Group(
					PanelIds.body,
					PanelNames.BODY,
					initiallyOpen = false,
					children = listOf(ParameterNode.Param(PanelIds.arm)),
				),
			),
		runtimeTarget = runtimeTarget,
	)
}

/**
 * The panel's localized chrome, resolved inside the composition so a test finds a node by the same string
 * the panel draws.
 *
 * @property String reset The reset glyph's accessible name.
 * @property String rangeToggle The range chevron's accessible name.
 * @property String link The link glyph's accessible name.
 * @property String unlink The unlink glyph's accessible name.
 * @property String reorderHandle The grip's accessible name.
 * @property String newGroup The New Group entry, which is also the header button's accessible name.
 * @property String defaultGroupName The name a created group starts with.
 * @property String rename The Rename entry.
 * @property String deleteGroup The Delete Group entry.
 * @property String addParameter The header's Add Parameter chip.
 * @property String addKeyFormParameter The Add Key Form Parameter entry.
 * @property String addBlendShapeParameter The Add Blend Shape Parameter entry.
 * @property String deleteParameter The Delete Parameter entry.
 * @property String defaultParameterName The name a created parameter starts with.
 * @property String resetAll The header's Reset All button.
 * @property String filters The header's filter chip.
 * @property String filterSelected The selected-object filter's checkbox.
 * @property String rangeMinimum The range editor's minimum caption.
 * @property String rangeDefault The range editor's default caption.
 * @property String rangeMaximum The range editor's maximum caption.
 * @property String more The overflow chip a narrow header collapses into.
 * @property String trackGeometry The keyform sheet's label for a geometry track.
 * @property String keyformInsert The keyform sheet's Insert Key lane entry.
 * @property String keyformDelete The keyform sheet's Delete Key lane entry.
 * @property String selectDrawable The keyform sheet's label entry that selects a drawable.
 * @property String selectPart The keyform sheet's label entry that selects a part.
 * @property String sheetNoTracks The keyform sheet's notice for a parameter nothing is keyed on.
 * @property String sheetAllFiltered The keyform sheet's notice for tracks the filters hide.
 * @property String outlinerRoot The outliner's puppet root row.
 * @property String outlinerArmature The outliner's deformer-hierarchy row.
 * @property String expand The accessible name of a closed branch's chevron in the outliner.
 * @property String collapse The accessible name of an open branch's chevron in the outliner.
 * @property String selectHierarchy The outliner's Select Hierarchy entry.
 * @property String toggleVisibility The outliner's Toggle Visibility entry, which also names a row's eye.
 * @property String toggleSelectable The outliner's Toggle Selectability entry, which also names a row's pointer.
 * @property String outlinerRename The outliner's Rename entry.
 * @property String outlinerDelete The outliner's Delete entry.
 * @property String deleteHierarchy The outliner's Delete Hierarchy entry.
 * @property String sourcesUnboundArt The Sources space's row for art bound to no layer.
 * @property String sourcesFileMenu The accessible name of a file row's actions chip.
 * @property String sourcesFileReplace The Sources space's Replace Artwork entry.
 * @property String sourcesFileReload The Sources space's Reload This File entry.
 * @property String sourcesLayerMenu The accessible name of an unbound layer row's actions chip.
 * @property String sourcesLayerIgnore The Sources space's Ignore Layer entry.
 * @property String sourcesLayerUnignore The Sources space's Stop Ignoring entry.
 * @property String sourcesReview The accessible name of a review row's chip.
 * @property String sourcesAccept The review chip's Accept entry, for the Sources rig's proposal.
 * @property String sourcesAcceptMerge The review chip's Accept entry when accepting retires a fresh drawable.
 * @property String sourcesRelinkByHand The review chip's entry that opens the relink list.
 * @property String sourcesLeave The review chip's entry that leaves the binding as it is.
 * @property String sourcesRelink The accessible name of a tile row's relink chip.
 * @property String sourcesUnbind The relink list's Unbind entry.
 * @property String sourcesDeleteArt The relink list's Delete Art entry.
 * @property String sourcesNoMatches The relink list's line for a search that matches nothing.
 * @property String sourcesPresent The status of a file that is where the document read it.
 * @property String sourcesMissing The status of a file that is gone.
 * @property String sourcesUnknown The status of a file nothing could check.
 * @property String sourcesBound The status of a layer bound by a stable key.
 * @property String sourcesBoundByName The status of a layer bound by its name and place.
 * @property String sourcesUnbound The status of a layer no tile binds.
 * @property String sourcesUnplaced The status of a tile on no page.
 * @property String sourcesNeedsReview The status of a binding whose layer the file lost.
 * @property String sourcesIgnored The status of a layer kept out of the rig.
 */
internal class PanelText(
	val reset: String,
	val rangeToggle: String,
	val link: String,
	val unlink: String,
	val reorderHandle: String,
	val newGroup: String,
	val defaultGroupName: String,
	val rename: String,
	val deleteGroup: String,
	val addParameter: String,
	val addKeyFormParameter: String,
	val addBlendShapeParameter: String,
	val deleteParameter: String,
	val defaultParameterName: String,
	val resetAll: String,
	val filters: String,
	val filterSelected: String,
	val rangeMinimum: String,
	val rangeDefault: String,
	val rangeMaximum: String,
	val more: String,
	val trackGeometry: String,
	val keyformInsert: String,
	val keyformDelete: String,
	val selectDrawable: String,
	val selectPart: String,
	val sheetNoTracks: String,
	val sheetAllFiltered: String,
	val outlinerRoot: String,
	val outlinerArmature: String,
	val expand: String,
	val collapse: String,
	val selectHierarchy: String,
	val toggleVisibility: String,
	val toggleSelectable: String,
	val outlinerRename: String,
	val outlinerDelete: String,
	val deleteHierarchy: String,
	val sourcesUnboundArt: String,
	val sourcesFileMenu: String,
	val sourcesFileReplace: String,
	val sourcesFileReload: String,
	val sourcesLayerMenu: String,
	val sourcesLayerIgnore: String,
	val sourcesLayerUnignore: String,
	val sourcesReview: String,
	val sourcesAccept: String,
	val sourcesAcceptMerge: String,
	val sourcesRelinkByHand: String,
	val sourcesLeave: String,
	val sourcesRelink: String,
	val sourcesUnbind: String,
	val sourcesDeleteArt: String,
	val sourcesNoMatches: String,
	val sourcesPresent: String,
	val sourcesMissing: String,
	val sourcesUnknown: String,
	val sourcesBound: String,
	val sourcesBoundByName: String,
	val sourcesUnbound: String,
	val sourcesUnplaced: String,
	val sourcesNeedsReview: String,
	val sourcesIgnored: String,
)

/**
 * What the Sources space's commands were asked to do, in the order the requests landed.
 *
 * @property MutableList relinks  Every relink request.
 * @property MutableList replaces Every Replace Artwork request.
 * @property MutableList reloads  Every reload's scope; null for a reload of every file.
 * @property MutableList deletes  Every Delete Art request.
 * @property MutableList ignores  Every ignore toggle.
 */
internal class ArtworkRequests(
	val relinks: MutableList<RelinkRequest> = ArrayList(),
	val replaces: MutableList<ReplaceRequest> = ArrayList(),
	val reloads: MutableList<ReloadScope?> = ArrayList(),
	val deletes: MutableList<DeleteArtRequest> = ArrayList(),
	val ignores: MutableList<IgnoreLayerRequest> = ArrayList(),
)

/**
 * The state one case shares between its composition and its assertions.
 *
 * @property Boolean provideLiveParams Whether the composition gets a live-parameter handle at all.
 * @property Boolean provideSession Whether the composition gets an editing session at all.  A rig with no
 *   document gets none whatever this says: a session is its document's, and the app provides the two together.
 * @property Boolean showHeader Whether the panel's header strip is mounted above the body.
 * @property Boolean provideDocument Whether the composition gets an open document at all.
 * @property Boolean showKeyformSheet Whether a keyform sheet is mounted beside the panel, over the same
 *   session and the same pose hand-off.  The sheet gets what the shell gives it: the keyable hover, the
 *   open-sheet registry, and the keyform and select commands, routed as though the pointer were over it.
 * @property Boolean showOutliner Whether an outliner is mounted beside the panel, over the same session.  It
 *   gets what the shell gives it: a relation pick controller, a thumbnail provider, and the object commands.
 * @property DrawableThumbnailProvider? thumbnails The art the outliner previews beside a rested-on row, or null
 *   for none, which is what a document without rendered art gives it.
 * @property PuppetModel model The rig the session opens on.
 * @property Boolean showSources Whether a Sources space is mounted beside the panel, over the same session.
 *   It gets what the app gives it: the file-presence probe, the watcher's state, the published proposals,
 *   and the artwork commands, whose requests are recorded.
 * @property SourceArtRasters? sourceArt The document's source-art pixels, which a Sources tile row previews,
 *   or null for a document that holds none.
 * @property Function onSourcePresenceAsked Told the path each time the file-presence probe is asked, as it is asked.
 */
internal class ParametersPanelHarness(
	runtimeTarget: RuntimeTarget = RuntimeTarget.NoTarget,
	val provideLiveParams: Boolean = true,
	val provideSession: Boolean = true,
	val showHeader: Boolean = false,
	val provideDocument: Boolean = true,
	val showKeyformSheet: Boolean = false,
	val showOutliner: Boolean = false,
	val thumbnails: DrawableThumbnailProvider? = null,
	val model: PuppetModel = panelFixtureModel(runtimeTarget),
	val showSources: Boolean = false,
	val sourceArt: SourceArtRasters? = null,
	val onSourcePresenceAsked: (path: String) -> Unit = {},
) {
	val session = EditorSession(model, PANEL_FIXTURE_POSE)
	val liveParams: LiveParams = initialLiveParams(session.model.value, PANEL_FIXTURE_POSE)
	val liveParamsHandle = LiveParamsAdapter(liveParams, session)
	val scope = AreaScope(PANEL_AREA_ID)
	val sheetScope = AreaScope(PANEL_SHEET_AREA_ID)
	val outlinerScope = AreaScope(PANEL_OUTLINER_AREA_ID)
	val inlineEditController = InlineEditController()
	val rowDragCancel = RowDragCancelController()
	val overlays = ShellOverlayState()
	val registry = CommandRegistry()
	val keymap = Keymap(mapOf(parseKeyChord("primary+KeyS")!! to PANEL_SHORTCUT_COMMAND))
	val rootFocus = FocusRequester()
	val keyableHover = KeyableHover()
	val keyformSheetViews = KeyformSheetViews()
	val relationPick = RelationPickController()
	val sourcesScope = AreaScope(PANEL_SOURCES_AREA_ID)
	val settings: Settings = inMemorySettings()

	/**
	 * What the file-presence probe answers per path: false for a file that is gone, null for one nothing can
	 * check.  A path with no entry is present.
	 */
	val sourcePresenceByPath: MutableMap<String, Boolean?> = HashMap()

	/**
	 * Holds every answer of the probe back until it completes, or null to answer at once.  A probe held
	 * here has taken its answer already and gives it however long it waits, even once the round that asked
	 * was overtaken: a look at the disk that is under way finishes whatever became of whoever asked.
	 */
	var sourcePresenceGate: CompletableDeferred<Unit>? = null

	/** The probe itself.  One instance for the harness's life: the Sources space keys its asking on it. */
	val sourcePresence: SourceFilePresence = { path ->
		onSourcePresenceAsked(path)
		val answer = if (sourcePresenceByPath.containsKey(path)) sourcePresenceByPath[path] else true
		val gate = sourcePresenceGate
		if (gate != null) {
			withContext(NonCancellable) { gate.await() }
		}
		answer
	}

	/** The files the watcher reports changed on disk. */
	val sourceWatchPending = MutableStateFlow<Set<ArtSourceId>>(emptySet())

	/** The watcher's presence serial; a bump makes the Sources space probe again. */
	val sourceWatchSerial = MutableStateFlow(0)

	/** The proposals the last operation that read a file published, by file and lost layer key. */
	val publishedSuggestions = MutableStateFlow<Map<Pair<ArtSourceId, String>, LayerMatch>>(emptyMap())
	val sourceWatch = SourceWatchState(sourceWatchPending, sourceWatchSerial)
	val sourceSuggestions = SourceSuggestionState(publishedSuggestions)

	/** Every request the Sources space's commands were handed. */
	val artworkRequests = ArtworkRequests()

	/**
	 * Whether a recorded request also lands on the session, as the binding-only change the app makes when it
	 * cannot read the layer's file.  On, so the table shows what a relink, a delete, or an ignore did.
	 */
	var applyArtworkEdits = true

	/** The app's artwork orchestrations, standing in for the ones that read files: each records, then applies. */
	val artwork =
		ArtworkOperations(
			importArtwork = { _, _ -> },
			reloadArtwork = { _, scope -> artworkRequests.reloads.add(scope) },
			relinkArtwork = { request, _ ->
				artworkRequests.relinks.add(request)
				if (applyArtworkEdits) {
					session.setTileSources(request.tileIds, request.ref)
				}
			},
			matchArtwork = { _ -> },
			replaceArtwork = { request, _ -> artworkRequests.replaces.add(request) },
			deleteArt = { request ->
				artworkRequests.deletes.add(request)
				if (applyArtworkEdits) {
					session.deleteTile(request.tileId)
				}
			},
			ignoreLayer = { request ->
				artworkRequests.ignores.add(request)
				if (applyArtworkEdits) {
					session.setLayerIgnored(request.ref, request.ignored)
				}
			},
			canReload = { true },
		)

	/** The size the keyform sheet is laid out at, when one is mounted. */
	var sheetSize by mutableStateOf(DpSize(PANEL_SHEET_WIDTH, PANEL_SHEET_HEIGHT))

	/** The size the outliner is laid out at, when one is mounted; a short one makes its list scroll. */
	var outlinerSize by mutableStateOf(DpSize(PANEL_OUTLINER_WIDTH, PANEL_OUTLINER_HEIGHT))

	/** The size the Sources space is laid out at, when one is mounted; a short one makes its list scroll. */
	var sourcesSize by mutableStateOf(DpSize(PANEL_SOURCES_WIDTH, PANEL_SOURCES_HEIGHT))

	/** Whether the root itself holds focus, so a focus left null can be told from one a field took. */
	var rootFocused by mutableStateOf(false)

	/** How many times the bound chord reached the keymap, which is what "shortcuts work" means here. */
	var shortcutRuns = 0

	/** The size the panel body is laid out at; a short one makes the list scroll. */
	var panelSize by mutableStateOf(DpSize(PANEL_WIDTH, PANEL_HEIGHT))

	/** The width the header strip is laid out at; a narrow one collapses its chips into the overflow. */
	var headerWidth by mutableStateOf(PANEL_WIDTH)

	/** The panel's localized chrome, available once the panel is mounted. */
	lateinit var text: PanelText

	/** The panel's view state, the same instance the header and the body read. */
	val viewState: ParametersViewState get() = scope.spaceState(PARAMETERS_VIEW_STATE_KEY) { ParametersViewState() }

	/** The undo stack's live position; one more than before means exactly one step was recorded. */
	val historyCursor: Int get() = session.historyView.value.cursor

	/**
	 * The committed value of a parameter.
	 *
	 * @param ParameterId id The parameter.
	 * @return Float? Its value in the session's pose, or null when the pose does not name it.
	 */
	fun committed(id: ParameterId): Float? = session.pose.value[id]

	/**
	 * The value the renderer would draw a parameter at, previews included.
	 *
	 * @param ParameterId id The parameter.
	 * @return Float? Its value in the live hand-off, or null when the hand-off does not name it.
	 */
	fun live(id: ParameterId): Float? = liveParams.values[id]

	/**
	 * Puts the session in Edit mode, which it refuses with no mesh selected.
	 */
	fun enterEditMode() {
		val target = SelectionTarget.Drawable(PanelIds.drawable)
		session.setSelection(Selection(setOf(target), target))
		session.setMode(EditorMode.Edit)
		assertEquals(EditorMode.Edit, session.mode.value, "the fixture must really be in Edit mode")
	}

	/**
	 * Returns the session to Object mode.
	 */
	fun leaveEditMode() {
		session.setMode(EditorMode.Object)
		assertEquals(EditorMode.Object, session.mode.value, "the fixture must really be back in Object mode")
	}
}

/**
 * Mounts the panel under a root that behaves like the shell: it holds focus, the modal ladder previews
 * every key, and a press away from a live text editor hands focus back.
 *
 * @param ParametersPanelHarness harness The state this case shares with its composition.
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.mountParametersPanel(harness: ParametersPanelHarness) {
	harness.registry.registerAll(
		chromeCommands(
			harness.overlays,
			AreaDragController(),
			SplitterDragCancelController(),
			harness.rowDragCancel,
			WorkspaceLayoutController(defaultLayout()) {},
		) {} + listOf(Command(PANEL_SHORTCUT_COMMAND, title = null) { harness.shortcutRuns += 1 }),
	)
	if (harness.showKeyformSheet) {
		// Routed as though the pointer were over the sheet, which is where a sheet command is aimed from.
		val routing = CommandRouting { HoveredSurface(PANEL_SHEET_AREA_ID, SpaceKind.KeyformSheet) }
		val availability = SessionAvailability(harness.session)
		harness.registry.registerAll(
			keyformCommands(harness.session, { harness.keyableHover.hovered }, routing, harness.keyformSheetViews, availability) +
				selectCommands(harness.session, routing, harness.keyformSheetViews, availability),
		)
	}
	if (harness.showOutliner) {
		// The outliner's menu dispatches Select Hierarchy through the registry.  No selection handle: that
		// command reads the selection off the session.
		harness.registry.registerAll(objectCommands(harness.session, null, SessionAvailability(harness.session)))
	}
	if (harness.showSources) {
		// The shell's own table over recording orchestrations, routed as though the pointer were over the
		// Sources space, which is where its rows dispatch from.
		val routing = CommandRouting { HoveredSurface(PANEL_SOURCES_AREA_ID, SpaceKind.Sources) }
		harness.registry.registerAll(fileArtworkCommands(routing) { harness.artwork })
	}
	setContent {
		harness.text =
			PanelText(
				reset = stringResource(Res.string.parameter_reset),
				rangeToggle = stringResource(Res.string.parameter_range_section),
				link = stringResource(Res.string.parameter_link),
				unlink = stringResource(Res.string.parameter_unlink),
				reorderHandle = stringResource(Res.string.parameter_reorder_handle),
				newGroup = stringResource(Res.string.parameter_new_group),
				defaultGroupName = stringResource(Res.string.parameter_group_default_name),
				rename = stringResource(Res.string.parameter_menu_rename),
				deleteGroup = stringResource(Res.string.parameter_menu_delete_group),
				addParameter = stringResource(Res.string.parameter_menu_add),
				addKeyFormParameter = stringResource(Res.string.parameter_menu_add_keyform),
				addBlendShapeParameter = stringResource(Res.string.parameter_menu_add_blendshape),
				deleteParameter = stringResource(Res.string.parameter_menu_delete),
				defaultParameterName = stringResource(Res.string.parameter_default_name),
				resetAll = stringResource(Res.string.parameter_reset_all),
				filters = stringResource(Res.string.common_filters),
				filterSelected = stringResource(Res.string.parameter_filter_selected),
				rangeMinimum = stringResource(Res.string.parameter_range_min),
				rangeDefault = stringResource(Res.string.parameter_range_default),
				rangeMaximum = stringResource(Res.string.parameter_range_max),
				more = stringResource(Res.string.header_more),
				trackGeometry = stringResource(Res.string.track_geometry),
				keyformInsert = stringResource(Res.string.cmd_keyform_insert),
				keyformDelete = stringResource(Res.string.cmd_keyform_delete),
				selectDrawable = stringResource(Res.string.keyform_sheet_select_owner, stringResource(Res.string.owner_kind_drawable)),
				selectPart = stringResource(Res.string.keyform_sheet_select_owner, stringResource(Res.string.owner_kind_part)),
				sheetNoTracks = stringResource(Res.string.keyform_sheet_no_tracks),
				sheetAllFiltered = stringResource(Res.string.keyform_sheet_all_filtered),
				outlinerRoot = stringResource(Res.string.outliner_root),
				outlinerArmature = stringResource(Res.string.outliner_armature),
				expand = stringResource(Res.string.common_expand),
				collapse = stringResource(Res.string.common_collapse),
				selectHierarchy = stringResource(Res.string.outliner_menu_select_hierarchy),
				toggleVisibility = stringResource(Res.string.outliner_menu_visibility),
				toggleSelectable = stringResource(Res.string.outliner_menu_selectable),
				outlinerRename = stringResource(Res.string.outliner_menu_rename),
				outlinerDelete = stringResource(Res.string.outliner_menu_delete),
				deleteHierarchy = stringResource(Res.string.outliner_menu_delete_hierarchy),
				sourcesUnboundArt = stringResource(Res.string.sources_unbound_art),
				sourcesFileMenu = stringResource(Res.string.sources_file_menu),
				sourcesFileReplace = stringResource(Res.string.sources_file_menu_replace),
				sourcesFileReload = stringResource(Res.string.sources_file_menu_reload),
				sourcesLayerMenu = stringResource(Res.string.sources_layer_menu),
				sourcesLayerIgnore = stringResource(Res.string.sources_layer_menu_ignore),
				sourcesLayerUnignore = stringResource(Res.string.sources_layer_menu_unignore),
				sourcesReview = stringResource(Res.string.sources_suggestion_title),
				sourcesAccept = stringResource(Res.string.sources_suggestion_accept, SOURCES_CANDIDATE_NAME, SOURCES_CANDIDATE_PERCENT),
				sourcesAcceptMerge = stringResource(Res.string.sources_suggestion_accept_merge, SOURCES_CANDIDATE_NAME, SOURCES_CANDIDATE_PERCENT),
				sourcesRelinkByHand = stringResource(Res.string.sources_suggestion_relink),
				sourcesLeave = stringResource(Res.string.sources_suggestion_leave),
				sourcesRelink = stringResource(Res.string.sources_relink_title),
				sourcesUnbind = stringResource(Res.string.sources_relink_clear),
				sourcesDeleteArt = stringResource(Res.string.sources_relink_delete),
				sourcesNoMatches = stringResource(Res.string.sources_relink_no_matches),
				sourcesPresent = stringResource(Res.string.sources_status_present),
				sourcesMissing = stringResource(Res.string.sources_status_missing),
				sourcesUnknown = stringResource(Res.string.sources_status_unknown),
				sourcesBound = stringResource(Res.string.sources_status_bound),
				sourcesBoundByName = stringResource(Res.string.sources_status_bound_unstable),
				sourcesUnbound = stringResource(Res.string.sources_status_unbound),
				sourcesUnplaced = stringResource(Res.string.sources_status_unplaced),
				sourcesNeedsReview = stringResource(Res.string.sources_status_needs_review),
				sourcesIgnored = stringResource(Res.string.sources_status_ignored),
			)
		// Collected, not read once: an edit publishes a new model, and the panel has to be handed it.
		val puppet by harness.session.model.collectAsState()
		// A session is the document's: the app provides the two together, so a rig with no document has
		// no session either, whatever it was asked for.
		val session = if (harness.provideSession && harness.provideDocument) harness.session else null
		UmamoTheme {
			CompositionLocalProvider(
				LocalInlineEditController provides harness.inlineEditController,
				LocalRowDragCancel provides harness.rowDragCancel,
				LocalCommands provides harness.registry,
				LocalKeymap provides harness.keymap,
				LocalEditorSession provides session,
				LocalPuppet provides (if (harness.provideDocument) puppet else null),
				LocalLiveParams provides (if (harness.provideLiveParams) harness.liveParamsHandle else null),
				LocalSelection provides rememberSessionEditorState(harness.session),
				LocalKeyableHover provides (if (harness.showKeyformSheet) harness.keyableHover else null),
				LocalKeyformSheetViews provides (if (harness.showKeyformSheet) harness.keyformSheetViews else null),
				LocalRelationPick provides harness.relationPick,
				LocalDrawableThumbnails provides harness.thumbnails,
				LocalSettings provides harness.settings,
				LocalSourceFilePresence provides (if (harness.showSources) harness.sourcePresence else null),
				LocalSourceWatch provides (if (harness.showSources) harness.sourceWatch else null),
				LocalSourceSuggestions provides (if (harness.showSources) harness.sourceSuggestions else null),
				LocalSourceArtRasters provides harness.sourceArt,
			) {
				// The viewport binding's pose mirror.  A commit records the live hand-off's map, so without
				// this an undo would leave the hand-off on the undone pose and the next commit would restore it.
				LaunchedEffect(harness.session) {
					combine(harness.session.pose, harness.session.mode) { pose, mode ->
						if (mode == EditorMode.Edit) emptyMap() else pose
					}.collect { effectivePose -> harness.liveParams.values = effectivePose }
				}
				// The shell's focus reclaim.  A text editor that leaves composition takes focus with it, and
				// a focus left null kills every shortcut, so the root takes it back once no editor is live.
				// Two frames, as in the shell: an immediate request is nulled right back out by the teardown.
				val textEntryLive = harness.overlays.selfFocusedOverlayOpen || harness.inlineEditController.cancel != null
				LaunchedEffect(textEntryLive) {
					if (textEntryLive) {
						return@LaunchedEffect
					}
					withFrameNanos {}
					withFrameNanos {}
					harness.rootFocus.requestFocus()
				}
				Box(
					modifier =
						Modifier
							.fillMaxSize()
							.testTag(PANEL_ROOT_TAG)
							// Before the focus target it observes: after focusable() it reports whichever
							// descendant holds focus, which would make every root-focus assertion pass.
							.onFocusChanged { focusState -> harness.rootFocused = focusState.isFocused }
							.focusRequester(harness.rootFocus)
							.focusable()
							.releaseTextEntryOnPress(harness.inlineEditController, harness.overlays, harness.rootFocus)
							.onPreviewKeyEvent { event ->
								handleModalKeyLadder(
									event.toShellKeyStroke(),
									ShellModalState(
										overlays = harness.overlays,
										inlineEditController = harness.inlineEditController,
										editorSession = session,
										rowDragCancel = harness.rowDragCancel,
										keyformSheets = harness.keyformSheetViews,
										commandRegistry = harness.registry,
										keymap = harness.keymap,
									),
								)
							},
				) {
					Row {
						Column {
							if (harness.showHeader) {
								Box(modifier = Modifier.width(harness.headerWidth).height(PANEL_HEADER_HEIGHT).testTag(PANEL_HEADER_TAG)) {
									OverflowRow(modifier = Modifier.fillMaxWidth()) {
										parametersHeaderControls(harness.scope)
									}
								}
							}
							Box(modifier = Modifier.size(harness.panelSize).testTag(PANEL_BODY_TAG)) {
								ParametersSpace(harness.scope, Modifier.fillMaxSize())
							}
							Box(modifier = Modifier.testTag(PANEL_ELSEWHERE_TAG).size(PANEL_ELSEWHERE_SIZE))
						}
						if (harness.showKeyformSheet) {
							Box(modifier = Modifier.size(harness.sheetSize).testTag(PANEL_SHEET_TAG)) {
								KeyformSheetSpace(harness.sheetScope)
							}
						}
						if (harness.showOutliner) {
							Box(modifier = Modifier.size(harness.outlinerSize).testTag(PANEL_OUTLINER_TAG)) {
								OutlinerSpace(harness.outlinerScope, Modifier.fillMaxSize())
							}
						}
						if (harness.showSources) {
							Box(modifier = Modifier.size(harness.sourcesSize).testTag(PANEL_SOURCES_TAG)) {
								SourcesSpace(harness.sourcesScope, Modifier.fillMaxSize())
							}
						}
					}
				}
			}
		}
	}
	waitForIdle()
}

/**
 * Where a control with no semantics sits, in the panel body's own pixels.
 *
 * @property Float left The control's left edge.
 * @property Float top The control's top edge.
 * @property Float right The control's right edge.
 * @property Float bottom The control's bottom edge.
 */
internal class ControlBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
	val width: Float get() = right - left
	val height: Float get() = bottom - top
	val center: Offset get() = Offset((left + right) / 2f, (top + bottom) / 2f)

	/**
	 * A point inside the box, by fraction of each axis.
	 *
	 * @param Float across The fraction of the width, 0 at the left edge.
	 * @param Float down The fraction of the height, 0 at the top edge.
	 * @return Offset The point.
	 */
	fun at(across: Float, down: Float = 0.5f): Offset = Offset(left + width * across, top + height * down)
}

/**
 * The bounds of the node showing exactly [text], in the panel body's pixels.
 *
 * @param String text The text one node shows.
 * @return ControlBox The node's bounds.
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.panelBoundsOfText(text: String): ControlBox = panelBoundsOf(onNodeWithText(text, useUnmergedTree = true))

/**
 * A node's bounds in the panel body's pixels.
 *
 * @param SemanticsNodeInteraction node The node.
 * @return ControlBox The node's bounds.
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.panelBoundsOf(node: SemanticsNodeInteraction): ControlBox {
	val body: DpRect = onNodeWithTag(PANEL_BODY_TAG).getUnclippedBoundsInRoot()
	val bounds: DpRect = node.getUnclippedBoundsInRoot()
	return with(density) {
		ControlBox(
			left = (bounds.left - body.left).toPx(),
			top = (bounds.top - body.top).toPx(),
			right = (bounds.right - body.left).toPx(),
			bottom = (bounds.bottom - body.top).toPx(),
		)
	}
}

/**
 * The bounds of the grip on the row at [rowIndex], counting the rows the list has composed from the top.
 *
 * @param ParametersPanelHarness harness The mounted harness.
 * @param Int rowIndex The row, 0 for the first composed row.
 * @return ControlBox The grip's bounds in the panel body's pixels.
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.gripBounds(harness: ParametersPanelHarness, rowIndex: Int): ControlBox =
	panelBoundsOf(onAllNodesWithContentDescription(harness.text.reorderHandle, useUnmergedTree = true)[rowIndex])

/**
 * Where a slider row's slider sits.  A slider is a bare Canvas, so its box is worked out from the two
 * neighbors that do have semantics: the row's grip on its left and its number field above its right end.
 *
 * The island pads its content by 6 dp and the slider pads itself by 8 dp, which puts the slider's left
 * edge 14 dp right of the grip.  The number field is followed by a 4 dp gap, the 20 dp link slot, and the
 * island's 6 dp, so the island ends 30 dp right of the field and the slider 14 dp short of that.  The
 * slider is 18 dp tall and sits directly under the value row.
 *
 * @param ParametersPanelHarness harness The mounted harness.
 * @param Int rowIndex The row, 0 for the first composed row.
 * @param String valueText The text the row's number field shows.
 * @return ControlBox The slider's box in the panel body's pixels.
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.sliderBox(harness: ParametersPanelHarness, rowIndex: Int, valueText: String): ControlBox {
	val grip = gripBounds(harness, rowIndex)
	val field = numberFieldBounds(valueText)
	return with(density) {
		ControlBox(
			left = grip.right + 14.dp.toPx(),
			top = field.bottom,
			right = field.right + 16.dp.toPx(),
			bottom = field.bottom + 18.dp.toPx(),
		)
	}
}

/**
 * Where a pad row's pad sits, worked out the way [sliderBox] is.  The pad pads itself by 10 dp, keeps a
 * 3 : 2 shape, and sits directly under the two stacked axis rows.
 *
 * @param ParametersPanelHarness harness The mounted harness.
 * @param Int rowIndex The row, 0 for the first composed row.
 * @param String horizontalValueText The text the horizontal axis's number field shows.
 * @param String verticalValueText The text the vertical axis's number field shows.
 * @return ControlBox The pad's box in the panel body's pixels.
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.padBox(
	harness: ParametersPanelHarness,
	rowIndex: Int,
	horizontalValueText: String,
	verticalValueText: String,
): ControlBox {
	val grip = gripBounds(harness, rowIndex)
	val horizontalField = numberFieldBounds(horizontalValueText)
	val verticalField = numberFieldBounds(verticalValueText)
	return with(density) {
		val left = grip.right + 16.dp.toPx()
		val right = horizontalField.right + 14.dp.toPx()
		val top = verticalField.bottom + 10.dp.toPx()
		ControlBox(left = left, top = top, right = right, bottom = top + (right - left) / 1.5f)
	}
}

/**
 * A point on a slider row's island that belongs to no control: inside the island's own padding, at its
 * top left corner.
 *
 * @param ParametersPanelHarness harness The mounted harness.
 * @param Int rowIndex The row, 0 for the first composed row.
 * @param String valueText The text the row's number field shows.
 * @return Offset The point, in the panel body's pixels.
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.islandSurfacePoint(harness: ParametersPanelHarness, rowIndex: Int, valueText: String): Offset {
	val grip = gripBounds(harness, rowIndex)
	val field = numberFieldBounds(valueText)
	val inset = with(density) { 3.dp.toPx() }
	val islandTop = field.top - with(density) { 6.dp.toPx() }
	return Offset(grip.right + inset, islandTop + inset)
}

/**
 * The bounds of the number field showing [valueText].  The text node is narrower than its field, so the
 * field's box is taken from the text node's parent, which is the 64 dp control itself.
 *
 * @param String valueText The text the field shows.
 * @return ControlBox The field's bounds in the panel body's pixels.
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.numberFieldBounds(valueText: String): ControlBox {
	val text = panelBoundsOfText(valueText)
	val halfField = with(density) { NUMBER_FIELD_WIDTH.toPx() / 2f }
	val halfHeight = with(density) { NUMBER_FIELD_HEIGHT.toPx() / 2f }
	return ControlBox(
		left = text.center.x - halfField,
		top = text.center.y - halfHeight,
		right = text.center.x + halfField,
		bottom = text.center.y + halfHeight,
	)
}

/**
 * Presses at [from], moves through [through], and leaves the button held, all in the panel body's pixels.
 *
 * @param Offset from Where the press lands.
 * @param List through The points the pointer then moves through, in order.
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.pressAndMove(from: Offset, through: List<Offset>) {
	onNodeWithTag(PANEL_BODY_TAG).performMouseInput {
		advanceEventTime(GESTURE_GAP_MILLIS)
		moveTo(from)
		press()
		for (point in through) {
			advanceEventTime(GESTURE_STEP_MILLIS)
			moveTo(point)
		}
	}
	waitForIdle()
}

/**
 * Moves the pointer on through [through] with the button a [pressAndMove] left held.
 *
 * @param List through The points the pointer moves through, in order.
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.moveOn(through: List<Offset>) {
	onNodeWithTag(PANEL_BODY_TAG).performMouseInput {
		for (point in through) {
			advanceEventTime(GESTURE_STEP_MILLIS)
			moveTo(point)
		}
	}
	waitForIdle()
}

/**
 * Releases the button a [pressAndMove] left held.
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.releasePress() {
	onNodeWithTag(PANEL_BODY_TAG).performMouseInput {
		advanceEventTime(GESTURE_STEP_MILLIS)
		release()
	}
	waitForIdle()
}

/**
 * A whole drag: press, move, release.
 *
 * @param Offset from Where the press lands.
 * @param List through The points the pointer then moves through, in order.
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.drag(from: Offset, through: List<Offset>) {
	pressAndMove(from, through)
	releasePress()
}

/**
 * A single click at a point in the panel body.
 *
 * @param Offset point Where to click.
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.clickAt(point: Offset) {
	onNodeWithTag(PANEL_BODY_TAG).performMouseInput {
		advanceEventTime(GESTURE_GAP_MILLIS)
		moveTo(point)
		press()
		advanceEventTime(GESTURE_STEP_MILLIS)
		release()
	}
	waitForIdle()
}

/**
 * A double click at a point in the panel body.
 *
 * @param Offset point Where to click twice.
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.doubleClickAt(point: Offset) {
	onNodeWithTag(PANEL_BODY_TAG).performMouseInput {
		advanceEventTime(GESTURE_GAP_MILLIS)
		moveTo(point)
		press()
		advanceEventTime(GESTURE_STEP_MILLIS)
		release()
		advanceEventTime(DOUBLE_CLICK_GAP_MILLIS)
		press()
		advanceEventTime(GESTURE_STEP_MILLIS)
		release()
	}
	waitForIdle()
}

/**
 * A secondary click at a point in the panel body, which is what opens a context menu on the desktop.
 *
 * @param Offset point Where to click.
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.secondaryClickAt(point: Offset) {
	onNodeWithTag(PANEL_BODY_TAG).performMouseInput {
		advanceEventTime(GESTURE_GAP_MILLIS)
		moveTo(point)
		press(MouseButton.Secondary)
		advanceEventTime(GESTURE_STEP_MILLIS)
		release(MouseButton.Secondary)
	}
	waitForIdle()
}

/**
 * Types a value into the number field showing [shownText] and commits it with Enter.
 *
 * @param String shownText The text the field shows before the edit.
 * @param String typedText The text to replace it with.
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.typeIntoNumberField(shownText: String, typedText: String) {
	clickAt(panelBoundsOfText(shownText).center)
	onNode(hasSetTextAction() and isFocused()).performTextReplacement(typedText)
	waitForIdle()
	pressKey(Key.Enter)
}

/**
 * Sends one key press and release to whatever holds focus.
 *
 * @param Key key The key.
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.pressKey(key: Key) {
	onNodeWithTag(PANEL_ROOT_TAG).performKeyInput {
		keyDown(key)
		keyUp(key)
	}
	waitForIdle()
}

/**
 * Sends the chord the fixture binds, standing in for undo and the rest of the keymap.
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.pressBoundChord() {
	onNodeWithTag(PANEL_ROOT_TAG).performKeyInput {
		keyDown(Key.CtrlLeft)
		keyDown(Key.S)
		keyUp(Key.S)
		keyUp(Key.CtrlLeft)
	}
	waitForIdle()
}

/**
 * Clicks the menu entry labelled [label] in whichever menu is open.
 *
 * @param String label The entry's label.
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.clickMenuEntry(label: String) {
	onNode(hasText(label) and hasAnyAncestor(isPopup()), useUnmergedTree = true).performClick()
	waitForIdle()
}

/**
 * Rests the pointer on the menu entry labelled [label], which is what opens a submenu's flyout on the
 * desktop.  A click would open it by the hover and close it again by the press.
 *
 * @param String label The entry's label.
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.hoverMenuEntry(label: String) {
	onNode(hasText(label) and hasAnyAncestor(isPopup()), useUnmergedTree = true).performMouseInput {
		moveTo(center)
	}
	waitForIdle()
}

/**
 * Whether an open menu or popup shows an entry labelled [label].
 *
 * @param String label The entry's label.
 * @return Boolean True when a popup shows it.
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.popupShows(label: String): Boolean =
	onAllNodes(hasText(label) and hasAnyAncestor(isPopup()), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

/**
 * Where an open popup shows the text [label], in window pixels.
 *
 * @param String label The text.
 * @return Rect The text's bounds.
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.popupTextInWindow(label: String): Rect =
	onNode(hasText(label) and hasAnyAncestor(isPopup()), useUnmergedTree = true).fetchSemanticsNode().boundsInWindow

/**
 * Clicks the control whose accessible name is [description].
 *
 * @param String description The accessible name.
 * @param Int index Which of the controls carrying that name, in list order.
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.clickDescribed(description: String, index: Int = 0) {
	onAllNodesWithContentDescription(description, useUnmergedTree = true)[index].performClick()
	waitForIdle()
}

/**
 * The labels among [labels] that an open menu shows, top to bottom.
 *
 * @param List labels The labels to look for.
 * @return List<String> The ones shown, in the order the menu lists them.
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.shownInOrder(labels: List<String>): List<String> =
	labels
		.filter { label -> popupShows(label) }
		.sortedBy { label ->
			onNode(hasText(label) and hasAnyAncestor(isPopup()), useUnmergedTree = true).getUnclippedBoundsInRoot().top
		}

/**
 * Renames whatever row has its rename field open, committing with Enter.
 *
 * @param String newName The name to type.
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.typeIntoRenameField(newName: String) {
	onNode(hasSetTextAction() and isFocused()).performTextReplacement(newName)
	waitForIdle()
}

/**
 * Whether a rename field is open anywhere in the panel.
 *
 * @return Boolean True when an editable, focused text field exists.
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.renameFieldOpen(): Boolean = onAllNodes(hasSetTextAction() and isFocused()).fetchSemanticsNodes().isNotEmpty()

/**
 * Whether any node shows exactly [text].
 *
 * @param String text The text to look for.
 * @return Boolean True when at least one node shows it.
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.showsText(text: String): Boolean =
	onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

/**
 * How many nodes carry [description] as their accessible name.
 *
 * @param String description The accessible name.
 * @return Int The number of nodes carrying it.
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.countOfDescription(description: String): Int =
	onAllNodesWithContentDescription(description, useUnmergedTree = true).fetchSemanticsNodes().size

/**
 * Asserts two values agree to within what a pixel of pointer travel can resolve.
 *
 * @param Float expected The value the gesture should have produced.
 * @param Float? actual The value it produced.
 * @param String message What the assertion is about.
 */
internal fun assertNear(expected: Float, actual: Float?, message: String) {
	assertTrue(actual != null && kotlin.math.abs(actual - expected) <= VALUE_TOLERANCE, "$message: expected about $expected, got $actual")
}

/**
 * The ids at the root of the parameter tree, in panel order: a parameter by its id, a group by its own.
 *
 * @param PuppetModel model The model to read.
 * @return List<String> The root's raw ids.
 */
internal fun rootOrderOf(model: PuppetModel): List<String> =
	model.parameterTree.map { node ->
		when (node) {
			is ParameterNode.Param -> node.id.raw
			is ParameterNode.Group -> node.id.raw
		}
	}

/**
 * The parameter ids directly inside a group, in panel order.
 *
 * @param PuppetModel model The model to read.
 * @param ParameterGroupId groupId The group.
 * @return List<ParameterId> The group's own parameters.
 */
internal fun membersOf(model: PuppetModel, groupId: ParameterGroupId): List<ParameterId> =
	model.parameterTree
		.filterIsInstance<ParameterNode.Group>()
		.first { group -> group.id == groupId }
		.children
		.filterIsInstance<ParameterNode.Param>()
		.map { node -> node.id }

/**
 * A parameter of the session's current model.
 *
 * @param ParametersPanelHarness harness The mounted harness.
 * @param ParameterId id The parameter.
 * @return Parameter The parameter as the model holds it now.
 */
internal fun parameterOf(harness: ParametersPanelHarness, id: ParameterId): Parameter =
	harness.session.model.value.parameters.first { parameter -> parameter.id == id }

/** The area the fixture mounts the panel in. */
internal const val PANEL_AREA_ID = "area-1"

/** The id of the area the keyform sheet beside the panel is mounted in. */
internal const val PANEL_SHEET_AREA_ID = "area-2"

/** The tag of the box the keyform sheet beside the panel is mounted in. */
internal const val PANEL_SHEET_TAG = "sheet"

/** The width the keyform sheet beside the panel is laid out at. */
internal val PANEL_SHEET_WIDTH: Dp = 580.dp

/** The height the keyform sheet beside the panel is laid out at. */
internal val PANEL_SHEET_HEIGHT: Dp = 320.dp

/** The id of the area the outliner beside the panel is mounted in. */
internal const val PANEL_OUTLINER_AREA_ID = "area-3"

/** The tag of the box the outliner beside the panel is mounted in. */
internal const val PANEL_OUTLINER_TAG = "outliner"

/** The width the outliner beside the panel is laid out at: wide enough that no fixture name scrolls. */
internal val PANEL_OUTLINER_WIDTH: Dp = 260.dp

/** The height the outliner beside the panel is laid out at: tall enough for every row of its rig, open or not. */
internal val PANEL_OUTLINER_HEIGHT: Dp = 480.dp

/** An outliner short enough that revealing a row deep in the tree has to scroll: five rows of 22 dp. */
internal val PANEL_OUTLINER_HEIGHT_SCROLLING: Dp = 110.dp

/** The id of the area the Sources space beside the panel is mounted in. */
internal const val PANEL_SOURCES_AREA_ID = "area-4"

/** The tag of the box the Sources space beside the panel is mounted in. */
internal const val PANEL_SOURCES_TAG = "sources"

/** The width the Sources space beside the panel is laid out at: wide enough that no fixture label is cut. */
internal val PANEL_SOURCES_WIDTH: Dp = 420.dp

/** The height the Sources space beside the panel is laid out at: tall enough for every row of its rig, open or not. */
internal val PANEL_SOURCES_HEIGHT: Dp = 480.dp

/** A Sources space short enough that its list has to scroll: five rows of 22 dp. */
internal val PANEL_SOURCES_HEIGHT_SCROLLING: Dp = 110.dp

/** The name of the layer the Sources rig's proposal names, which the Accept entry shows. */
internal const val SOURCES_CANDIDATE_NAME = "Brow"

/** The confidence of the Sources rig's proposal as the Accept entry shows it. */
internal const val SOURCES_CANDIDATE_PERCENT = 92

/** The command the bound chord runs. */
internal const val PANEL_SHORTCUT_COMMAND = "test.shortcut"

/** The tag on the shell root, which is where key input goes. */
internal const val PANEL_ROOT_TAG = "root"

/** The tag on the box holding the panel body, whose pixels every gesture is measured in. */
internal const val PANEL_BODY_TAG = "body"

/** The tag on the box holding the header strip. */
internal const val PANEL_HEADER_TAG = "header"

/** The tag on the empty target under the panel, standing in for whatever a user clicks next. */
internal const val PANEL_ELSEWHERE_TAG = "elsewhere"

/** The panel body's width: wide enough that no row wraps. */
internal val PANEL_WIDTH: Dp = 360.dp

/** The panel body's height: tall enough that every row of the fixture rig is composed. */
internal val PANEL_HEIGHT: Dp = 640.dp

/** A body tall enough to leave bare panel under the last row. */
internal val PANEL_HEIGHT_WITH_SPACE: Dp = 720.dp

/** A body short enough that the list has to scroll. */
internal val PANEL_HEIGHT_SCROLLING: Dp = 260.dp

/** The header strip's height, matching the area header's. */
internal val PANEL_HEADER_HEIGHT: Dp = 28.dp

/** Big enough to press without landing on the panel above it. */
internal val PANEL_ELSEWHERE_SIZE: Dp = 40.dp

/** The width ParameterValueRow gives its number field. */
internal val NUMBER_FIELD_WIDTH: Dp = 64.dp

/** The height the kit gives a form control. */
internal val NUMBER_FIELD_HEIGHT: Dp = 20.dp

/** The time between two pointer events of one gesture. */
internal const val GESTURE_STEP_MILLIS = 16L

/** The time between two separate gestures, well outside the double-click window. */
internal const val GESTURE_GAP_MILLIS = 1_000L

/** The gap between the two clicks of a double click, well inside the double-click window. */
internal const val DOUBLE_CLICK_GAP_MILLIS = 100L

/** How far a value may sit from its target after a gesture aimed by the pixel. */
internal const val VALUE_TOLERANCE = 0.6f