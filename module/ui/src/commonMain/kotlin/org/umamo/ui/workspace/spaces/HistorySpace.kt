package org.umamo.ui.workspace.spaces

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import org.umamo.ui.kit.Text
import org.umamo.ui.kit.VerticalScrollbarOverlay
import org.umamo.ui.model.LocalEditorSession
import org.umamo.ui.resources.*
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.LocalUmamoShapes
import org.umamo.ui.theme.LocalUmamoTypography

/**
 * The history space: the session's undo stack as a clickable list, oldest at the top and the live step
 * highlighted (Blender's Undo History / Photoshop's History panel). Clicking a row jumps the session
 * straight to that step — undoing or redoing several levels at once — and the steps ahead of the cursor
 * (the redo branch) are dimmed. A trailing dot marks the last-saved step. The whole list is a projection
 * the session republishes ([org.umamo.edit.EditorSession.historyView]), so it tracks edits made anywhere.
 *
 * 履歴空間。取り消しスタックをクリック可能な一覧として表示（最古が上、現在段を強調）。行をクリックでその段へ移動。
 *
 * @param Modifier modifier The layout modifier.
 */
@Composable
fun HistorySpace(modifier: Modifier = Modifier) {
	val session = LocalEditorSession.current
	if (session == null) {
		Box(modifier = modifier.fillMaxSize().zebraFill(rememberLazyListState(), ROW_HEIGHT, LocalUmamoColors.current.rowStripe))
		return
	}
	val view by session.historyView.collectAsState()
	val listState = rememberLazyListState()
	// Keep the live step on screen as the cursor moves (a row click, an undo / redo from the keyboard),
	// but only scroll when it has actually left the viewport so manual scrolling is never fought.
	LaunchedEffect(view.cursor, view.steps.size) {
		val visible = listState.layoutInfo.visibleItemsInfo
		val firstVisible = visible.firstOrNull()?.index ?: 0
		val lastVisible = visible.lastOrNull()?.index ?: 0
		if (view.steps.isNotEmpty() && (view.cursor < firstVisible || view.cursor > lastVisible)) {
			listState.animateScrollToItem(view.cursor)
		}
	}
	val stripeColor = LocalUmamoColors.current.rowStripe
	Box(modifier = modifier.fillMaxSize().zebraFill(listState, ROW_HEIGHT, stripeColor)) {
		LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
			itemsIndexed(view.steps) { index, step ->
				HistoryStepRow(
					label = historyStepLabel(step.labelKey),
					isCurrent = index == view.cursor,
					isFuture = index > view.cursor,
					saved = step.saved,
					onClick = { session.jumpTo(index) },
				)
			}
		}
		VerticalScrollbarOverlay(listState)
	}
}

/**
 * One step row: its [label], styled by where it sits relative to the cursor (current is the accent band,
 * a step ahead of the cursor is dimmed as a pending redo), with a hover highlight and an optional saved
 * dot. Clicking jumps the session to this step.
 *
 * @param String label The localized step label.
 * @param Boolean isCurrent Whether this is the live step (the highlighted row).
 * @param Boolean isFuture Whether this step is ahead of the cursor (a dimmed redo step).
 * @param Boolean saved Whether this is the last-saved step (draws the trailing dot).
 * @param Function onClick Jumps the session to this step.
 */
@Composable
private fun HistoryStepRow(
	label: String,
	isCurrent: Boolean,
	isFuture: Boolean,
	saved: Boolean,
	onClick: () -> Unit,
) {
	val colors = LocalUmamoColors.current
	val shapes = LocalUmamoShapes.current
	val typography = LocalUmamoTypography.current
	val interaction = remember { MutableInteractionSource() }
	val hovered by interaction.collectIsHoveredAsState()
	val background =
		when {
			isCurrent -> colors.selection
			hovered -> colors.rowHover
			else -> Color.Transparent
		}
	val labelColor =
		when {
			isCurrent -> colors.selectionText
			isFuture -> colors.textMuted
			else -> colors.text
		}
	Row(
		modifier =
			Modifier.fillMaxWidth()
				.height(ROW_HEIGHT)
				.background(background, shape = shapes.medium)
				.clickable(interactionSource = interaction, indication = null, onClick = onClick)
				.padding(horizontal = 8.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Text(
			text = label,
			style = typography.bodySmall,
			color = labelColor,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
			modifier = Modifier.weight(1f),
		)
		// Fixed trailing slot so labels align whether or not the dot is present; the dot marks the saved step.
		Box(modifier = Modifier.size(SAVED_DOT_SLOT), contentAlignment = Alignment.Center) {
			if (saved) {
				Box(modifier = Modifier.size(SAVED_DOT).background(if (isCurrent) colors.selectionText else colors.accent, CircleShape))
			}
		}
	}
}

/**
 * Maps a [org.umamo.edit.Change.labelKey] (or null, the seed entry) to its localized history label. The
 * keys mirror those declared in :edit's Change taxonomy; an unmapped key falls back to a generic "Edit"
 * so a newly added change kind never renders blank.
 *
 * @param String? labelKey The change's stable label key, or null for the initial-open step.
 * @return String The localized step label.
 */
@Composable
private fun historyStepLabel(labelKey: String?): String =
	when (labelKey) {
		null -> stringResource(Res.string.history_open)
		"change.selection" -> stringResource(Res.string.history_selection)
		"change.mode" -> stringResource(Res.string.history_mode)
		"change.part.visibility" -> stringResource(Res.string.history_part_visibility)
		"change.part.rename" -> stringResource(Res.string.history_part_rename)
		"change.part.selectable" -> stringResource(Res.string.history_part_selectable)
		"change.part.move" -> stringResource(Res.string.history_part_move)
		"change.drawable.visibility" -> stringResource(Res.string.history_drawable_visibility)
		"change.drawable.rename" -> stringResource(Res.string.history_drawable_rename)
		"change.drawable.selectable" -> stringResource(Res.string.history_drawable_selectable)
		"change.drawable.move" -> stringResource(Res.string.history_drawable_move)
		"change.deformer.rename" -> stringResource(Res.string.history_deformer_rename)
		"change.deformer.selectable" -> stringResource(Res.string.history_deformer_selectable)
		"change.deformer.move" -> stringResource(Res.string.history_deformer_move)
		"change.parameter.value" -> stringResource(Res.string.history_parameter_value)
		"change.parameter.range" -> stringResource(Res.string.history_parameter_range)
		"change.parameter.link" -> stringResource(Res.string.history_parameter_link)
		"change.parameter.unlink" -> stringResource(Res.string.history_parameter_unlink)
		"change.parameter.move" -> stringResource(Res.string.history_parameter_move)
		"change.parameter.groupCreate" -> stringResource(Res.string.history_parameter_group_create)
		"change.parameter.groupDelete" -> stringResource(Res.string.history_parameter_group_delete)
		"change.parameter.groupRename" -> stringResource(Res.string.history_parameter_group_rename)
		"change.parameter.create" -> stringResource(Res.string.history_parameter_create)
		"change.parameter.rename" -> stringResource(Res.string.history_parameter_rename)
		"change.parameter.delete" -> stringResource(Res.string.history_parameter_delete)
		"change.mesh.move" -> stringResource(Res.string.history_mesh_move)
		"change.mesh.scale" -> stringResource(Res.string.history_mesh_scale)
		"change.mesh.rotate" -> stringResource(Res.string.history_mesh_rotate)
		"change.mesh.slide" -> stringResource(Res.string.history_mesh_slide)
		"change.uv.move" -> stringResource(Res.string.history_uv_move)
		"change.uv.scale" -> stringResource(Res.string.history_uv_scale)
		"change.uv.rotate" -> stringResource(Res.string.history_uv_rotate)
		"change.uv.mirror" -> stringResource(Res.string.history_uv_mirror)
		"change.mesh.duplicate" -> stringResource(Res.string.history_mesh_duplicate)
		"change.mesh.merge" -> stringResource(Res.string.history_mesh_merge)
		"change.mesh.rip" -> stringResource(Res.string.history_mesh_rip)
		"change.mesh.connect" -> stringResource(Res.string.history_mesh_connect)
		"change.drawable.duplicate" -> stringResource(Res.string.history_drawable_duplicate)
		"change.object.move" -> stringResource(Res.string.history_object_move)
		"change.object.scale" -> stringResource(Res.string.history_object_scale)
		"change.object.rotate" -> stringResource(Res.string.history_object_rotate)
		"change.mesh.select" -> stringResource(Res.string.history_mesh_select)
		"change.mesh.selectMode" -> stringResource(Res.string.history_mesh_select_mode)
		"change.part.delete" -> stringResource(Res.string.history_part_delete)
		"change.drawable.delete" -> stringResource(Res.string.history_drawable_delete)
		"change.deformer.delete" -> stringResource(Res.string.history_deformer_delete)
		"change.part.sketch" -> stringResource(Res.string.history_part_sketch)
		"change.part.drawOrder" -> stringResource(Res.string.history_part_draw_order)
		"change.part.groupMode" -> stringResource(Res.string.history_part_group_mode)
		"change.part.composite" -> stringResource(Res.string.history_part_composite)
		"change.drawable.blendMode" -> stringResource(Res.string.history_drawable_blend_mode)
		"change.drawable.alphaBlendMode" -> stringResource(Res.string.history_drawable_alpha_blend_mode)
		"change.drawable.culling" -> stringResource(Res.string.history_drawable_culling)
		"change.drawable.invertMask" -> stringResource(Res.string.history_drawable_invert_mask)
		"change.drawable.parentDeformer" -> stringResource(Res.string.history_drawable_parent_deformer)
		"change.drawable.maskedBy" -> stringResource(Res.string.history_drawable_masked_by)
		"change.deformer.part" -> stringResource(Res.string.history_deformer_part)
		"change.deformer.baseAngle" -> stringResource(Res.string.history_deformer_base_angle)
		"change.deformer.quadTransform" -> stringResource(Res.string.history_deformer_quad_transform)
		"change.document.canvasSize" -> stringResource(Res.string.history_document_canvas_size)
		"change.document.worldOrigin" -> stringResource(Res.string.history_document_world_origin)
		else -> stringResource(Res.string.history_unknown)
	}

/** The fixed height of one history step row. */
private val ROW_HEIGHT = 22.dp

/** The diameter of the saved-step dot. */
private val SAVED_DOT = 6.dp

/** The fixed width reserved at the row's trailing edge for the saved dot, so labels align regardless. */
private val SAVED_DOT_SLOT = 14.dp
