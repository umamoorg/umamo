package org.umamo.ui.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import org.umamo.edit.EditorMode
import org.umamo.edit.MeshSelectMode
import org.umamo.edit.MeshTopology
import org.umamo.edit.NoticePlacement
import org.umamo.edit.SelectionTarget
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.ui.action.Keymap
import org.umamo.ui.action.LocalKeymap
import org.umamo.ui.action.formatAccelerator
import org.umamo.ui.kit.Text
import org.umamo.ui.model.LocalEditorSession
import org.umamo.ui.model.LocalPuppet
import org.umamo.ui.model.LocalSelection
import org.umamo.ui.model.noticeText
import org.umamo.ui.resources.*
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.LocalUmamoTypography

// The strip reads better a touch shorter than the area header (HEADER_HEIGHT = 28.dp in AreaHeader);
// it is fixed-height chrome, so the area tree above takes the remaining height.
private val STATUS_BAR_HEIGHT = 24.dp

// Separates the inline entries within a zone; a middot-style bar keeps the run-on counts grouped.
private const val STAT_SEPARATOR = " | "

/**
 * The bottom status strip: thin, always-present window chrome below the area tree, laid out as four
 * conceptual zones left to right - the current context's input binds, a blank flexible middle, the
 * selected item, and the model stats. Lives in commonMain so the strip is shared chrome on desktop
 * and Android.
 *
 * 下部ステータスバー。エリアツリー下に常駐する細い枠。左からコンテキスト入力割当・空白・選択項目・
 * モデル統計の四区画。
 *
 * @param Modifier modifier The layout modifier (the shell passes fillMaxWidth).
 */
@Composable
fun StatusBar(modifier: Modifier = Modifier) {
	val colors = LocalUmamoColors.current
	Column(modifier = modifier) {
		// A hairline above the strip separates it from the area tree (and the offscreen GL viewport).
		Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(colors.divider))
		Row(
			modifier =
				Modifier
					.fillMaxWidth()
					.height(STATUS_BAR_HEIGHT)
					.background(colors.headerBackground)
					.padding(horizontal = 8.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			ContextBindsZone()
			// The flexible middle doubles as the transient-notice slot: a blank gap normally, a brief message
			// when the session emits one (e.g. an Object-mode transform blocked by a part / deformer selection).
			Box(modifier = Modifier.weight(1f).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
				StatusNotice()
			}
			SelectedItemZone()
			Spacer(modifier = Modifier.width(16.dp))
			ModelStats()
		}
	}
}

/** How long (ms) a transient status notice stays visible before it auto-dismisses. */
private const val NOTICE_VISIBLE_MS = 4000L

/**
 * The transient-notice slot: shows the session's most recent status-bar-placed notice for a few seconds,
 * then clears itself.  A notice is momentary feedback explaining why an action did nothing, so it lives
 * outside the undo history and the change bus.  Notices placed [NoticePlacement.NearCursor] are the
 * viewport HUD overlay's to draw, not this slot's.  Renders nothing when no notice is active or no
 * document is open.
 */
@Composable
private fun StatusNotice() {
	val session = LocalEditorSession.current ?: return
	val notice by session.notice.collectAsState()
	val current = notice ?: return
	if (current.placement != NoticePlacement.StatusBar) {
		return
	}
	// Auto-dismiss this notice after the visible window; keyed on the serial so a repeat of the same text
	// restarts the timer, and clearNotice only clears if a newer notice has not replaced it meanwhile.
	LaunchedEffect(current.serial) {
		delay(NOTICE_VISIBLE_MS)
		session.clearNotice(current.serial)
	}
	Text(
		text = noticeText(current.messageKey),
		style = LocalUmamoTypography.current.labelMedium,
		color = LocalUmamoColors.current.text,
		maxLines = 1,
		overflow = TextOverflow.Ellipsis,
	)
}

/**
 * One "chord label" bind entry, or null when the command has no bound chord - an unbound command is
 * simply omitted so the strip never advertises a key that does nothing (and a rebind is reflected
 * automatically, since the chord always comes from the live keymap).
 *
 * @param Keymap keymap The active keymap.
 * @param String commandId The command whose canonical chord is shown.
 * @param String label The short localized action label.
 * @return String? The formatted entry, or null when the command is unbound.
 */
private fun bindEntry(keymap: Keymap, commandId: String, label: String): String? {
	val chord = keymap.chordFor(commandId) ?: return null
	return "${formatAccelerator(chord)} $label"
}

/**
 * The context-binds zone: the handful of input binds most relevant to the current editor context -
 * no document, Object mode, Edit mode, or an active modal mesh operator. Keyboard binds always
 * resolve through the live keymap (never hardcoded, so a rebind shows here everywhere); the modal
 * operator's pointer / confirm / cancel hints are literal strings because those inputs live in the
 * overlay's own pointer loop and the shell's hardcoded modal branches, not in the keymap - there is
 * nothing rebindable to resolve.
 *
 * コンテキスト割当区画。現在の編集コンテキストで最も関係する入力割当を表示する。キーボード割当は
 * 常にキーマップから解決する（リバインドが即反映される）。
 */
@Composable
private fun ContextBindsZone() {
	val keymap = LocalKeymap.current
	val session = LocalEditorSession.current
	val entries = mutableListOf<String>()
	if (session == null) {
		bindEntry(keymap, "file.open", stringResource(Res.string.menu_open))?.let { entry -> entries.add(entry) }
		bindEntry(keymap, "palette.toggle", stringResource(Res.string.status_bind_palette))?.let { entry -> entries.add(entry) }
	} else {
		val editorMode by session.mode.collectAsState()
		val activeOperator by session.activeMeshOperator.collectAsState()
		if (activeOperator != null) {
			entries.add(stringResource(Res.string.status_bind_confirm))
			entries.add(stringResource(Res.string.status_bind_cancel))
		} else if (editorMode == EditorMode.Object) {
			bindEntry(keymap, "mode.toggleEdit", stringResource(Res.string.status_bind_edit_mode))?.let { entry -> entries.add(entry) }
			bindEntry(keymap, "object.toggleVisibility", stringResource(Res.string.status_bind_visibility))?.let { entry -> entries.add(entry) }
		} else {
			bindEntry(keymap, "mesh.grab", stringResource(Res.string.status_bind_grab))?.let { entry -> entries.add(entry) }
			bindEntry(keymap, "mesh.scale", stringResource(Res.string.status_bind_scale))?.let { entry -> entries.add(entry) }
			bindEntry(keymap, "mesh.rotate", stringResource(Res.string.status_bind_rotate))?.let { entry -> entries.add(entry) }
			// One composite entry for the three select-mode keys, shown only when all three are
			// bound (a partial listing would misread as the full set).
			val vertexChord = keymap.chordFor("mesh.selectMode.vertex")
			val edgeChord = keymap.chordFor("mesh.selectMode.edge")
			val faceChord = keymap.chordFor("mesh.selectMode.face")
			if (vertexChord != null && edgeChord != null && faceChord != null) {
				val chordRun = "${formatAccelerator(vertexChord)}/${formatAccelerator(edgeChord)}/${formatAccelerator(faceChord)}"
				entries.add("$chordRun ${stringResource(Res.string.status_select_mode)}")
			}
		}
	}
	if (entries.isEmpty()) {
		return
	}
	Text(
		text = entries.joinToString(separator = STAT_SEPARATOR),
		style = LocalUmamoTypography.current.labelMedium,
		color = LocalUmamoColors.current.textMuted,
	)
}

/**
 * The selected-item zone: in Object mode the active target's display name (or a "%d selected" count
 * for a multi-selection); in Edit mode the selected element count over the edited drawable's domain
 * total ("3/120 vertices"). Renders nothing when nothing is selected or no document is open.
 *
 * 選択項目区画。オブジェクトモードではアクティブ対象の表示名（複数選択時は件数）、編集モードでは
 * 選択要素数／総数を表示する。
 */
@Composable
private fun SelectedItemZone() {
	val puppet = LocalPuppet.current
	val session = LocalEditorSession.current
	if (puppet == null || session == null) {
		return
	}
	val editorMode by session.mode.collectAsState()
	val text: String?
	if (editorMode == EditorMode.Edit) {
		// The denominators sum over EVERY session mesh (multi-mesh edit), so "3/240 vertices" reads
		// against everything the session can select.
		val meshSelection by session.meshSelection.collectAsState()
		val drawableById = remember(puppet) { puppet.drawables.associateBy { drawable -> drawable.id } }
		val sessionMeshes =
			remember(puppet, meshSelection.drawableIds) {
				meshSelection.drawableIds.mapNotNull { drawableId -> drawableById[drawableId]?.mesh }
			}
		text =
			if (sessionMeshes.isEmpty() || meshSelection.isEmpty) {
				null
			} else {
				when (meshSelection.selectMode) {
					MeshSelectMode.Vertex ->
						stringResource(Res.string.status_mesh_vertices, meshSelection.size, sessionMeshes.sumOf { mesh -> mesh.vertexCount })
					MeshSelectMode.Edge -> {
						val totalEdges = remember(sessionMeshes) { sessionMeshes.sumOf { mesh -> MeshTopology.uniqueEdges(mesh.indices).size } }
						stringResource(Res.string.status_mesh_edges, meshSelection.size, totalEdges)
					}
					MeshSelectMode.Face ->
						stringResource(Res.string.status_mesh_faces, meshSelection.size, sessionMeshes.sumOf { mesh -> mesh.triangleCount })
				}
			}
	} else {
		val selection = LocalSelection.current?.selection
		text =
			if (selection == null || selection.isEmpty) {
				null
			} else if (selection.size > 1) {
				stringResource(Res.string.inspector_multi_selected, selection.size)
			} else {
				val target = selection.active ?: selection.targets.first()
				when (target) {
					is SelectionTarget.Part -> remember(puppet) { puppet.parts.associateBy { part -> part.id } }[target.id]?.name
					is SelectionTarget.Drawable -> remember(puppet) { puppet.drawables.associateBy { drawable -> drawable.id } }[target.id]?.name
					is SelectionTarget.Deformer -> remember(puppet) { puppet.deformers.associateBy { deformer -> deformer.id } }[target.id]?.name
				}
			}
	}
	if (text == null) {
		return
	}
	Text(
		text = text,
		style = LocalUmamoTypography.current.labelMedium,
		color = LocalUmamoColors.current.textMuted,
	)
}

/**
 * The model-stats zone: an inline summary of the open model's element counts (parts, drawables,
 * parameters, deformers, glues) plus its mesh vertex / triangle totals. While a selection exists the
 * mesh numbers turn selection-relative ("Verts: 12/3400") - in Object mode over the selected
 * drawables (a selected part counts its nested art), in Edit mode over the selected elements' covered
 * vertices and fully-covered faces. Reads the open document's [LocalPuppet] and renders nothing when
 * no model is open.
 *
 * モデル統計区画。各種件数と頂点・三角形の合計を表示し、選択があるあいだは選択分／合計の形になる。
 * 未読込時は何も描かない。
 */
@Composable
private fun ModelStats() {
	val puppet = LocalPuppet.current ?: return
	val session = LocalEditorSession.current
	var selectedTotals: MeshTotals? = null
	// The session meshes Edit mode scopes to (paired with their ids for per-mesh element lookups), or
	// null in Object mode / no session; drives the denominator below.
	var editedMeshes: List<Pair<DrawableId, DrawableMesh>>? = null
	if (session != null) {
		val editorMode by session.mode.collectAsState()
		if (editorMode == EditorMode.Edit) {
			val meshSelection by session.meshSelection.collectAsState()
			val drawableById = remember(puppet) { puppet.drawables.associateBy { drawable -> drawable.id } }
			val sessionMeshes =
				remember(puppet, meshSelection.drawableIds) {
					meshSelection.drawableIds.mapNotNull { drawableId -> drawableById[drawableId]?.mesh?.let { mesh -> drawableId to mesh } }
				}
			editedMeshes = sessionMeshes.takeIf { pairs -> pairs.isNotEmpty() }
			if (sessionMeshes.isNotEmpty() && !meshSelection.isEmpty) {
				selectedTotals =
					remember(meshSelection, sessionMeshes) {
						var coveredVertexTotal = 0
						var coveredFaceTotal = 0
						for ((drawableId, mesh) in sessionMeshes) {
							val coveredVertexIndices = MeshTopology.coveredVertexIndices(meshSelection.elementsOf(drawableId), mesh.indices)
							coveredVertexTotal += coveredVertexIndices.size
							coveredFaceTotal += MeshTopology.facesWithAllVerticesSelected(mesh.indices, coveredVertexIndices).size
						}
						MeshTotals(vertexCount = coveredVertexTotal, triangleCount = coveredFaceTotal)
					}
			}
		} else {
			val selection = LocalSelection.current?.selection
			if (selection != null && !selection.isEmpty) {
				selectedTotals = remember(puppet, selection) { selectedMeshTotals(puppet, selection) }
			}
		}
	}
	// The stats denominator: in Edit mode the session meshes' own totals (so "3/240" reads against the
	// meshes being edited, matching the selected-item zone), else the whole model's totals. A val for
	// the smart-cast to survive into the remember lambda.
	val editedMeshesForTotals = editedMeshes
	val totals =
		if (editedMeshesForTotals != null) {
			remember(editedMeshesForTotals) {
				MeshTotals(
					vertexCount = editedMeshesForTotals.sumOf { (_, mesh) -> mesh.vertexCount },
					triangleCount = editedMeshesForTotals.sumOf { (_, mesh) -> mesh.triangleCount },
				)
			}
		} else {
			remember(puppet) { meshTotals(puppet) }
		}
	val vertsEntry =
		selectedTotals?.let { selected -> stringResource(Res.string.status_verts_selected, selected.vertexCount, totals.vertexCount) }
			?: stringResource(Res.string.status_verts_selected, "0", totals.vertexCount)
	val trisEntry =
		selectedTotals?.let { selected -> stringResource(Res.string.status_tris_selected, selected.triangleCount, totals.triangleCount) }
			?: stringResource(Res.string.status_tris_selected, "0", totals.triangleCount)
	val line =
		listOf(
			stringResource(Res.string.inspector_parts, puppet.parts.size),
			stringResource(Res.string.inspector_drawables, puppet.drawables.size),
			stringResource(Res.string.inspector_parameters, puppet.parameters.size),
			stringResource(Res.string.inspector_deformers, puppet.deformers.size),
			stringResource(Res.string.inspector_glues, puppet.glues.size),
			vertsEntry,
			trisEntry,
		).joinToString(separator = STAT_SEPARATOR)
	Text(
		text = line,
		style = LocalUmamoTypography.current.labelMedium,
		color = LocalUmamoColors.current.textMuted,
	)
}
