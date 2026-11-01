package org.umamo.ui.workspace.spaces.parameters

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import org.umamo.ui.kit.Text
import org.umamo.ui.kit.button.DisclosureChevron
import org.umamo.ui.kit.singleOrDoubleClick
import org.umamo.ui.kit.textentry.InlineRenameField
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.LocalUmamoShapes
import org.umamo.ui.theme.LocalUmamoTypography
import org.umamo.ui.workspace.rowdrag.rowDropHighlight

/** The height of a group header row and of its rename field, the same as the kit's SectionHeader. */
private val GROUP_HEADER_HEIGHT = 22.dp

/**
 * A parameter group's header rail: a recessed rounded row with a disclosure chevron and the group name, where
 * a single tap toggles the group and a double tap opens inline rename.  A nest-into drop tints and outlines it.
 *
 * @param String name The group's display name.
 * @param Boolean expanded Whether the group is open (chevron points down).
 * @param Boolean nesting Whether a drag is hovering to nest into this group (drop highlight).
 * @param Function onToggle Called on a single tap to expand / collapse.
 * @param Function onStartRename Called on a double tap to begin inline rename.
 */
@Composable
internal fun ParameterGroupHeaderBody(
	name: String,
	expanded: Boolean,
	nesting: Boolean,
	onToggle: () -> Unit,
	onStartRename: () -> Unit,
) {
	val colors = LocalUmamoColors.current
	val shapes = LocalUmamoShapes.current
	Row(
		modifier =
			Modifier
				.fillMaxWidth()
				.height(GROUP_HEADER_HEIGHT)
				.clip(shapes.small)
				.background(colors.tabBackground, shape = shapes.small)
				// A "nest into" drop rings the island the way every row drag rings its target.
				.rowDropHighlight(nesting, shapes.small, colors)
				// A single tap toggles the group immediately (no double-tap wait, so it never feels laggy);
				// a double tap opens inline rename.
				.singleOrDoubleClick(onSingle = { onToggle() }, onDouble = onStartRename)
				.padding(horizontal = 4.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		DisclosureChevron(expanded = expanded, tint = colors.text, glyphSize = 10.dp)
		Spacer(modifier = Modifier.width(5.dp))
		Text(text = name, style = LocalUmamoTypography.current.labelMedium)
	}
}

/**
 * The inline rename field for a group header, styled as the recessed rail it replaces while editing.
 * Commits on Enter or focus loss, cancels on Escape (routed by the shell through LocalInlineEditController).
 *
 * @param String initialName The current group name, pre-selected for replacement.
 * @param Function onCommit Called with the trimmed new name.
 * @param Function onCancel Called when the edit is abandoned.
 */
@Composable
internal fun ParameterGroupRenameField(initialName: String, onCommit: (String) -> Unit, onCancel: () -> Unit) {
	val colors = LocalUmamoColors.current
	val shapes = LocalUmamoShapes.current
	Box(
		modifier =
			Modifier
				.fillMaxWidth()
				.height(GROUP_HEADER_HEIGHT)
				.clip(shapes.small)
				.background(colors.tabBackground, shape = shapes.small)
				.padding(horizontal = 4.dp),
		contentAlignment = Alignment.CenterStart,
	) {
		InlineRenameField(
			initialName = initialName,
			textStyle = LocalUmamoTypography.current.labelMedium.copy(color = colors.text),
			cursorColor = colors.text,
			onCommit = onCommit,
			onCancel = onCancel,
		)
	}
}