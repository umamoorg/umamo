package org.umamo.ui.transform

import org.umamo.edit.DepthSurface
import org.umamo.edit.MeshOperatorKind
import org.umamo.edit.transform.RotationAxis
import org.umamo.edit.transform.TransformGestureParameters
import org.umamo.edit.transform.TransformPivotGroup
import org.umamo.render.eval.warpLatticeAt
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.ElementOwner
import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.LatticeDepth
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.WarpLatticeForm
import org.umamo.runtime.model.valuesFor
import org.umamo.ui.assertArrayNear
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/**
 * Pins how the 2D drive carries a lattice's depth: a posed Grab lands the depth the lattice has at the pose (the
 * grid's blend, or the pending form's), a posed depth rotation turns it and lands the turned depth, a Vertex Slide moves
 * the slid point's depth along the edge, and at rest the reshape keeps every keyform's depth or shifts them all alike.
 */
class LatticeDepthDriveTest {
	private val angle = ParameterId("ParamAngleX")
	private val warpId = DeformerId("warp")
	private val everyPoint = setOf(0, 1, 2, 3)

	/** The depths at angle -1 and at angle 1, so angle 0 blends them half and half. */
	private val lowDepth = floatArrayOf(0f, 4f, 8f, 12f)
	private val highDepth = floatArrayOf(10f, 20f, 30f, 40f)
	private val blendedDepth = floatArrayOf(5f, 12f, 19f, 26f)

	/** A 1x1 lattice over a 10-unit square at the root. */
	private val square = floatArrayOf(0f, 0f, 10f, 0f, 0f, 10f, 10f, 10f)

	/**
	 * A root 1x1 warp keyed on the angle at -1 and 1, deep at both keys.
	 *
	 * @return PuppetModel The rig.
	 */
	private fun model(): PuppetModel {
		val warp =
			Deformer.Warp(
				id = warpId,
				name = "warp",
				parent = null,
				partId = null,
				rows = 1,
				columns = 1,
				isQuadTransform = true,
				geometryGrid =
					KeyformGrid(
						listOf(KeyformAxis(angle, floatArrayOf(-1f, 1f))),
						listOf(
							KeyformCell(intArrayOf(0), WarpLatticeForm(square, LatticeDepth.PerPoint(lowDepth))),
							KeyformCell(intArrayOf(1), WarpLatticeForm(square.copyOf(), LatticeDepth.PerPoint(highDepth))),
						),
					),
			)

		return PuppetModel(
			parameters = listOf(Parameter(angle, "X", -1f, 1f, 0f)),
			parts = emptyList(),
			deformers = listOf(warp),
			drawables = emptyList(),
			rootChildren = emptyList(),
			rootPartId = null,
		)
	}

	/**
	 * The drive of the whole lattice captured at [stage] under [operator].
	 *
	 * @param PuppetModel model The rig.
	 * @param ElementEditStage stage Where the lattice is captured.
	 * @param MeshOperatorKind operator The operator.
	 * @param TransformGestureParameters parameters The gesture.
	 * @param SlideMove? slide The slide, for a Vertex Slide.
	 * @return MeshDriveResult The result.
	 */
	private fun drive(
		model: PuppetModel,
		stage: ElementEditStage,
		operator: MeshOperatorKind,
		parameters: TransformGestureParameters,
		slide: SlideMove? = null,
	): MeshDriveResult {
		val geometry = assertNotNull(captureLatticeWorld(model, warpId, stage))
		val job = MeshDriveJob(ElementOwner.Deformer(warpId), geometry.world.copyOf(), listOf(TransformPivotGroup(everyPoint, 5f, 5f)), emptyMap(), everyPoint, geometry)

		return computeMeshDrive(MeshDriveRequest(operator, parameters, listOf(job), slide, model, stage))
	}

	/**
	 * The depths warp [warpId]'s grid holds at each key, in key order.
	 *
	 * @param PuppetModel model The rig.
	 * @return List<FloatArray> One depth array per key.
	 */
	private fun keyDepths(model: PuppetModel): List<FloatArray> =
		assertNotNull((model.deformers.single() as Deformer.Warp).geometryGrid).cells.map { cell -> cell.form.depth.valuesFor(4) }

	/** A posed Grab lands the lattice's 2D move with the depth it has at the pose. */
	@Test
	fun aPosedGrabKeepsTheDepthAtThePose() {
		val model = model()
		val stage = ElementEditStage.Posed(mapOf(angle to 0f), emptyMap())

		val before = assertNotNull(warpLatticeAt(model, stage.pose, warpId)).world

		val result = drive(model, stage, MeshOperatorKind.Grab, TransformGestureParameters(3f, -2f, 1f, 1f, 0f))
		val form = assertNotNull(result.latticeForms[warpId])

		assertContentEquals(blendedDepth, assertIs<LatticeDepth.PerPoint>(form.depth).values)
		assertArrayNear(FloatArray(8) { componentIndex -> before[componentIndex] + if (componentIndex % 2 == 0) 3f else -2f }, assertNotNull(warpLatticeAt(model, stage.pose, warpId, result.latticeForms)).world, 1e-3f, "the points moved")
	}

	/** A second Grab over a pending deep lattice keeps the pending depth, not the grid's. */
	@Test
	fun aGrabOverAPendingLatticeKeepsItsDepth() {
		val model = model()
		val pending = WarpLatticeForm(square, LatticeDepth.PerPoint(floatArrayOf(-7f, 0f, 7f, 14f)))
		val stage = ElementEditStage.Posed(mapOf(angle to 0f), mapOf(warpId to pending))

		val result = drive(model, stage, MeshOperatorKind.Grab, TransformGestureParameters(1f, 1f, 1f, 1f, 0f))

		assertContentEquals(floatArrayOf(-7f, 0f, 7f, 14f), assertIs<LatticeDepth.PerPoint>(assertNotNull(result.latticeForms[warpId]).depth).values)
	}

	/**
	 * A posed turn about Z over Unchanged turns each point at the depth it has at the pose and lands the turned depth:
	 * x' = pivot + x cos - d sin, d' = pivot depth + x sin + d cos, offsets from the pivot.
	 */
	@Test
	fun aPosedDepthRotationTurnsAndLandsTheDepth() {
		val model = model()
		val stage = ElementEditStage.Posed(mapOf(angle to 0f), emptyMap())
		val before = assertNotNull(warpLatticeAt(model, stage.pose, warpId)).world
		val radians = 0.4f
		val pivotDepth = 10f

		val result = drive(model, stage, MeshOperatorKind.Rotate, TransformGestureParameters(0f, 0f, 1f, 1f, radians, RotationAxis.Z, pivotDepth, DepthSurface.Unchanged))
		val form = assertNotNull(result.latticeForms[warpId])
		val after = assertNotNull(warpLatticeAt(model, stage.pose, warpId, result.latticeForms)).world
		val landedDepth = assertIs<LatticeDepth.PerPoint>(form.depth).values

		for (pointIndex in 0 until 4) {
			val offsetX = before[pointIndex * 2] - 5f
			val offsetDepth = blendedDepth[pointIndex] - pivotDepth
			assertEquals(5f + offsetX * cos(radians) - offsetDepth * sin(radians), after[pointIndex * 2], 1e-3f, "point $pointIndex")
			assertEquals(pivotDepth + offsetX * sin(radians) + offsetDepth * cos(radians), landedDepth[pointIndex], 1e-3f, "point $pointIndex's depth")
		}
	}

	/** A Vertex Slide moves the slid point's depth toward its neighbor's by the factor, and no other point's. */
	@Test
	fun aVertexSlideSlidesTheDepthAlongTheEdge() {
		val model = model()
		val stage = ElementEditStage.Posed(mapOf(angle to 0f), emptyMap())
		val slide = SlideMove(ElementOwner.Deformer(warpId), 0, SlideLanding(neighborIndex = 1, factor = 0.25f))

		val result = drive(model, stage, MeshOperatorKind.VertexSlide, TransformGestureParameters.IDENTITY, slide)

		assertContentEquals(floatArrayOf(6.75f, 12f, 19f, 26f), assertIs<LatticeDepth.PerPoint>(assertNotNull(result.latticeForms[warpId]).depth).values)
	}

	/** At rest a Grab reshapes the cage and leaves every keyform's depth as it was. */
	@Test
	fun aRestGrabKeepsEveryKeyformsDepth() {
		val model = model()

		val result = drive(model, ElementEditStage.Rest, MeshOperatorKind.Grab, TransformGestureParameters(3f, -2f, 1f, 1f, 0f))
		val landing = assertIs<LatticeLanding.Reshape>(result.latticeLanding(ElementEditStage.Rest))
		val depths = keyDepths(landing.reshape.model)

		assertContentEquals(lowDepth, depths[0])
		assertContentEquals(highDepth, depths[1])
	}

	/** At rest a Vertex Slide moves the point's depth in every keyform alike, so each keyed motion in depth stays. */
	@Test
	fun aRestSlideShiftsEveryKeyformsDepthAlike() {
		val model = model()
		val slide = SlideMove(ElementOwner.Deformer(warpId), 0, SlideLanding(neighborIndex = 1, factor = 0.5f))

		val result = drive(model, ElementEditStage.Rest, MeshOperatorKind.VertexSlide, TransformGestureParameters.IDENTITY, slide)
		val landing = assertIs<LatticeLanding.Reshape>(result.latticeLanding(ElementEditStage.Rest))
		val depths = keyDepths(landing.reshape.model)

		// At rest the point sits at 5 and its neighbor at 12, so the slide lands it 3.5 deeper in every keyform.
		assertContentEquals(floatArrayOf(3.5f, 4f, 8f, 12f), depths[0])
		assertContentEquals(floatArrayOf(13.5f, 20f, 30f, 40f), depths[1])
	}
}