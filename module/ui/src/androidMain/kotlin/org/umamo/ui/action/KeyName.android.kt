package org.umamo.ui.action

import androidx.compose.ui.input.key.Key

/**
 * The identity: Android key codes carry no location (KEYCODE_NUMPAD_DOT is its own code), so the
 * event key already equals the named constant.
 *
 * @param Key key The key from a key event.
 * @return Key The key unchanged.
 */
internal actual fun normalizeKeyPosition(key: Key): Key = key
