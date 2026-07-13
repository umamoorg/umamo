package org.umamo.render.eval

import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.MeshForm
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.RotationForm
import org.umamo.runtime.model.WarpForm
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies [DrawableSpaceMapping]: the forward local->world projection matches the evaluator's world
 * positions exactly (same chain, same Y negation), and the inverse recovers local coordinates - in
 * closed form for rotation parents, by Newton iteration for warp parents - including for a moved
 * world target (the Edit-gizmo drag case). Degenerate transforms keep the seed instead of exploding.
 * The single-step [DrawableSpaceMapping.worldToLocalLinearized] used by the transform path is exact for
 * affine parents and, unlike the Newton inverse, follows a large warp-child drag without pinning or
 * boiling.
 */
class DrawableSpaceMappingTest {
	private val tol = 1e-3f
	private val paramA = ParameterId("A")
	private val drawableId = DrawableId("M")

	private fun axis() = listOf(KeyformAxis(paramA, floatArrayOf(0f)))

	private fun zeroMeshGrid(coordCount: Int) = KeyformGrid(axis(), listOf(KeyformCell(intArrayOf(0), MeshForm(FloatArray(coordCount)))))

	private fun drawable(parent: DeformerId?, positions: FloatArray) =
		Drawable(drawableId, "M", parent, BlendMode.Normal, emptyList(), DrawableMesh(positions, FloatArray(0), IntArray(0)), zeroMeshGrid(positions.size))

	private fun model(deformers: List<Deformer>, drawable: Drawable) =
		PuppetModel(listOf(Parameter(paramA, "A", -1f, 1f, 0f)), emptyList(), deformers, listOf(drawable), emptyList(), null)

	/** 2x4 world rectangle at (10,20): the same 1x1 quad lattice the evaluator's warp routing test uses. */
	private fun warpDeformer(): Deformer.Warp {
		val controlPoints = floatArrayOf(10f, 20f, 12f, 20f, 10f, 24f, 12f, 24f)
		return Deformer.Warp(DeformerId("W"), "W", null, null, 1, 1, true, KeyformGrid(axis(), listOf(KeyformCell(intArrayOf(0), WarpForm(controlPoints)))))
	}

	/** Rotation about (5,5): 90 degrees, scale 2, no flips. */
	private fun rotationDeformer(scale: Float = 2f) =
		Deformer.Rotation(
			DeformerId("R"),
			"R",
			null,
			null,
			0f,
			KeyformGrid(axis(), listOf(KeyformCell(intArrayOf(0), RotationForm(5f, 5f, 90f, scale, flipX = false, flipY = false)))),
		)

	/** A direct (deformer-less) drawable maps by pure Y negation, and the inverse recovers it exactly. */
	@Test
	fun directDrawableIsPureYNegate() {
		val local = floatArrayOf(10f, 5f, 20f, 7f)
		val mapping = assertNotNull(drawableSpaceMapping(model(emptyList(), drawable(null, local)), emptyMap(), drawableId))
		val world = mapping.localToWorld(local)
		assertEquals(listOf(10f, -5f, 20f, -7f), world.toList())
		val recovered = mapping.worldToLocal(world, FloatArray(local.size), setOf(0, 1))
		assertEquals(local.toList(), recovered.toList())
	}

	/** A warp child's forward projection matches the evaluator, and Newton recovers the seed UV. */
	@Test
	fun warpChildMatchesEvaluatorAndRoundTrips() {
		val local = floatArrayOf(0.5f, 0.5f)
		val puppet = model(listOf(warpDeformer()), drawable(DeformerId("W"), local))
		val mapping = assertNotNull(drawableSpaceMapping(puppet, emptyMap(), drawableId))
		val world = mapping.localToWorld(local)
		val evaluatorWorld = CpuDeformationEvaluator().evaluate(puppet, emptyMap()).worldPositions.getValue(drawableId)
		assertEquals(evaluatorWorld[0], world[0], tol, "forward x matches the evaluator")
		assertEquals(evaluatorWorld[1], world[1], tol, "forward y matches the evaluator")
		val recovered = mapping.worldToLocal(world, local, setOf(0))
		assertEquals(0.5f, recovered[0], 1e-4f, "u round-trips")
		assertEquals(0.5f, recovered[1], 1e-4f, "v round-trips")
	}

	/** The drag case: inverting a MOVED world target yields a UV whose forward projection hits it. */
	@Test
	fun warpInverseSolvesAMovedTarget() {
		val local = floatArrayOf(0.5f, 0.5f)
		val puppet = model(listOf(warpDeformer()), drawable(DeformerId("W"), local))
		val mapping = assertNotNull(drawableSpaceMapping(puppet, emptyMap(), drawableId))
		val movedWorld = mapping.localToWorld(local)
		movedWorld[0] += 0.5f
		movedWorld[1] -= 0.9f
		val solvedLocal = mapping.worldToLocal(movedWorld, local, setOf(0))
		val reprojected = mapping.localToWorld(solvedLocal)
		assertEquals(movedWorld[0], reprojected[0], tol, "reprojected x hits the moved target")
		assertEquals(movedWorld[1], reprojected[1], tol, "reprojected y hits the moved target")
	}

	/** A rotation child's forward projection matches the evaluator; the closed-form inverse recovers it. */
	@Test
	fun rotationChildMatchesEvaluatorAndRoundTrips() {
		val local = floatArrayOf(1f, 0f, 0f, 3f)
		val puppet = model(listOf(rotationDeformer()), drawable(DeformerId("R"), local))
		val mapping = assertNotNull(drawableSpaceMapping(puppet, emptyMap(), drawableId))
		val world = mapping.localToWorld(local)
		val evaluatorWorld = CpuDeformationEvaluator().evaluate(puppet, emptyMap()).worldPositions.getValue(drawableId)
		for (coordIndex in world.indices) {
			assertEquals(evaluatorWorld[coordIndex], world[coordIndex], tol, "forward coord $coordIndex matches the evaluator")
		}
		val recovered = mapping.worldToLocal(world, FloatArray(local.size), setOf(0, 1))
		for (coordIndex in local.indices) {
			assertEquals(local[coordIndex], recovered[coordIndex], tol, "inverse coord $coordIndex round-trips")
		}
	}

	/** A zero-scale rotation has no inverse: the seed is kept untouched and nothing goes NaN. */
	@Test
	fun degenerateRotationKeepsSeed() {
		val local = floatArrayOf(1f, 0f)
		val puppet = model(listOf(rotationDeformer(scale = 0f)), drawable(DeformerId("R"), local))
		val mapping = assertNotNull(drawableSpaceMapping(puppet, emptyMap(), drawableId))
		val world = mapping.localToWorld(local)
		val recovered = mapping.worldToLocal(world, local, setOf(0))
		assertEquals(local.toList(), recovered.toList(), "seed kept")
		assertFalse(recovered.any { it.isNaN() }, "no NaN")
	}

	/** drawableLocalPosed blends base + keyform deltas exactly like the renderer's local stage. */
	@Test
	fun drawableLocalPosedBlendsBetweenKeys() {
		val blendGrid =
			KeyformGrid(
				axes = listOf(KeyformAxis(paramA, floatArrayOf(0f, 10f))),
				cells =
					listOf(
						KeyformCell(intArrayOf(0), MeshForm(floatArrayOf(0f, 0f))),
						KeyformCell(intArrayOf(1), MeshForm(floatArrayOf(4f, 0f))),
					),
			)
		val blendDrawable =
			Drawable(drawableId, "M", null, BlendMode.Normal, emptyList(), DrawableMesh(floatArrayOf(1f, 2f), FloatArray(0), IntArray(0)), blendGrid)
		val puppet = PuppetModel(listOf(Parameter(paramA, "A", 0f, 10f, 0f)), emptyList(), emptyList(), listOf(blendDrawable), emptyList(), null)
		val halfway = assertNotNull(drawableLocalPosed(puppet, mapOf(paramA to 5f), drawableId))
		assertEquals(3f, halfway[0], tol, "base 1 + half of delta 4")
		assertEquals(2f, halfway[1], tol, "y unchanged")
		assertNull(drawableLocalPosed(puppet, emptyMap(), DrawableId("absent")), "missing drawable")
	}

	/** No such drawable, or a parent whose world cannot be built, cannot be mapped. */
	@Test
	fun unmappableCasesReturnNull() {
		val puppet = model(emptyList(), drawable(DeformerId("missing"), floatArrayOf(0f, 0f)))
		assertNull(drawableSpaceMapping(puppet, emptyMap(), DrawableId("absent")), "missing drawable")
		assertNull(drawableSpaceMapping(puppet, emptyMap(), drawableId), "unbuildable parent world")
	}

	/**
	 * A warp-child grab far past the lattice follows the cursor with the linearized inverse, where the
	 * damped iterative [DrawableSpaceMapping.worldToLocal] pins short of the target: the 2x4 cage spans 2
	 * world-x per lattice UV, so its damped ~10-UV Newton reach stalls near world-x 31, and a +200 grab
	 * targets far beyond that reach.
	 */
	@Test
	fun warpLargeGrabFollowsInsteadOfPinning() {
		val local = floatArrayOf(0.5f, 0.5f)
		val puppet = model(listOf(warpDeformer()), drawable(DeformerId("W"), local))
		val mapping = assertNotNull(drawableSpaceMapping(puppet, emptyMap(), drawableId))
		val worldRest = mapping.localToWorld(local)
		val worldTarget = worldRest.copyOf().also { it[0] += 200f }

		// The linearized inverse follows the 200-unit grab to within a world unit (the residual is
		// finite-difference Jacobian error, not the Newton reach cliff). A true inverse would be exact, but
		// sub-unit accuracy on a 200-unit drag is visually indistinguishable and never pins.
		val solvedLinearized = mapping.worldToLocalLinearized(worldTarget, local, worldRest, setOf(0))
		val reprojectedLinearized = mapping.localToWorld(solvedLinearized)
		val linearizedShortfall = abs(reprojectedLinearized[0] - worldTarget[0])
		assertTrue(linearizedShortfall < 1f, "linearized inverse follows the cursor (shortfall=$linearizedShortfall)")
		assertEquals(worldTarget[1], reprojectedLinearized[1], tol, "no drift on the unmoved axis")

		// Regression guard: the damped iterative inverse pins well short of the target.
		val solvedNewton = mapping.worldToLocal(worldTarget, local, setOf(0))
		val reprojectedNewton = mapping.localToWorld(solvedNewton)
		assertTrue(abs(reprojectedNewton[0] - worldTarget[0]) > 100f, "the Newton inverse pins at its reach limit")
	}

	/**
	 * A swept warp-child grab follows the cursor without pinning or boiling. The reach-uncapped per-vertex
	 * inverse lands the vertex on the moving target every frame (it reprojects onto it - never pins short),
	 * and the solved local coordinate advances in bounded steps (no jitter / scatter), even as the sweep
	 * crosses the piecewise extrapolation band where the reach-capped inverse was noisy.
	 */
	@Test
	fun warpSweptGrabIsContinuousNoBoil() {
		val controlPoints = floatArrayOf(0f, 0f, 10f, 0f, 0f, 10f, 14f, 12f)
		val warp = Deformer.Warp(DeformerId("W"), "W", null, null, 1, 1, true, KeyformGrid(axis(), listOf(KeyformCell(intArrayOf(0), WarpForm(controlPoints)))))
		val local = floatArrayOf(0.5f, 0.5f)
		val mapping = assertNotNull(drawableSpaceMapping(model(listOf(warp), drawable(DeformerId("W"), local)), emptyMap(), drawableId))
		val worldRest = mapping.localToWorld(local)

		var previousLocalU = Float.NaN
		for (frame in 1..200) {
			val worldTarget =
				worldRest.copyOf().also {
					it[0] += frame * 3f
					it[1] -= frame * 2f
				}
			val solved = mapping.worldToLocalLinearized(worldTarget, local, worldRest, setOf(0))
			val reprojected = mapping.localToWorld(solved)
			// Follows: the reach-uncapped inverse lands the vertex on the moving target (never pins).
			assertEquals(worldTarget[0], reprojected[0], 1f, "swept grab follows the target x (frame $frame)")
			assertEquals(worldTarget[1], reprojected[1], 1f, "swept grab follows the target y (frame $frame)")
			if (!previousLocalU.isNaN()) {
				// No boil: the solved coordinate advances in a bounded step, never jittering / scattering.
				assertTrue(abs(solved[0] - previousLocalU) < 1f, "solved u advances smoothly, no boil (frame $frame)")
			}
			previousLocalU = solved[0]
		}
	}

	/** The linearized inverse is exact for affine parents (direct and rotation) and agrees with the closed form. */
	@Test
	fun linearizedIsExactForAffineParents() {
		val directLocal = floatArrayOf(10f, 5f, 20f, 7f)
		val directMapping = assertNotNull(drawableSpaceMapping(model(emptyList(), drawable(null, directLocal)), emptyMap(), drawableId))
		val directRest = directMapping.localToWorld(directLocal)
		val directTarget =
			directRest.copyOf().also {
				it[0] += 3f
				it[1] += 4f
				it[2] -= 5f
				it[3] += 6f
			}
		val directSolved = directMapping.worldToLocalLinearized(directTarget, directLocal, directRest, setOf(0, 1))
		val directReprojected = directMapping.localToWorld(directSolved)
		for (coordIndex in directTarget.indices) {
			assertEquals(directTarget[coordIndex], directReprojected[coordIndex], tol, "direct reprojects onto target")
		}
		val directClosed = directMapping.worldToLocal(directTarget, directLocal, setOf(0, 1))
		for (coordIndex in directSolved.indices) {
			assertEquals(directClosed[coordIndex], directSolved[coordIndex], tol, "direct agrees with the closed form")
		}

		val rotationLocal = floatArrayOf(1f, 0f, 0f, 3f)
		val rotationMapping = assertNotNull(drawableSpaceMapping(model(listOf(rotationDeformer()), drawable(DeformerId("R"), rotationLocal)), emptyMap(), drawableId))
		val rotationRest = rotationMapping.localToWorld(rotationLocal)
		val rotationTarget =
			rotationRest.copyOf().also {
				it[0] += 40f
				it[1] -= 25f
			}
		val rotationSolved = rotationMapping.worldToLocalLinearized(rotationTarget, rotationLocal, rotationRest, setOf(0))
		val rotationReprojected = rotationMapping.localToWorld(rotationSolved)
		assertEquals(rotationTarget[0], rotationReprojected[0], tol, "rotation reprojects onto target")
		assertEquals(rotationTarget[1], rotationReprojected[1], tol, "rotation reprojects onto target")
		assertEquals(rotationLocal[2], rotationSolved[2], tol, "untouched vertex kept")
		assertEquals(rotationLocal[3], rotationSolved[3], tol, "untouched vertex kept")
	}

	/**
	 * A warp-child Grab is world-rigid: the per-vertex exact inverse lands every vertex on its own world
	 * target, so the mesh translates as a rigid body in world space with no squish at all - the property
	 * the shared-Jacobian linearizations cannot hold on a curved cage.
	 */
	@Test
	fun warpGrabIsWorldRigid() {
		// A non-parallelogram cage so the warp Jacobian genuinely varies across the lattice.
		val controlPoints = floatArrayOf(0f, 0f, 10f, 0f, 0f, 10f, 14f, 12f)
		val warp = Deformer.Warp(DeformerId("W"), "W", null, null, 1, 1, true, KeyformGrid(axis(), listOf(KeyformCell(intArrayOf(0), WarpForm(controlPoints)))))
		val local = floatArrayOf(0.3f, 0.3f, 0.7f, 0.6f)
		val mapping = assertNotNull(drawableSpaceMapping(model(listOf(warp), drawable(DeformerId("W"), local)), emptyMap(), drawableId))
		val worldRest = mapping.localToWorld(local)
		// One shared world delta on both vertices (a Grab).
		val worldTarget =
			worldRest.copyOf().also {
				it[0] += 8f
				it[1] -= 5f
				it[2] += 8f
				it[3] -= 5f
			}

		val solved = mapping.worldToLocalLinearized(worldTarget, local, worldRest, setOf(0, 1))
		val reprojected = mapping.localToWorld(solved)
		for (coordIndex in worldTarget.indices) {
			assertEquals(worldTarget[coordIndex], reprojected[coordIndex], tol, "vertex reprojects exactly onto its world target (coord $coordIndex)")
		}
	}

	/**
	 * On a strongly curved cage - the real-model failure (three nested, upward-bowed warps) in miniature -
	 * a large multi-vertex grab lands the selection's centroid on the cursor. The world-rigid inverse lands
	 * every vertex on target, so the centroid trivially follows too (the shared-Jacobian inverses miss here).
	 */
	@Test
	fun warpCurvedCageGrabLandsCentroidOnTarget() {
		// A 2x1 cage bowed upward in the middle column, so the local->world scale varies sharply across it.
		val controlPoints = floatArrayOf(0f, 0f, 10f, -4f, 20f, 0f, 0f, 10f, 10f, 6f, 20f, 10f)
		val warp = Deformer.Warp(DeformerId("W"), "W", null, null, 1, 2, true, KeyformGrid(axis(), listOf(KeyformCell(intArrayOf(0), WarpForm(controlPoints)))))
		val local = floatArrayOf(0.2f, 0.4f, 0.5f, 0.3f, 0.8f, 0.4f, 0.5f, 0.7f)
		val indices = setOf(0, 1, 2, 3)
		val mapping = assertNotNull(drawableSpaceMapping(model(listOf(warp), drawable(DeformerId("W"), local)), emptyMap(), drawableId))
		val worldRest = mapping.localToWorld(local)
		// A large grab: shift every vertex by the same world delta, far past the cage.
		val worldTarget = worldRest.copyOf()
		for (vertexIndex in 0 until 4) {
			worldTarget[vertexIndex * 2] += 60f
			worldTarget[vertexIndex * 2 + 1] += 40f
		}
		val solved = mapping.worldToLocalLinearized(worldTarget, local, worldRest, indices)

		// Reproject the solved centroid and compare to the target centroid: the gross offset is gone.
		var centroidU = 0f
		var centroidV = 0f
		for (vertexIndex in 0 until 4) {
			centroidU += solved[vertexIndex * 2]
			centroidV += solved[vertexIndex * 2 + 1]
		}
		val reprojectedCentroid = mapping.localToWorld(floatArrayOf(centroidU / 4f, centroidV / 4f))
		val targetCentroidX = (worldTarget[0] + worldTarget[2] + worldTarget[4] + worldTarget[6]) / 4f
		val targetCentroidY = (worldTarget[1] + worldTarget[3] + worldTarget[5] + worldTarget[7]) / 4f
		assertEquals(targetCentroidX, reprojectedCentroid[0], 1f, "grabbed centroid lands on the cursor x")
		assertEquals(targetCentroidY, reprojectedCentroid[1], 1f, "grabbed centroid lands on the cursor y")
	}

	/** A zero-scale (degenerate) rotation parent keeps the seed in the linearized inverse; nothing goes NaN. */
	@Test
	fun linearizedDegenerateRotationKeepsSeed() {
		val local = floatArrayOf(1f, 0f)
		val puppet = model(listOf(rotationDeformer(scale = 0f)), drawable(DeformerId("R"), local))
		val mapping = assertNotNull(drawableSpaceMapping(puppet, emptyMap(), drawableId))
		val worldRest = mapping.localToWorld(local)
		val worldTarget = worldRest.copyOf().also { it[0] += 9f }
		val solved = mapping.worldToLocalLinearized(worldTarget, local, worldRest, setOf(0))
		assertEquals(local.toList(), solved.toList(), "seed kept")
		assertFalse(solved.any { it.isNaN() }, "no NaN")
	}
}
