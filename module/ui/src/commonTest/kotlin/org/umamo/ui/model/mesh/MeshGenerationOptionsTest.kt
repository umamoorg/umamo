package org.umamo.ui.model.mesh

import org.umamo.edit.OperatorParameter
import org.umamo.edit.booleanValue
import org.umamo.edit.choiceValue
import org.umamo.edit.floatValue
import org.umamo.edit.intValue
import org.umamo.interop.art.mesh.ArtMeshPreset
import org.umamo.interop.art.mesh.MAXIMUM_ART_MESH_MINIMUM_ANGLE
import org.umamo.interop.art.mesh.MAXIMUM_ART_MESH_VERTEX_BUDGET
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Generate Mesh from Art rows: options survive the trip through the rows, every row is clamped into
 * what the mesher accepts (the threshold never below the atlas's), a preset re-seeds only the density
 * rows, the strand width among them (the budget, its interior thinning limit, Strand Rungs, and the
 * deformation tolerance stay), and the session memory starts from the settings' preset and then follows
 * what landed.
 */
class MeshGenerationOptionsTest {
	@Test
	fun optionsSurviveTheRows() {
		val options = MeshGenerationOptions(ArtMeshPreset.Fine, ArtMeshPreset.Fine.settings.copy(vertexBudget = 2048, fillHoles = true, minimumAngle = 26.0, interiorThinningLimit = 6.5, deformationTolerance = 1.5, strandRungs = false, strandWidth = 33.5))
		val rows = meshGenerationParameters(options, minimumAlphaThreshold = 1)

		assertEquals(options, meshGenerationOptionsOf(rows, MeshGenerationOptions.of(ArtMeshPreset.Standard), minimumAlphaThreshold = 1))
	}

	@Test
	fun everyRowIsClampedIntoWhatTheMesherAccepts() {
		val rows =
			meshGenerationParameters(MeshGenerationOptions.of(ArtMeshPreset.Standard), minimumAlphaThreshold = 1)
				.replaced(MeshGenerationParameterKeys.ALPHA_THRESHOLD) { row -> (row as OperatorParameter.IntParameter).copy(value = 0) }
				.replaced(MeshGenerationParameterKeys.OUTLINE_SPACING) { row -> (row as OperatorParameter.FloatParameter).copy(value = -5f) }
				.replaced(MeshGenerationParameterKeys.INNER_MARGIN) { row -> (row as OperatorParameter.FloatParameter).copy(value = Float.NaN) }
				.replaced(MeshGenerationParameterKeys.MINIMUM_MARGIN) { row -> (row as OperatorParameter.FloatParameter).copy(value = 0.1f) }
				.replaced(MeshGenerationParameterKeys.MINIMUM_OUTLINE_POINTS) { row -> (row as OperatorParameter.IntParameter).copy(value = 1) }
				.replaced(MeshGenerationParameterKeys.VERTEX_BUDGET) { row -> (row as OperatorParameter.IntParameter).copy(value = 99999) }
				.replaced(MeshGenerationParameterKeys.MINIMUM_ANGLE) { row -> (row as OperatorParameter.FloatParameter).copy(value = 90f) }
				.replaced(MeshGenerationParameterKeys.INTERIOR_THINNING_LIMIT) { row -> (row as OperatorParameter.FloatParameter).copy(value = 0.2f) }
				.replaced(MeshGenerationParameterKeys.STRAND_WIDTH) { row -> (row as OperatorParameter.FloatParameter).copy(value = 0f) }

		val settings = meshGenerationOptionsOf(rows, MeshGenerationOptions.of(ArtMeshPreset.Standard), minimumAlphaThreshold = 40).settings

		assertEquals(40, settings.alphaThreshold, "the threshold never drops below the atlas's")
		assertEquals(1.0, settings.outlineSpacing)
		assertEquals(0.0, settings.innerMargin, "a value that is not finite falls back to its floor")
		assertEquals(0.5, settings.minimumMargin)
		assertEquals(3, settings.minimumOutlinePoints)
		assertEquals(MAXIMUM_ART_MESH_VERTEX_BUDGET, settings.vertexBudget)
		assertEquals(MAXIMUM_ART_MESH_MINIMUM_ANGLE, settings.minimumAngle)
		assertEquals(1.0, settings.interiorThinningLimit)
		assertEquals(1.0, settings.strandWidth)
	}

	@Test
	fun aPresetReseedsOnlyTheDensityRows() {
		val custom = ArtMeshPreset.Standard.settings.copy(alphaThreshold = 50, vertexBudget = 2048, fillHoles = true, outlineSpacing = 99.0, minimumAngle = 5.0, interiorThinningLimit = 9.0, deformationTolerance = 2.0, strandRungs = false, strandWidth = 99.0)
		val rows = meshGenerationParameters(MeshGenerationOptions(ArtMeshPreset.Standard, custom), minimumAlphaThreshold = 1)

		val reseeded = reseededMeshGenerationParameters(rows, ArtMeshPreset.Coarse, minimumAlphaThreshold = 1)

		val coarse = ArtMeshPreset.Coarse.settings
		assertEquals("coarse", reseeded.choiceValue(MeshGenerationParameterKeys.PRESET, ""))
		assertEquals(coarse.outlineSpacing.toFloat(), reseeded.floatValue(MeshGenerationParameterKeys.OUTLINE_SPACING, 0f))
		assertEquals(coarse.interiorSpacing.toFloat(), reseeded.floatValue(MeshGenerationParameterKeys.INTERIOR_SPACING, 0f))
		assertEquals(coarse.outerMargin.toFloat(), reseeded.floatValue(MeshGenerationParameterKeys.OUTER_MARGIN, 0f))
		assertEquals(coarse.minimumOutlinePoints, reseeded.intValue(MeshGenerationParameterKeys.MINIMUM_OUTLINE_POINTS, 0))
		assertEquals(coarse.minimumAngle.toFloat(), reseeded.floatValue(MeshGenerationParameterKeys.MINIMUM_ANGLE, 0f), "the minimum angle is a density row")
		assertEquals(coarse.strandWidth.toFloat(), reseeded.floatValue(MeshGenerationParameterKeys.STRAND_WIDTH, 0f), "the strand width is a density row")
		assertEquals(50, reseeded.intValue(MeshGenerationParameterKeys.ALPHA_THRESHOLD, 0), "the threshold stays")
		assertEquals(2048, reseeded.intValue(MeshGenerationParameterKeys.VERTEX_BUDGET, 0), "the budget stays")
		assertTrue(reseeded.booleanValue(MeshGenerationParameterKeys.FILL_HOLES, false), "Fill Holes stays")
		assertFalse(reseeded.booleanValue(MeshGenerationParameterKeys.STRAND_RUNGS, true), "Strand Rungs stays")
		assertEquals(9f, reseeded.floatValue(MeshGenerationParameterKeys.INTERIOR_THINNING_LIMIT, 0f), "the budget's thinning limit stays")
		assertEquals(2f, reseeded.floatValue(MeshGenerationParameterKeys.DEFORMATION_TOLERANCE, 0f), "the deformation tolerance stays")
	}

	@Test
	fun theSessionMemoryStartsFromTheSettingsAndFollowsWhatLanded() {
		val memory = MeshGenerationSessionOptions()
		assertEquals(MeshGenerationOptions.of(ArtMeshPreset.Fine), memory.optionsFor("fine"))
		assertEquals(MeshGenerationOptions.of(ArtMeshPreset.Standard), memory.optionsFor("unknown"))
		val landed = MeshGenerationOptions(ArtMeshPreset.Coarse, ArtMeshPreset.Coarse.settings.copy(fillHoles = true))
		memory.record(landed)

		assertEquals(landed, memory.optionsFor("fine"), "a landed run outranks the settings' preset")
	}

	/**
	 * The rows with one row replaced.
	 *
	 * @param String   key       The row's key.
	 * @param Function transform Builds the replacement.
	 * @return List<OperatorParameter> The rows.
	 */
	private fun List<OperatorParameter>.replaced(key: String, transform: (OperatorParameter) -> OperatorParameter): List<OperatorParameter> =
		map { row -> if (row.key == key) transform(row) else row }
}