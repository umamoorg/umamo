package org.umamo.edit.transform

import org.umamo.edit.DepthSurface
import org.umamo.edit.next
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies the depth rotation's math: Flat's closed form (a squash by the cosine and a slide of the pivot's depth times
 * the sine), the right-handed signs, the Cylinder along the axis and the Sphere around the pivot and where each meets Flat,
 * Unchanged turning each point at its own depth and every surface handing the turned depth back, T's surface cycle, the
 * weighted halo, and the in-plane Y turn staying exactly the ordinary rotation and keeping every depth.
 */
class DepthRotationTest {
	private val quarterTurn = (PI / 2.0).toFloat()
	private val sixthTurn = (PI / 6.0).toFloat()

	/** A Flat turn about Z squashes x about the pivot by the cosine, slides it by depth times the sine, and keeps y. */
	@Test
	fun aFlatTurnAboutZSquashesAndSlides() {
		val positions = floatArrayOf(30f, 7f)
		val turned = MeshTransforms.rotateVerticesInDepth(positions, setOf(0), RotationAxis.Z, sixthTurn, 100f, DepthSurface.Flat, 10f, 0f)

		assertEquals(10f + 20f * cos(sixthTurn) + 100f * sin(sixthTurn), turned[0], 1e-3f)
		assertEquals(7f, turned[1], 0f, "a turn about Z never moves y")
		assertEquals(listOf(30f, 7f), positions.toList(), "the input array is never mutated")
	}

	/** A Flat tilt about X squashes y about the pivot and slides it down for a pushed pivot, keeping x. */
	@Test
	fun aFlatTiltAboutXSquashesAndSlidesDown() {
		val turned = MeshTransforms.rotateVerticesInDepth(floatArrayOf(4f, 25f), setOf(0), RotationAxis.X, sixthTurn, 100f, DepthSurface.Flat, 0f, 5f)

		assertEquals(4f, turned[0], 0f, "a tilt about X never moves x")
		assertEquals(5f + 20f * cos(sixthTurn) - 100f * sin(sixthTurn), turned[1], 1e-3f)
	}

	/** Positive angles turn the front of a pushed pivot toward +x about Z and toward -y about X (right-handed). */
	@Test
	fun theSignsFollowTheRightHandRule() {
		val aboutZ = MeshTransforms.rotateVerticesInDepth(floatArrayOf(0f, 0f), setOf(0), RotationAxis.Z, 0.2f, 50f, DepthSurface.Flat, 0f, 0f)
		val aboutX = MeshTransforms.rotateVerticesInDepth(floatArrayOf(0f, 0f), setOf(0), RotationAxis.X, 0.2f, 50f, DepthSurface.Flat, 0f, 0f)

		assertTrue(aboutZ[0] > 0f, "the front swings right")
		assertTrue(aboutX[1] < 0f, "the front tilts down")
	}

	/** A pivot pulled toward the viewer slides the art the other way. */
	@Test
	fun aNegativeDepthMirrorsTheSlide() {
		val pushed = MeshTransforms.rotateVerticesInDepth(floatArrayOf(0f, 0f), setOf(0), RotationAxis.Z, 0.3f, 40f, DepthSurface.Flat, 0f, 0f)
		val pulled = MeshTransforms.rotateVerticesInDepth(floatArrayOf(0f, 0f), setOf(0), RotationAxis.Z, 0.3f, -40f, DepthSurface.Flat, 0f, 0f)

		assertEquals(-pushed[0], pulled[0], 1e-4f)
	}

	/** At depth 0 Flat, Cylinder, and Sphere agree: every point sits in the pivot's plane and only squashes. */
	@Test
	fun theSurfacesAgreeAtDepthZero() {
		val positions = floatArrayOf(12f, 3f, -8f, 40f, 0f, 0f)
		val flat = MeshTransforms.rotateVerticesInDepth(positions, setOf(0, 1, 2), RotationAxis.Z, 0.7f, 0f, DepthSurface.Flat, 0f, 0f)

		for (surface in listOf(DepthSurface.Cylinder, DepthSurface.Sphere)) {
			val curved = MeshTransforms.rotateVerticesInDepth(positions, setOf(0, 1, 2), RotationAxis.Z, 0.7f, 0f, surface, 0f, 0f)

			for (componentIndex in flat.indices) {
				assertEquals(flat[componentIndex], curved[componentIndex], 1e-5f, "$surface")
			}
		}

		assertEquals(12f * cos(0.7f), flat[0], 1e-4f)
	}

	/** Over the pivot itself both curved surfaces sit at the full depth in front, exactly where Flat puts every point. */
	@Test
	fun theCurvedSurfacesMeetFlatOverThePivot() {
		val flat = MeshTransforms.rotateVerticesInDepth(floatArrayOf(5f, 5f), setOf(0), RotationAxis.Z, 0.4f, 60f, DepthSurface.Flat, 5f, 5f)

		for (surface in listOf(DepthSurface.Cylinder, DepthSurface.Sphere)) {
			val curved = MeshTransforms.rotateVerticesInDepth(floatArrayOf(5f, 5f), setOf(0), RotationAxis.Z, 0.4f, 60f, surface, 5f, 5f)

			assertEquals(flat[0], curved[0], 1e-4f, "$surface")
		}
	}

	/** Cylinder bends around the axis: about Z every row moves alike, whatever its height. */
	@Test
	fun cylinderAboutZMovesEveryRowAlike() {
		val depth = 100f
		val positions = floatArrayOf(60f, 0f, 60f, 70f, 60f, -90f)
		val turned = MeshTransforms.rotateVerticesInDepth(positions, setOf(0, 1, 2), RotationAxis.Z, sixthTurn, depth, DepthSurface.Cylinder, 0f, 0f)
		val sideDepth = sqrt(depth * depth - 60f * 60f)

		for (pointIndex in 0 until 3) {
			assertEquals(60f * cos(sixthTurn) + sideDepth * sin(sixthTurn), turned[pointIndex * 2], 1e-3f, "row $pointIndex sits on the cylinder")
			assertEquals(positions[pointIndex * 2 + 1], turned[pointIndex * 2 + 1], 0f, "and keeps its height")
		}
	}

	/** About X the cylinder lies along X, so every column tilts alike, whatever its x. */
	@Test
	fun cylinderAboutXMovesEveryColumnAlike() {
		val depth = 100f
		val positions = floatArrayOf(0f, 60f, 80f, 60f, -95f, 60f)
		val turned = MeshTransforms.rotateVerticesInDepth(positions, setOf(0, 1, 2), RotationAxis.X, sixthTurn, depth, DepthSurface.Cylinder, 0f, 0f)
		val rowDepth = -sqrt(depth * depth - 60f * 60f)

		for (pointIndex in 0 until 3) {
			assertEquals(rowDepth * sin(sixthTurn) + 60f * cos(sixthTurn), turned[pointIndex * 2 + 1], 1e-3f, "column $pointIndex sits on the cylinder")
			assertEquals(positions[pointIndex * 2], turned[pointIndex * 2], 0f, "and keeps its x")
		}
	}

	/** Sphere lays the points over the sphere, so the middle travels further than the outline, and rows away from the pivot less. */
	@Test
	fun sphereMovesTheMiddleFurthest() {
		val depth = 100f
		val positions = floatArrayOf(0f, 0f, 60f, 0f, -60f, 0f, 0f, 70f)
		val turned = MeshTransforms.rotateVerticesInDepth(positions, setOf(0, 1, 2, 3), RotationAxis.Z, sixthTurn, depth, DepthSurface.Sphere, 0f, 0f)
		val middleTravel = turned[0] - positions[0]
		val sideDepth = sqrt(depth * depth - 60f * 60f)

		assertEquals(60f * cos(sixthTurn) + sideDepth * sin(sixthTurn), turned[2], 1e-3f, "a side point sits on the sphere")
		assertTrue(middleTravel > turned[2] - positions[2], "the middle travels further than the near side")
		assertTrue(middleTravel > turned[4] - positions[4], "and further than the far side")
		assertTrue(middleTravel > turned[6] - positions[6], "and further than a point above it, unlike the cylinder")
	}

	/** A point past either curved surface's rim sits in the pivot's plane, and the rim itself is continuous. */
	@Test
	fun theRimIsContinuous() {
		val depth = 50f

		for (surface in listOf(DepthSurface.Cylinder, DepthSurface.Sphere)) {
			val atRim = MeshTransforms.rotateVerticesInDepth(floatArrayOf(50f, 0f), setOf(0), RotationAxis.Z, 0.5f, depth, surface, 0f, 0f)
			val justInside = MeshTransforms.rotateVerticesInDepth(floatArrayOf(49.999f, 0f), setOf(0), RotationAxis.Z, 0.5f, depth, surface, 0f, 0f)
			val beyond = MeshTransforms.rotateVerticesInDepth(floatArrayOf(80f, 0f), setOf(0), RotationAxis.Z, 0.5f, depth, surface, 0f, 0f)

			assertEquals(atRim[0], justInside[0], 0.5f, "$surface")
			assertEquals(80f * cos(0.5f), beyond[0], 1e-3f, "past the $surface rim the point only squashes")
		}
	}

	/** T cycles the surfaces in declaration order and wraps. */
	@Test
	fun theSurfacesCycleInOrder() {
		assertEquals(DepthSurface.Flat, DepthSurface.Unchanged.next())
		assertEquals(DepthSurface.Cylinder, DepthSurface.Flat.next())
		assertEquals(DepthSurface.Sphere, DepthSurface.Cylinder.next())
		assertEquals(DepthSurface.Unchanged, DepthSurface.Sphere.next())
	}

	/** Unchanged turns each point at its own depth: about Z, x' = x cos - d sin and d' = d cos + x sin, about the pivot's depth. */
	@Test
	fun unchangedTurnsEachPointAtItsOwnDepth() {
		val points = PointsInDepth(floatArrayOf(30f, 7f, -10f, 2f), floatArrayOf(25f, 90f))
		val turned = MeshTransforms.rotateVerticesInDepth(points, setOf(0, 1), RotationAxis.Z, sixthTurn, 40f, DepthSurface.Unchanged, 10f, 0f)

		for ((pointIndex, offsetX) in listOf(20f, -20f).withIndex()) {
			val offsetDepth = points.depths[pointIndex] - 40f
			assertEquals(10f + offsetX * cos(sixthTurn) - offsetDepth * sin(sixthTurn), turned.positions[pointIndex * 2], 1e-3f, "point $pointIndex")
			assertEquals(40f + offsetX * sin(sixthTurn) + offsetDepth * cos(sixthTurn), turned.depths[pointIndex], 1e-3f, "point $pointIndex's depth")
		}

		assertEquals(listOf(25f, 90f), points.depths.toList(), "the input depths are never mutated")
	}

	/** About X the depth turns with z: z' = z cos + d sin and d' = d cos - z sin. */
	@Test
	fun aTiltAboutXHandsBackTheTurnedDepth() {
		val turned = MeshTransforms.rotateVerticesInDepth(PointsInDepth(floatArrayOf(4f, 25f), floatArrayOf(60f)), setOf(0), RotationAxis.X, sixthTurn, 100f, DepthSurface.Unchanged, 0f, 5f)

		assertEquals(4f, turned.positions[0], 0f, "a tilt about X never moves x")
		assertEquals(5f + 20f * cos(sixthTurn) - 40f * sin(sixthTurn), turned.positions[1], 1e-3f)
		assertEquals(100f - 40f * cos(sixthTurn) - 20f * sin(sixthTurn), turned.depths[0], 1e-3f)
	}

	/** On flat art Flat and Unchanged agree; Flat lays deep art on the plane first; and every surface writes the depth back. */
	@Test
	fun flatOnFlatArtIsUnchangedAndEverySurfaceWritesDepth() {
		val positions = floatArrayOf(12f, 3f, -8f, 40f, 0f, 0f)
		val flatArt = PointsInDepth(positions, FloatArray(3))
		val deepArt = PointsInDepth(positions, floatArrayOf(10f, 20f, 30f))
		val unchanged = MeshTransforms.rotateVerticesInDepth(flatArt, setOf(0, 1, 2), RotationAxis.Z, 0.7f, 35f, DepthSurface.Unchanged, 0f, 0f)
		val flat = MeshTransforms.rotateVerticesInDepth(flatArt, setOf(0, 1, 2), RotationAxis.Z, 0.7f, 35f, DepthSurface.Flat, 0f, 0f)

		assertEquals(flat.positions.toList(), unchanged.positions.toList())
		assertEquals(flat.depths.toList(), unchanged.depths.toList())
		assertEquals(flat.positions.toList(), MeshTransforms.rotateVerticesInDepth(deepArt, setOf(0, 1, 2), RotationAxis.Z, 0.7f, 35f, DepthSurface.Flat, 0f, 0f).positions.toList(), "Flat reads no depth")

		for (surface in DepthSurface.entries) {
			val turned = MeshTransforms.rotateVerticesInDepth(deepArt, setOf(0), RotationAxis.Z, 0.7f, 35f, surface, 0f, 0f)
			assertTrue(turned.depths[0] != deepArt.depths[0], "$surface writes the turned depth")
		}
	}

	/** A turn by an angle and back returns every point and depth; the in-plane Y turn keeps every depth as it was. */
	@Test
	fun aTurnAndItsNegationReturnAndTheYTurnKeepsDepth() {
		val points = PointsInDepth(floatArrayOf(30f, 7f, -10f, 2f), floatArrayOf(25f, 90f))
		val there = MeshTransforms.rotateVerticesInDepth(points, setOf(0, 1), RotationAxis.Z, 0.6f, 40f, DepthSurface.Unchanged, 10f, 0f)
		val back = MeshTransforms.rotateVerticesInDepth(there, setOf(0, 1), RotationAxis.Z, -0.6f, 40f, DepthSurface.Unchanged, 10f, 0f)
		val inPlane = MeshTransforms.rotateVerticesInDepth(points, setOf(0, 1), RotationAxis.Y, 0.6f, 40f, DepthSurface.Unchanged, 10f, 0f)

		for (componentIndex in points.positions.indices) {
			assertEquals(points.positions[componentIndex], back.positions[componentIndex], 1e-3f)
		}

		for (pointIndex in points.depths.indices) {
			assertEquals(points.depths[pointIndex], back.depths[pointIndex], 1e-3f)
		}

		assertEquals(points.depths.toList(), inPlane.depths.toList())
	}

	/** The weighted halo turns each depth by its weight's share too: a zero weight keeps the depth. */
	@Test
	fun theHaloTurnsTheDepthByItsWeight() {
		val points = PointsInDepth(floatArrayOf(10f, 0f, 20f, 0f), floatArrayOf(5f, 5f))
		val influences = mapOf(0 to ProportionalInfluence(0f, 0f, 0f), 1 to ProportionalInfluence(1f, 0f, 0f))
		val weighted = MeshTransforms.rotateVerticesInDepthWeighted(points, influences, RotationAxis.Z, quarterTurn, 30f, DepthSurface.Unchanged)
		val full = MeshTransforms.rotateVerticesInDepth(points, setOf(1), RotationAxis.Z, quarterTurn, 30f, DepthSurface.Unchanged, 0f, 0f)

		assertEquals(5f, weighted.depths[0], 1e-5f, "a zero weight leaves the depth")
		assertEquals(full.depths[1], weighted.depths[1], 1e-5f, "a full weight is the whole turn")
	}

	/** The weighted halo turns each vertex by its weight's share of the angle about its own pivot. */
	@Test
	fun theHaloWeightsTheAngle() {
		val positions = floatArrayOf(10f, 0f, 20f, 0f)
		val influences = mapOf(0 to ProportionalInfluence(0f, 0f, 0f), 1 to ProportionalInfluence(1f, 0f, 0f))
		val weighted = MeshTransforms.rotateVerticesInDepthWeighted(positions, influences, RotationAxis.Z, quarterTurn, 30f, DepthSurface.Flat)
		val full = MeshTransforms.rotateVerticesInDepth(positions, setOf(1), RotationAxis.Z, quarterTurn, 30f, DepthSurface.Flat, 0f, 0f)

		assertEquals(10f, weighted[0], 1e-5f, "a zero weight leaves the vertex")
		assertEquals(full[2], weighted[2], 1e-5f, "a full weight is the whole turn")
	}

	/** The Y axis is the in-plane turn, exactly the ordinary rotation, depth and surface unread. */
	@Test
	fun theYAxisIsTheOrdinaryRotation() {
		val positions = floatArrayOf(3f, 4f, -2f, 9f)
		val turned = MeshTransforms.rotateVerticesInDepth(positions, setOf(0, 1), RotationAxis.Y, 0.9f, 75f, DepthSurface.Cylinder, 1f, 1f)

		assertEquals(MeshTransforms.rotateVertices(positions, setOf(0, 1), 0.9f, 1f, 1f).toList(), turned.toList())
	}

	/** A wheel step moves one fixed step whatever the scroll's size, and lands on the step grid so 0 stays reachable. */
	@Test
	fun aWheelStepLandsOnTheNextGridStep() {
		assertEquals(PIVOT_DEPTH_STEP, pivotDepthSteppedFrom(0f, -1f), "wheel up pushes one step away")
		assertEquals(-PIVOT_DEPTH_STEP, pivotDepthSteppedFrom(0f, 1f), "wheel down pulls one step toward the viewer")
		assertEquals(pivotDepthSteppedFrom(10f, -0.1f), pivotDepthSteppedFrom(10f, -3f), "only the sign is read")
		assertEquals(10f, pivotDepthSteppedFrom(10f, 0f), "a zero scroll leaves the depth")

		// A depth typed off the grid steps onto it, so stepping down always reaches 0.
		assertEquals(15f, pivotDepthSteppedFrom(12f, -1f))
		assertEquals(10f, pivotDepthSteppedFrom(12f, 1f))
		var depth = 12f

		repeat(3) {
			depth = pivotDepthSteppedFrom(depth, 1f)
		}

		assertEquals(0f, depth, "12 steps down through 10 and 5 to 0")
		assertEquals(0f, pivotDepthSteppedFrom(-3f, -1f), "and a pulled pivot steps up to 0")
	}

	/** The stored keys read back to their values, and an unknown key to the defaults. */
	@Test
	fun theChoiceKeysRoundTrip() {
		for (axis in RotationAxis.entries) {
			assertEquals(axis, rotationAxisOf(axis.choiceKey))
		}

		for (surface in DepthSurface.entries) {
			assertEquals(surface, depthSurfaceOf(surface.choiceKey))
		}

		assertEquals(RotationAxis.Y, rotationAxisOf("w"))
		assertEquals(DepthSurface.Unchanged, depthSurfaceOf("bumpy"), "the default")
	}
}