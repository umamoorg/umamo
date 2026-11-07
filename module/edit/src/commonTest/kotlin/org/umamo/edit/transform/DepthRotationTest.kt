package org.umamo.edit.transform

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies the depth rotation's math: Flat's closed form (a squash by the cosine and a slide of the pivot's depth times
 * the sine), the right-handed signs, Rounded's sphere and where it meets Flat, the weighted halo, and the in-plane Y
 * turn staying exactly the ordinary rotation.
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

	/** At depth 0 the two surfaces agree: every point sits in the pivot's plane and only squashes. */
	@Test
	fun theSurfacesAgreeAtDepthZero() {
		val positions = floatArrayOf(12f, 3f, -8f, 40f, 0f, 0f)
		val flat = MeshTransforms.rotateVerticesInDepth(positions, setOf(0, 1, 2), RotationAxis.Z, 0.7f, 0f, DepthSurface.Flat, 0f, 0f)
		val rounded = MeshTransforms.rotateVerticesInDepth(positions, setOf(0, 1, 2), RotationAxis.Z, 0.7f, 0f, DepthSurface.Rounded, 0f, 0f)

		for (componentIndex in flat.indices) {
			assertEquals(flat[componentIndex], rounded[componentIndex], 1e-5f)
		}

		assertEquals(12f * cos(0.7f), flat[0], 1e-4f)
	}

	/** Over the pivot itself Rounded sits at the full depth in front, exactly where Flat puts every point. */
	@Test
	fun roundedMeetsFlatOverThePivot() {
		val flat = MeshTransforms.rotateVerticesInDepth(floatArrayOf(5f, 5f), setOf(0), RotationAxis.Z, 0.4f, 60f, DepthSurface.Flat, 5f, 5f)
		val rounded = MeshTransforms.rotateVerticesInDepth(floatArrayOf(5f, 5f), setOf(0), RotationAxis.Z, 0.4f, 60f, DepthSurface.Rounded, 5f, 5f)

		assertEquals(flat[0], rounded[0], 1e-4f)
	}

	/** Rounded lays the points over the sphere, so the middle travels further than the outline. */
	@Test
	fun roundedMovesTheMiddleFurthest() {
		val depth = 100f
		val positions = floatArrayOf(0f, 0f, 60f, 0f, -60f, 0f)
		val turned = MeshTransforms.rotateVerticesInDepth(positions, setOf(0, 1, 2), RotationAxis.Z, sixthTurn, depth, DepthSurface.Rounded, 0f, 0f)
		val middleTravel = turned[0] - positions[0]
		val sideDepth = sqrt(depth * depth - 60f * 60f)

		assertEquals(60f * cos(sixthTurn) + sideDepth * sin(sixthTurn), turned[2], 1e-3f, "a side point sits on the sphere")
		assertTrue(middleTravel > turned[2] - positions[2], "the middle travels further than the near side")
		assertTrue(middleTravel > turned[4] - positions[4], "and further than the far side")
	}

	/** A point past the sphere's rim sits in the pivot's plane, and the rim itself is continuous. */
	@Test
	fun theRimIsContinuous() {
		val depth = 50f
		val atRim = MeshTransforms.rotateVerticesInDepth(floatArrayOf(50f, 0f), setOf(0), RotationAxis.Z, 0.5f, depth, DepthSurface.Rounded, 0f, 0f)
		val justInside = MeshTransforms.rotateVerticesInDepth(floatArrayOf(49.999f, 0f), setOf(0), RotationAxis.Z, 0.5f, depth, DepthSurface.Rounded, 0f, 0f)
		val beyond = MeshTransforms.rotateVerticesInDepth(floatArrayOf(80f, 0f), setOf(0), RotationAxis.Z, 0.5f, depth, DepthSurface.Rounded, 0f, 0f)

		assertEquals(atRim[0], justInside[0], 0.5f)
		assertEquals(80f * cos(0.5f), beyond[0], 1e-3f, "past the rim the point only squashes")
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
		val turned = MeshTransforms.rotateVerticesInDepth(positions, setOf(0, 1), RotationAxis.Y, 0.9f, 75f, DepthSurface.Rounded, 1f, 1f)

		assertEquals(MeshTransforms.rotateVertices(positions, setOf(0, 1), 0.9f, 1f, 1f).toList(), turned.toList())
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
		assertEquals(DepthSurface.Flat, depthSurfaceOf("bumpy"))
	}
}