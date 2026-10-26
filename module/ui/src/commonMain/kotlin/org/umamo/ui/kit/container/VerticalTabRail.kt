package org.umamo.ui.kit.container

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.umamo.ui.kit.Tooltip
import org.umamo.ui.kit.VerticalText
import org.umamo.ui.kit.rememberMeasuredVerticalText
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.LocalUmamoShapes
import org.umamo.ui.theme.LocalUmamoTypography

/** The longest a tab's label runs before it is cut short with an ellipsis and shown whole in a tooltip. */
private val DEFAULT_MAX_TAB_LABEL_LENGTH = 160.dp

/** The rail's inset on its outer side and at its ends; the side the content is on has none. */
private val RAIL_INSET = 4.dp

/** The gap between two tabs. */
private val TAB_SPACING = 2.dp

/** A tab's inset around its label, across the rail. */
private val TAB_PADDING_ACROSS = 4.dp

/** A tab's inset around its label, along the rail. */
private val TAB_PADDING_ALONG = 8.dp

/**
 * One tab on a [VerticalTabRail].
 *
 * @property String id The tab's identity, handed back when it is selected.
 * @property String label The tab's label, already localized.
 */
@Immutable
data class VerticalTabRailItem(val id: String, val label: String)

/** Which side of a [VerticalTabRail] the content its tabs switch sits on. */
enum class RailContentSide {
	/** The content is on the rail's start side: the rail runs down the area's end edge, as a sidebar's does. */
	Start,

	/** The content is on the rail's end side: the rail runs down the area's start edge. */
	End,
}

/**
 * A vertical rail of labelled tabs, read down the column ([VerticalText]): Blender's sidebar tabs.  The
 * selected tab takes the color of the content it switches and meets it, square on the content's side and
 * rounded on the outer side, so it reads as pulled out of the rail toward its content.  Tabs never take
 * focus, so selecting one leaves the keyboard where it was.  A rail too long for its height scrolls, and a
 * label too long for [maxLabelLength] is cut short and shown whole in a tooltip.
 *
 * The rail paints no background of its own; the caller paints the strip it sits in.
 *
 * @param List tabs The tabs, top to bottom.
 * @param String? selectedId The selected tab's id, or null for none.
 * @param Function onSelect Called with a tab's id when it is clicked.
 * @param RailContentSide contentSide Which side the content the tabs switch sits on.
 * @param Modifier modifier Layout modifier.
 * @param Dp maxLabelLength The longest a label runs before it is cut short.
 * @param Color tabFill A tab's fill, or Unspecified for the panel background.
 * @param Color selectedFill The selected tab's fill, the color of the content it meets, or Unspecified for
 *   the header background.
 */
@Composable
fun VerticalTabRail(
	tabs: List<VerticalTabRailItem>,
	selectedId: String?,
	onSelect: (String) -> Unit,
	contentSide: RailContentSide,
	modifier: Modifier = Modifier,
	maxLabelLength: Dp = DEFAULT_MAX_TAB_LABEL_LENGTH,
	tabFill: Color = Color.Unspecified,
	selectedFill: Color = Color.Unspecified,
) {
	val colors = LocalUmamoColors.current
	val resolvedTabFill = tabFill.takeOrElse { colors.panelBackground }
	val resolvedSelectedFill = selectedFill.takeOrElse { colors.headerBackground }
	val railInsets =
		if (contentSide == RailContentSide.Start) {
			PaddingValues(end = RAIL_INSET, top = RAIL_INSET, bottom = RAIL_INSET)
		} else {
			PaddingValues(start = RAIL_INSET, top = RAIL_INSET, bottom = RAIL_INSET)
		}
	Column(
		modifier =
			modifier
				.selectableGroup()
				.width(IntrinsicSize.Max)
				.verticalScroll(rememberScrollState())
				.padding(railInsets),
		verticalArrangement = Arrangement.spacedBy(TAB_SPACING),
	) {
		for (tab in tabs) {
			key(tab.id) {
				RailTab(
					tab = tab,
					selected = tab.id == selectedId,
					onSelect = onSelect,
					contentSide = contentSide,
					maxLabelLength = maxLabelLength,
					tabFill = resolvedTabFill,
					selectedFill = resolvedSelectedFill,
				)
			}
		}
	}
}

/**
 * One tab on the rail.  Its label is measured here so whether it was cut short is known in the same
 * composition, and the tooltip carrying the whole label is decided without a frame's delay.
 *
 * @param VerticalTabRailItem tab The tab.
 * @param Boolean selected Whether it is the selected tab.
 * @param Function onSelect Called with its id when it is clicked.
 * @param RailContentSide contentSide Which side the content sits on, the side its corners are square on.
 * @param Dp maxLabelLength The longest its label runs before it is cut short.
 * @param Color tabFill Its fill when neither selected nor hovered.
 * @param Color selectedFill Its fill when selected.
 */
@Composable
private fun RailTab(
	tab: VerticalTabRailItem,
	selected: Boolean,
	onSelect: (String) -> Unit,
	contentSide: RailContentSide,
	maxLabelLength: Dp,
	tabFill: Color,
	selectedFill: Color,
) {
	val colors = LocalUmamoColors.current
	val corner = LocalUmamoShapes.current.small.topStart
	val square = CornerSize(0.dp)
	val shape =
		if (contentSide == RailContentSide.Start) {
			RoundedCornerShape(topStart = square, topEnd = corner, bottomEnd = corner, bottomStart = square)
		} else {
			RoundedCornerShape(topStart = corner, topEnd = square, bottomEnd = square, bottomStart = corner)
		}
	val interaction = remember { MutableInteractionSource() }
	val hovered by interaction.collectIsHoveredAsState()
	val fill =
		when {
			selected -> selectedFill
			hovered -> colors.rowHover
			else -> tabFill
		}
	val textColor = if (selected || hovered) colors.text else colors.textMuted
	val measured = rememberMeasuredVerticalText(tab.label, LocalUmamoTypography.current.labelMedium, maxLabelLength)
	Tooltip(text = if (measured.truncated) tab.label else "", modifier = Modifier.fillMaxWidth()) {
		Box(
			modifier =
				Modifier
					.fillMaxWidth()
					.clip(shape)
					.background(fill, shape)
					// NOT focusable: a tab click must leave the keyboard with the shell root, or every shortcut
					// dies until something focusable is clicked again.
					.focusProperties { canFocus = false }
					.selectable(
						selected = selected,
						interactionSource = interaction,
						indication = null,
						role = Role.Tab,
						onClick = { onSelect(tab.id) },
					)
					.padding(horizontal = TAB_PADDING_ACROSS, vertical = TAB_PADDING_ALONG),
			contentAlignment = Alignment.Center,
		) {
			VerticalText(measured, textColor)
		}
	}
}