package org.umamo.ui.action

import androidx.compose.ui.input.key.Key

/**
 * Normalizes a key event's [Key] before name lookup.  Desktop Compose builds its located numpad
 * constants from different AWT codes than AWT actually delivers (Key.NumPadDot is Key(VK_PERIOD,
 * NUMPAD) while the physical key arrives as VK_DECIMAL), so without normalization the numpad decimal
 * - and with it the NumpadDecimal binding - can never match.  The desktop actual remaps those codes
 * (and defensively re-locates the numpad-only ones); Android keys carry no location and match their
 * constants directly, so its actual is the identity.
 *
 * @param Key key The key from a key event.
 * @return Key The key to match the named constants against.
 */
internal expect fun normalizeKeyPosition(key: Key): Key

/**
 * Maps a Compose [Key] to the position-based token a [KeyChord] stores ("KeyP", "Digit0", "F5", "ArrowUp",
 * …), or null for a key the keymap does not bind (modifiers, media keys, anything unlisted).  This is the
 * single source of truth shared by the live keyboard dispatch (handleShellKey) and the rebinding editor's
 * chord capture, so a captured chord and a dispatched chord always agree on a key's name - the tokens here
 * are exactly the ones a preset spec or a stored override uses.
 *
 * Compose の Key を KeyChord が保持する位置ベースのトークンに変換する。入力ディスパッチと再割り当て UI で共有し、
 * 取り込んだコードと発火するコードのキー名が必ず一致するようにする。
 *
 * @param Key key The Compose key from a key event.
 * @return String? The position-based token, or null if the key is not bindable.
 */
fun keyName(key: Key): String? =
	when (normalizeKeyPosition(key)) {
		Key.A -> "KeyA"
		Key.B -> "KeyB"
		Key.C -> "KeyC"
		Key.D -> "KeyD"
		Key.E -> "KeyE"
		Key.F -> "KeyF"
		Key.G -> "KeyG"
		Key.H -> "KeyH"
		Key.I -> "KeyI"
		Key.J -> "KeyJ"
		Key.K -> "KeyK"
		Key.L -> "KeyL"
		Key.M -> "KeyM"
		Key.N -> "KeyN"
		Key.O -> "KeyO"
		Key.P -> "KeyP"
		Key.Q -> "KeyQ"
		Key.R -> "KeyR"
		Key.S -> "KeyS"
		Key.T -> "KeyT"
		Key.U -> "KeyU"
		Key.V -> "KeyV"
		Key.W -> "KeyW"
		Key.X -> "KeyX"
		Key.Y -> "KeyY"
		Key.Z -> "KeyZ"
		Key.Zero -> "Digit0"
		Key.One -> "Digit1"
		Key.Two -> "Digit2"
		Key.Three -> "Digit3"
		Key.Four -> "Digit4"
		Key.Five -> "Digit5"
		Key.Six -> "Digit6"
		Key.Seven -> "Digit7"
		Key.Eight -> "Digit8"
		Key.Nine -> "Digit9"
		Key.F1 -> "F1"
		Key.F2 -> "F2"
		Key.F3 -> "F3"
		Key.F4 -> "F4"
		Key.F5 -> "F5"
		Key.F6 -> "F6"
		Key.F7 -> "F7"
		Key.F8 -> "F8"
		Key.F9 -> "F9"
		Key.F10 -> "F10"
		Key.F11 -> "F11"
		Key.F12 -> "F12"
		Key.Spacebar -> "Space"
		Key.Enter -> "Enter"
		Key.Tab -> "Tab"
		Key.Escape -> "Escape"
		Key.Backspace -> "Backspace"
		Key.Delete -> "Delete"
		Key.Insert -> "Insert"
		Key.DirectionUp -> "ArrowUp"
		Key.DirectionDown -> "ArrowDown"
		Key.DirectionLeft -> "ArrowLeft"
		Key.DirectionRight -> "ArrowRight"
		Key.MoveHome -> "Home"
		Key.MoveEnd -> "End"
		Key.PageUp -> "PageUp"
		Key.PageDown -> "PageDown"
		Key.Comma -> "Comma"
		Key.Period -> "Period"
		Key.Minus -> "Minus"
		Key.Equals -> "Equals"
		Key.Semicolon -> "Semicolon"
		Key.Apostrophe -> "Quote"
		Key.LeftBracket -> "BracketLeft"
		Key.RightBracket -> "BracketRight"
		Key.Backslash -> "Backslash"
		Key.Slash -> "Slash"
		Key.Grave -> "Backquote"
		Key.NumPadAdd -> "NumpadAdd"
		Key.NumPadSubtract -> "NumpadSubtract"
		Key.NumPadMultiply -> "NumpadMultiply"
		Key.NumPadDivide -> "NumpadDivide"
		Key.NumPadDot -> "NumpadDecimal"
		Key.NumPadEnter -> "NumpadEnter"
		Key.NumPad0 -> "Numpad0"
		Key.NumPad1 -> "Numpad1"
		Key.NumPad2 -> "Numpad2"
		Key.NumPad3 -> "Numpad3"
		Key.NumPad4 -> "Numpad4"
		Key.NumPad5 -> "Numpad5"
		Key.NumPad6 -> "Numpad6"
		Key.NumPad7 -> "Numpad7"
		Key.NumPad8 -> "Numpad8"
		Key.NumPad9 -> "Numpad9"
		else -> null
	}
