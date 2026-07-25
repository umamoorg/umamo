package org.umamo.runtime.keyform

import org.umamo.runtime.model.ChannelGrids
import org.umamo.runtime.model.ChannelValue
import org.umamo.runtime.model.ColorRgb
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.FormChannel
import org.umamo.runtime.model.Glue
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.Part
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.RenderGroup

/*
 * The post-import compaction pass: reduce every channel track to the axes and keys it actually needs, and
 * lift a track that never varies into its owner's static field.
 *
 * Cubism keys every channel on every cell of one bundled grid, so straight after the fan-out a typical
 * drawable's opacity track is the same value repeated across the whole grid.  Compacting retires those
 * tracks entirely, which is what turns an imported Cubism rig into a sparse Umamo one - and it is where
 * the split pays for itself: the per-frame scalar blends collapse into field reads.
 *
 * Two rules keep this safe:
 *
 *   - GEOMETRY IS NEVER COMPACTED.  A toggle-part drawable can carry bit-identical geometry across an axis
 *     whose keys deliberately do not bracket the parameter's default; dropping that axis would make the
 *     drawable permanently visible, which is a change to someone's rig, not a change in precision.  It
 *     would also move the delta texture's column layout for no benefit.
 *   - EXACT ONLY.  A key is dropped only when the stored value is bit-equal to the blend of its
 *     neighbours; see KeyformGridCompaction.  A near-miss is a value the rigger chose.
 *
 * Run as a separate model pass rather than inside the importers so it is independently testable and can be
 * switched off wholesale when a result needs bisecting against the un-compacted import.
 *
 * DEFERRED: three importer special cases are now redundant in principle - Cmo3Import's
 * `takeIf { it.axes.isNotEmpty() }` on part tracks, its PartComposite seed from the first form, and
 * Moc3Import's deliberate zero-axis single-cell grid.  They are left in place because removing them is a
 * BEHAVIOUR change, not a cleanup: each decides whether a static part's authored composite or its baked
 * keyform wins, and the compaction-on/off equivalence test cannot see the difference (both sides carry the
 * special case).  Only the corpus parity and C-core oracles would catch a mistake, so they should come out
 * one at a time under those gates rather than as a batch.
 *
 * 取り込み後の圧縮パス。変化しないトラックは静的値へ畳み込む。幾何格子は決して圧縮しない。
 */

/**
 * One owner's channel tracks after compaction: the tracks that still vary, and the constants lifted out.
 *
 * @property ChannelGrids channelGrids The surviving tracks.
 * @property Map constants The channels that collapsed, and the value each holds everywhere.
 */
private class CompactedChannels(
	val channelGrids: ChannelGrids,
	val constants: Map<FormChannel, ChannelValue>,
)

/**
 * Compacts every track, separating the ones that collapsed to a constant from the ones that still vary.
 *
 * @return CompactedChannels The surviving tracks plus the lifted constants.
 */
private fun ChannelGrids.compactedTracks(): CompactedChannels {
	if (isEmpty) {
		return CompactedChannels(this, emptyMap())
	}
	val surviving = LinkedHashMap<FormChannel, KeyformGrid<ChannelValue>>(gridsByChannel.size)
	val constants = LinkedHashMap<FormChannel, ChannelValue>()
	for ((channel, grid) in gridsByChannel) {
		when (val result = grid.compacted(ChannelValueInterpolator, channel.valueKind)) {
			is CompactionResult.Constant -> constants[channel] = result.form
			is CompactionResult.Reduced -> surviving[channel] = result.grid
		}
	}
	val newGrids = if (surviving.isEmpty()) ChannelGrids.Empty else ChannelGrids(surviving)
	return CompactedChannels(newGrids, constants)
}

/** The lifted constant for [channel] as a float, or [fallback] when the channel did not collapse. */
private fun Map<FormChannel, ChannelValue>.scalarOr(channel: FormChannel, fallback: Float): Float =
	(this[channel] as? ChannelValue.Scalar)?.value ?: fallback

/** The lifted constant for [channel] as a color, or [fallback] when the channel did not collapse. */
private fun Map<FormChannel, ChannelValue>.colorOr(channel: FormChannel, fallback: ColorRgb): ColorRgb =
	(this[channel] as? ChannelValue.Color)?.color ?: fallback

/** The lifted constant for [channel] as a flag, or [fallback] when the channel did not collapse. */
private fun Map<FormChannel, ChannelValue>.flagOr(channel: FormChannel, fallback: Boolean): Boolean =
	(this[channel] as? ChannelValue.Flag)?.flag ?: fallback

/**
 * This model with every channel track compacted and every collapsed track lifted into its owner's static.
 *
 * Geometry grids are untouched (see the file note).  Evaluation is unchanged to within the tolerance the
 * fidelity contract already allows for geometry, and is EXACT wherever a track collapsed to a constant -
 * a field read cannot drift the way a weighted sum over corners can.
 *
 * @return PuppetModel The compacted model.
 */
fun PuppetModel.withChannelsCompacted(): PuppetModel =
	copy(
		drawables =
			drawables.map { drawable ->
				val compacted = drawable.channelGrids.compactedTracks()
				if (compacted.constants.isEmpty() && compacted.channelGrids === drawable.channelGrids) {
					drawable
				} else {
					drawable.copy(
						channelGrids = compacted.channelGrids,
						drawOrder = compacted.constants.scalarOr(FormChannel.DRAW_ORDER, drawable.drawOrder),
						opacity = compacted.constants.scalarOr(FormChannel.OPACITY, drawable.opacity),
						multiplyColor = compacted.constants.colorOr(FormChannel.MULTIPLY_COLOR, drawable.multiplyColor),
						screenColor = compacted.constants.colorOr(FormChannel.SCREEN_COLOR, drawable.screenColor),
					)
				}
			},
		deformers = deformers.map { deformer -> deformer.withChannelsCompacted() },
		parts = parts.map { part -> part.withChannelsCompacted() },
		glues =
			glues.map { glue ->
				val compacted = glue.channelGrids.compactedTracks()
				if (compacted.constants.isEmpty() && compacted.channelGrids === glue.channelGrids) {
					glue
				} else {
					Glue(
						glue.meshA,
						glue.meshB,
						glue.pairs,
						compacted.channelGrids,
						compacted.constants.scalarOr(FormChannel.GLUE_INTENSITY, glue.intensity),
					)
				}
			},
		renderRoot = renderRoot.withChannelsCompacted(),
	)

/** This deformer with its channel tracks compacted and any constants lifted into its statics. */
private fun Deformer.withChannelsCompacted(): Deformer {
	val compacted = channelGrids.compactedTracks()
	if (compacted.constants.isEmpty() && compacted.channelGrids === channelGrids) {
		return this
	}
	return when (this) {
		is Deformer.Warp ->
			copy(
				channelGrids = compacted.channelGrids,
				opacity = compacted.constants.scalarOr(FormChannel.OPACITY, opacity),
				multiplyColor = compacted.constants.colorOr(FormChannel.MULTIPLY_COLOR, multiplyColor),
				screenColor = compacted.constants.colorOr(FormChannel.SCREEN_COLOR, screenColor),
			)

		is Deformer.Rotation ->
			copy(
				channelGrids = compacted.channelGrids,
				opacity = compacted.constants.scalarOr(FormChannel.OPACITY, opacity),
				multiplyColor = compacted.constants.colorOr(FormChannel.MULTIPLY_COLOR, multiplyColor),
				screenColor = compacted.constants.colorOr(FormChannel.SCREEN_COLOR, screenColor),
				flipX = compacted.constants.flagOr(FormChannel.FLIP_X, flipX),
				flipY = compacted.constants.flagOr(FormChannel.FLIP_Y, flipY),
			)
	}
}

/**
 * This part with its channel tracks compacted, constants lifted into [Part.drawOrder] and the composite.
 *
 * A collapsed DRAW_ORDER is lifted only when its value is exactly an integer: the part's static slot is an
 * Int, so a fractional constant would have to round, and rounding could reorder two parts that the track
 * kept distinct.  Such a track keeps its (reduced) grid instead - correct, just not free.
 */
private fun Part.withChannelsCompacted(): Part {
	val compacted = channelGrids.compactedTracks()
	if (compacted.constants.isEmpty() && compacted.channelGrids === channelGrids) {
		return this
	}
	val liftedDrawOrder = compacted.constants[FormChannel.DRAW_ORDER] as? ChannelValue.Scalar
	val integralDrawOrder = liftedDrawOrder?.takeIf { lifted -> lifted.value == lifted.value.toInt().toFloat() }
	val keptGrids =
		if (liftedDrawOrder != null && integralDrawOrder == null) {
			// Put the fractional draw-order track back rather than rounding it into the Int static.
			ChannelGrids(compacted.channelGrids.gridsByChannel + (FormChannel.DRAW_ORDER to (channelGrids[FormChannel.DRAW_ORDER]!!)))
		} else {
			compacted.channelGrids
		}
	return copy(
		channelGrids = keptGrids,
		drawOrder = integralDrawOrder?.value?.toInt() ?: drawOrder,
		composite =
			composite.copy(
				opacity = compacted.constants.scalarOr(FormChannel.OPACITY, composite.opacity),
				multiplyColor = compacted.constants.colorOr(FormChannel.MULTIPLY_COLOR, composite.multiplyColor),
				screenColor = compacted.constants.colorOr(FormChannel.SCREEN_COLOR, composite.screenColor),
			),
	)
}

/**
 * This render group (and its subtree) with its channel tracks compacted.
 *
 * The render tree carries its own copy of each part's tracks, and for a MOC3 import it is the baked
 * authority rather than something derived from the parts - so it is compacted in place instead of being
 * re-derived, which would discard the baked order.
 */
private fun RenderGroup.withChannelsCompacted(): RenderGroup {
	val compacted = channelGrids.compactedTracks()
	val liftedDrawOrder = compacted.constants[FormChannel.DRAW_ORDER] as? ChannelValue.Scalar
	val integralDrawOrder = liftedDrawOrder?.takeIf { lifted -> lifted.value == lifted.value.toInt().toFloat() }
	val keptGrids =
		if (liftedDrawOrder != null && integralDrawOrder == null) {
			ChannelGrids(compacted.channelGrids.gridsByChannel + (FormChannel.DRAW_ORDER to (channelGrids[FormChannel.DRAW_ORDER]!!)))
		} else {
			compacted.channelGrids
		}
	return copy(
		drawOrder = integralDrawOrder?.value?.toInt() ?: drawOrder,
		children = children.map { child -> if (child is RenderGroup) child.withChannelsCompacted() else child },
		channelGrids = keptGrids,
		composite =
			composite?.copy(
				opacity = compacted.constants.scalarOr(FormChannel.OPACITY, composite.opacity),
				multiplyColor = compacted.constants.colorOr(FormChannel.MULTIPLY_COLOR, composite.multiplyColor),
				screenColor = compacted.constants.colorOr(FormChannel.SCREEN_COLOR, composite.screenColor),
			),
	)
}
