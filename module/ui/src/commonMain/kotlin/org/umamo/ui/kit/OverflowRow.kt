package org.umamo.ui.kit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import org.umamo.ui.resources.*
import org.umamo.ui.theme.LocalUmamoIcons

/** The inset around one collapsed control inside the overflow panel. */
private val OVERFLOW_MENU_ROW_PADDING_HORIZONTAL = 8.dp

/** The gap between two stacked controls inside the overflow panel. */
private val OVERFLOW_MENU_ROW_PADDING_VERTICAL = 2.dp

/**
 * The separation a flexible gap keeps once its leftover is gone - the same 8.dp the strip puts between
 * two adjacent controls, so a fully squeezed gap reads as ordinary spacing rather than as a seam.
 */
val FLEXIBLE_SPACE_MIN_WIDTH = 8.dp

/**
 * Declares the controls on an [OverflowRow].  Deliberately NOT a composable scope: the row has to hold
 * each control's body as a value so the overflow panel can render exactly the collapsed subset, and a
 * composable collector would let one inner restartable scope re-append to a list the outer scope never
 * rebuilt.  This is [androidx.compose.foundation.lazy.LazyListScope]'s shape, for the same reason.
 *
 * The consequence is worth stating plainly: CompositionLocals may only be read inside an item body,
 * never in the builder that declares the items.
 *
 * It is also not a RowScope, so Modifier.weight inside an item is a compile error - weight distribution
 * belongs to [flexibleSpace], which the packer understands.
 */
interface OverflowRowScope {
	/**
	 * A control that collapses into the overflow dropdown when the strip runs out of room.  Collapsing
	 * happens from the end: the last declared item goes first.
	 *
	 * Pass [minWidth] for a control that can usefully give up width before it gives up entirely - a search
	 * box is the obvious one - and it is measured against the room actually left, compressing down to that
	 * floor and only collapsing below it.  Left unspecified the control is placed at its natural width or
	 * not at all, which is what a chip or a button wants.
	 *
	 * @param Any?     key      A stable identity for the slot, so its state survives a sibling appearing or
	 *   disappearing.  Defaults to the declaration index, which is only safe when the item list is fixed.
	 * @param Dp       minWidth The narrowest this control may be squeezed to; unspecified to never compress.
	 * @param Function content  The control.
	 */
	fun item(key: Any? = null, minWidth: Dp = Dp.Unspecified, content: @Composable () -> Unit)

	/**
	 * A control that is always placed, even when placing it overflows the strip.  Reserve this for the
	 * one control a strip is about - a header that hides its own subject is worse than one that clips.
	 *
	 * @param Any?     key     A stable identity for the slot; see [item].
	 * @param Function content The control.
	 */
	fun pinnedItem(key: Any? = null, content: @Composable () -> Unit)

	/**
	 * A weighted gap that absorbs leftover width - the replacement for Spacer(Modifier.weight(w)).  It
	 * carries no spacing of its own on either side, since it IS the separation.
	 *
	 * [minWidth] is the separation the gap never closes below, reserved before the controls around it are
	 * admitted rather than shared out of what happens to be left.  Without it a starved strip resolves the
	 * gap to nothing and the controls either side butt together, which reads as a rendering fault.
	 *
	 * @param Float weight   This gap's share of the leftover width.
	 * @param Dp    minWidth The separation this gap keeps even with no leftover to share.
	 */
	fun flexibleSpace(weight: Float = 1f, minWidth: Dp = FLEXIBLE_SPACE_MIN_WIDTH)
}

/**
 * One declared slot: its identity, how it behaves when width runs short, and its body.
 *
 * @property Any               key      The slot's stable identity, also its subcomposition slot id.
 * @property OverflowSlotKind  kind     How the slot behaves when width runs short.
 * @property Float             weight   The share of leftover width, for a Flexible slot.
 * @property Dp                minWidth A control's squeeze floor, or a gap's minimum separation.
 * @property Function?         content  The control, or null for a Flexible gap.
 */
private class OverflowSlot(
	val key: Any,
	val kind: OverflowSlotKind,
	val weight: Float,
	val minWidth: Dp,
	val content: (@Composable () -> Unit)?,
)

/** Subcomposition slot ids, wrapped so a caller's item key can never collide with the overflow chip's. */
private sealed interface OverflowSlotId {
	data class Item(val key: Any) : OverflowSlotId

	data object OverflowButton : OverflowSlotId
}

/** Collects the declared slots into a plain list; rebuilt on every recomposition, never remembered. */
private class OverflowRowCollector : OverflowRowScope {
	val slots = mutableListOf<OverflowSlot>()

	override fun item(key: Any?, minWidth: Dp, content: @Composable () -> Unit) {
		add(key, OverflowSlotKind.Collapsible, weight = 0f, minWidth = minWidth, content = content)
	}

	override fun pinnedItem(key: Any?, content: @Composable () -> Unit) {
		add(key, OverflowSlotKind.Pinned, weight = 0f, minWidth = Dp.Unspecified, content = content)
	}

	override fun flexibleSpace(weight: Float, minWidth: Dp) {
		add(key = null, kind = OverflowSlotKind.Flexible, weight = weight, minWidth = minWidth, content = null)
	}

	/**
	 * Appends one slot, defaulting its key to the declaration index and rejecting duplicates.
	 *
	 * @param Any?             key      The caller's key, or null to key by declaration index.
	 * @param OverflowSlotKind kind     How the slot behaves when width runs short.
	 * @param Float            weight   The share of leftover width, for a Flexible gap.
	 * @param Dp               minWidth The narrowest the control may be squeezed to, or unspecified.
	 * @param Function?        content  The control, or null for a Flexible gap.
	 */
	private fun add(key: Any?, kind: OverflowSlotKind, weight: Float, minWidth: Dp, content: (@Composable () -> Unit)?) {
		val slotKey = key ?: slots.size
		// A duplicate key silently merges two slots' subcompositions, so their state would swap under the
		// user.  That is a programming error, not a runtime branch - same posture as SpaceRegistry.
		require(slots.none { existing -> existing.key == slotKey }) { "duplicate OverflowRow slot key: $slotKey" }
		slots.add(OverflowSlot(key = slotKey, kind = kind, weight = weight, minWidth = minWidth, content = content))
	}
}

/**
 * A horizontal strip whose controls collapse into a trailing overflow dropdown instead of being crushed
 * when the strip runs out of room.  A plain Row measures each un-weighted child against whatever width
 * is left, so a narrowing strip squeezes its controls to nothing in place; this measures every control
 * at its natural width and simply stops placing the ones that do not fit, handing them to the chip.
 *
 * Each item is measured with an unbounded width and the strip's own height, so an item must be
 * intrinsically sized - Modifier.fillMaxWidth at an item root resolves to zero against an infinite
 * maximum and the item is dropped.  An item that renders nothing costs nothing: it measures zero and is
 * skipped, spacing and all, which is how a mode-gated control disappears cleanly.
 *
 * Note for future adopters: this is a SubcomposeLayout, whose intrinsic measurements are expensive and
 * lossy.  It is fine under a fixed height (an area header), but do not drop it into a parent that asks
 * for intrinsics - Modifier.height(IntrinsicSize.Min), say - without checking first.
 *
 * @param Modifier          modifier                   The layout modifier.
 * @param Dp                horizontalSpacing          The gap between adjacent controls.
 * @param Alignment         verticalAlignment          How controls align across the strip's height.
 * @param String            overflowContentDescription The overflow chip's accessible label and tooltip.
 * @param Function          content                    Declares the controls; see [OverflowRowScope].
 */
@Composable
fun OverflowRow(
	modifier: Modifier = Modifier,
	horizontalSpacing: Dp = 8.dp,
	verticalAlignment: Alignment.Vertical = Alignment.CenterVertically,
	overflowContentDescription: String = stringResource(Res.string.header_more),
	content: OverflowRowScope.() -> Unit,
) {
	val collector = OverflowRowCollector()
	collector.content()
	val slots = collector.slots
	var overflowExpanded by remember { mutableStateOf(false) }
	// Keys rather than slots: keys compare cheaply and correctly, while a slot holds a lambda that is a
	// fresh instance every recomposition and so would never compare equal.
	val collapsedKeys = remember { mutableStateOf<List<Any>>(emptyList()) }
	// What the verdict in collapsedKeys was computed against.  Reusing it while the strip and its slot list
	// are unchanged is what lets the second pass DROP the slot whose measurement decided the collapse -
	// deciding it costs one composition, and without this that composition would linger unplaced, showing up
	// in the semantics tree and duplicating the copy the panel renders.
	val lastPackKey = remember { mutableStateOf<Pair<Int, List<Any>>?>(null) }
	// Remembered rather than measured up front: the chip is composed ONLY when something collapsed (an
	// always-composed chip is an unplaced node in the tree), yet its width has to be reserved on every pass
	// or the strip oscillates.  Reserving the last measured width keeps both true.
	val overflowButtonWidthPx = remember { mutableStateOf(0) }

	SubcomposeLayout(modifier = modifier) { constraints ->
		val slotConstraints = Constraints(maxHeight = constraints.maxHeight)
		val spacingPx = horizontalSpacing.roundToPx()
		val placeables = HashMap<Int, Placeable>(slots.size)
		val slotKeys = slots.map { slot -> slot.key }
		val packKey = constraints.maxWidth to slotKeys
		// A changed width or slot list re-evaluates from scratch, so a widened strip can re-admit what it
		// collapsed; an unchanged one honors the previous verdict and leaves the decided slots uncomposed.
		val preCollapsed =
			if (lastPackKey.value == packKey) {
				slots.indices.filterTo(mutableSetOf()) { slotIndex -> slotKeys[slotIndex] in collapsedKeys.value }
			} else {
				emptySet()
			}

		// Measurables are cached, not just placeables: the packer walks the strip up to twice, and a
		// compressible slot is offered a different maximum each time.  subcompose may not be called twice
		// with one id in a pass, so the slot is composed once and re-measured against the new bound.
		val measurables = HashMap<Int, Measurable>(slots.size)
		val measuredAtMaxWidthPx = HashMap<Int, Int>(slots.size)
		val packing =
			packOverflowRow(
				slots =
					slots.map { slot ->
						OverflowSlotSpec(
							kind = slot.kind,
							weight = slot.weight,
							minWidthPx = if (slot.minWidth == Dp.Unspecified) 0 else slot.minWidth.roundToPx(),
						)
					},
				availableWidthPx = constraints.maxWidth,
				boundedWidth = constraints.hasBoundedWidth,
				spacingPx = spacingPx,
				overflowButtonWidthPx = overflowButtonWidthPx.value,
				preCollapsedSlotIndices = preCollapsed,
			) { slotIndex, maxWidthPx ->
				val slot = slots[slotIndex]
				val measurable =
					measurables.getOrPut(slotIndex) {
						subcompose(OverflowSlotId.Item(slot.key)) {
							// One wrapper Row per item, so a multi-control item is one indivisible group whose
							// internal spacing matches the strip's, and subcompose always yields one measurable.
							Row(verticalAlignment = verticalAlignment, horizontalArrangement = Arrangement.spacedBy(horizontalSpacing)) {
								slot.content?.invoke()
							}
						}.first()
					}
				if (measuredAtMaxWidthPx[slotIndex] != maxWidthPx) {
					val boundedMaxPx = if (maxWidthPx == OVERFLOW_WIDTH_UNBOUNDED) Constraints.Infinity else maxWidthPx.coerceAtLeast(0)
					placeables[slotIndex] = measurable.measure(slotConstraints.copy(maxWidth = boundedMaxPx))
					measuredAtMaxWidthPx[slotIndex] = maxWidthPx
				}
				placeables.getValue(slotIndex).width
			}

		val collapsed = packing.collapsedSlotIndices.map { slotIndex -> slotKeys[slotIndex] }
		if (collapsedKeys.value != collapsed) {
			collapsedKeys.value = collapsed
		}
		if (lastPackKey.value != packKey) {
			lastPackKey.value = packKey
		}

		val overflowPlaceable =
			if (collapsed.isEmpty()) {
				null
			} else {
				subcompose(OverflowSlotId.OverflowButton) {
					val collapsedSlots = slots.filter { slot -> slot.key in collapsed }
					PopupChip(
						contentDescription = overflowContentDescription,
						icon = LocalUmamoIcons.dots,
						expanded = overflowExpanded,
						onExpandedChange = { next -> overflowExpanded = next },
					) {
						collapsedSlots.forEach { slot ->
							key(slot.key) {
								OverflowMenuRow { slot.content?.invoke() }
							}
						}
					}
				}.first().measure(slotConstraints)
			}
		if (overflowPlaceable != null && overflowButtonWidthPx.value != overflowPlaceable.width) {
			// The first strip to overflow reserved nothing, so it packed one chip too generously.  Recording
			// the real width re-measures once and settles; from then on the reserve is a constant.
			overflowButtonWidthPx.value = overflowPlaceable.width
		}
		// The chip is gone, so an open panel would be anchored to nothing.  It cannot be OPENED while
		// unplaced (an absent node takes no clicks), but it can be open when the strip widens under it.
		if (collapsed.isEmpty() && overflowExpanded) {
			overflowExpanded = false
		}

		// The chip counts toward the strip's height too - it is placed, so a strip sized to the items alone
		// would clip it whenever it is the tallest thing on the row.
		val contentHeight = maxOf(placeables.values.maxOfOrNull { it.height } ?: 0, overflowPlaceable?.height ?: 0)
		val height = constraints.constrainHeight(contentHeight)
		layout(constraints.constrainWidth(packing.contentWidthPx), height) {
			packing.placements.forEach { placement ->
				val placeable = placeables[placement.slotIndex] ?: return@forEach
				placeable.place(x = placement.xPx, y = verticalAlignment.align(placeable.height, height))
			}
			val buttonXPx = packing.overflowButtonXPx
			if (overflowPlaceable != null && buttonXPx != null) {
				overflowPlaceable.place(x = buttonXPx, y = verticalAlignment.align(overflowPlaceable.height, height))
			}
		}
	}
}

/**
 * One control's row inside the overflow panel: the control at its natural size, inset from the panel
 * edges.  Collapses to nothing when the control renders nothing, so a mode-gated item that ended up past
 * the collapse point leaves an empty sliver rather than a visible gap.
 *
 * @param Function content The collapsed control.
 */
@Composable
private fun OverflowMenuRow(content: @Composable () -> Unit) {
	Layout(content = content) { measurables, constraints ->
		val horizontalPaddingPx = OVERFLOW_MENU_ROW_PADDING_HORIZONTAL.roundToPx()
		val verticalPaddingPx = OVERFLOW_MENU_ROW_PADDING_VERTICAL.roundToPx()
		val placeables = measurables.map { measurable -> measurable.measure(constraints) }
		val contentWidth = placeables.maxOfOrNull { placeable -> placeable.width } ?: 0
		if (contentWidth == 0) {
			return@Layout layout(0, 0) {}
		}
		val contentHeight = placeables.maxOf { placeable -> placeable.height }
		layout(contentWidth + horizontalPaddingPx * 2, contentHeight + verticalPaddingPx * 2) {
			placeables.forEach { placeable -> placeable.place(horizontalPaddingPx, verticalPaddingPx) }
		}
	}
}