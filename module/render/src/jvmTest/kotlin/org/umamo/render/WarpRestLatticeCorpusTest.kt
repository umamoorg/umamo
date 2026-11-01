package org.umamo.render

import org.umamo.format.cmo3.Cmo3
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.interop.cmo3.Cmo3Import
import org.umamo.render.eval.DrawableSpaceResolver
import org.umamo.render.eval.PoseParameters
import org.umamo.render.eval.RotationWorld
import org.umamo.render.eval.buildDeformerWorlds
import org.umamo.render.eval.warpLatticeAt
import org.umamo.runtime.keyform.withLatticeShifted
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Whether a warp's rest lattice can be DERIVED rather than stored, checked over every warp of every corpus model.
 *
 * Edit mode reshapes a warp's lattice at rest by shifting every form it carries, with the rest itself derived as
 * the lattice at the default pose; that holds only if the derivation is reliable on real rigs.  This counts what
 * would reopen storing a rest lattice instead - warps the default pose hides, which have no derived rest, and blend
 * shapes that contribute at the default pose, which make the derived rest more than the grid's - and asserts what
 * the edit relies on:
 *  - a shift moves the evaluated lattice by exactly its delta wherever the warp's own parameters take it;
 *  - repeated shifts and their negations return, with no drift beyond a few ULP;
 *  - re-expressing a shifted warp's direct children keeps every converted child where it rests, within the
 *    landing tolerance, on the corpus's real (often nested, curved) cages.
 * The counts are printed for the record; the assertions fail on any violation.
 */
class WarpRestLatticeCorpusTest {
	private companion object {
		/** A shift's size, as a fraction of the lattice's span in its parent's space. */
		const val SHIFT_FRACTION = 0.01f

		/** The relative error a shifted lattice may show against old + delta: float summation, a few ULP. */
		const val RELATIVE_TOLERANCE = 1e-4f

		/** Parameter positions swept, as fractions across the range. */
		val SWEEP_FRACTIONS = listOf(0f, 0.3f, 0.5f, 0.8f, 1f)
	}

	/**
	 * The corpus models to check.
	 *
	 * @return List<File> The probe files present.
	 */
	private fun probeFiles(): List<File> =
		System.getProperty("cmo3.probe")
			?.split(',')
			?.map(::File)
			?.filter { file -> file.isFile }
			?: emptyList()

	/**
	 * A corpus CMO3 as a model.
	 *
	 * @param File file The CMO3.
	 * @return PuppetModel The imported model.
	 */
	private fun importCmo3(file: File): PuppetModel {
		val root = Cmo3.read(file).root as? CModelSource ?: error("${file.name}: root is not a CModelSource")

		return Cmo3Import.fromModelSource(root)
	}

	/**
	 * The poses that move one of [warp]'s own parameters at a time - its geometry axes and its blend shapes - with
	 * the defaults first.
	 *
	 * @param PuppetModel model The rig.
	 * @param Deformer.Warp warp The warp.
	 * @return List The poses.
	 */
	private fun ownSweep(model: PuppetModel, warp: Deformer.Warp): List<Map<ParameterId, Float>> {
		val own = (warp.geometryGrid?.axes.orEmpty().map { axis -> axis.parameterId } + warp.blendShapes.map { binding -> binding.parameterId }).toSet()
		val poses = mutableListOf(emptyMap<ParameterId, Float>())

		for (parameter in model.parameters.filter { candidate -> candidate.id in own }) {
			for (fraction in SWEEP_FRACTIONS) {
				poses.add(mapOf(parameter.id to parameter.min + (parameter.max - parameter.min) * fraction))
			}
		}

		return poses
	}

	/**
	 * A small smooth shift for a lattice: a gentle wave across its grid, each component at most [SHIFT_FRACTION] of
	 * the lattice's span, with a random phase.  Smooth because an edit is - a grab, a scale, a proportional halo; a
	 * per-point random offset that large would fold the cells of a fine lattice, which no edit does.
	 *
	 * @param FloatArray lattice The rest lattice.
	 * @param Deformer.Warp warp The warp, for its grid size.
	 * @param Random random The source of the phase.
	 * @return FloatArray The shift.
	 */
	private fun smoothShift(lattice: FloatArray, warp: Deformer.Warp, random: Random): FloatArray {
		var span = 0f

		for (componentIndex in lattice.indices) {
			for (otherIndex in componentIndex % 2 until lattice.size step 2) {
				span = max(span, abs(lattice[componentIndex] - lattice[otherIndex]))
			}
		}

		val phaseX = random.nextFloat() * 6.28f
		val phaseY = random.nextFloat() * 6.28f

		return FloatArray(lattice.size) { componentIndex ->
			val pointIndex = componentIndex / 2
			val u = (pointIndex % (warp.columns + 1)).toFloat() / warp.columns
			val v = (pointIndex / (warp.columns + 1)).toFloat() / warp.rows
			span * SHIFT_FRACTION * if (componentIndex % 2 == 0) sin(3.1f * u + phaseX) else cos(2.7f * v + phaseY)
		}
	}

	/**
	 * The point (10, 0) of a rotation's frame in the cascade's world at the default pose.
	 *
	 * @param PuppetModel model The rig.
	 * @param DeformerId rotationId The rotation.
	 * @return FloatArray? The point, or null when the rotation is not built.
	 */
	private fun throughRotation(model: PuppetModel, rotationId: DeformerId): FloatArray? {
		val rest = PoseParameters(model, emptyMap())
		val world = buildDeformerWorlds(model.deformers, rest.paramValue, rest.defaultValue)[rotationId] as? RotationWorld ?: return null
		val out = FloatArray(2)
		world.apply(10f, 0f, out, 0)

		return out
	}

	/**
	 * The largest difference between two arrays.
	 *
	 * @param FloatArray first One array.
	 * @param FloatArray second The other, the same size.
	 * @return Float The largest component difference.
	 */
	private fun largestDifference(first: FloatArray, second: FloatArray): Float {
		var largest = 0f

		for (componentIndex in first.indices) {
			largest = max(largest, abs(first[componentIndex] - second[componentIndex]))
		}

		return largest
	}

	@Test
	fun theDerivedRestLatticeHoldsOverTheCorpus() {
		val files = probeFiles()

		if (files.isEmpty()) {
			println("cmo3.probe not present; skipping the rest-lattice corpus check")

			return
		}

		val random = Random(20261029)
		val violations = mutableListOf<String>()
		var warpCount = 0
		var hiddenAtDefaults = 0
		var blendAtDefaults = 0
		var sweptLattices = 0
		var warpsWithChildren = 0
		var convertedChildren = 0
		var unconvertedHidden = 0
		var unconvertedUnreachable = 0

		for (file in files) {
			val model = importCmo3(file)
			val restSpaces = DrawableSpaceResolver(model, emptyMap())
			var modelHidden = 0
			var modelBlend = 0

			for (warp in model.deformers.filterIsInstance<Deformer.Warp>()) {
				warpCount++
				val rest = warpLatticeAt(model, emptyMap(), warp.id)

				if (rest == null) {
					hiddenAtDefaults++
					modelHidden++

					continue
				}

				if (largestDifference(rest.local, rest.gridLocal) > 1e-6f) {
					blendAtDefaults++
					modelBlend++
				}

				val delta = smoothShift(rest.local, warp, random)
				val shiftedWarp = warp.withLatticeShifted(delta)
				val shifted = model.copy(deformers = model.deformers.map { deformer -> if (deformer.id == warp.id) shiftedWarp else deformer })

				for (pose in ownSweep(model, warp)) {
					val before = warpLatticeAt(model, pose, warp.id) ?: continue
					val after = warpLatticeAt(shifted, pose, warp.id)

					if (after == null) {
						violations += "${file.name}: ${warp.id.raw} vanished at $pose after a shift"

						continue
					}

					val expected = FloatArray(before.local.size) { componentIndex -> before.local[componentIndex] + delta[componentIndex] }
					val scale = max(1f, before.local.maxOf { component -> abs(component) })

					if (largestDifference(expected, after.local) > RELATIVE_TOLERANCE * scale) {
						violations += "${file.name}: ${warp.id.raw} at $pose moved ${largestDifference(expected, after.local)} off old + delta"
					}

					sweptLattices++
				}

				var roundTripped = warp
				val negated = FloatArray(delta.size) { componentIndex -> -delta[componentIndex] }

				repeat(100) {
					roundTripped = roundTripped.withLatticeShifted(delta).withLatticeShifted(negated)
				}

				val originalCells = warp.geometryGrid?.cells.orEmpty()
				val returnedCells = roundTripped.geometryGrid?.cells.orEmpty()

				for (cellIndex in originalCells.indices) {
					val original = originalCells[cellIndex].form.controlPoints
					val drift = largestDifference(original, returnedCells[cellIndex].form.controlPoints)

					if (drift > RELATIVE_TOLERANCE * max(1f, original.maxOf { component -> abs(component) })) {
						violations += "${file.name}: ${warp.id.raw} drifted $drift over 100 shift round trips"
					}
				}

				val childDrawables = model.drawables.filter { drawable -> drawable.parentDeformerId == warp.id && drawable.mesh != null }.map { drawable -> drawable.id }
				val childDeformers = model.deformers.filter { deformer -> deformer.parent == warp.id }

				if (childDrawables.isEmpty() && childDeformers.isEmpty()) {
					continue
				}

				warpsWithChildren++
				val kept = warpChildrenKeptInPlace(model, shifted, warp.id)

				// An unconverted child is either one the default pose hides (it has no rest to keep) or one the shifted
				// lattice cannot reach; only the second is a limit of the conversion.
				for (drawableId in kept.unconvertedDrawables) {
					if (restSpaces.localPosed(drawableId) == null || restSpaces.mapping(drawableId) == null) {
						unconvertedHidden++
					} else {
						unconvertedUnreachable++
						println("[Umamo][rest-lattice] ${file.name}: drawable ${drawableId.raw} under ${warp.id.raw} not reachable after a shift")
					}
				}

				for (deformerId in kept.unconvertedDeformers) {
					val hidden =
						when (model.deformers.first { deformer -> deformer.id == deformerId }) {
							is Deformer.Warp -> warpLatticeAt(model, emptyMap(), deformerId) == null
							is Deformer.Rotation -> throughRotation(model, deformerId) == null
						}

					if (hidden) {
						unconvertedHidden++
					} else {
						unconvertedUnreachable++
						println("[Umamo][rest-lattice] ${file.name}: deformer ${deformerId.raw} under ${warp.id.raw} not reachable after a shift")
					}
				}

				val keptSpaces = DrawableSpaceResolver(kept.model, emptyMap())

				for (drawableId in childDrawables.filter { childId -> childId !in kept.unconvertedDrawables }) {
					convertedChildren++
					val before = restSpaces.mapping(drawableId)?.localToWorld(restSpaces.localPosed(drawableId) ?: continue) ?: continue
					val after = keptSpaces.mapping(drawableId)?.localToWorld(keptSpaces.localPosed(drawableId) ?: FloatArray(0)) ?: FloatArray(0)

					if (after.size != before.size || largestDifference(before, after) > LANDING_TOLERANCE_PX) {
						violations += "${file.name}: drawable ${drawableId.raw} under ${warp.id.raw} moved at rest"
					}
				}

				for (deformer in childDeformers.filter { child -> child.id !in kept.unconvertedDeformers }) {
					convertedChildren++
					val moved =
						when (deformer) {
							is Deformer.Warp -> {
								val before = warpLatticeAt(model, emptyMap(), deformer.id)?.world
								val after = warpLatticeAt(kept.model, emptyMap(), deformer.id)?.world
								before == null || after == null || largestDifference(before, after) > LANDING_TOLERANCE_PX
							}

							is Deformer.Rotation -> {
								val before = throughRotation(model, deformer.id)
								val after = throughRotation(kept.model, deformer.id)
								before == null || after == null || largestDifference(before, after) > LANDING_TOLERANCE_PX
							}
						}

					if (moved) {
						violations += "${file.name}: deformer ${deformer.id.raw} under ${warp.id.raw} moved at rest"
					}
				}
			}

			println("[Umamo][rest-lattice] ${file.name}: ${model.deformers.count { deformer -> deformer is Deformer.Warp }} warps, $modelHidden hidden at the defaults, $modelBlend with a blend shape at the defaults")
		}

		println(
			"[Umamo][rest-lattice] ${files.size} models, $warpCount warps: $hiddenAtDefaults hidden at the defaults, $blendAtDefaults with a blend shape at the defaults; " +
				"$sweptLattices swept lattices; $warpsWithChildren warps with children, $convertedChildren children kept in place, $unconvertedHidden left hidden at the defaults, $unconvertedUnreachable unreachable",
		)
		assertTrue(warpCount > 0, "the corpus holds warps")
		assertTrue(violations.isEmpty(), "${violations.size} violations, the first: ${violations.take(10).joinToString("\n")}")
	}
}