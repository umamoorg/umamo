package org.umamo.interop

import org.umamo.runtime.keyform.ChannelValueInterpolator
import org.umamo.runtime.keyform.FormInterpolator
import org.umamo.runtime.model.ChannelGrids
import org.umamo.runtime.model.ChannelValue
import org.umamo.runtime.model.FormChannel
import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.ParameterId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The shared re-bundler's contract, and in particular the two [OutOfSpanPolicy] branches.
 *
 * The out-of-span case is the one worth pinning: a channel keyed over a NARROWER span than the union
 * cannot be placed in the bundled grid without inventing values at the keys it never covered.  CMO3
 * declines the whole owner; MOC3 cannot, because a MOC3 object with no keyform produces a file the
 * runtime will not load, so it drops that one channel to its static instead.  Both branches are
 * exercised here because each caller only ever reaches its own - `Cmo3KeyformLowering` never passes
 * DemoteChannel and `Moc3KeyformLowering` never passes RejectOwner - so neither format's own gates
 * can catch a regression in the other's policy.
 */
class KeyformBundleTest {
	private val angleX = ParameterId("ParamAngleX")

	/**
	 * A single-axis channel track over [keys] with a distinct scalar per key.
	 *
	 * @param FloatArray keys The axis key positions.
	 * @return KeyformGrid<ChannelValue> The track.
	 */
	private fun track(keys: FloatArray): KeyformGrid<ChannelValue> =
		KeyformGrid(
			listOf(KeyformAxis(angleX, keys)),
			keys.indices.map { keyIndex ->
				KeyformCell(intArrayOf(keyIndex), ChannelValue.Scalar(keyIndex.toFloat()))
			},
		)

	/**
	 * A geometry grid over [keys], carrying the key index as its form so cells stay identifiable.
	 *
	 * @param FloatArray keys The axis key positions.
	 * @return KeyformGrid<Float> The grid.
	 */
	private fun geometry(keys: FloatArray): KeyformGrid<Float> =
		KeyformGrid(
			listOf(KeyformAxis(angleX, keys)),
			keys.indices.map { keyIndex -> KeyformCell(intArrayOf(keyIndex), keyIndex.toFloat()) },
		)

	/** A float interpolator, so the geometry can be refined onto the union like a real payload. */
	private val floatBlend =
		object : FormInterpolator<Float> {
			override fun interpolate(lower: Float, upper: Float, fraction: Float): Float =
				lower + (upper - lower) * fraction

			override fun isExactlyEqual(left: Float, right: Float): Boolean = left == right
		}

	private val statics = mapOf(FormChannel.OPACITY to ChannelValue.Scalar(1f))

	/** Geometry spanning -30..30 with a channel keyed only over the NARROWER -10..10. */
	private fun outOfSpanInputs(): Pair<KeyformGrid<Float>, ChannelGrids> =
		geometry(floatArrayOf(-30f, 0f, 30f)) to
			ChannelGrids(mapOf(FormChannel.OPACITY to track(floatArrayOf(-10f, 0f, 10f))))

	@Test
	fun rejectOwnerRefusesAChannelKeyedOutsideTheUnionSpan() {
		val (geometryGrid, channels) = outOfSpanInputs()
		val result =
			buildKeyformBundle(
				geometryGrid,
				floatBlend,
				channels,
				statics,
				requireGeometry = false,
				outOfSpanPolicy = OutOfSpanPolicy.RejectOwner,
			)
		assertTrue(result is KeyformBundleResult.Unrepresentable, "an out-of-span channel rejects the owner")
		val rejection = result.rejection
		assertTrue(
			rejection is KeyformBundleRejection.KeysOutsideChannelSpan &&
				rejection.channel == FormChannel.OPACITY,
			"the rejection names the offending channel: $rejection",
		)
	}

	@Test
	fun demoteChannelKeepsTheOwnerAndNamesWhatItDropped() {
		val (geometryGrid, channels) = outOfSpanInputs()
		val result =
			buildKeyformBundle(
				geometryGrid,
				floatBlend,
				channels,
				statics,
				requireGeometry = false,
				outOfSpanPolicy = OutOfSpanPolicy.DemoteChannel,
			)
		assertTrue(result is KeyformBundleResult.Bundled, "the owner still bundles")
		val bundle = result.bundle
		assertEquals(listOf(FormChannel.OPACITY), bundle.demotedChannels, "the dropped channel is named")
		// Every cell falls back to the static rather than to an invented interpolation.
		assertTrue(bundle.cells.isNotEmpty(), "the bundle has cells")
		assertTrue(
			bundle.cells.all { cell -> cell.channels[FormChannel.OPACITY] == statics.getValue(FormChannel.OPACITY) },
			"a demoted channel reads its static in every cell",
		)
		// The union is BOTH sets of keys (-30, -10, 0, 10, 30): the geometry refines onto all five
		// because they fall inside its own span, which is precisely why the narrower channel cannot -
		// it would have to invent values at -30 and 30.  Demoting it does not shrink the grid.
		assertEquals(5, bundle.cells.size, "the union keeps every key from both sides")
		assertEquals(
			listOf(-30f, -10f, 0f, 10f, 30f),
			bundle.axes.single().keys.toList(),
			"the union axis carries both key sets",
		)
	}

	@Test
	fun bothPoliciesAgreeWhenNothingIsOutOfSpan() {
		val geometryGrid = geometry(floatArrayOf(-30f, 0f, 30f))
		val channels = ChannelGrids(mapOf(FormChannel.OPACITY to track(floatArrayOf(-30f, 0f, 30f))))
		val bundles =
			listOf(OutOfSpanPolicy.RejectOwner, OutOfSpanPolicy.DemoteChannel).map { policy ->
				val result =
					buildKeyformBundle(geometryGrid, floatBlend, channels, statics, false, policy)
				assertTrue(result is KeyformBundleResult.Bundled, "$policy bundles an in-span owner")
				result.bundle
			}
		val (rejectBundle, demoteBundle) = bundles
		assertEquals(emptyList(), rejectBundle.demotedChannels, "nothing is demoted when nothing is out of span")
		assertEquals(emptyList(), demoteBundle.demotedChannels, "nothing is demoted when nothing is out of span")
		assertEquals(rejectBundle.cells.size, demoteBundle.cells.size, "the policies produce the same grid")
		for (cellIndex in rejectBundle.cells.indices) {
			assertEquals(
				rejectBundle.cells[cellIndex].channels,
				demoteBundle.cells[cellIndex].channels,
				"cell $cellIndex agrees across policies",
			)
		}
	}

	@Test
	fun aFullyUnkeyedOwnerBundlesToNothing() {
		val result =
			buildKeyformBundle(
				null as KeyformGrid<Float>?,
				floatBlend,
				ChannelGrids.Empty,
				statics,
				requireGeometry = false,
				outOfSpanPolicy = OutOfSpanPolicy.RejectOwner,
			)
		assertTrue(result is KeyformBundleResult.Bundled, "an unkeyed owner is not an error")
		assertEquals(emptyList(), result.bundle.axes, "no axes")
		assertEquals(emptyList(), result.bundle.cells, "no cells")
	}

	@Test
	fun geometryRequiredMeansAChannelOnlyOwnerIsUnrepresentable() {
		val channels = ChannelGrids(mapOf(FormChannel.OPACITY to track(floatArrayOf(-10f, 10f))))
		val result =
			buildKeyformBundle(
				null as KeyformGrid<Float>?,
				floatBlend,
				channels,
				statics,
				requireGeometry = true,
				outOfSpanPolicy = OutOfSpanPolicy.DemoteChannel,
			)
		// Not demotable: a warp or rotation with keyed channels but no lattice has nothing to write.
		assertTrue(result is KeyformBundleResult.Unrepresentable, "geometry-less is an error when required")
	}

	/**
	 * An AXIS-LESS channel track still carries its value, and that value beats the owner's static.
	 *
	 * A track can be a single cell with no axes - an authored constant that was never lifted into the
	 * static - and the static in that state still holds an untouched default.  The early return for
	 * "no keys anywhere" used to fill channels straight from the statics, which silently replaced every
	 * such value; a MOC3 export of an uncompacted import wrote 1.0 opacity over an authored 0.66.
	 */
	@Test
	fun anAxisLessChannelTrackBeatsTheStatic() {
		val authored: ChannelValue = ChannelValue.Scalar(0.66f)
		val axisLessTrack = KeyformGrid(emptyList<KeyformAxis>(), listOf(KeyformCell(IntArray(0), authored)))
		val result =
			buildKeyformBundle(
				KeyformGrid(emptyList<KeyformAxis>(), listOf(KeyformCell(IntArray(0), 0f))),
				floatBlend,
				ChannelGrids(mapOf(FormChannel.OPACITY to axisLessTrack)),
				// The static is the constructor default the import leaves behind when nothing lifted it.
				mapOf(FormChannel.OPACITY to ChannelValue.Scalar(1f)),
				requireGeometry = false,
				outOfSpanPolicy = OutOfSpanPolicy.DemoteChannel,
			)
		assertTrue(result is KeyformBundleResult.Bundled, "an axis-less owner still bundles")
		assertEquals(1, result.bundle.cells.size, "one static cell")
		assertEquals(
			authored,
			result.bundle.cells.single().channels[FormChannel.OPACITY],
			"the track's authored value wins over the static",
		)
	}

	/** Guards the interpolator the other cases lean on, so a failure there reads as its own cause. */
	@Test
	fun channelValueInterpolatorBlendsScalars() {
		val blended = ChannelValueInterpolator.interpolate(ChannelValue.Scalar(0f), ChannelValue.Scalar(10f), 0.25f)
		assertEquals(ChannelValue.Scalar(2.5f), blended)
	}
}