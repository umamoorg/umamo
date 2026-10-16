package org.umamo.interop.art.mesh

import org.umamo.format.art.LayerRaster
import org.umamo.format.art.alphaChannel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Fitting a mesh the settings asked for cannot fit: the budget raised up to its cap, then holes filled
 * from the asked budget up, and a refusal for any other reason left standing.
 */
class FitArtMeshTest {
	private val noPoints = DoubleArray(0)

	@Test
	fun artNoBudgetUnderTheCapFitsIsMeshedWithItsHolesFilled() {
		// A square with four large holes: five outline rings, at least thirty vertices with the holes.
		val webbed =
			alphaOf(64, 64) { column, row ->
				val inSquare = column in 4 until 60 && row in 4 until 60
				val inHole = (column in 12 until 28 || column in 36 until 52) && (row in 12 until 28 || row in 36 until 52)
				inSquare && !inHole
			}
		val settings = ArtMeshPreset.Standard.settings.copy(vertexBudget = 16)
		assertNull(generateArtMesh(webbed, settings.copy(vertexBudget = 24)).mesh, "no budget up to the cap fits the holes")

		val fitted = fitArtMesh(webbed, settings, noPoints, noPoints, budgetCap = 24)

		assertNotNull(fitted.result.mesh, "a mesh: ${fitted.result.notices}")
		assertTrue(assertNotNull(fitted.fit).holesFilled, "its holes were filled")
	}

	@Test
	fun theBudgetRisesBeforeHolesAreFilled() {
		val webbed =
			alphaOf(64, 64) { column, row ->
				val inSquare = column in 4 until 60 && row in 4 until 60
				val inHole = (column in 12 until 28 || column in 36 until 52) && (row in 12 until 28 || row in 36 until 52)
				inSquare && !inHole
			}

		val fitted = fitArtMesh(webbed, ArtMeshPreset.Standard.settings.copy(vertexBudget = 16), noPoints, noPoints)

		val fit = assertNotNull(fitted.fit)
		assertEquals(false, fit.holesFilled, "a raised budget fits the holes, so they stay")
		assertTrue(assertNotNull(fit.budget) > 16)
		assertTrue(assertNotNull(fitted.result.mesh).vertexCount <= fit.budget!!)
	}

	@Test
	fun aRefusalForAnotherReasonStands() {
		val fitted = fitArtMesh(alphaOf(16, 16) { _, _ -> false }, ArtMeshPreset.Standard.settings, noPoints, noPoints)

		assertNull(fitted.result.mesh)
		assertNull(fitted.fit, "nothing was raised or filled")
		assertTrue(fitted.result.notices.any { notice -> notice is ArtMeshNotice.NothingOpaque })
	}

	/**
	 * An alpha channel opaque where a predicate says so.
	 *
	 * @param Int                   width  The width.
	 * @param Int                   height The height.
	 * @param (Int, Int) -> Boolean opaque Whether a pixel is opaque.
	 * @return AlphaChannel The channel.
	 */
	private fun alphaOf(width: Int, height: Int, opaque: (Int, Int) -> Boolean) =
		LayerRaster(width, height, ByteArray(width * height * 4) { component -> if (component % 4 == 3 && opaque((component / 4) % width, (component / 4) / width)) 0xFF.toByte() else 0 }).alphaChannel()
}