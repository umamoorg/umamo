package org.umamo.ui.workspace

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type

/**
 * One key press reduced to the five facts the shell's modal ladder and keymap dispatch actually read.
 *
 * Compose's KeyEvent is an expect class with no common constructor - it wraps the platform's native
 * event - so a function taking one cannot be exercised from commonTest at all.  Decoding to this value
 * type at the root handler keeps the whole precedence a pure common function, which is what lets
 * ModalKeyLadderTest state a modal condition and press a key without standing up a composition.
 *
 * [primaryModifier] folds Ctrl and Meta the way [org.umamo.ui.action.KeyChord] does, so the keymap
 * fallthrough builds its chord straight from these fields rather than decoding the event a second time.
 *
 * @property Key key The key that moved.
 * @property Boolean isDown True on key-down; the ladder's arms that test for a press read this.
 * @property Boolean primaryModifier True when Ctrl (or Meta on macOS) is held.
 * @property Boolean shift True when Shift is held.
 * @property Boolean alt True when Alt / Option is held.
 */
internal data class ShellKeyStroke(
	val key: Key,
	val isDown: Boolean,
	val primaryModifier: Boolean = false,
	val shift: Boolean = false,
	val alt: Boolean = false,
)

/**
 * Reduces a Compose key event to the [ShellKeyStroke] the shell routes on.
 *
 * Every accessor read here is common API on the expect class; only constructing a KeyEvent is
 * platform-specific, which is why this adapter can live in commonMain while its result is what the
 * tests drive.
 *
 * @return ShellKeyStroke The decoded stroke.
 */
internal fun KeyEvent.toShellKeyStroke(): ShellKeyStroke =
	ShellKeyStroke(
		key = key,
		isDown = type == KeyEventType.KeyDown,
		primaryModifier = isCtrlPressed || isMetaPressed,
		shift = isShiftPressed,
		alt = isAltPressed,
	)
