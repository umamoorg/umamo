package org.umamo.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.LocalUmamoShapes

/**
 * A flat tab strip - a [Row] over the header background, hosting [Tab]s. No animated indicator (the active
 * tab is shown by its fill); replaces Material's TabRow.
 *
 * @param Modifier modifier Layout modifier.
 * @param Function content  The [Tab]s.
 */
@Composable
fun TabRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
	Row(modifier = modifier.fillMaxWidth(), content = content)
}

/**
 * One tab: a clickable cell that takes the panel color when [selected] and a hover highlight otherwise.
 *
 * @param Boolean  selected Whether this tab is active.
 * @param Function onClick  Selection callback.
 * @param Modifier modifier Layout modifier.
 * @param Function label    The tab's label content.
 */
@Composable
fun Tab(selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, label: @Composable () -> Unit) {
	val colors = LocalUmamoColors.current
	val shapes = LocalUmamoShapes.current
	val interaction = remember { MutableInteractionSource() }
	val hovered by interaction.collectIsHoveredAsState()
	val fill =
		when {
			selected -> colors.headerBackground
			hovered -> colors.rowHover
			else -> colors.tabBackground
		}
	Box(
		modifier =
			modifier
				.padding(horizontal = 2.dp)
				.clickable(interactionSource = interaction, indication = null, onClick = onClick)
				.background(fill, shape = shapes.medium)
				.padding(horizontal = 10.dp, vertical = 4.dp),
		contentAlignment = Alignment.Center,
	) {
		label()
	}
}
