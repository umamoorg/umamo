package org.umamo.ui.model.mesh

import org.umamo.edit.OperatorParameter
import org.umamo.edit.ParameterUnit
import org.umamo.edit.mesh.MeshGenerationKind
import org.umamo.interop.art.mesh.ArtMeshPreset
import org.umamo.ui.model.artwork.IMPORT_MAX_MARGIN
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The Generate Quad Mesh rows: the options survive the rows, the Margin row then the shared Alpha
 * Threshold row, every value clamped (the threshold never below the atlas's, so the default shows as
 * that floor), and the session memory keeping each command's own last options.
 */
class QuadMeshOptionsTest {
	@Test
	fun optionsSurviveTheRows() {
		val options = QuadMeshOptions(margin = 7, alphaThreshold = 90)

		assertEquals(options, QuadMeshOptions().fromRows(options.rows(minimumAlphaThreshold = 1), minimumAlphaThreshold = 1))
		assertEquals(MeshGenerationKind.Quad, options.kind)
	}

	@Test
	fun theMarginRowComesFirstThenTheSharedThreshold() {
		val rows = QuadMeshOptions().rows(minimumAlphaThreshold = 40)
		val margin = rows[0] as OperatorParameter.IntParameter
		val threshold = rows[1] as OperatorParameter.IntParameter

		assertEquals(listOf(QuadMeshParameterKeys.MARGIN, MeshGenerationParameterKeys.ALPHA_THRESHOLD), rows.map { row -> row.key })
		assertEquals(OperatorParameter.IntParameter(QuadMeshParameterKeys.MARGIN, QuadMeshParameterKeys.MARGIN, 2, 0, IMPORT_MAX_MARGIN, unit = ParameterUnit.Pixels), margin)
		assertEquals(40, threshold.value, "the default shows as the atlas's threshold")
		assertEquals(40, threshold.min)
		assertEquals(255, threshold.max)
	}

	@Test
	fun everyValueIsClamped() {
		assertEquals(QuadMeshOptions(margin = 0, alphaThreshold = 40), QuadMeshOptions(margin = -3, alphaThreshold = 0).clamped(minimumAlphaThreshold = 40))
		assertEquals(QuadMeshOptions(margin = IMPORT_MAX_MARGIN, alphaThreshold = 255), QuadMeshOptions(margin = 999, alphaThreshold = 300).clamped(minimumAlphaThreshold = 40))

		val typed =
			QuadMeshOptions().rows(minimumAlphaThreshold = 1).map { row ->
				when (row.key) {
					QuadMeshParameterKeys.MARGIN -> (row as OperatorParameter.IntParameter).copy(value = -1)
					else -> (row as OperatorParameter.IntParameter).copy(value = 3)
				}
			}

		assertEquals(QuadMeshOptions(margin = 0, alphaThreshold = 20), QuadMeshOptions().fromRows(typed, minimumAlphaThreshold = 20))
	}

	@Test
	fun theSessionMemoryKeepsEachCommandsOwnOptions() {
		val memory = MeshGenerationSessionOptions()
		val fine = MeshGenerationOptions.of(ArtMeshPreset.Fine)
		val quad = QuadMeshOptions(margin = 9, alphaThreshold = 30)

		assertEquals(QuadMeshOptions(), memory.quadOptions())

		memory.record(quad)

		assertEquals(quad, memory.quadOptions())
		assertEquals(MeshGenerationOptions.of(ArtMeshPreset.Coarse), memory.optionsFor("coarse"), "a quad run leaves Generate Mesh from Art's start alone")

		memory.record(fine)

		assertEquals(fine, memory.optionsFor("coarse"))
		assertEquals(quad, memory.quadOptions(), "a from-art run leaves the quad's alone")
	}
}