package org.umamo.ui.kit.menu

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.umamo.ui.action.LocalCommands
import org.umamo.ui.kit.Text
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.LocalUmamoShapes
import org.umamo.ui.theme.LocalUmamoTypography
import org.umamo.ui.theme.UmamoIcon
import org.umamo.ui.theme.drawIcon
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * One radial pie-menu entry.  Entries dispatch through the command registry (the action-registry
 * guardrail - the pie never hardcodes a handler), so a rebind or palette invocation of the same
 * command behaves identically.
 *
 * パイメニューの1項目。コマンドレジストリ経由で実行される。
 *
 * @property String commandId The command to invoke when picked.
 * @property StringResource label The entry's localized label.
 * @property Any? argument Optional argument forwarded to the command handler.
 * @property Boolean enabled False renders the entry dimmed and refuses the pick.
 * @property UmamoIcon? icon The entry's leading icon; null renders the bright-pink placeholder
 *   square, a deliberately loud reminder that the entry still needs an authored icon.
 */
data class PieMenuEntry(
	val commandId: String,
	val label: StringResource,
	val argument: Any? = null,
	val enabled: Boolean = true,
	val icon: UmamoIcon? = null,
)

// Blender's slot order for up to eight pie entries: W, E, S, N, NW, NE, SW, SE - the first entries
// land on the cardinal directions, so a four-entry pie reads as a clean compass.  Angles are radians
// in screen space (y down).
private val PIE_SLOT_ANGLES =
	floatArrayOf(
		PI.toFloat(), // W
		0f, // E
		(PI / 2).toFloat(), // S
		(-PI / 2).toFloat(), // N
		(-3 * PI / 4).toFloat(), // NW
		(-PI / 4).toFloat(), // NE
		(3 * PI / 4).toFloat(), // SW
		(PI / 4).toFloat(), // SE
	)

/** The ring radius the entry chips anchor to: each chip's inner edge touches it (see [pieChipOffset]). */
private val PIE_RADIUS = 96.dp

/** How far past the chip ring the wedge background disc extends. */
private val PIE_DISC_OVERSHOOT = 40.dp

/** The entry chips' leading icon size (the pink placeholder square shares it). */
private val PIE_ICON_SIZE = 16.dp

/** Pointer travel (px) from the pie center below which a release dismisses instead of picking. */
private const val PIE_DEAD_ZONE_PX = 24f

/** The placeholder tint for an entry with no authored icon yet - deliberately loud (see PieMenuEntry.icon). */
private val PIE_ICON_PLACEHOLDER = Color(0xFFFF2BD6)

/**
 * The pie's center as last placed, written by the layout and read by the pointer loop.  A plain holder,
 * not snapshot state: the center depends on the measured chips, so only the layout can compute it, and
 * the pointer loop reads it at event time, after the frame's layout has run.
 *
 * @property Offset position The placed center in the overlay's coordinates.
 */
private class PlacedPieCenter(var position: Offset)

/**
 * Where one entry chip sits relative to the pie center, following Blender's pie layout rule: a chip on
 * the left half ends at its ring point and a chip on the right half starts there, so the diagonal chips
 * grow away from each other and the West / East chips away from the title; the North and South chips
 * center on their ring point horizontally and sit outside it, and every other chip centers on its ring
 * point vertically.
 *
 * @param Int slotIndex The entry's slot (see PIE_SLOT_ANGLES).
 * @param Int chipWidth The chip's measured width in pixels.
 * @param Int chipHeight The chip's measured height in pixels.
 * @param Float ringRadius The ring radius in pixels.
 * @return Offset The chip's top-left corner relative to the pie center (screen space, y down).
 */
internal fun pieChipOffset(slotIndex: Int, chipWidth: Int, chipHeight: Int, ringRadius: Float): Offset {
	val directionX = cos(PIE_SLOT_ANGLES[slotIndex])
	val directionY = sin(PIE_SLOT_ANGLES[slotIndex])
	val ringX = directionX * ringRadius
	val ringY = directionY * ringRadius
	// Blender's thresholds: any horizontal lean picks a side, and only the exact North / South slots
	// stack outside the ring.  Screen y grows downward, so North is directionY = -1.
	val left =
		when {
			directionX > 0.01f -> ringX
			directionX < -0.01f -> ringX - chipWidth
			else -> ringX - chipWidth / 2f
		}
	val top =
		when {
			directionY < -0.99f -> ringY - chipHeight
			directionY > 0.99f -> ringY
			else -> ringY - chipHeight / 2f
		}
	return Offset(left, top)
}

/**
 * Shifts a requested pie center along one axis just far enough that the pie's whole extent fits inside
 * the overlay, the way Blender moves a pie that would leave the window.  A pie larger than the overlay
 * along the axis centers its extent instead (coerceIn would throw on min > max).
 *
 * @param Float requested The requested center coordinate.
 * @param Float extentMin How far the pie reaches below the center (zero or negative).
 * @param Float extentMax How far the pie reaches above the center (zero or positive).
 * @param Float bounds The overlay's size along the axis.
 * @return Float The clamped center coordinate.
 */
internal fun clampPieCenterAxis(requested: Float, extentMin: Float, extentMax: Float, bounds: Float): Float =
	if (extentMax - extentMin <= bounds) {
		requested.coerceIn(-extentMin, bounds - extentMax)
	} else {
		(bounds - extentMin - extentMax) / 2f
	}

/**
 * A Blender-style radial pie menu centered at [center]: up to eight entries on a ring, picked by
 * DIRECTION from the center (not chip bounds), so a coarse flick works as well as a precise click -
 * and the same gesture works for press-drag-release (pen-friendly; the future pen radial menu reuses
 * this component).  A click (or release) inside the dead zone, or on a disabled entry's direction,
 * dismisses without invoking.  Escape is the shell's to route (it closes the pie via the session
 * latch), and so are the 1..N instant digit picks; this composable only handles pointer input.
 *
 * The wedge background disc makes the direction mapping readable: each entry's sector is the exact
 * angular region the pick function resolves to it (the angular Voronoi of the used slot directions),
 * with the hovered sector highlighted; the dead zone renders as the center disc, holding [title].
 * Each chip is anchored to the ring by its inner edge (see [pieChipOffset]), so labels grow outward
 * and never cover each other or the title.
 *
 * The center is CLAMPED so the whole pie - the disc and every chip, as measured - fits inside the
 * overlay's bounds (Blender constrains its pies to the screen the same way): the whole pie shifts
 * inward rather than squishing chips at an edge, and only as far as its own labels need.  The clamp
 * lives in this layout - not in the host - because it needs the chips' measured sizes, and the
 * direction pick, the wedges, the title, and the chips must all share the one effective center or the
 * pick math desyncs from the drawing.
 *
 * @param List<PieMenuEntry> entries The entries, in Blender slot order (W, E, S, N, NW, NE, SW, SE).
 * @param Offset center The requested pie center in the host's local pixels (frozen at open by the
 *   host); the rendered center is this clamped inside the overlay so the pie never clips.
 * @param Function onDismiss Called after an invocation or a dismissing click.
 * @param StringResource? title The pie's name, rendered at the center (null for none).
 * @param Modifier modifier The layout modifier (the host passes a stack fill).
 */
@Composable
fun PieMenuOverlay(
	entries: List<PieMenuEntry>,
	center: Offset,
	onDismiss: () -> Unit,
	title: StringResource? = null,
	modifier: Modifier = Modifier,
) {
	val commands = LocalCommands.current
	val colors = LocalUmamoColors.current
	val shapes = LocalUmamoShapes.current
	val liveEntries = rememberUpdatedState(entries)
	val placedCenter = remember { PlacedPieCenter(center) }
	var hoveredSlot by remember { mutableStateOf(-1) }

	/**
	 * Picks the entry slot whose direction is nearest the pointer's direction from the center.
	 *
	 * @param Offset position The pointer position in the overlay's coordinates.
	 * @return Int The nearest slot index, or -1 inside the dead zone.
	 */
	fun slotAt(position: Offset): Int {
		val delta = position - placedCenter.position
		if (delta.getDistance() < PIE_DEAD_ZONE_PX) {
			return -1
		}
		val pointerAngle = atan2(delta.y, delta.x)
		var best = -1
		var bestDifference = Float.MAX_VALUE
		for (slotIndex in liveEntries.value.indices) {
			var difference = abs(pointerAngle - PIE_SLOT_ANGLES[slotIndex])
			if (difference > PI.toFloat()) {
				difference = 2 * PI.toFloat() - difference
			}
			if (difference < bestDifference) {
				bestDifference = difference
				best = slotIndex
			}
		}
		return best
	}

	Layout(
		contents =
			listOf(
				{
					// The wedge background: each used slot's sector spans the midpoints to its angular
					// neighbors - exactly the region slotAt() resolves to it - with the hovered sector
					// highlighted and the dead zone drawn as the center disc.  The layout sizes this canvas
					// to the disc and centers it on the pie, so it draws about its own center.
					Canvas(modifier = Modifier) {
						val discCenter = Offset(size.width / 2f, size.height / 2f)
						val outerRadius = size.minDimension / 2f
						val sortedSlots = entries.indices.sortedBy { slotIndex -> PIE_SLOT_ANGLES[slotIndex] }
						for ((sortedPosition, slotIndex) in sortedSlots.withIndex()) {
							val slotAngle = PIE_SLOT_ANGLES[slotIndex]
							val startDeg: Float
							val sweepDeg: Float
							if (sortedSlots.size == 1) {
								startDeg = 0f
								sweepDeg = 360f
							} else {
								val previousAngle = PIE_SLOT_ANGLES[sortedSlots[(sortedPosition - 1 + sortedSlots.size) % sortedSlots.size]]
								val nextAngle = PIE_SLOT_ANGLES[sortedSlots[(sortedPosition + 1) % sortedSlots.size]]
								var gapBefore = slotAngle - previousAngle
								if (gapBefore <= 0f) {
									gapBefore += 2f * PI.toFloat()
								}
								var gapAfter = nextAngle - slotAngle
								if (gapAfter <= 0f) {
									gapAfter += 2f * PI.toFloat()
								}
								startDeg = (slotAngle - gapBefore / 2f) * 180f / PI.toFloat()
								sweepDeg = (gapBefore + gapAfter) / 2f * 180f / PI.toFloat()
							}
							val hovered = slotIndex == hoveredSlot && entries[slotIndex].enabled
							drawArc(
								color = if (hovered) colors.accent.copy(alpha = 0.25f) else colors.viewportBadgeBackground,
								startAngle = startDeg,
								sweepAngle = sweepDeg,
								useCenter = true,
								topLeft = Offset.Zero,
								size = size,
							)
						}
						// Sector separators, then the dead-zone disc (the dismiss region reads as "no pick").
						if (sortedSlots.size > 1) {
							for ((sortedPosition, slotIndex) in sortedSlots.withIndex()) {
								val nextAngle = PIE_SLOT_ANGLES[sortedSlots[(sortedPosition + 1) % sortedSlots.size]]
								var gapAfter = nextAngle - PIE_SLOT_ANGLES[slotIndex]
								if (gapAfter <= 0f) {
									gapAfter += 2f * PI.toFloat()
								}
								val boundary = PIE_SLOT_ANGLES[slotIndex] + gapAfter / 2f
								drawLine(
									color = colors.panelBorder,
									start = discCenter,
									end = Offset(discCenter.x + cos(boundary) * outerRadius, discCenter.y + sin(boundary) * outerRadius),
									strokeWidth = 1f,
								)
							}
						}
						drawCircle(color = colors.panelBackground, radius = PIE_DEAD_ZONE_PX * 1.6f, center = discCenter)
					}
				},
				{
					if (title != null) {
						Text(
							text = stringResource(title),
							style = LocalUmamoTypography.current.labelSmall,
							color = colors.textMuted,
						)
					}
				},
				{
					entries.forEachIndexed { slotIndex, entry ->
						Row(
							verticalAlignment = Alignment.CenterVertically,
							modifier =
								Modifier
									.background(
										if (slotIndex == hoveredSlot && entry.enabled) colors.accent.copy(alpha = 0.25f) else colors.viewportBadgeBackground,
										shapes.small,
									)
									.alpha(if (entry.enabled) 1f else 0.6f)
									.padding(horizontal = 10.dp, vertical = 5.dp),
						) {
							// The leading icon; a missing one renders the loud placeholder square (an authoring reminder).
							val entryIcon = entry.icon
							Canvas(modifier = Modifier.size(PIE_ICON_SIZE)) {
								if (entryIcon != null) {
									drawIcon(entryIcon, colors.controlGlyph)
								} else {
									drawRect(color = PIE_ICON_PLACEHOLDER)
								}
							}
							Spacer(modifier = Modifier.width(6.dp))
							Text(
								text = stringResource(entry.label),
								style = LocalUmamoTypography.current.labelMedium,
								color = if (slotIndex == hoveredSlot && entry.enabled) colors.text else colors.textMuted,
							)
							Spacer(modifier = Modifier.width(PIE_ICON_SIZE))
							// The instant digit shortcut (the shell's pie key branch maps 1..N to the entry order).
							Text(
								text = "${slotIndex + 1}",
								style = LocalUmamoTypography.current.labelSmall,
								color = colors.textMuted,
							)
						}
					}
				},
			),
		modifier =
			modifier
				.fillMaxSize()
				.pointerInput(Unit) {
					awaitPointerEventScope {
						while (true) {
							val event = awaitPointerEvent()
							val change = event.changes.firstOrNull() ?: continue
							when (event.type) {
								PointerEventType.Move -> hoveredSlot = slotAt(change.position)

								PointerEventType.Release -> {
									// Only the release picks: a click and a press-drag-release both end in exactly
									// one Release, so one gesture invokes the command exactly once - picking on
									// Press as well would double-invoke non-idempotent commands like merge.
									// A dead-zone or disabled-direction release dismisses without invoking.
									val slotIndex = slotAt(change.position)
									val entry = liveEntries.value.getOrNull(slotIndex)
									if (entry != null && entry.enabled) {
										commands.invoke(entry.commandId, entry.argument)
									}
									onDismiss()
								}

								// A press only anchors the gesture (and is consumed below); its release decides.
								PointerEventType.Press -> {}

								else -> {}
							}
							change.consume()
						}
					}
				},
	) { (discMeasurables, titleMeasurables, chipMeasurables), constraints ->
		val looseConstraints = constraints.copy(minWidth = 0, minHeight = 0)
		val discDiameter = ((PIE_RADIUS + PIE_DISC_OVERSHOOT) * 2).roundToPx()
		val discPlaceable = discMeasurables.single().measure(Constraints.fixed(discDiameter, discDiameter))
		val titlePlaceable = titleMeasurables.singleOrNull()?.measure(looseConstraints)
		val chipPlaceables = chipMeasurables.map { measurable -> measurable.measure(looseConstraints) }
		val ringRadius = PIE_RADIUS.toPx()
		val chipOffsets =
			chipPlaceables.mapIndexed { slotIndex, placeable ->
				pieChipOffset(slotIndex, placeable.width, placeable.height, ringRadius)
			}

		// The pie's extent around its center - the disc and every chip - is what the clamp keeps inside.
		val discRadius = discDiameter / 2f
		var extentLeft = -discRadius
		var extentRight = discRadius
		var extentTop = -discRadius
		var extentBottom = discRadius
		for ((slotIndex, placeable) in chipPlaceables.withIndex()) {
			val chipOffset = chipOffsets[slotIndex]
			extentLeft = minOf(extentLeft, chipOffset.x)
			extentRight = maxOf(extentRight, chipOffset.x + placeable.width)
			extentTop = minOf(extentTop, chipOffset.y)
			extentBottom = maxOf(extentBottom, chipOffset.y + placeable.height)
		}
		val overlayWidth = constraints.maxWidth
		val overlayHeight = constraints.maxHeight
		val effectiveCenter =
			Offset(
				clampPieCenterAxis(center.x, extentLeft, extentRight, overlayWidth.toFloat()),
				clampPieCenterAxis(center.y, extentTop, extentBottom, overlayHeight.toFloat()),
			)

		layout(overlayWidth, overlayHeight) {
			placedCenter.position = effectiveCenter
			discPlaceable.place((effectiveCenter.x - discRadius).roundToInt(), (effectiveCenter.y - discRadius).roundToInt())
			if (titlePlaceable != null) {
				titlePlaceable.place(
					(effectiveCenter.x - titlePlaceable.width / 2f).roundToInt().coerceIn(0, (overlayWidth - titlePlaceable.width).coerceAtLeast(0)),
					(effectiveCenter.y - titlePlaceable.height / 2f).roundToInt().coerceIn(0, (overlayHeight - titlePlaceable.height).coerceAtLeast(0)),
				)
			}
			// The per-chip coercion is the safety net for a pie larger than the overlay itself.
			for ((slotIndex, placeable) in chipPlaceables.withIndex()) {
				val chipOffset = chipOffsets[slotIndex]
				placeable.place(
					(effectiveCenter.x + chipOffset.x).roundToInt().coerceIn(0, (overlayWidth - placeable.width).coerceAtLeast(0)),
					(effectiveCenter.y + chipOffset.y).roundToInt().coerceIn(0, (overlayHeight - placeable.height).coerceAtLeast(0)),
				)
			}
		}
	}
}