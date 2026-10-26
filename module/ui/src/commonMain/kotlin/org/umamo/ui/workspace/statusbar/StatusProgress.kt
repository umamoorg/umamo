package org.umamo.ui.workspace.statusbar

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import org.umamo.ui.kit.ProgressBar
import org.umamo.ui.kit.Text
import org.umamo.ui.progress.ProgressLabels
import org.umamo.ui.progress.ProgressTask
import org.umamo.ui.resources.*
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.LocalUmamoTypography

/** The bar's width beside the label. */
private val PROGRESS_BAR_WIDTH = 96.dp

/** The bar's height: a thin line under the strip's text height. */
private val PROGRESS_BAR_HEIGHT = 4.dp

/**
 * The running tasks as the strip's middle zone shows them: the newest one's label, its count when it has
 * one, its bar, and how many more are running behind it.
 *
 * @param List<ProgressTask> tasks The running tasks, oldest first; not empty.
 */
@Composable
internal fun StatusProgress(tasks: List<ProgressTask>) {
	val newest = tasks.last()
	val colors = LocalUmamoColors.current
	val style = LocalUmamoTypography.current.labelMedium
	val total = newest.total

	Row(verticalAlignment = Alignment.CenterVertically) {
		Text(
			text = progressText(newest.labelKey, newest.arguments),
			style = style,
			color = colors.text,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
			modifier = Modifier.weight(1f, fill = false),
		)

		if (total != null) {
			Spacer(modifier = Modifier.width(8.dp))
			Text(text = stringResource(Res.string.progress_count, newest.completed, total), style = style, color = colors.textMuted, maxLines = 1)
		}

		Spacer(modifier = Modifier.width(8.dp))
		ProgressBar(
			fraction = total?.let { steps -> newest.completed.toFloat() / maxOf(1, steps) },
			modifier = Modifier.width(PROGRESS_BAR_WIDTH).height(PROGRESS_BAR_HEIGHT),
		)

		if (tasks.size > 1) {
			Spacer(modifier = Modifier.width(8.dp))
			Text(text = stringResource(Res.string.progress_more_tasks, tasks.size - 1), style = style, color = colors.textMuted, maxLines = 1)
		}
	}
}

/**
 * A task's label, localized; a label this table does not know shows its key, so a new task never shows blank.
 *
 * @param String       labelKey  The label, one of [ProgressLabels].
 * @param List<String> arguments Its arguments.
 * @return String The text.
 */
@Composable
internal fun progressText(labelKey: String, arguments: List<String>): String =
	when (labelKey) {
		ProgressLabels.DOCUMENT_OPENING -> stringResource(Res.string.progress_document_opening, arguments.firstOrNull().orEmpty())
		ProgressLabels.ARTWORK_ADDING -> stringResource(Res.string.progress_artwork_adding, arguments.firstOrNull().orEmpty())
		ProgressLabels.ARTWORK_RELOADING -> stringResource(Res.string.progress_artwork_reloading)
		ProgressLabels.ARTWORK_MESHING -> stringResource(Res.string.progress_artwork_meshing)
		ProgressLabels.MESH_GENERATING -> stringResource(Res.string.progress_mesh_generating)
		ProgressLabels.ATLAS_PACKING -> stringResource(Res.string.progress_atlas_packing)
		else -> labelKey
	}