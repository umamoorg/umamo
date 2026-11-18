package org.umamo.ui.workspace.operationstrip

import org.umamo.edit.BrushMode
import org.umamo.edit.DEFAULT_BRUSH_SETTINGS
import org.umamo.edit.OperatorParameter
import org.umamo.edit.choiceValue
import org.umamo.edit.transform.BrushParameterKeys
import org.umamo.edit.transform.brushParameters
import org.umamo.edit.transform.brushSettingsOf
import org.umamo.edit.transform.choiceKey
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the Deform brush's Mode row on the operation settings strip: it carries the mode the stroke landed
 * with, it offers one entry per mode, and reading the rows back gives the mode again, so a finished
 * stroke can be re-run under the other mode.
 */
class BrushModeRowTest {
	/** The row carries the mode, and the rows read it back. */
	@Test
	fun theModeRowCarriesTheModeAndReadsItBack() {
		for (mode in BrushMode.entries) {
			val settings = DEFAULT_BRUSH_SETTINGS.copy(mode = mode)
			val rows = brushParameters(settings)

			assertEquals(mode.choiceKey, rows.choiceValue(BrushParameterKeys.MODE, ""), "$mode is the row's value")
			assertEquals(settings, brushSettingsOf(rows, DEFAULT_BRUSH_SETTINGS), "$mode reads back")
		}
	}

	/** The row offers one entry per mode, keyed by the mode's own name. */
	@Test
	fun theModeRowOffersEveryMode() {
		val row =
			brushParameters(DEFAULT_BRUSH_SETTINGS).first { parameter -> parameter.key == BrushParameterKeys.MODE }
				as OperatorParameter.ChoiceParameter

		assertEquals(BrushMode.entries.map { mode -> mode.choiceKey }, row.choices.map { choice -> choice.key })
	}
}