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
import org.umamo.edit.KeyableTarget

/**
 * The keyable property the pointer is currently over - what a keyform insert aims at.
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
class KeyableHover {
	/** The property under the pointer, or null when the pointer is over nothing keyable. */
	var target: KeyableTarget? by mutableStateOf(null)
		private set

	/**
	 * Records that the pointer entered [hovered].
	 *
	 * @param KeyableTarget hovered The property now under the pointer.
	 */
	fun enter(hovered: KeyableTarget) {
		target = hovered
	}

	/**
	 * Records that the pointer left [hovered].
	 *
	 * Takes the target rather than clearing unconditionally, because enter / exit events for adjacent rows
	 * can interleave - the new row's enter can arrive before the old row's exit, and a blind clear would
	 * then wipe a target the pointer is actually over.
	 *
	 * @param KeyableTarget hovered The property the pointer left.
	 */
	fun exit(hovered: KeyableTarget) {
		if (target == hovered) {
			target = null
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
fun Modifier.keyableTarget(target: KeyableTarget): Modifier {
	val hover = LocalKeyableHover.current ?: return this
	val interaction = remember { MutableInteractionSource() }
	val hovered by interaction.collectIsHoveredAsState()
	DisposableEffect(hover, target, hovered) {
		if (hovered) {
			hover.enter(target)
		} else {
			hover.exit(target)
		}
		// Leaving composition mid-hover (the panel switching tabs under the pointer) must not strand the
		// target, or the next I would key a row that is no longer on screen.
		onDispose { hover.exit(target) }
	}
	return this.hoverable(interaction)
}
