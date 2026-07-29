package org.umamo.ui.kit

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The selection arithmetic behind Cut and Paste.
 *
 * Small, but the caret placement is exactly the kind of off-by-one that only shows up as "pasting twice puts
 * the second one in the wrong place" - a bug reported as flakiness rather than as arithmetic.
 */
class TextEditSelectionTest {
	/** Cut and Copy act on the selected span, in text order whichever way the user dragged. */
	@Test
	fun theSelectedTextIsTheSpanInTextOrder() {
		assertEquals("bcd", TextFieldValue("abcde", TextRange(1, 4)).selectedText())
		assertEquals("bcd", TextFieldValue("abcde", TextRange(4, 1)).selectedText(), "a backwards selection")
		assertEquals("", TextFieldValue("abcde", TextRange(2)).selectedText(), "a bare caret selects nothing")
	}

	/** Pasting over a selection replaces it and leaves the caret AFTER the inserted text, ready to keep typing. */
	@Test
	fun replacingASelectionLeavesTheCaretAfterTheInsertion() {
		val pasted = TextFieldValue("abcde", TextRange(1, 4)).withSelectionReplaced("XY")
		assertEquals("aXYe", pasted.text)
		assertEquals(TextRange(3), pasted.selection)
	}

	/** Pasting at a bare caret inserts without deleting anything. */
	@Test
	fun replacingABareCaretInserts() {
		val pasted = TextFieldValue("abc", TextRange(3)).withSelectionReplaced("!")
		assertEquals("abc!", pasted.text)
		assertEquals(TextRange(4), pasted.selection)
	}

	/** Cutting is the same op with an empty replacement, so the caret lands where the removed text began. */
	@Test
	fun cuttingLeavesTheCaretWhereTheTextWas() {
		val cut = TextFieldValue("abcde", TextRange(1, 4)).withSelectionReplaced("")
		assertEquals("ae", cut.text)
		assertEquals(TextRange(1), cut.selection)
	}
}
