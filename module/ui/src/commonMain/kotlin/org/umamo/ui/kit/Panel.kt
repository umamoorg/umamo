package org.umamo.ui.kit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.dp
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.LocalUmamoIcons
import org.umamo.ui.theme.LocalUmamoTypography
import org.umamo.ui.theme.drawIcon

/**
 * A thin-bordered panel with a header bar (title on the left, an optional [headerAction] on the right) over a
 * tight content column.
 *
 * @param String   title        The header title.
 * @param Modifier modifier     Layout modifier.
 * @param Function headerAction Optional trailing header content (e.g. a [Button]).
 * @param Function content      The panel body.
 */
@Composable
fun Panel(
	title: String,
	modifier: Modifier = Modifier,
	headerAction: (@Composable () -> Unit)? = null,
	content: @Composable ColumnScope.() -> Unit,
) {
	val colors = LocalUmamoColors.current
	Column(modifier = modifier.border(1.dp, colors.panelBorder).background(colors.panelBackground)) {
		Row(
			modifier =
				Modifier.fillMaxWidth().background(colors.headerBackground)
					.padding(horizontal = 6.dp, vertical = 4.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			Text(text = title, style = LocalUmamoTypography.current.labelLarge)
			Spacer(modifier = Modifier.weight(1f))
			headerAction?.invoke()
		}
		Column(modifier = Modifier.padding(6.dp), content = content)
	}
}

/**
 * A disclosure row: a small chevron (right when collapsed, down when expanded) plus a label, the whole row
 * clickable to [onToggle], with a hover highlight.
 *
 * @param String   label    The section label.
 * @param Boolean  expanded Whether the section is open.
 * @param Function onToggle Toggle callback.
 * @param Modifier modifier Layout modifier (e.g. a leading indent for a nested section).
 */
@Composable
fun SectionHeader(label: String, expanded: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
	val colors = LocalUmamoColors.current
	val interaction = remember { MutableInteractionSource() }
	val hovered by interaction.collectIsHoveredAsState()
	val chevronColor = colors.textMuted
	Row(
		modifier =
			modifier
				.fillMaxWidth()
				.height(22.dp)
				.background(if (hovered) colors.rowHover else Color.Transparent)
				.clickable(interactionSource = interaction, indication = null, onClick = onToggle)
				.padding(horizontal = 4.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		val chevron =
			if (expanded) {
				LocalUmamoIcons.chevronDown
			} else {
				LocalUmamoIcons.chevronRight
			}
		Canvas(modifier = Modifier.size(10.dp)) {
			drawIcon(chevron, colors.textMuted)
		}
		Spacer(modifier = Modifier.width(5.dp))
		Text(text = label, style = LocalUmamoTypography.current.labelMedium)
	}
}
