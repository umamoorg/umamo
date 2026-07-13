package org.umamo.ui.action

import androidx.compose.ui.input.key.Key
import java.awt.event.KeyEvent
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the desktop numpad normalization: AWT delivers the physical numpad keys as codes that differ
 * from (or arrive located differently than) the codes Compose's Key.NumPad* constants are built from,
 * and without [normalizeKeyPosition] those bindings silently never fire.
 */
class KeyNameJvmTest {
	@Test
	fun numpadDecimalAsDeliveredByAwtResolves() {
		// AWT reports the numpad period as VK_DECIMAL at the NUMPAD location; Compose's constant is
		// Key(VK_PERIOD, NUMPAD), so the raw key only names through the remap.
		assertEquals("NumpadDecimal", keyName(Key(KeyEvent.VK_DECIMAL, KeyEvent.KEY_LOCATION_NUMPAD)))
		assertEquals("NumpadDecimal", keyName(Key.NumPadDot), "the constant itself keeps naming")
	}

	@Test
	fun numpadOperatorsResolveEvenWithAMisreportedLocation() {
		assertEquals("NumpadAdd", keyName(Key(KeyEvent.VK_ADD, KeyEvent.KEY_LOCATION_NUMPAD)))
		// Some X stacks under-report the location; numpad-only codes re-locate defensively.
		assertEquals("NumpadAdd", keyName(Key(KeyEvent.VK_ADD, KeyEvent.KEY_LOCATION_STANDARD)))
		assertEquals("Numpad7", keyName(Key(KeyEvent.VK_NUMPAD7, KeyEvent.KEY_LOCATION_STANDARD)))
	}

	@Test
	fun mainRowKeysAreUntouchedByTheNormalization() {
		// The main-row period shares the VK code the NumPadDot constant is built from; it must keep
		// resolving to Period (the pivot pie's binding), never remap into a numpad key.
		assertEquals("Period", keyName(Key(KeyEvent.VK_PERIOD, KeyEvent.KEY_LOCATION_STANDARD)))
		assertEquals("KeyG", keyName(Key.G))
	}
}
