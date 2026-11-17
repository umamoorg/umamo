package org.umamo.ui.workspace.spaces.keyformsheet

import org.umamo.edit.TrackKeyRef
import org.umamo.runtime.model.ParameterId
import org.umamo.ui.tracks.TrackKeyMark
import org.umamo.ui.tracks.TrackRow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What a held-Ctrl gesture in the keyform sheet may latch onto.  A scrub snaps to every key the section's
 * tracks hold.  A key drag snaps to other rows' keys and the playhead, never to what it carries along, and
 * never where it or a key it carries would land on a key its own track keeps in place.
 *
 *   drawable:d           (group, with a stray mark at 9 that no target may come from)
 *     geometry           -5, 0, 5
 *     OPACITY            0, 5, 8
 *   part:p               (group)
 *     OPACITY            -8, 7
 */
class KeyformSheetSnapTargetsTest {
	private val angleX = ParameterId("ParamAngleX")
	private val groupRowKeys = setOf(DRAWABLE, PART)
	private val rows =
		listOf(
			TrackRow(
				key = DRAWABLE,
				label = "d",
				marks = listOf(TrackKeyMark(0, 9f)),
				children =
					listOf(
						TrackRow(key = GEOMETRY, label = "geometry", marks = marksAt(-5f, 0f, 5f)),
						TrackRow(key = OPACITY, label = "opacity", marks = marksAt(0f, 5f, 8f)),
					),
			),
			TrackRow(
				key = PART,
				label = "p",
				children = listOf(TrackRow(key = PART_OPACITY, label = "opacity", marks = marksAt(-8f, 7f))),
			),
		)

	/**
	 * Marks at [positions], numbered in order.
	 *
	 * @param FloatArray positions The positions, ascending.
	 * @return List The marks.
	 */
	private fun marksAt(vararg positions: Float): List<TrackKeyMark> =
		positions.mapIndexed { keyIndex, position -> TrackKeyMark(keyIndex, position) }

	/**
	 * The key on row [rowKey] at [ordinal].
	 *
	 * @param String rowKey The row.
	 * @param Int ordinal The key's ordinal.
	 * @return TrackKeyRef The key.
	 */
	private fun key(rowKey: String, ordinal: Int): TrackKeyRef = TrackKeyRef(angleX, rowKey, ordinal)

	/**
	 * The targets of a drag of the geometry key at -5, alone.
	 *
	 * @param Float? playhead The playhead.
	 * @return Set The targets.
	 */
	private fun singleKeyDragTargets(playhead: Float?): Set<Float> =
		markDragSnapTargets(
			rows = rows,
			groupRowKeys = groupRowKeys,
			parameterId = angleX,
			draggedKeys = listOf(key(GEOMETRY, 0)),
			moving = setOf(key(GEOMETRY, 0)),
			startPosition = -5f,
			playhead = playhead,
		).toSet()

	/** A scrub latches onto every track's keys, folded or not, and never onto a group row's own mark. */
	@Test
	fun aScrubTargetsEveryTrackKey() {
		assertEquals(setOf(-5f, 0f, 5f, 8f, -8f, 7f), scrubSnapTargets(rows, groupRowKeys).toSet())
	}

	/** A key drag latches onto other rows' keys and the playhead. */
	@Test
	fun aKeyDragTargetsOtherRowsAndThePlayhead() {
		assertEquals(setOf(8f, -8f, 7f, 3f), singleKeyDragTargets(playhead = 3f))
	}

	/**
	 * Another row's key that sits where the dragged track keeps a key is no target: landing there would stack
	 * two keys on one track.  The opacity keys at 0 and 5 share their places with the geometry keys that stay.
	 */
	@Test
	fun aTargetOnAKeyTheDraggedTrackKeepsIsDropped() {
		val targets = singleKeyDragTargets(playhead = null)

		assertFalse(0f in targets, "the geometry track keeps a key at 0")
		assertFalse(5f in targets, "and one at 5")
		assertTrue(8f in targets, "an opacity key with no geometry key under it stays a target")
	}

	/**
	 * The targets of a drag of the geometry key at -5 carrying the opacity key at 8 along.
	 *
	 * @return Set The targets.
	 */
	private fun carryingOpacityTargets(): Set<Float> =
		markDragSnapTargets(
			rows = rows,
			groupRowKeys = groupRowKeys,
			parameterId = angleX,
			draggedKeys = listOf(key(GEOMETRY, 0)),
			moving = setOf(key(GEOMETRY, 0), key(OPACITY, 2)),
			startPosition = -5f,
			playhead = null,
		).toSet()

	/** A key the drag carries along moves with the hand, so it is no target. */
	@Test
	fun aKeyTheDragCarriesIsNoTarget() {
		assertFalse(8f in carryingOpacityTargets())
	}

	/**
	 * A target that would set a carried key down on a key its own track keeps is dropped.  Latching the hand
	 * onto the part key at -8 moves everything by -3, which takes the carried opacity key from 8 onto the
	 * opacity key kept at 5; the part key at 7 moves it to 20, clear of every kept opacity key.
	 */
	@Test
	fun aTargetThatStacksACarriedKeyIsDropped() {
		val targets = carryingOpacityTargets()

		assertFalse(-8f in targets, "the opacity key at 8 would land on the one kept at 5")
		assertEquals(setOf(7f), targets)
	}

	/** Scrub targets come back once each, however many tracks key the same value. */
	@Test
	fun aScrubTargetIsListedOnce() {
		val targets = scrubSnapTargets(rows, groupRowKeys)

		assertEquals(targets.distinct(), targets)
	}

	/** The playhead is no target where the dragged key starts, nor where the dragged track keeps a key. */
	@Test
	fun thePlayheadIsDroppedOnTheStartOrOnAKeptKey() {
		assertFalse(-5f in singleKeyDragTargets(playhead = -5f), "on the start, it would hold the mark in place")
		assertFalse(0f in singleKeyDragTargets(playhead = 0f), "on a kept key, it would stack two keys")
		assertEquals(setOf(8f, -8f, 7f), singleKeyDragTargets(playhead = null), "with no playhead, only keys")
	}

	/** A summary drag counts every track it stands for as its own, so none of their kept keys is a target. */
	@Test
	fun aSummaryDragLeavesOutEveryTrackItMoves() {
		val members = listOf(key(GEOMETRY, 1), key(OPACITY, 0))
		val targets =
			markDragSnapTargets(
				rows = rows,
				groupRowKeys = groupRowKeys,
				parameterId = angleX,
				draggedKeys = members,
				moving = members.toSet(),
				startPosition = 0f,
				playhead = 3f,
			)

		assertEquals(setOf(-8f, 7f, 3f), targets.toSet())
	}

	private companion object {
		const val DRAWABLE = "drawable:d"
		const val GEOMETRY = "drawable:d/geometry"
		const val OPACITY = "drawable:d/OPACITY"
		const val PART = "part:p"
		const val PART_OPACITY = "part:p/OPACITY"
	}
}