package org.umamo.ui.workspace.spaces.outliner

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import org.umamo.ui.kit.button.IconSlot
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.LocalUmamoIcons
import org.umamo.ui.theme.drawIcon

/*
 * The slots a row is built from: the disclosure chevron, the type icon, the visibility eye, and the
 * selectable pointer.  The three that take a press consume it, so the row's whole-row selection handler
 * skips them and a slot only does its own job.
 */

/**
 * Consumes every press on the slot and hands it to [onPress] with whether Shift was held, so the row's
 * whole-row selection handler skips it.  The pointer loop is long-lived and never restarts, so it reads
 * the latest callback through rememberUpdatedState.
 *
 * @param Function onPress Runs on each press; the argument reports whether Shift was held.
 * @return Modifier This modifier with the press handler attached.
 */
@Composable
private fun Modifier.consumingPress(onPress: (shiftHeld: Boolean) -> Unit): Modifier {
	val currentOnPress by rememberUpdatedState(onPress)

	return this.pointerInput(Unit) {
		awaitPointerEventScope {
			while (true) {
				val event = awaitPointerEvent()

				if (event.type == PointerEventType.Press) {
					event.changes.forEach { change -> change.consume() }
					currentOnPress(event.keyboardModifiers.isShiftPressed)
				}
			}
		}
	}
}

/**
 * The disclosure chevron slot - a fixed-width box drawing a right (collapsed) or down (expanded)
 * triangle, the whole slot toggling expansion.  Always occupies its width even when [visible] is false,
 * so leaf and parent rows align, and a leaf row's chevron area selects the row like any blank space.
 *
 * The accessible name is the action a click performs, because the row's own name never says whether the
 * node is open - the chevron is the only place that state is exposed.  It carries no tooltip for the
 * matching reason [org.umamo.ui.kit.button.DisclosureChevron] carries none: the row label sits right beside it.
 *
 * @param Boolean visible Whether to draw the chevron (false for a childless node).
 * @param Boolean expanded Whether the node is expanded.
 * @param OutlinerLabels labels The chrome the accessible name comes from.
 * @param Function onToggle Toggle callback.
 * @param Color tint The chevron color.
 */
@Composable
internal fun ChevronSlot(visible: Boolean, expanded: Boolean, labels: OutlinerLabels, onToggle: () -> Unit, tint: Color) {
	val base = Modifier.size(OUTLINER_CHEVRON_WIDTH)
	val slot =
		if (visible) {
			val toggleLabel = if (expanded) labels.collapse else labels.expand
			base
				.consumingPress { onToggle() }
				.semantics { contentDescription = toggleLabel }
		} else {
			base
		}
	Box(modifier = slot, contentAlignment = Alignment.Center) {
		if (visible) {
			Canvas(modifier = Modifier.size(9.dp)) {
				val chevron = Path()

				if (expanded) {
					chevron.moveTo(size.width * 0.15f, size.height * 0.35f)
					chevron.lineTo(size.width * 0.85f, size.height * 0.35f)
					chevron.lineTo(size.width * 0.50f, size.height * 0.72f)
				} else {
					chevron.moveTo(size.width * 0.35f, size.height * 0.15f)
					chevron.lineTo(size.width * 0.72f, size.height * 0.50f)
					chevron.lineTo(size.width * 0.35f, size.height * 0.85f)
				}

				chevron.close()
				drawPath(chevron, color = tint)
			}
		}
	}
}

/**
 * The type-icon slot: a fixed-width box drawing the themed icon for [OutlinerIcon], tinted by node family
 * ([UmamoColors.outlinerObjectTint] / [UmamoColors.outlinerDeformTint]).  Only this function maps an
 * [OutlinerIcon] to its art, so the row layout never changes when an icon does.
 *
 * @param OutlinerIcon icon The icon kind.
 * @param Boolean dimmed Whether the row is muted (the glyph fades to match).
 */
@Composable
internal fun OutlinerIconSlot(icon: OutlinerIcon, dimmed: Boolean) {
	val colors = LocalUmamoColors.current
	val tint =
		when (icon) {
			OutlinerIcon.PuppetRoot, OutlinerIcon.Part, OutlinerIcon.Drawable -> {
				if (dimmed) colors.outlinerObjectTintDimmed else colors.outlinerObjectTint
			}

			OutlinerIcon.Armature, OutlinerIcon.WarpDeformer, OutlinerIcon.RotationDeformer -> {
				if (dimmed) colors.outlinerDeformTintDimmed else colors.outlinerDeformTint
			}
		}
	Box(modifier = Modifier.size(OUTLINER_ICON_WIDTH), contentAlignment = Alignment.Center) {
		Canvas(modifier = Modifier.size(20.dp)) {
			when (icon) {
				OutlinerIcon.PuppetRoot -> drawIcon(LocalUmamoIcons.puppetRoot, tint)
				OutlinerIcon.Armature -> drawIcon(LocalUmamoIcons.armature, tint)
				OutlinerIcon.Part -> drawIcon(LocalUmamoIcons.part, tint)
				OutlinerIcon.Drawable -> drawIcon(LocalUmamoIcons.mesh, tint)
				OutlinerIcon.WarpDeformer -> drawIcon(LocalUmamoIcons.warpDeformer, tint)
				OutlinerIcon.RotationDeformer -> drawIcon(LocalUmamoIcons.rotationDeformer, tint)
			}
		}
	}
}

/**
 * The clickable visibility eye: open while the row's entity is shown, struck-through while hidden.  The slot
 * consumes its own press so the whole-row selection handler skips it.
 *
 * Unlike the chevron this one does take a tooltip: the eye is genuinely icon-only, and nothing else in the
 * row says what it does.  It reuses the context menu's own label so the two routes to the same edit read
 * identically.
 *
 * @param Boolean hidden Whether the row is dimmed (hidden or sketch).
 * @param String label The accessible name and tooltip: the menu's visibility entry.
 * @param Color tint The indicator color.
 * @param Function onToggle Flips the visibility; the argument reports whether Shift was held (subtree).
 */
@Composable
internal fun VisibilityIndicator(hidden: Boolean, label: String, tint: Color, onToggle: (shiftHeld: Boolean) -> Unit) {
	IconSlot(
		icon = if (hidden) LocalUmamoIcons.eyeHidden else LocalUmamoIcons.eyeVisible,
		contentDescription = label,
		tint = tint,
		modifier = Modifier.consumingPress(onToggle),
		slotSize = DpSize(OUTLINER_RESTRICTION_SLOT_WIDTH, OUTLINER_RESTRICTION_SLOT_WIDTH),
		glyphSize = 16.dp,
	)
}

/**
 * The trailing selectable toggle: a small pointer that flips whether the row's entity can be picked in the
 * viewport.  Like [VisibilityIndicator], it consumes its own press and is labeled from the context menu's
 * own string.
 *
 * @param Boolean selectable Whether the entity is viewport-selectable (plain vs struck-through pointer).
 * @param String label The accessible name and tooltip: the menu's selectability entry.
 * @param Color tint The base glyph color.
 * @param Function onToggle Flips the selectability; the argument reports whether Shift was held (subtree).
 */
@Composable
internal fun SelectableIndicator(selectable: Boolean, label: String, tint: Color, onToggle: (shiftHeld: Boolean) -> Unit) {
	IconSlot(
		icon = if (selectable) LocalUmamoIcons.selectable else LocalUmamoIcons.unselectable,
		contentDescription = label,
		tint = tint,
		modifier = Modifier.consumingPress(onToggle),
		slotSize = DpSize(OUTLINER_RESTRICTION_SLOT_WIDTH, OUTLINER_RESTRICTION_SLOT_WIDTH),
		glyphSize = 16.dp,
	)
}