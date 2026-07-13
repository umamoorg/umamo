package org.umamo.ui.action

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.nativeKeyLocation
import java.awt.event.KeyEvent

/*
 * The AWT virtual-key codes that only exist on the numeric keypad and whose Compose constants use the
 * same code (NumPad0..9, the four operators).  An event key with one of these codes but a non-NUMPAD
 * reported location can be re-located without ambiguity - no main-row key shares these codes.
 */
private val numpadOnlyKeyCodes =
	setOf(
		KeyEvent.VK_NUMPAD0,
		KeyEvent.VK_NUMPAD1,
		KeyEvent.VK_NUMPAD2,
		KeyEvent.VK_NUMPAD3,
		KeyEvent.VK_NUMPAD4,
		KeyEvent.VK_NUMPAD5,
		KeyEvent.VK_NUMPAD6,
		KeyEvent.VK_NUMPAD7,
		KeyEvent.VK_NUMPAD8,
		KeyEvent.VK_NUMPAD9,
		KeyEvent.VK_ADD,
		KeyEvent.VK_SUBTRACT,
		KeyEvent.VK_MULTIPLY,
		KeyEvent.VK_DIVIDE,
	)

/**
 * Remaps the numpad key codes AWT actually delivers onto the codes Compose's located constants are
 * built from.  Compose desktop defines Key.NumPadDot as Key(VK_PERIOD, NUMPAD) and Key.NumPadComma as
 * Key(VK_COMMA, NUMPAD), but AWT reports the physical keys as VK_DECIMAL / VK_SEPARATOR - so without
 * this remap the raw event key can never equal the constant and every NumpadDecimal binding is dead.
 * The remaining numpad-only codes match their constants code-for-code and only need the defensive
 * re-location for X stacks that under-report the key location.
 *
 * @param Key key The key from a key event.
 * @return Key The key to match the named constants against.
 */
internal actual fun normalizeKeyPosition(key: Key): Key =
	when {
		key.nativeKeyCode == KeyEvent.VK_DECIMAL -> Key(KeyEvent.VK_PERIOD, KeyEvent.KEY_LOCATION_NUMPAD)
		key.nativeKeyCode == KeyEvent.VK_SEPARATOR -> Key(KeyEvent.VK_COMMA, KeyEvent.KEY_LOCATION_NUMPAD)
		key.nativeKeyCode in numpadOnlyKeyCodes && key.nativeKeyLocation != KeyEvent.KEY_LOCATION_NUMPAD ->
			Key(key.nativeKeyCode, KeyEvent.KEY_LOCATION_NUMPAD)
		else -> key
	}
