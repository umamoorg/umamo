package org.umamo.ui.model

import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import org.umamo.runtime.model.KeyableTarget
import org.umamo.runtime.model.KeyformTrackRef

/**
 * The keyform track the pointer is currently over - what a keyform insert aims at.
 *
 * A TRACK rather than a channel, so the keyform sheet's rows can publish here too: pointing at a
 * Geometry row and pressing `I` has to mean something, and geometry is not addressable as a channel.
 *
 * Hover targeting is what lets `I` work with no prior selection: point at Opacity, press I, done.  It
 * mirrors the shell's existing hovered-surface seam, and like that one it is resolved at DISPATCH time,
 * never latched at registration - a command reads the live hover when it fires, so it can never act on a
 * row the pointer left three interactions ago.
 *
 * Plain mutable state rather than a flow: it changes on every pointer enter / exit and nothing needs to
 * observe it reactively, only sample it when a command runs.
 *
 * ポインタ下のキー可能プロパティ。キーフレーム挿入の対象を、事前選択なしで決める。
 */
data class KeyformHover(
	/** The track under the pointer. */
	val track: KeyformTrackRef,
	/**
	 * Where along the parameter the pointer sits, or null when the row has no position axis.
	 *
	 * A Properties row is a single value with nowhere to point AT, so it publishes null and a keyframe op
	 * falls back to the pose.  A keyform-sheet lane spans the whole parameter, so pointing at a spot on it
	 * is a statement about WHICH spot - and acting on the playhead instead would ignore what was aimed at.
	 */
	val position: Float? = null,
	/** The ordinal of the key under the pointer, or null when the pointer is not on one. */
	val keyIndex: Int? = null,
)

class KeyableHover {
	/** What the pointer is over, or null when it is over nothing keyable. */
	var hovered: KeyformHover? by mutableStateOf(null)
		private set

	/**
	 * Records what the pointer is now over.
	 *
	 * Also called as the pointer MOVES along a lane, since the position is part of what is hovered - so
	 * this is a plain assignment rather than an enter-once latch.
	 *
	 * @param KeyformHover hover The track, and where on it, now under the pointer.
	 */
	fun enter(hover: KeyformHover) {
		hovered = hover
	}

	/**
	 * Records that the pointer left [track].
	 *
	 * Takes the track rather than clearing unconditionally, because enter / exit events for adjacent rows
	 * can interleave - the new row's enter can arrive before the old row's exit, and a blind clear would
	 * then wipe a target the pointer is actually over.
	 *
	 * @param KeyformTrackRef track The track the pointer left.
	 */
	fun exit(track: KeyformTrackRef) {
		if (hovered?.track == track) {
			hovered = null
		}
	}
}

/**
 * The shell's keyable-hover holder, or null outside a shell that provides one.
 *
 * Static because the holder instance never changes for a shell's lifetime; only its contents do.
 */
val LocalKeyableHover = staticCompositionLocalOf<KeyableHover?> { null }

/**
 * Publishes this composable as the keyable property under the pointer while it is hovered.
 *
 * Apply to any row that edits a keyform channel, and `I` will key it with no prior selection - the command
 * samples the hover when it fires.  Uses hoverable / collectIsHoveredAsState rather than raw enter-exit
 * events so it behaves identically on desktop and on Android, where hover only exists with a stylus.
 *
 * Inert (returns the receiver unchanged) outside a shell that provides a holder, so a composable carrying
 * it stays usable in isolation - a preview, or a test harness.
 *
 * @param KeyableTarget target The property this row edits.
 * @return Modifier The modifier publishing the hover.
 */
@Composable
fun Modifier.keyableTarget(target: KeyableTarget): Modifier = keyableTrack(KeyformTrackRef.Channel(target))

/**
 * Publishes this composable as the keyform TRACK under the pointer while it is hovered.
 *
 * The general form of [keyableTarget], for callers that can point at a geometry track as well as a
 * channel - the keyform sheet's rows.
 *
 * @param KeyformTrackRef track The track this row edits.
 * @return Modifier The modifier publishing the hover.
 */
@Composable
fun Modifier.keyableTrack(track: KeyformTrackRef): Modifier {
	val hover = LocalKeyableHover.current ?: return this
	val interaction = remember { MutableInteractionSource() }
	val hovered by interaction.collectIsHoveredAsState()
	DisposableEffect(hover, track, hovered) {
		if (hovered) {
			hover.enter(KeyformHover(track))
		} else {
			hover.exit(track)
		}
		// Leaving composition mid-hover (the panel switching tabs under the pointer) must not strand the
		// target, or the next I would key a row that is no longer on screen.
		onDispose { hover.exit(track) }
	}
	return this.hoverable(interaction)
}
