package org.umamo.render.puppet

import org.umamo.render.FrameOverlays
import org.umamo.render.eval.preparePose
import org.umamo.render.eval.warpLatticeAt
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.ElementOwner
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins the lattice layer's derive from a pose: its points are exactly where the bake puts each warp, in world
 * space, they follow the pose, a warp the pose hides and a deformer that is not a warp have none, and an unmoved
 * lattice or an unchanged layer keeps its instances, which is what spares the GPU its uploads.
 */
class LatticeLayerTest {
	private val sizes = MeshOverlaySizes(2.5f, 1f, 2.5f)

	/**
	 * The rig's lattices at [pose], from the same pose inputs the renderer keeps.
	 *
	 * @param PuppetModel model The rig.
	 * @param Map<ParameterId, Float> pose The pose.
	 * @param Map<DeformerId, LatticePoints> previous The previous pose's lattices.
	 * @return Map<DeformerId, LatticePoints> The lattices.
	 */
	private fun latticesAt(
		model: PuppetModel,
		pose: Map<ParameterId, Float>,
		previous: Map<DeformerId, LatticePoints> = emptyMap(),
	): Map<DeformerId, LatticePoints> = latticePointsOf(model.deformers, preparePose(model, pose).deformerWorlds, previous)

	@Test
	fun thePointsAreWhereTheBakeDrawsEachWarp() {
		val model = latticeRigModel()

		for (pose in listOf(emptyMap(), mapOf(LATTICE_RIG_ANGLE_X to -1f), mapOf(LATTICE_RIG_ANGLE_X to 0.6f, LATTICE_RIG_ANGLE_Y to 0.3f))) {
			val lattices = latticesAt(model, pose)

			for (warpId in listOf(LATTICE_RIG_OUTER, LATTICE_RIG_INNER, LATTICE_RIG_RANGED)) {
				val expected = assertNotNull(warpLatticeAt(model, pose, warpId), "$warpId is drawn at $pose")
				val lattice = assertNotNull(lattices[warpId], "$warpId has a lattice at $pose")
				assertContentEquals(expected.world, lattice.world, "$warpId sits where the bake draws it at $pose")
				assertEquals(expected.rows to expected.columns, lattice.layout.rows to lattice.layout.columns)
			}
		}
	}

	@Test
	fun theOuterLatticeIsInWorldSpaceWithZUp() {
		val lattice = assertNotNull(latticesAt(latticeRigModel(), mapOf(LATTICE_RIG_ANGLE_X to 1f))[LATTICE_RIG_OUTER])

		assertEquals(10f, lattice.world[0], "the first point slid by the key's 10")
		assertEquals(0f, lattice.world[1], 0f, "the top row stays on the axis")
		assertEquals(70f, lattice.world[16], "the last point's x")
		assertEquals(-60f, lattice.world[17], "a point lower in the cascade's y-down space is lower in the world")
	}

	@Test
	fun aPoseChangeMovesTheLattices() {
		val model = latticeRigModel()
		val left = assertNotNull(latticesAt(model, mapOf(LATTICE_RIG_ANGLE_X to -1f))[LATTICE_RIG_OUTER])
		val right = assertNotNull(latticesAt(model, mapOf(LATTICE_RIG_ANGLE_X to 1f))[LATTICE_RIG_OUTER])

		for (pointIndex in 0 until left.layout.pointCount) {
			assertEquals(20f, right.world[pointIndex * 2] - left.world[pointIndex * 2], "point $pointIndex slid along x")
			assertEquals(left.world[pointIndex * 2 + 1], right.world[pointIndex * 2 + 1], "and kept its height")
		}

		val innerLeft = assertNotNull(latticesAt(model, mapOf(LATTICE_RIG_ANGLE_X to -1f))[LATTICE_RIG_INNER])
		val innerRight = assertNotNull(latticesAt(model, mapOf(LATTICE_RIG_ANGLE_X to 1f))[LATTICE_RIG_INNER])
		assertEquals(20f, innerRight.world[0] - innerLeft.world[0], "the child warp rides its parent")
	}

	@Test
	fun aHiddenWarpAndARotationHaveNoLattice() {
		val model = latticeRigModel()
		val shown = latticesAt(model, mapOf(LATTICE_RIG_ANGLE_Y to 0.25f))
		val hidden = latticesAt(model, mapOf(LATTICE_RIG_ANGLE_Y to 0.75f))

		assertEquals(listOf(LATTICE_RIG_OUTER, LATTICE_RIG_INNER, LATTICE_RIG_RANGED), shown.keys.toList(), "every warp, in model order, and no rotation")
		assertFalse(LATTICE_RIG_RANGED in hidden, "a pose past the ranged warp's keys hides its lattice")
		assertTrue(LATTICE_RIG_OUTER in hidden)
	}

	@Test
	fun anUnmovedLatticeKeepsItsInstance() {
		val model = latticeRigModel()
		val before = latticesAt(model, mapOf(LATTICE_RIG_ANGLE_X to -1f))

		val again = latticesAt(model, mapOf(LATTICE_RIG_ANGLE_X to -1f), before)
		val moved = latticesAt(model, mapOf(LATTICE_RIG_ANGLE_X to 1f), before)

		assertSame(before[LATTICE_RIG_OUTER], again[LATTICE_RIG_OUTER], "the same pose keeps the lattice")
		assertNotSame(before[LATTICE_RIG_OUTER], moved[LATTICE_RIG_OUTER], "a moved lattice is a new one")
		assertSame(before[LATTICE_RIG_OUTER]?.layout, moved[LATTICE_RIG_OUTER]?.layout, "over the same layout")
		assertSame(before[LATTICE_RIG_RANGED], moved[LATTICE_RIG_RANGED], "a lattice the pose did not move keeps its instance")
	}

	@Test
	fun theLayerHoldsEachLatticeInModelOrderStyledByItsRole() {
		val lattices = latticesAt(latticeRigModel(), emptyMap())
		val roles = mapOf(LATTICE_RIG_OUTER to LatticeRole.Active, LATTICE_RIG_INNER to LatticeRole.Idle, LATTICE_RIG_RANGED to LatticeRole.Selected)

		val layer = assertNotNull(latticeLayerOf(LatticeOverlay(roles, sizes), lattices, null))
		val modelOrder = lattices.keys.filter { warpId -> warpId in roles }

		assertEquals(MeshOverlayKind.Lattice, layer.overlay.kind)
		assertEquals(modelOrder.map { warpId -> ElementOwner.Deformer(warpId) }, layer.overlay.meshes.map { mesh -> mesh.owner })
		assertEquals(modelOrder.map { warpId -> MeshStyle.Lattice(roles.getValue(warpId), pending = false) }, layer.overlay.meshes.map { mesh -> mesh.style })

		for (mesh in layer.overlay.meshes) {
			val lattice = assertNotNull(lattices[(mesh.owner as ElementOwner.Deformer).id])
			assertSame(lattice.world, layer.positionsByOwner[mesh.owner], "${mesh.owner} draws the lattice's own points")
			assertSame(lattice.layout.edgeEndpoints, mesh.edgeEndpoints, "${mesh.owner} draws the grid lines")
			assertEquals(0, assertNotNull(layer.triangleIndicesByOwner[mesh.owner]).size, "${mesh.owner} has no triangles to draw")
		}
	}

	@Test
	fun aWarpTheLayerCannotDrawIsLeftOut() {
		val lattices = latticesAt(latticeRigModel(), mapOf(LATTICE_RIG_ANGLE_Y to 0.75f))
		val roles = mapOf(LATTICE_RIG_RANGED to LatticeRole.Selected, LATTICE_RIG_ROTATION to LatticeRole.Active, DeformerId("missing") to LatticeRole.Idle)

		assertNull(latticeLayerOf(LatticeOverlay(roles, sizes), lattices, null), "a hidden warp, a rotation, and an unknown id draw nothing")

		val layer = assertNotNull(latticeLayerOf(LatticeOverlay(roles + (LATTICE_RIG_INNER to LatticeRole.Idle), sizes), lattices, null))
		assertEquals(listOf<ElementOwner>(ElementOwner.Deformer(LATTICE_RIG_INNER)), layer.overlay.meshes.map { mesh -> mesh.owner })
	}

	@Test
	fun anUnchangedLayerIsTheSameValue() {
		val model = latticeRigModel()
		val lattices = latticesAt(model, emptyMap())
		val roles = mapOf(LATTICE_RIG_OUTER to LatticeRole.Selected, LATTICE_RIG_INNER to LatticeRole.Idle)
		val first = assertNotNull(latticeLayerOf(LatticeOverlay(roles, sizes), lattices, null))

		assertSame(first, latticeLayerOf(LatticeOverlay(roles, sizes), lattices, first), "a new value naming the same roles is the same layer")
		assertSame(first, latticeLayerOf(LatticeOverlay(roles, sizes), latticesAt(model, emptyMap(), lattices), first), "and so is a pose that moved nothing")

		val promoted = assertNotNull(latticeLayerOf(LatticeOverlay(roles + (LATTICE_RIG_OUTER to LatticeRole.Active), sizes), lattices, first))
		assertNotSame(first, promoted, "a role change is a new layer")
		assertSame(
			first.overlay.meshes.first { mesh -> mesh.owner == ElementOwner.Deformer(LATTICE_RIG_INNER) },
			promoted.overlay.meshes.first { mesh -> mesh.owner == ElementOwner.Deformer(LATTICE_RIG_INNER) },
			"that keeps the mesh whose role held",
		)

		val moved = assertNotNull(latticeLayerOf(LatticeOverlay(roles, sizes), latticesAt(model, mapOf(LATTICE_RIG_ANGLE_X to 1f), lattices), first))
		assertNotSame(first, moved, "a moved lattice is a new layer")
		assertSame(first.overlay.meshes[0], moved.overlay.meshes[0], "over the same mesh values")

		assertNotSame(first, latticeLayerOf(LatticeOverlay(roles, sizes.copy(vertexDotRadiusPx = 4f)), lattices, first), "and new sizes are a new layer")
	}

	@Test
	fun aFrameDrawsTheLatticesItsOverlaysAllow() {
		val lattices = latticesAt(latticeRigModel(), emptyMap())
		val overlay = LatticeOverlay(mapOf(LATTICE_RIG_OUTER to LatticeRole.Selected, LATTICE_RIG_INNER to LatticeRole.Idle), sizes)

		assertEquals(listOf(LATTICE_RIG_OUTER, LATTICE_RIG_INNER), latticesDrawnIn(overlay, lattices, FrameOverlays()).keys.toList(), "every listed warp, in model order")
		assertEquals(listOf(LATTICE_RIG_OUTER), latticesDrawnIn(overlay, lattices, FrameOverlays(allLattices = false)).keys.toList(), "no idle one without every deformer")
		assertTrue(latticesDrawnIn(overlay, lattices, FrameOverlays(meshOverlay = false)).isEmpty(), "none without the overlays")
		assertTrue(latticesDrawnIn(null, lattices, FrameOverlays()).isEmpty(), "none without an overlay")
	}
}