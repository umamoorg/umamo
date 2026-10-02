package org.umamo.render.puppet

import org.umamo.render.eval.meshLocalComponent
import org.umamo.render.eval.resolveMeshCorners
import org.umamo.runtime.eval.WeightedCell
import org.umamo.runtime.eval.cellsByLinearIndex
import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.MeshDeltaForm
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.deltasFromBase
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The delta upload's re-base (deltaUploadReference): the float32 shader, fed the re-based rest positions and texels,
 * lands where the double CPU evaluator does, on a drawable whose base is the canvas-space mesh and whose keyforms
 * are in a warp's 0..1 space - modelA's "Display 1 Line Out", whose opacity-0 keys squash the line a few hundred
 * thousandths of a unit wide.  The shader is emulated in float arithmetic, exactly the sum the vertex shader runs.
 */
class DeltaUploadReBaseTest {
	private val parameter = ParameterId("P")

	// Canvas pixels: Cubism's editable mesh for the drawable, the base Umamo keeps.
	private val base = floatArrayOf(4070.4497f, 1649.7695f, 4102.375f, 1649.7695f, 4134.3f, 1612.6583f)

	// The keyforms, in the parent warp's space: open, then squashed toward x 0.7214.
	private val openForm = floatArrayOf(0.6472907f, 0.36849776f, 0.65970415f, 0.36849776f, 0.6721175f, 0.36828208f)
	private val squashedForm = floatArrayOf(0.721398f, 0.36849776f, 0.72146004f, 0.36849776f, 0.72152215f, 0.36828208f)

	/**
	 * The two-key grid over [base]: the open form at key 0, the squashed one at key 1, or only the open form
	 * when [withSquashedCell] is false (the second cell missing - the rest mesh).
	 *
	 * @param Boolean withSquashedCell Whether the key-1 cell exists.
	 * @return KeyformGrid The grid.
	 */
	private fun grid(withSquashedCell: Boolean = true): KeyformGrid<MeshDeltaForm> =
		KeyformGrid(
			listOf(KeyformAxis(parameter, floatArrayOf(0f, 1f))),
			listOfNotNull(
				KeyformCell(intArrayOf(0), MeshDeltaForm(deltasFromBase(openForm, base))),
				if (withSquashedCell) KeyformCell(intArrayOf(1), MeshDeltaForm(deltasFromBase(squashedForm, base))) else null,
			),
		)

	/**
	 * The vertex shader's morph, emulated in float: the rest attribute plus each active corner's weighted texel.
	 *
	 * @param FloatArray         rest    The uploaded rest positions.
	 * @param FloatArray         texels  The uploaded texels, row-major (vertex rows, cell columns).
	 * @param Int                cellCount The texture's column count.
	 * @param List<WeightedCell> corners The active corners.
	 * @return FloatArray The local positions the shader computes.
	 */
	private fun shaderLocal(rest: FloatArray, texels: FloatArray, cellCount: Int, corners: List<WeightedCell>): FloatArray =
		FloatArray(rest.size) { componentIndex ->
			val vertexIndex = componentIndex / 2
			var local = rest[componentIndex]
			for (corner in corners) {
				local += corner.weight * texels[(vertexIndex * cellCount + corner.linearIndex) * 2 + componentIndex % 2]
			}
			local
		}

	/**
	 * The double CPU evaluator's local positions.
	 *
	 * @param KeyformGrid        keyformGrid The grid.
	 * @param List<WeightedCell> corners     The active corners.
	 * @return FloatArray The local positions.
	 */
	private fun cpuLocal(keyformGrid: KeyformGrid<MeshDeltaForm>, corners: List<WeightedCell>): FloatArray {
		val resolved = resolveMeshCorners(cellsByLinearIndex(keyformGrid), corners)
		return FloatArray(base.size) { componentIndex -> meshLocalComponent(base, componentIndex, resolved, null) }
	}

	/**
	 * The largest component gap between two position arrays.
	 *
	 * @param FloatArray left  One array.
	 * @param FloatArray right The other, the same length.
	 * @return Float The gap.
	 */
	private fun largestGap(left: FloatArray, right: FloatArray): Float = left.indices.maxOf { componentIndex -> abs(left[componentIndex] - right[componentIndex]) }

	/**
	 * At poses between the keys, the re-based upload agrees with the double CPU evaluation to float precision in the
	 * warp's space - where uploading the canvas-relative deltas themselves is off by the canvas magnitude's step.
	 */
	@Test
	fun theReBasedShaderAgreesWithTheCpuBetweenKeys() {
		val keyformGrid = grid()
		val cells = cellsByLinearIndex(keyformGrid)
		val reference = assertSameSizeReference(deltaUploadReference(cells, base.size / 2, 2))
		val rest = reBasedRestPositions(base, reference)
		val texels = buildDeltaTexels(keyformGrid, base.size / 2, 2, cells, reference)
		val plainTexels = buildDeltaTexels(keyformGrid, base.size / 2, 2, cells, null)
		var plainWorst = 0f
		for (fraction in listOf(0.1f, 0.3f, 0.5f, 0.7f, 0.9f, 0.97f)) {
			val corners = listOf(WeightedCell(0, 1f - fraction), WeightedCell(1, fraction))
			val cpu = cpuLocal(keyformGrid, corners)
			val gap = largestGap(shaderLocal(rest, texels, 2, corners), cpu)
			assertTrue(gap <= 1e-6f, "at fraction $fraction the re-based shader is $gap away from the CPU")
			plainWorst = maxOf(plainWorst, largestGap(shaderLocal(base, plainTexels, 2, corners), cpu))
		}
		assertTrue(plainWorst > 1e-5f, "the canvas-relative upload should show the rounding this re-base removes, got $plainWorst")
	}

	/**
	 * On each key the re-based shader gives that keyform back to float precision, the squashed one included.
	 */
	@Test
	fun theReBasedShaderLandsOnEachKeyform() {
		val keyformGrid = grid()
		val cells = cellsByLinearIndex(keyformGrid)
		val reference = deltaUploadReference(cells, base.size / 2, 2)
		val rest = reBasedRestPositions(base, reference)
		val texels = buildDeltaTexels(keyformGrid, base.size / 2, 2, cells, reference)
		val atOpen = shaderLocal(rest, texels, 2, listOf(WeightedCell(0, 1f)))
		val atSquashed = shaderLocal(rest, texels, 2, listOf(WeightedCell(1, 1f)))
		assertTrue(largestGap(atOpen, openForm) <= 1e-7f, "the open key lands ${largestGap(atOpen, openForm)} away")
		assertTrue(largestGap(atSquashed, squashedForm) <= 1e-7f, "the squashed key lands ${largestGap(atSquashed, squashedForm)} away")
	}

	/**
	 * A corner on a missing cell leaves the vertex at its rest mesh, as the CPU evaluates it: the texel is
	 * `-reference`, not zero, once the upload is re-based.
	 */
	@Test
	fun aMissingCellStillMeansTheRestMesh() {
		val keyformGrid = grid(withSquashedCell = false)
		val cells = cellsByLinearIndex(keyformGrid)
		val reference = deltaUploadReference(cells, base.size / 2, 2)
		val rest = reBasedRestPositions(base, reference)
		val texels = buildDeltaTexels(keyformGrid, base.size / 2, 2, cells, reference)
		val corners = listOf(WeightedCell(0, 0.25f), WeightedCell(1, 0.75f))
		val gap = largestGap(shaderLocal(rest, texels, 2, corners), cpuLocal(keyformGrid, corners))
		// The rest mesh is canvas-scale here, so float agreement is the canvas magnitude's step.
		assertTrue(gap <= 1e-3f, "a missing cell put the vertex $gap away from the CPU's rest blend")
	}

	/**
	 * A grid whose deltas are all zero, and an unkeyed drawable, upload exactly as before: no reference, and the
	 * model's own rest array.
	 */
	@Test
	fun aZeroGridUploadsThePlainRestArray() {
		val zeroGrid = KeyformGrid(listOf(KeyformAxis(parameter, floatArrayOf(0f))), listOf(KeyformCell(intArrayOf(0), MeshDeltaForm(DoubleArray(base.size)))))
		assertNull(deltaUploadReference(cellsByLinearIndex(zeroGrid), base.size / 2, 1))
		assertNull(deltaUploadReference(emptyMap(), base.size / 2, 1))
		assertSame(base, reBasedRestPositions(base, null))
	}

	/**
	 * Asserts the reference exists and covers every component.
	 *
	 * @param DoubleArray? reference The reference.
	 * @return DoubleArray The reference.
	 */
	private fun assertSameSizeReference(reference: DoubleArray?): DoubleArray {
		assertTrue(reference != null && reference.size == base.size, "a canvas-relative grid needs a reference over every component")
		return reference
	}
}