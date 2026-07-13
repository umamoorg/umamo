package org.umamo.ui.kit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.umamo.ui.action.LocalCommands
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

/** The ring radius the entry chips sit on. */
private val PIE_RADIUS = 96.dp

/** How far past the chip ring the wedge background disc extends. */
private val PIE_DISC_OVERSHOOT = 40.dp

/** The entry chips' leading icon size (the pink placeholder square shares it). */
private val PIE_ICON_SIZE = 12.dp

/**
 * The horizontal half-extent reserved for a West / East chip when clamping the pie centre: the chips
 * sit centered ON the ring, so the ring radius plus this covers the widest labels.  Conservative - a
 * pathological label still has the per-chip coercion as its safety net.
 */
private val PIE_CHIP_MAX_HALF_WIDTH = 120.dp

/** Pointer travel (px) from the pie centre below which a release dismisses instead of picking. */
private const val PIE_DEAD_ZONE_PX = 24f

/** The placeholder tint for an entry with no authored icon yet - deliberately loud (see PieMenuEntry.icon). */
private val PIE_ICON_PLACEHOLDER = Color(0xFFFF2BD6)

/**
 * A Blender-style radial pie menu centered at [center]: up to eight entries on a ring, picked by
 * DIRECTION from the centre (not chip bounds), so a coarse flick works as well as a precise click -
 * and the same gesture works for press-drag-release (pen-friendly; the future pen radial menu reuses
 * this component).  A click (or release) inside the dead zone, or on a disabled entry's direction,
 * dismisses without invoking.  Escape is the shell's to route (it closes the pie via the session
 * latch), and so are the 1..N instant digit picks; this composable only handles pointer input.
 *
 * The wedge background disc makes the direction mapping readable: each entry's sector is the exact
 * angular region the pick function resolves to it (the angular Voronoi of the used slot directions),
 * with the hovered sector highlighted; the dead zone renders as the centre disc, holding [title].
 *
 * The centre is CLAMPED so the full ring fits inside the overlay's bounds (Blender constrains its
 * pies to the screen the same way): the whole pie shifts inward rather than squishing chips at an
 * edge.  The clamp lives here - not in the host - because the direction pick, the wedges, the title,
 * and the chips must all share the one effective centre or the pick math desyncs from the drawing.
 *
 * Blender 風の放射状パイメニュー。方向で選択するため、クリックでもドラッグ＆リリースでも使える。
 * 扇形の背景が方向と項目の対応を示し、中央の円にタイトルが乗る。中心はリング全体が収まるよう
 * オーバーレイ境界内にクランプされる。
 *
 * @param List<PieMenuEntry> entries The entries, in Blender slot order (W, E, S, N, NW, NE, SW, SE).
 * @param Offset center The requested pie centre in the host's local pixels (frozen at open by the
 *   host); the rendered centre is this clamped inside the overlay so the ring never clips.
 * @param Function onDismiss Called after an invocation or a dismissing click.
 * @param StringResource? title The pie's name, rendered at the centre (null for none).
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
	val density = LocalDensity.current
	val liveEntries = rememberUpdatedState(entries)
	var hoveredSlot by remember { mutableStateOf(-1) }

	BoxWithConstraints(modifier = modifier.fillMaxSize()) {
		// The centre everything draws and picks from: [center] shifted so the full ring - disc
		// vertically, ring plus the widest chip horizontally - fits inside these bounds.  An overlay
		// too small to fit the pie at all centres it instead (coerceIn would throw on min > max).
		val effectiveCenter =
			with(density) {
				val horizontalMargin = PIE_RADIUS.toPx() + PIE_CHIP_MAX_HALF_WIDTH.toPx()
				val verticalMargin = PIE_RADIUS.toPx() + PIE_DISC_OVERSHOOT.toPx()
				val boundsWidth = constraints.maxWidth.toFloat()
				val boundsHeight = constraints.maxHeight.toFloat()
				Offset(
					if (boundsWidth >= 2f * horizontalMargin) center.x.coerceIn(horizontalMargin, boundsWidth - horizontalMargin) else boundsWidth / 2f,
					if (boundsHeight >= 2f * verticalMargin) center.y.coerceIn(verticalMargin, boundsHeight - verticalMargin) else boundsHeight / 2f,
				)
			}
		// The pointer loop below outlives recompositions (pointerInput(Unit)), so it must read the
		// centre through a live reference - a window resize while the pie is open moves the clamp.
		val liveCenter = rememberUpdatedState(effectiveCenter)

		/**
		 * Picks the entry slot whose direction is nearest the pointer's direction from the centre.
		 *
		 * @param Offset position The pointer position in the overlay's coordinates.
		 * @return Int The nearest slot index, or -1 inside the dead zone.
		 */
		fun slotAt(position: Offset): Int {
			val delta = position - liveCenter.value
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

		Box(
			modifier =
				Modifier
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
		) {
			// The wedge background: each used slot's sector spans the midpoints to its angular neighbors -
			// exactly the region slotAt() resolves to it - with the hovered sector highlighted and the dead
			// zone drawn as the centre disc.  Drawn beneath the chips so labels stay crisp.
			Canvas(modifier = Modifier.fillMaxSize()) {
				val outerRadius = PIE_RADIUS.toPx() + PIE_DISC_OVERSHOOT.toPx()
				val discTopLeft = Offset(effectiveCenter.x - outerRadius, effectiveCenter.y - outerRadius)
				val discSize = Size(outerRadius * 2, outerRadius * 2)
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
						topLeft = discTopLeft,
						size = discSize,
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
							start = effectiveCenter,
							end = Offset(effectiveCenter.x + cos(boundary) * outerRadius, effectiveCenter.y + sin(boundary) * outerRadius),
							strokeWidth = 1f,
						)
					}
				}
				drawCircle(color = colors.panelBackground, radius = PIE_DEAD_ZONE_PX * 1.6f, center = effectiveCenter)
			}
			if (title != null) {
				Text(
					text = stringResource(title),
					style = LocalUmamoTypography.current.labelSmall,
					color = colors.textMuted,
					modifier =
						Modifier.layout { measurable, constraints ->
							val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
							layout(constraints.maxWidth, constraints.maxHeight) {
								placeable.place(
									(effectiveCenter.x - placeable.width / 2f).roundToInt().coerceIn(0, (constraints.maxWidth - placeable.width).coerceAtLeast(0)),
									(effectiveCenter.y - placeable.height / 2f).roundToInt().coerceIn(0, (constraints.maxHeight - placeable.height).coerceAtLeast(0)),
								)
							}
						},
				)
			}
			entries.forEachIndexed { slotIndex, entry ->
				val angle = PIE_SLOT_ANGLES[slotIndex]
				Row(
					verticalAlignment = Alignment.CenterVertically,
					modifier =
						Modifier
							.layout { measurable, constraints ->
								val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
								layout(constraints.maxWidth, constraints.maxHeight) {
									val radiusPx = PIE_RADIUS.toPx()
									val slotX = (effectiveCenter.x + cos(angle) * radiusPx - placeable.width / 2f).roundToInt()
									val slotY = (effectiveCenter.y + sin(angle) * radiusPx - placeable.height / 2f).roundToInt()
									placeable.place(
										slotX.coerceIn(0, (constraints.maxWidth - placeable.width).coerceAtLeast(0)),
										slotY.coerceIn(0, (constraints.maxHeight - placeable.height).coerceAtLeast(0)),
									)
								}
							}
							.background(
								if (slotIndex == hoveredSlot && entry.enabled) colors.accent.copy(alpha = 0.25f) else colors.viewportBadgeBackground,
								shapes.small,
							)
							.alpha(if (entry.enabled) 1f else 0.4f)
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
					// The instant digit shortcut (the shell's pie key branch maps 1..N to the entry order).
					Text(
						text = "${slotIndex + 1}",
						style = LocalUmamoTypography.current.labelSmall,
						color = colors.textMuted,
					)
					Spacer(modifier = Modifier.width(6.dp))
					Text(
						text = stringResource(entry.label),
						style = LocalUmamoTypography.current.labelMedium,
						color = if (slotIndex == hoveredSlot && entry.enabled) colors.text else colors.textMuted,
					)
				}
			}
		}
	}
}
