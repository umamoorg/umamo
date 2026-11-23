package org.umamo.interop

import org.umamo.runtime.eval.EPS_KEY
import org.umamo.runtime.keyform.ChannelValueInterpolator
import org.umamo.runtime.keyform.FormInterpolator
import org.umamo.runtime.keyform.refinedToUnion
import org.umamo.runtime.model.ChannelGrids
import org.umamo.runtime.model.ChannelValue
import org.umamo.runtime.model.FormChannel
import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.ParameterId
import kotlin.math.abs

/*
 * Re-bundles a runtime entity's SPLIT keyform representation - one geometry grid plus independent
 * per-channel tracks - back into the single dense grid every source format stores.
 *
 * The runtime deliberately keeps geometry and each render channel on their own axes so a rigger
 * can key opacity without touching the mesh.  Both CMO3 and MOC3 store one grid per object
 * instead, with every cell carrying every value, so an export has to union the axes and
 * re-interpolate each part onto the result.  That inversion is exactly `refinedToUnion`'s
 * contract, and doing it identically for both formats is the point of this file: two
 * independent copies would drift the moment one gained a clamp or a tolerance the other lacked,
 * and only a differential oracle would ever notice.
 *
 * @see <a href="https://docs.umamo.org/format/CMO3.md">CMO3.md §6 Export</a>
 * @see <a href="https://docs.umamo.org/format/MOC3.md">MOC3.md §8 Export</a>
 */

/** What to do with a channel that cannot share its owner's bundled grid. */
enum class OutOfSpanPolicy {
	/**
	 * Reject the whole owner: the export cannot represent it and says so.
	 *
	 * CMO3's choice - a partially-representable owner would silently change the rig's shape, and CMO3
	 * can simply decline to write that object's grid.
	 */
	RejectOwner,

	/**
	 * Drop the offending channel to its static value and bundle the rest, naming what was demoted.
	 *
	 * MOC3's choice, and not a preference: every MOC3 object MUST carry at least one keyform, so
	 * rejecting the owner would mean emitting nothing for it and producing a file the runtime cannot
	 * load.  Losing one channel's animation is the strictly smaller loss.
	 */
	DemoteChannel,
}

/** Why [OutOfSpanPolicy.DemoteChannel] dropped a channel to its static. */
enum class ChannelDemotionCause {
	/**
	 * The channel's own track does not refine onto the union: it is keyed over less of a parameter than
	 * the union spans, or a union key falls closer to one of its own than a key can be inserted.
	 */
	ChannelCannotRefineOntoUnion,

	/**
	 * The geometry does not refine onto the channel's keys: they fall past the geometry's own span, or
	 * closer to one of its keys than a key can be inserted.  Geometry is what the grid is for, so the
	 * channel's keys stay out of the union.
	 */
	GeometryCannotRefineOntoChannelKeys,
}

/** One re-bundled cell: its coordinate, per-parameter key values, geometry, and channel values. */
class KeyformBundleCell(
	val coordinate: IntArray,
	val values: Map<ParameterId, Float>,
	val geometry: Any?,
	val channels: Map<FormChannel, ChannelValue>,
)

/**
 * A dense bundled grid: the union axes and every cell of their cross product.
 *
 * @property List axes            The union axes, geometry axes first.
 * @property List cells           Every cell, in first-axis-fastest order.
 * @property Map  demotedChannels Channels dropped to their static under [OutOfSpanPolicy.DemoteChannel],
 *                                each with why, in track order.
 */
class KeyformBundle(
	val axes: List<KeyformAxis>,
	val cells: List<KeyformBundleCell>,
	val demotedChannels: Map<FormChannel, ChannelDemotionCause> = emptyMap(),
)

/** The outcome of a bundling attempt. */
sealed interface KeyformBundleResult {
	/** The entity bundled; [bundle] may still name demoted channels. */
	class Bundled(val bundle: KeyformBundle) : KeyformBundleResult

	/**
	 * The split state cannot be expressed as one grid, and the caller must report it.
	 *
	 * @property KeyformBundleRejection rejection Which bundling rule the keyforms broke.
	 */
	data class Unrepresentable(val rejection: KeyformBundleRejection) : KeyformBundleResult
}

/**
 * Why a bundling attempt failed, for the caller to lower into an export notice.
 *
 * The bundler owns this vocabulary rather than [ExportNoticeReason] because the rule that was broken
 * is a fact about bundling, not about any one format - the CMO3 lowering wraps it in
 * [ExportNoticeReason.KeyformCannotBundle], the MOC3 lowering in
 * [ExportNoticeReason.KeyformsDemotedToStatic], and a future format would wrap the same cases its own way.
 * Format-neutral for the same reason: the "one grid per object" clause a reader sees is supplied by
 * the wrapping notice, not baked in here.
 */
sealed interface KeyformBundleRejection {
	/** Geometry keys fall outside the union span, and geometry is never demotable. */
	data object KeysOutsideGeometrySpan : KeyformBundleRejection

	/** Channels key but there is no geometry to hang them on, where the caller requires geometry. */
	data object ChannelKeysWithoutGeometry : KeyformBundleRejection

	/**
	 * A channel keys outside its track span, under a policy that rejects the owner rather than
	 * demoting the channel.
	 *
	 * @property FormChannel channel The offending channel.
	 */
	data class KeysOutsideChannelSpan(val channel: FormChannel) : KeyformBundleRejection
}

/**
 * Re-bundles a split representation onto union axes.
 *
 * The axis ORDER follows the geometry grid first, which is the order the import's fan-out preserved -
 * so a round trip through split-then-bundled keeps the source's own axis order rather than inventing
 * one.  Cells are emitted first-axis-fastest, matching the mixed radix both formats index with.
 *
 * The geometry always stays keyed.  A channel whose keys the geometry cannot carry is the one that gives
 * way, by demotion or by rejecting the owner, as [outOfSpanPolicy] says.
 *
 * @param KeyformGrid?     geometryGrid    The geometry grid, or null for a channel-only owner.
 * @param FormInterpolator geometryBlend   The geometry interpolator (unused when there is no geometry).
 * @param ChannelGrids     channels        The owner's channel tracks.
 * @param Map              statics         Fallback value per relevant channel.
 * @param Boolean          requireGeometry Whether a geometry-less bundle is an error (warp/rotation).
 * @param OutOfSpanPolicy  outOfSpanPolicy What to do with a channel that cannot share the grid.
 * @return KeyformBundleResult The bundle - possibly with empty axes when fully unkeyed - or a rejection.
 */
fun <TGeometry> buildKeyformBundle(
	geometryGrid: KeyformGrid<TGeometry>?,
	geometryBlend: FormInterpolator<TGeometry>,
	channels: ChannelGrids,
	statics: Map<FormChannel, ChannelValue>,
	requireGeometry: Boolean,
	outOfSpanPolicy: OutOfSpanPolicy,
): KeyformBundleResult {
	// Union keys per parameter, geometry axes first so the bundled axis order follows the
	// geometry grid (the order the import's fan-out preserved).  First also means kept: the union
	// never gives up a key it holds, so the geometry's keys all survive the channels merged after.
	val unionKeys = LinkedHashMap<ParameterId, FloatArray>()
	geometryGrid?.axes?.forEach(unionKeys::mergeAxis)

	for (grid in channels.gridsByChannel.values) {
		grid.axes.forEach(unionKeys::mergeAxis)
	}

	if (unionKeys.isEmpty()) {
		// No parameter keys anywhere.  An axis-less grid (one static cell) is a real shape in both
		// formats and keeps its single cell; a grid-less, channel-less owner is fully unkeyed (empty).
		//
		// AXIS-LESS IS NOT VALUE-LESS.  A channel track can be a single cell with no axes - an authored
		// constant that was never lifted into the owner's static - and that value must win over the
		// static, which in that state still holds its untouched constructor default.  Falling straight
		// back to the statics here would silently replace every such value.
		val channelValues =
			buildMap {
				for ((channel, staticValue) in statics) {
					put(channel, channels.gridsByChannel[channel]?.cells?.firstOrNull()?.form ?: staticValue)
				}
			}
		val staticCell = geometryGrid?.cells?.firstOrNull()

		if (staticCell != null) {
			return KeyformBundleResult.Bundled(
				KeyformBundle(
					emptyList(),
					listOf(KeyformBundleCell(IntArray(0), emptyMap(), staticCell.form, channelValues)),
				),
			)
		}

		// A geometry-less owner whose channels are all axis-less still has one cell's worth of values;
		// emitting nothing would make the caller fall back to the same statics this just corrected.
		if (channels.gridsByChannel.isNotEmpty()) {
			return KeyformBundleResult.Bundled(
				KeyformBundle(emptyList(), listOf(KeyformBundleCell(IntArray(0), emptyMap(), null, channelValues))),
			)
		}

		return KeyformBundleResult.Bundled(KeyformBundle(emptyList(), emptyList()))
	}

	// Refine geometry over its own axes only (an empty ranges map suppresses the append path); every
	// axis must then carry exactly the union keys - a span that does not cover them means an
	// out-of-span key the bundle cannot represent without inventing values.
	var axes: List<KeyformAxis>
	var geometryByCoordinate: Map<List<Int>, TGeometry>
	val demotedChannels = LinkedHashMap<FormChannel, ChannelDemotionCause>()

	if (geometryGrid != null) {
		var refined = geometryGrid.refinedToUnion(unionKeys, emptyMap(), geometryBlend)

		// A channel keyed where the geometry cannot be leaves a union the geometry does not refine onto.
		// Geometry is never demotable - it is the thing being exported - so where the policy lets a
		// channel give way, the union is rebuilt from the geometry's own axes, taking each channel in
		// turn only while the geometry still carries the result.
		if (!refined.carriesUnionKeys(unionKeys) && outOfSpanPolicy == OutOfSpanPolicy.DemoteChannel) {
			unionKeys.clear()
			geometryGrid.axes.forEach(unionKeys::mergeAxis)
			refined = geometryGrid

			for ((channel, track) in channels.gridsByChannel) {
				val keysWithChannel = LinkedHashMap(unionKeys)
				track.axes.forEach(keysWithChannel::mergeAxis)
				val refinedWithChannel = geometryGrid.refinedToUnion(keysWithChannel, emptyMap(), geometryBlend)

				if (refinedWithChannel.carriesUnionKeys(keysWithChannel)) {
					unionKeys.putAll(keysWithChannel)
					refined = refinedWithChannel
				} else {
					demotedChannels[channel] = ChannelDemotionCause.GeometryCannotRefineOntoChannelKeys
				}
			}
		}

		// The other policy's answer to the same union; the rebuild above leaves one the geometry carries.
		if (!refined.carriesUnionKeys(unionKeys)) {
			return KeyformBundleResult.Unrepresentable(KeyformBundleRejection.KeysOutsideGeometrySpan)
		}

		axes = refined.axes
		geometryByCoordinate = refined.cells.associate { cell -> cell.coordinate.toList() to cell.form }

		// Replicate across parameters only channels key (the value is constant along them).
		for ((parameterId, keys) in unionKeys) {
			if (axes.none { it.parameterId == parameterId }) {
				axes = axes + KeyformAxis(parameterId, keys)
				geometryByCoordinate =
					buildMap {
						for (keyIndex in keys.indices) {
							for ((coordinate, form) in geometryByCoordinate) {
								put(coordinate + keyIndex, form)
							}
						}
					}
			}
		}
	} else {
		if (requireGeometry) {
			return KeyformBundleResult.Unrepresentable(KeyformBundleRejection.ChannelKeysWithoutGeometry)
		}

		axes = unionKeys.map { (parameterId, keys) -> KeyformAxis(parameterId, keys) }
		geometryByCoordinate = emptyMap()
	}

	// Refine each channel over its own axes; a final-coordinate lookup then projects onto the
	// channel's axis subset (the channel is constant along axes it does not key).
	val channelLookups = HashMap<FormChannel, (IntArray) -> ChannelValue?>()
	val finalAxisPosition = axes.mapIndexed { position, axis -> axis.parameterId to position }.toMap()

	for ((channel, track) in channels.gridsByChannel) {
		// Left out of the union above, so the grid holds none of this channel's keys to refine it onto.
		if (channel in demotedChannels) {
			continue
		}

		val refined = track.refinedToUnion(unionKeys, emptyMap(), ChannelValueInterpolator)

		if (!refined.carriesUnionKeys(unionKeys)) {
			if (outOfSpanPolicy == OutOfSpanPolicy.RejectOwner) {
				return KeyformBundleResult.Unrepresentable(
					KeyformBundleRejection.KeysOutsideChannelSpan(channel),
				)
			}

			// Demoted: no lookup is registered, so every cell falls back to the channel's static below.
			demotedChannels[channel] = ChannelDemotionCause.ChannelCannotRefineOntoUnion

			continue
		}

		val subPositions =
			refined.axes.map { axis -> finalAxisPosition.getValue(axis.parameterId) }.toIntArray()
		channelLookups[channel] =
			{ coordinate ->
				val subCoordinate = IntArray(subPositions.size) { axisIndex -> coordinate[subPositions[axisIndex]] }
				refined.cellsByLinearIndex[refined.linearIndexOf(subCoordinate)]?.form
			}
	}

	// Assemble every cell of the dense final grid.
	val keyCounts = axes.map { it.keys.size }
	val totalCells = keyCounts.fold(1) { product, count -> product * count }
	val cells = ArrayList<KeyformBundleCell>(totalCells)
	val coordinate = IntArray(axes.size)

	for (cellOrdinal in 0 until totalCells) {
		var remainder = cellOrdinal

		for (axisIndex in axes.indices) {
			coordinate[axisIndex] = remainder % keyCounts[axisIndex]
			remainder /= keyCounts[axisIndex]
		}

		val cellCoordinate = coordinate.copyOf()
		val values =
			buildMap {
				for (axisIndex in axes.indices) {
					put(axes[axisIndex].parameterId, axes[axisIndex].keys[cellCoordinate[axisIndex]])
				}
			}
		val channelValues =
			buildMap {
				for ((channel, staticValue) in statics) {
					put(channel, channelLookups[channel]?.invoke(cellCoordinate) ?: staticValue)
				}
			}
		cells.add(
			KeyformBundleCell(cellCoordinate, values, geometryByCoordinate[cellCoordinate.toList()], channelValues),
		)
	}

	return KeyformBundleResult.Bundled(KeyformBundle(axes, cells, demotedChannels))
}

/**
 * Merges an axis's keys into the union kept for its parameter.
 *
 * @param KeyformAxis axis The axis to merge.
 */
private fun MutableMap<ParameterId, FloatArray>.mergeAxis(axis: KeyformAxis) {
	val existing = this[axis.parameterId]
	this[axis.parameterId] = if (existing == null) axis.keys.copyOf() else mergedKeys(existing, axis.keys)
}

/**
 * Whether every axis of this grid carries exactly the union's keys for its parameter.
 *
 * @param Map unionKeys The union keys per parameter.
 * @return Boolean True when the grid, as refined, can sit in a bundle on those keys.
 */
private fun KeyformGrid<*>.carriesUnionKeys(unionKeys: Map<ParameterId, FloatArray>): Boolean =
	axes.all { axis -> axis.keys.contentEquals(unionKeys.getValue(axis.parameterId)) }

/**
 * A union's keys with another axis's merged in, ascending.
 *
 * A key the evaluator cannot tell apart from one the union already holds is that key met again on another
 * grid, and stays one key: the union's own.  The keys the union holds are never merged with each other,
 * however close, because a grid that stores both keys of a near pair can only refine onto a union that
 * holds both.
 *
 * @param FloatArray union The keys held so far.
 * @param FloatArray added The other axis's keys.
 * @return FloatArray The merged ascending keys.
 */
private fun mergedKeys(union: FloatArray, added: FloatArray): FloatArray {
	val kept = ArrayList<Float>(union.size + added.size)
	kept.addAll(union.asList())

	for (candidate in added) {
		// The evaluator's own key-snap tolerance.
		if (kept.none { held -> abs(held - candidate) < EPS_KEY }) {
			kept.add(candidate)
		}
	}

	return kept.sorted().toFloatArray()
}