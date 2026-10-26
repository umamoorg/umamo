package org.umamo.ui.kit.container

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.umamo.ui.kit.Text
import org.umamo.ui.kit.button.DisclosureChevron
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.LocalUmamoShapes
import org.umamo.ui.theme.LocalUmamoTypography

/*
 * The kit's containers, which hold content they know nothing about: the titled panel, the section header
 * row, and the collapsible section card (this file), the tab strip (Tabs.kt), the vertical tab rail
 * (VerticalTabRail.kt), and the strip that collapses what does not fit into an overflow chip (OverflowRow.kt,
 * over the pure packing in OverflowRowPacking.kt).
 */

/**
 * A thin-bordered panel with a header bar (title on the left, an optional [headerAction] on the right) over a
 * tight content column.
 *
 * @param String   title        The header title.
 * @param Modifier modifier     Layout modifier.
 * @param Function headerAction Optional trailing header content (e.g. a [org.umamo.ui.kit.button.Button]).
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
	Row(
		modifier =
			modifier
				.fillMaxWidth()
				.height(22.dp)
				.background(if (hovered) colors.rowHover else Color.Transparent)
				// NOT focusable: a header can sit in chrome that leaves the composition on the next edit (the
				// operation settings strip, a Properties section that hides when its rows go), and Compose
				// leaves focus null when the focused node is disposed - every keyboard shortcut then dies
				// until something focusable is clicked again.  The keyboard belongs to the shell root.
				.focusProperties { canFocus = false }
				.clickable(interactionSource = interaction, indication = null, onClick = onToggle)
				.padding(horizontal = 4.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		DisclosureChevron(expanded = expanded, tint = colors.text, glyphSize = 10.dp)
		Spacer(modifier = Modifier.width(5.dp))
		Text(text = label, style = LocalUmamoTypography.current.labelMedium)
	}
}

/**
 * A collapsible section card: a [SectionHeader] over the section's rows, on a raised card one step above
 * the surface it sits on (the panelThirdElevation fill and border, rounded with the medium shape).  The
 * rows are composed only while the card is [expanded].  The header spans the card and its hover fill is
 * clipped to the rounded corners, so the card adds no inset of its own around it; the rows get the
 * section indent and a small gap between one another.
 *
 * @param String   title    The section heading.
 * @param Boolean  expanded Whether the rows are shown.
 * @param Function onToggle Called when the header is clicked, to open or fold the section.
 * @param Modifier modifier Layout modifier.
 * @param Function content  The section's rows, top to bottom.
 */
@Composable
fun SectionCard(
	title: String,
	expanded: Boolean,
	onToggle: () -> Unit,
	modifier: Modifier = Modifier,
	content: @Composable ColumnScope.() -> Unit,
) {
	val colors = LocalUmamoColors.current
	val shapes = LocalUmamoShapes.current
	Column(
		modifier =
			modifier
				.fillMaxWidth()
				.clip(shapes.medium)
				.background(colors.panelThirdElevation, shape = shapes.medium)
				.border(width = 1.dp, color = colors.panelThirdElevationBorder, shape = shapes.medium),
	) {
		SectionHeader(label = title, expanded = expanded, onToggle = onToggle)

		if (expanded) {
			Column(
				modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, top = 2.dp, bottom = 8.dp),
				// Breathing room between separate rows so filled controls do not touch; a FieldStack's own
				// 1.dp seam is internal, so stacked groups stay tight.
				verticalArrangement = Arrangement.spacedBy(4.dp),
				content = content,
			)
		}
	}
}