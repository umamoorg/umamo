package org.umamo.ui.transform

import org.umamo.edit.MeshElement
import org.umamo.edit.MeshSelection
import org.umamo.edit.mesh.MeshRestPositions
import org.umamo.edit.mesh.withMeshPositions
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.MeshDeltaForm
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.RotationPivotForm
import org.umamo.runtime.model.WarpLatticeForm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The covered vertices and their median: weighted by vertex count across meshes, measured live from a preview
 * through each mesh's captured mapping exactly where a recapture of the previewed model puts them, and never
 * reading past a mesh's vertices.
 */
class CoveredVerticesTest {
	private val tolerance = 1e-3f
	private val parameterId = ParameterId("P")
	private val flat = DrawableId("flat")
	private val keyed = DrawableId("keyed")
	private val warped = DrawableId("warped")
	private val turned = DrawableId("turned")
	private val allMeshes = listOf(flat, keyed, warped, turned)

	/**
	 * The rig: a direct triangle, a direct triangle whose neutral keyform displaces it, a triangle under a warp
	 * stretching the unit square over x 100..140 and y 200..280, and a triangle under a rotation turned a quarter
	 * turn about (300, 300).
	 *
	 * @return PuppetModel The rig.
	 */
	private fun model(): PuppetModel {
		val triangle = floatArrayOf(0f, 0f, 10f, 0f, 0f, 10f)
		val neutralDeltas = floatArrayOf(5f, 7f, 5f, 7f, 5f, 7f)
		val keyedGrid =
			KeyformGrid(
				listOf(KeyformAxis(parameterId, floatArrayOf(-1f, 0f, 1f))),
				(0..2).map { keyIndex -> KeyformCell(intArrayOf(keyIndex), MeshDeltaForm(neutralDeltas.copyOf())) },
			)
		val warp =
			Deformer.Warp(
				DeformerId("W"),
				"W",
				null,
				null,
				1,
				1,
				true,
				KeyformGrid(
					listOf(KeyformAxis(parameterId, floatArrayOf(0f))),
					listOf(KeyformCell(intArrayOf(0), WarpLatticeForm(floatArrayOf(100f, 200f, 140f, 200f, 100f, 280f, 140f, 280f)))),
				),
			)
		val rotation =
			Deformer.Rotation(
				DeformerId("R"),
				"R",
				null,
				null,
				0f,
				KeyformGrid(
					listOf(KeyformAxis(parameterId, floatArrayOf(0f))),
					listOf(KeyformCell(intArrayOf(0), RotationPivotForm(300f, 300f, 90f, 1f))),
				),
			)

		return PuppetModel(
			parameters = listOf(Parameter(parameterId, "P", -1f, 1f, 0f)),
			parts = emptyList(),
			deformers = listOf(warp, rotation),
			drawables =
				listOf(
					drawable(flat, null, triangle, null),
					drawable(keyed, null, triangle, keyedGrid),
					drawable(warped, DeformerId("W"), floatArrayOf(0.25f, 0.25f, 0.75f, 0.25f, 0.25f, 0.75f), null),
					drawable(turned, DeformerId("R"), triangle, null),
				),
			rootChildren = emptyList(),
			rootPartId = null,
		)
	}

	/**
	 * One triangle drawable.
	 *
	 * @param DrawableId id The drawable's id.
	 * @param DeformerId? parent Its parent deformer, or null for a direct drawable.
	 * @param FloatArray positions Its rest positions.
	 * @param KeyformGrid? grid Its keyform grid, or null for an unkeyed drawable.
	 * @return Drawable The drawable.
	 */
	private fun drawable(id: DrawableId, parent: DeformerId?, positions: FloatArray, grid: KeyformGrid<MeshDeltaForm>?): Drawable =
		Drawable(id, id.raw, parent, BlendMode.Normal, emptyList(), DrawableMesh.withLocalEqualToCanvas(positions, positions.copyOf(), intArrayOf(0, 1, 2)), grid)

	/**
	 * A selection of the given vertices on each mesh.
	 *
	 * @param Map<DrawableId, List<Int>> verticesById The vertices to select per mesh.
	 * @return MeshSelection The selection, every mesh of the rig in the edit.
	 */
	private fun selecting(verticesById: Map<DrawableId, List<Int>>): MeshSelection =
		MeshSelection(
			drawableIds = allMeshes,
			activeDrawableId = allMeshes.first(),
			elementsByDrawable = verticesById.mapValues { (_, vertices) -> vertices.map { vertexIndex -> MeshElement.Vertex(vertexIndex) }.toSet() },
		)

	/**
	 * The covered vertices of [selection] over a fresh capture of [model] at the neutral pose.
	 *
	 * @param PuppetModel model The model.
	 * @param MeshSelection selection The selection.
	 * @return List<CoveredVertices> The covered vertices.
	 */
	private fun coveredIn(model: PuppetModel, selection: MeshSelection): List<CoveredVertices> =
		coveredVerticesOf(
			selection,
			captureDrawableWorlds(model, emptyMap(), allMeshes),
			model.drawables.associate { drawable -> drawable.id to drawable.mesh!!.indices },
		)

	/** The median is the mean over every covered vertex, so a mesh with more covered vertices weighs more. */
	@Test
	fun theMedianIsWeightedByVertexCount() {
		val median = assertNotNull(coveredMedian(coveredIn(model(), selecting(mapOf(flat to listOf(0, 1, 2), warped to listOf(0))))))
		// The flat triangle is (0, 0), (10, 0), (0, -10) in world; the warped vertex lands at (110, -220).
		assertEquals(30f, median.worldX, tolerance)
		assertEquals(-57.5f, median.worldZ, tolerance)
		assertEquals(4, median.vertexCount)
	}

	/**
	 * A previewed base, carried through each mesh's captured mapping, lands where a recapture of the previewed
	 * model puts it: under a keyform, a warp, and a rotation alike.
	 */
	@Test
	fun theLiveMedianMatchesARecaptureOfThePreview() {
		val model = model()
		val selection = selecting(mapOf(flat to listOf(0, 1), keyed to listOf(0, 1, 2), warped to listOf(1, 2), turned to listOf(0, 2)))
		val covered = coveredIn(model, selection)
		val restById =
			covered.associate { mesh ->
				val base = mesh.geometry.local.copyOf()

				for (vertexIndex in mesh.indices) {
					base[vertexIndex * 2] += if (mesh.geometry.drawableId == warped) 0.1f else 3f
					base[vertexIndex * 2 + 1] -= if (mesh.geometry.drawableId == warped) 0.05f else 2f
				}

				mesh.geometry.drawableId to MeshRestPositions(base.copyOf(), base)
			}
		val live = assertNotNull(coveredMedian(covered, restById))
		val recaptured = assertNotNull(coveredMedian(coveredIn(model.withMeshPositions(restById), selection)))
		assertEquals(recaptured.worldX, live.worldX, tolerance)
		assertEquals(recaptured.worldZ, live.worldZ, tolerance)
		val committed = assertNotNull(coveredMedian(covered))
		assertTrue(committed.worldX != live.worldX, "precondition: the preview moved the median")
	}

	/** With nothing previewed, the live median is the captured one. */
	@Test
	fun aMeshThePreviewDoesNotMoveCountsWhereItWas() {
		val covered = coveredIn(model(), selecting(mapOf(flat to listOf(0, 2), turned to listOf(1))))
		val captured = assertNotNull(coveredMedian(covered))
		val live = assertNotNull(coveredMedian(covered, emptyMap()))
		assertEquals(captured.worldX, live.worldX)
		assertEquals(captured.worldZ, live.worldZ)
	}

	/** A selected index past the mesh, as a selection a frame ahead of its model can hold, is dropped, not read. */
	@Test
	fun anIndexPastTheMeshIsDropped() {
		val covered = coveredIn(model(), selecting(mapOf(flat to listOf(0, 7), warped to listOf(9))))
		assertEquals(listOf(flat), covered.map { mesh -> mesh.geometry.drawableId }, "a mesh with only out-of-range indices covers nothing")
		assertEquals(setOf(0), covered.single().indices)
	}
}