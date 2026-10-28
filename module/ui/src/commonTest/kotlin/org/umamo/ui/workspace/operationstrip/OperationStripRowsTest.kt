package org.umamo.ui.workspace.operationstrip

import org.umamo.edit.OperatorParameter
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins how the strip groups a record's parameters into rows: a real-valued parameter naming the real-valued one
 * right after it as its ratio partner pairs with it, and any other partner key leaves both rows standing alone.
 */
class OperationStripRowsTest {
	private val scaleX = OperatorParameter.FloatParameter("scaleX", "scaleX", 1f, 0.01f, 100f, ratioPartnerKey = "scaleY")
	private val scaleY = OperatorParameter.FloatParameter("scaleY", "scaleY", 2f, 0.01f, 100f)
	private val proportional = OperatorParameter.BooleanParameter("proportional", "proportional", false)

	/** A hinted pair becomes one row keyed by its first parameter; the rows around it stand alone. */
	@Test
	fun aHintedPairGroups() {
		val rows = operationStripRows(listOf(scaleX, scaleY, proportional))

		assertEquals(listOf(OperationStripRow.RatioPair(scaleX, scaleY), OperationStripRow.Single(proportional)), rows)
		assertEquals(listOf("scaleX", "proportional"), rows.map { row -> row.key })
	}

	/** Without a hint, two real-valued rows stay two rows. */
	@Test
	fun anUnhintedPairStaysSingle() {
		val plainX = scaleX.copy(ratioPartnerKey = null)

		assertEquals(listOf(OperationStripRow.Single(plainX), OperationStripRow.Single(scaleY)), operationStripRows(listOf(plainX, scaleY)))
	}

	/** A partner key naming a parameter that is not the next one is ignored. */
	@Test
	fun aPartnerThatIsNotNextIsIgnored() {
		val rows = operationStripRows(listOf(scaleX, proportional, scaleY))

		assertEquals(listOf(OperationStripRow.Single(scaleX), OperationStripRow.Single(proportional), OperationStripRow.Single(scaleY)), rows)
	}

	/** A partner key naming the next parameter is ignored when that parameter is not real-valued. */
	@Test
	fun aPartnerThatIsNotAFloatIsIgnored() {
		val pointsAtFlag = scaleX.copy(ratioPartnerKey = "proportional")

		assertEquals(listOf(OperationStripRow.Single(pointsAtFlag), OperationStripRow.Single(proportional)), operationStripRows(listOf(pointsAtFlag, proportional)))
	}
}