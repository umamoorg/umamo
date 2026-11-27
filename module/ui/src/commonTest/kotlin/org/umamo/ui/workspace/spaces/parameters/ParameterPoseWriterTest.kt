package org.umamo.ui.workspace.spaces.parameters

import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.ui.model.LiveParamsHandle
import kotlin.test.Test
import kotlin.test.assertEquals

/** One thing a pose write did, in the order it did it. */
private sealed interface PoseCall {
	/** The panel's own record of a previewed value. */
	data class Echo(val id: ParameterId, val value: Float) : PoseCall

	/** A preview handed to the seam. */
	data class Preview(val id: ParameterId, val value: Float) : PoseCall

	/** A commit handed to the seam. */
	data class Commit(val ids: Set<ParameterId>) : PoseCall
}

/**
 * A pose seam that only writes down what it was asked.
 *
 * @param MutableList<PoseCall> calls The log the seam appends to, shared with the writer's echo.
 */
private class RecordingLiveParams(private val calls: MutableList<PoseCall>) : LiveParamsHandle {
	override val values: Map<ParameterId, Float> = emptyMap()
	override val observedValues: Map<ParameterId, Float> = emptyMap()

	/**
	 * Records a preview.
	 *
	 * @param ParameterId id The parameter previewed.
	 * @param Float value The value previewed.
	 */
	override fun preview(id: ParameterId, value: Float) {
		calls += PoseCall.Preview(id, value)
	}

	/**
	 * Records a commit.
	 *
	 * @param Set<ParameterId> changedIds The parameters committed.
	 */
	override fun commit(changedIds: Set<ParameterId>) {
		calls += PoseCall.Commit(changedIds)
	}
}

/**
 * A map that counts what is written to it, so a write of an equal value can be told from no write.
 *
 * @param MutableMap<ParameterId, Float> backing The map the entries really live in.
 */
private class CountingValues(private val backing: MutableMap<ParameterId, Float>) : MutableMap<ParameterId, Float> by backing {
	/** How many entries have been written. */
	var writes = 0

	/**
	 * Writes an entry and counts it.
	 *
	 * @param ParameterId key The parameter.
	 * @param Float value The value.
	 * @return Float? The value the entry held before.
	 */
	override fun put(key: ParameterId, value: Float): Float? {
		writes += 1

		return backing.put(key, value)
	}
}

/**
 * Pins the panel's pose writes: what reaches the seam and in which order, what a lock refuses, and which
 * entries a follow touches.
 */
class ParameterPoseWriterTest {
	private val bodyX = ParameterId("ParamBodyX")
	private val breath = ParameterId("ParamBreath")
	private val parameters =
		listOf(
			Parameter(bodyX, "Body X", min = -10f, max = 10f, default = 0f),
			Parameter(breath, "Breath", min = 0f, max = 1f, default = 0.5f),
		)

	private val calls = mutableListOf<PoseCall>()
	private val seam = RecordingLiveParams(calls)

	/**
	 * A writer over the recording seam whose echo lands in the same log.
	 *
	 * @param Boolean locked Whether the writer refuses every write.
	 * @return ParameterPoseWriter The writer under test.
	 */
	private fun writer(locked: Boolean): ParameterPoseWriter = ParameterPoseWriter(seam, locked) { id, value -> calls += PoseCall.Echo(id, value) }

	/** A preview moves the panel's own value first, then the renderer's, and records no step. */
	@Test
	fun aPreviewEchoesThenReachesTheSeam() {
		writer(locked = false).preview(bodyX, 3f)

		assertEquals(listOf(PoseCall.Echo(bodyX, 3f), PoseCall.Preview(bodyX, 3f)), calls)
	}

	/** The end of a gesture is one commit over what the gesture moved. */
	@Test
	fun aGestureCommitsOnce() {
		writer(locked = false).commitGesture(setOf(bodyX, breath))

		assertEquals(listOf<PoseCall>(PoseCall.Commit(setOf(bodyX, breath))), calls)
	}

	/** A discrete edit is a preview followed by the commit of that one parameter. */
	@Test
	fun aDiscreteEditIsAPreviewThenOneCommit() {
		writer(locked = false).commitValue(breath, 0.25f)

		assertEquals(
			listOf(PoseCall.Echo(breath, 0.25f), PoseCall.Preview(breath, 0.25f), PoseCall.Commit(setOf(breath))),
			calls,
		)
	}

	/** Reset All previews every default, then commits them all as one step. */
	@Test
	fun resetAllPreviewsEveryDefaultThenCommitsOnce() {
		ParameterPoseWriter(seam, locked = false).resetAll(parameters)

		assertEquals(
			listOf(PoseCall.Preview(bodyX, 0f), PoseCall.Preview(breath, 0.5f), PoseCall.Commit(setOf(bodyX, breath))),
			calls,
		)
	}

	/** A locked writer refuses every write, the echo included, so a locked panel shows what it showed. */
	@Test
	fun aLockedWriterRefusesEveryWrite() {
		val locked = writer(locked = true)

		locked.preview(bodyX, 3f)
		locked.commitGesture(setOf(bodyX))
		locked.commitValue(breath, 0.25f)
		locked.resetAll(parameters)

		assertEquals(emptyList(), calls)
	}

	/** With no seam to write through, the panel's own value still moves. */
	@Test
	fun aWriterWithNoSeamStillEchoes() {
		val unattached = ParameterPoseWriter(liveParams = null, locked = false) { id, value -> calls += PoseCall.Echo(id, value) }

		unattached.commitValue(bodyX, 3f)

		assertEquals(listOf<PoseCall>(PoseCall.Echo(bodyX, 3f)), calls)
	}

	/** A writer asked for no echo writes through the seam alone. */
	@Test
	fun aWriterEchoesNothingByDefault() {
		ParameterPoseWriter(seam, locked = false).commitValue(bodyX, 3f)

		assertEquals(listOf(PoseCall.Preview(bodyX, 3f), PoseCall.Commit(setOf(bodyX))), calls)
	}

	/** A follow writes the entries that differ and leaves the ones that already match untouched. */
	@Test
	fun aFollowWritesOnlyWhatDiffers() {
		val values = CountingValues(mutableMapOf(bodyX to 2f, breath to 0.25f))

		followPose(values, mapOf(bodyX to 2f, breath to 0.75f))

		assertEquals(1, values.writes)
		assertEquals(mapOf(bodyX to 2f, breath to 0.75f), values.toMap())
	}

	/** A pose that names nothing, as Edit mode's does, writes nothing. */
	@Test
	fun aFollowOfAnEmptyPoseWritesNothing() {
		val values = CountingValues(mutableMapOf(bodyX to 2f, breath to 0.25f))

		followPose(values, emptyMap())

		assertEquals(0, values.writes)
		assertEquals(mapOf(bodyX to 2f, breath to 0.25f), values.toMap())
	}
}