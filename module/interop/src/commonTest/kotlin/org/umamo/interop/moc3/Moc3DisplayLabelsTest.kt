package org.umamo.interop.moc3

import org.umamo.interop.moc3.import.displayLabelsOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins which cdi3 display labels an import takes.  An object with no label shows its id: the importer
 * asks the label table first and falls back to the id, so a blank label has to be absent from the table
 * for the id to be reached.
 */
class Moc3DisplayLabelsTest {
	/** A label with text in it is taken as written. */
	@Test
	fun aLabelWithTextIsTaken() {
		val labels = displayLabelsOf(listOf("ParamAngleX" to "Angle X", "ArtMesh3" to " Eye L "))

		assertEquals(mapOf("ParamAngleX" to "Angle X", "ArtMesh3" to " Eye L "), labels)
	}

	/** An empty or blank label is left out, so the id shows in its place. */
	@Test
	fun anEmptyOrBlankLabelIsLeftOut() {
		val labels = displayLabelsOf(listOf("ArtMesh1" to "", "ArtMesh2" to "   ", "ArtMesh3" to "Eye"))

		assertEquals(mapOf("ArtMesh3" to "Eye"), labels)
		assertEquals("ArtMesh1", labels["ArtMesh1"] ?: "ArtMesh1", "which is how the importer reaches the id")
	}

	/** A file with no such list has no labels. */
	@Test
	fun aMissingListHasNoLabels() {
		assertTrue(displayLabelsOf(null).isEmpty())
	}
}