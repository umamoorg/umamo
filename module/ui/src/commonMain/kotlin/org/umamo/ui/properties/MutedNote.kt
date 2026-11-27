package org.umamo.ui.properties

import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.umamo.ui.kit.Text
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.LocalUmamoTypography

/**
 * A muted one-line note standing in for rows that have nothing to show: a transform the item has none of at
 * the shown pose, a sidebar panel with nothing selected, or a tool with no settings yet.
 *
 * @param StringResource note The note.
 */
@Composable
internal fun MutedNote(note: StringResource) {
	Text(text = stringResource(note), style = LocalUmamoTypography.current.labelSmall, color = LocalUmamoColors.current.textMuted)
}