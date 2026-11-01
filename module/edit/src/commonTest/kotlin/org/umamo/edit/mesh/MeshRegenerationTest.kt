package org.umamo.edit.mesh

import org.umamo.edit.mesh.RegenerationFixtures.TILE_SIZE
import org.umamo.edit.mesh.RegenerationFixtures.drawableId
import org.umamo.edit.mesh.RegenerationFixtures.gluedVertices
import org.umamo.edit.mesh.RegenerationFixtures.gridIndices
import org.umamo.edit.mesh.RegenerationFixtures.gridPoints
import org.umamo.geometry.mesh.PlanarTriangleMesh
import org.umamo.geometry.predicate.orient2d
import org.umamo.geometry.triangulation.triangulate
import org.umamo.runtime.model.AtlasPage
import org.umamo.runtime.model.AtlasPlacement
import org.umamo.runtime.model.AtlasTile
import org.umamo.runtime.model.AtlasTileId
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.PuppetAtlas
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.applyAffine
import org.umamo.runtime.model.storedToArtAffineForTile
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The mesh regeneration builder: an identity regeneration is exact, the base array's sharing is kept,
 * affine relations survive inside and outside the old mesh (slivers and collinear neighbourhoods
 * included), inside deltas are the piecewise-linear interpolant, outside deltas never leave their
 * boundary's range, the winding follows the old mesh, UVs lower through a placed tile, and every
 * refusal but the pin mismatch.  Glue and the pin mismatch live in MeshRegenerationGlueTest.
 */
class MeshRegenerationTest {
	@Test
	fun identityRegenerationReproducesTheMeshExactly() {
		// A jittered grid over three tiles, the last placed at 30 degrees and three-quarter scale: there a
		// UV lowered from its art point does not round-trip to the stored bits, so only the copy of a
		// coincident vertex reproduces every bit.
		val jittered = FloatArray(gridPoints.size) { component -> gridPoints[component] + 0.17f * (component % 5) + 0.29f * (component / 6) }
		val skewedAtlas =
			PuppetAtlas(
				pages = listOf(AtlasPage(256, 256)),
				tiles = listOf(AtlasTile(RegenerationFixtures.tileId, "tile", TILE_SIZE, TILE_SIZE, placement = AtlasPlacement(0, 120f, 90f, 0.75f, 0.75f, 30f))),
			)

		for (atlas in listOf(RegenerationFixtures.unplacedAtlas(), RegenerationFixtures.placedAtlas(), skewedAtlas)) {
			val oldDrawable = RegenerationFixtures.riggedDrawable(RegenerationFixtures.meshOver(atlas, jittered, gridIndices))
			val model = RegenerationFixtures.modelOf(listOf(oldDrawable, RegenerationFixtures.partnerDrawable()), listOf(RegenerationFixtures.partnerGlue()), atlas)
			val oldMesh = oldDrawable.mesh!!
			val artUvs = applyAffine(oldMesh.uvs, atlas.storedToArtAffineForTile(RegenerationFixtures.tileId)!!)
			val rasterPoints = FloatArray(artUvs.size) { component -> (artUvs[component].toDouble() * TILE_SIZE).toFloat() }
			val built = RegenerationFixtures.built(model, PlanarTriangleMesh(rasterPoints, gridIndices.copyOf(), gluedVertices.copyOf()))
			val edited = model.withMeshTopologyEdit(drawableId, built.edit)
			val newDrawable = edited.drawables.first { it.id == drawableId }
			val newMesh = newDrawable.mesh!!
			assertContentEquals(oldMesh.positions, newMesh.positions)
			assertContentEquals(oldMesh.localPositions, newMesh.localPositions)
			assertContentEquals(oldMesh.uvs, newMesh.uvs)
			assertContentEquals(oldMesh.indices, newMesh.indices)

			for (cell in oldDrawable.geometryGrid!!.cells.indices) {
				assertContentEquals(oldDrawable.geometryGrid!!.cells[cell].form.positionDeltas, newDrawable.geometryGrid!!.cells[cell].form.positionDeltas, "cell $cell")
			}

			assertContentEquals(oldDrawable.blendShapes[0].forms[1]!!.positionDeltas, newDrawable.blendShapes[0].forms[1]!!.positionDeltas)
			assertEquals(model.glues[0].pairs.map { it.indexA to it.indexB }, edited.glues[0].pairs.map { it.indexA to it.indexB })
			assertEquals(0, built.extrapolatedVertices.size)
		}
	}

	@Test
	fun aSharedBaseStaysOneArrayAndASeparateOneStaysSeparate() {
		val points = RegenerationFixtures.pointGrid(4.0, 12.0, 5)

		for (sharedBase in listOf(true, false)) {
			val model = RegenerationFixtures.model(sharedBase = sharedBase)
			val pins = RegenerationFixtures.readyInput(model).pinPoints
			val newMesh = RegenerationFixtures.built(model, RegenerationFixtures.generated(points, pins)).edit.newMesh

			if (sharedBase) {
				assertSame(newMesh.positions, newMesh.localPositions)
			} else {
				assertNotSame(newMesh.positions, newMesh.localPositions)
			}
		}
	}

	@Test
	fun affineMapsAreExactInsideAndOutside() {
		// The grid plus a sliver along its top edge: a vertex 0.05 px above the edge's middle.
		val artPoints = gridPoints + floatArrayOf(32f, -0.05f)
		val indices = intArrayOf(0, 1, 4, 0, 4, 3, 1, 2, 5, 1, 5, 4, 3, 4, 7, 3, 7, 6, 4, 5, 8, 4, 8, 7, 0, 9, 1, 9, 2, 1)
		val model = affineModel(artPoints, indices)
		val generated = RegenerationFixtures.generated(RegenerationFixtures.pointGrid(-24.0, 14.0, 9), RegenerationFixtures.readyInput(model).pinPoints)
		val built = RegenerationFixtures.built(model, generated)
		assertTrue(built.extrapolatedVertices.isNotEmpty(), "the grid reaches past the old mesh")
		assertAffine(generated, built.edit.newMesh)
	}

	@Test
	fun extrapolationWidensPastACollinearNeighbourhood() {
		// Seventeen vertices along a straight top edge and two far below: the nearest neighbourhood of
		// any top-edge feature is collinear until it widens to take in the two.
		val edge = DoubleArray(34) { component -> if (component % 2 == 0) 4.0 * (component / 2) else 0.0 }
		val pointsAsDouble = edge + doubleArrayOf(16.0, 40.0, 48.0, 40.0)
		val triangulation = triangulate(pointsAsDouble)
		val artPoints = FloatArray(pointsAsDouble.size) { component -> pointsAsDouble[component].toFloat() }
		val model = affineModel(artPoints, triangulation.triangles)
		val generated = RegenerationFixtures.generated(doubleArrayOf(32.0, -6.0, 10.0, -3.0, 20.0, 20.0), DoubleArray(0))
		val built = RegenerationFixtures.built(model, generated)
		assertEquals(2, built.extrapolatedVertices.size)
		assertAffine(generated, built.edit.newMesh)
	}

	@Test
	fun insideDeltasAreThePiecewiseLinearInterpolant() {
		val model = RegenerationFixtures.model()
		val oldDrawable = model.drawables.first { it.id == drawableId }
		val pins = RegenerationFixtures.readyInput(model).pinPoints
		val points = doubleArrayOf(10.0, 7.0, 40.0, 20.0, 50.0, 55.0, 20.0, 45.0, 5.0, 60.0, 61.0, 3.0, 0.0, 0.0, 64.0, 64.0, 0.0, 64.0)
		val generated = RegenerationFixtures.generated(points, pins)
		val built = RegenerationFixtures.built(model, generated)
		assertEquals(0, built.extrapolatedVertices.size)
		val newDrawable = model.withMeshTopologyEdit(drawableId, built.edit).drawables.first { it.id == drawableId }
		val oldForms = oldDrawable.geometryGrid!!.cells.map { it.form.positionDeltas } + listOf(oldDrawable.blendShapes[0].forms[1]!!.positionDeltas)
		val newForms = newDrawable.geometryGrid!!.cells.map { it.form.positionDeltas } + listOf(newDrawable.blendShapes[0].forms[1]!!.positionDeltas)

		for (vertex in 0 until generated.vertexCount) {
			val (corners, weights) = oracleBarycentric(generated.positions[2 * vertex].toDouble(), generated.positions[2 * vertex + 1].toDouble())

			for (form in oldForms.indices) {
				for (axis in 0 until 2) {
					val expected = (0 until 3).sumOf { corner -> weights[corner] * oldForms[form][2 * corners[corner] + axis] }
					assertEquals(expected, newForms[form][2 * vertex + axis].toDouble(), 1e-4, "form $form vertex $vertex axis $axis")
				}
			}

			for (axis in 0 until 2) {
				val expectedCanvas = (0 until 3).sumOf { corner -> weights[corner] * oldDrawable.mesh!!.positions[2 * corners[corner] + axis] }
				assertEquals(expectedCanvas, newDrawable.mesh!!.positions[2 * vertex + axis].toDouble(), 1e-3, "canvas vertex $vertex axis $axis")
			}
		}
	}

	@Test
	fun outsideDeltasStayWithinTheirBoundary() {
		val model = RegenerationFixtures.model()
		val oldDrawable = model.drawables.first { it.id == drawableId }
		val pins = RegenerationFixtures.readyInput(model).pinPoints
		// (-10, -10) lies off the corner vertex 0; (32, -12) off the top edge between vertices 0..2.
		val generated = RegenerationFixtures.generated(doubleArrayOf(-10.0, -10.0, 32.0, -12.0, 80.0, 40.0, 30.0, 30.0, 70.0, 70.0), pins)
		val built = RegenerationFixtures.built(model, generated)
		assertEquals(4, built.extrapolatedVertices.size)
		val newDrawable = model.withMeshTopologyEdit(drawableId, built.edit).drawables.first { it.id == drawableId }
		val oldDeltas = oldDrawable.geometryGrid!!.cells[0].form.positionDeltas
		val newDeltas = newDrawable.geometryGrid!!.cells[0].form.positionDeltas
		val cornerVertex = (0 until generated.vertexCount).first { generated.positions[2 * it] == -10f && generated.positions[2 * it + 1] == -10f }
		assertEquals(oldDeltas[0], newDeltas[2 * cornerVertex], "off a corner, the corner's own delta")
		assertEquals(oldDeltas[1], newDeltas[2 * cornerVertex + 1])

		for (vertex in built.extrapolatedVertices) {
			for (axis in 0 until 2) {
				val oldRange = (0 until 9).map { oldDeltas[2 * it + axis] }
				assertTrue(newDeltas[2 * vertex + axis] in oldRange.min()..oldRange.max(), "vertex $vertex axis $axis left the old range")
			}
		}
	}

	@Test
	fun regenerationKeepsTheOldWindingInTheArtFrame() {
		val reversed = IntArray(gridIndices.size) { entry -> gridIndices[entry - entry % 3 + (3 - entry % 3) % 3] }
		val atlas = RegenerationFixtures.unplacedAtlas()
		val drawable = RegenerationFixtures.riggedDrawable(RegenerationFixtures.meshOver(atlas, gridPoints, reversed))
		val model = RegenerationFixtures.modelOf(listOf(drawable), emptyList(), atlas)
		val generated = RegenerationFixtures.generated(RegenerationFixtures.pointGrid(4.0, 12.0, 5), DoubleArray(0))
		val newIndices = RegenerationFixtures.built(model, generated).edit.newMesh.indices

		for (triangle in 0 until newIndices.size / 3) {
			val orientation =
				orient2d(
					generated.positions[2 * newIndices[3 * triangle]].toDouble(),
					generated.positions[2 * newIndices[3 * triangle] + 1].toDouble(),
					generated.positions[2 * newIndices[3 * triangle + 1]].toDouble(),
					generated.positions[2 * newIndices[3 * triangle + 1] + 1].toDouble(),
					generated.positions[2 * newIndices[3 * triangle + 2]].toDouble(),
					generated.positions[2 * newIndices[3 * triangle + 2] + 1].toDouble(),
				)
			assertTrue(orientation < 0.0, "triangle $triangle keeps the old, reversed winding")
		}
	}

	@Test
	fun uvsLowerThroughAPlacedTile() {
		val atlas = RegenerationFixtures.placedAtlas()
		val model = RegenerationFixtures.model(atlas)
		val pins = RegenerationFixtures.readyInput(model).pinPoints
		val generated = RegenerationFixtures.generated(doubleArrayOf(10.0, 7.0, 40.0, 20.0, -6.0, 30.0), pins)
		val newMesh = RegenerationFixtures.built(model, generated).edit.newMesh
		val artUvs = applyAffine(newMesh.uvs, atlas.storedToArtAffineForTile(RegenerationFixtures.tileId)!!)

		for (vertex in 0 until generated.vertexCount) {
			assertEquals(generated.positions[2 * vertex] / TILE_SIZE, artUvs[2 * vertex], 1e-5f, "vertex $vertex u")
			assertEquals(generated.positions[2 * vertex + 1] / TILE_SIZE, artUvs[2 * vertex + 1], 1e-5f, "vertex $vertex v")
		}
	}

	@Test
	fun drawablesWithoutMeshOrTileAreRefused() {
		val model = RegenerationFixtures.model()
		val rigged = model.drawables.first { it.id == drawableId }
		val noMesh = model.copy(drawables = listOf(rigged.copy(mesh = null)))
		val noTile = model.copy(drawables = listOf(rigged.copy(atlasTileId = null)))
		val unknownTile = model.copy(drawables = listOf(rigged.copy(atlasTileId = AtlasTileId("elsewhere"))))
		val zeroTile = model.copy(atlas = PuppetAtlas(tiles = listOf(AtlasTile(RegenerationFixtures.tileId, "tile", 0, TILE_SIZE))))
		assertEquals(RegenerationInput.Refused(RegenerationRefusal.NoMesh), noMesh.regenerationInputFor(drawableId))
		assertEquals(RegenerationInput.Refused(RegenerationRefusal.NoMesh), model.regenerationInputFor(DrawableId("missing")))
		assertEquals(RegenerationInput.Refused(RegenerationRefusal.NoTile), noTile.regenerationInputFor(drawableId))
		assertEquals(RegenerationInput.Refused(RegenerationRefusal.NoTile), unknownTile.regenerationInputFor(drawableId))
		assertEquals(RegenerationInput.Refused(RegenerationRefusal.NoArtFrame), zeroTile.regenerationInputFor(drawableId))
	}

	@Test
	fun malformedAndCollinearOldMeshesAreDegenerate() {
		val atlas = RegenerationFixtures.unplacedAtlas()
		val collinear = floatArrayOf(0f, 0f, 32f, 0f, 64f, 0f)
		val collinearModel = singleDrawableModel(RegenerationFixtures.meshOver(atlas, collinear, intArrayOf(0, 1, 2)))
		val grid = RegenerationFixtures.meshOver(atlas, gridPoints, gridIndices)
		val shortUvs = singleDrawableModel(DrawableMesh(grid.positions, grid.localPositions, grid.uvs.copyOf(10), grid.indices))
		val badIndex = singleDrawableModel(DrawableMesh(grid.positions, grid.localPositions, grid.uvs, intArrayOf(0, 1, 9)))

		for (model in listOf(collinearModel, shortUvs, badIndex)) {
			assertEquals(RegenerationInput.Refused(RegenerationRefusal.DegenerateOldMesh), model.regenerationInputFor(drawableId))
		}
	}

	@Test
	fun aSliverOldMeshRefusesOnlyWhenItMustExtrapolate() {
		// One triangle half a pixel tall: there is no direction across it to extrapolate along.
		val atlas = RegenerationFixtures.unplacedAtlas()
		val model = singleDrawableModel(RegenerationFixtures.meshOver(atlas, floatArrayOf(0f, 0f, 64f, 0f, 32f, 0.5f), intArrayOf(0, 1, 2)))
		val input = RegenerationFixtures.readyInput(model)
		val inside = RegenerationFixtures.generated(doubleArrayOf(0.0, 0.0, 64.0, 0.0, 32.0, 0.5, 32.0, 0.25), DoubleArray(0))
		val outside = RegenerationFixtures.generated(doubleArrayOf(0.0, 0.0, 64.0, 0.0, 32.0, 0.5, 32.0, 6.0), DoubleArray(0))
		assertTrue(model.meshRegeneration(input, inside) is MeshRegeneration.Built)
		assertEquals(MeshRegeneration.Refused(RegenerationRefusal.DegenerateOldMesh), model.meshRegeneration(input, outside))
	}

	/**
	 * A model whose only drawable is the rigged one over a given mesh, with no glue.
	 *
	 * @param DrawableMesh mesh The mesh.
	 * @return PuppetModel The model.
	 */
	private fun singleDrawableModel(mesh: DrawableMesh): PuppetModel {
		val drawable: Drawable = RegenerationFixtures.riggedDrawable(mesh)

		return RegenerationFixtures.modelOf(listOf(drawable), emptyList(), RegenerationFixtures.unplacedAtlas())
	}

	/**
	 * A model whose rigged drawable's canvas and base are exact affine maps of its art points.
	 *
	 * @param FloatArray artPoints The art points.
	 * @param IntArray   indices   The triangles.
	 * @return PuppetModel The model.
	 */
	private fun affineModel(artPoints: FloatArray, indices: IntArray): PuppetModel {
		val atlas = RegenerationFixtures.unplacedAtlas()
		val vertexCount = artPoints.size / 2
		val canvas = FloatArray(artPoints.size)
		val local = FloatArray(artPoints.size)

		for (vertex in 0 until vertexCount) {
			canvasAffine(artPoints[2 * vertex].toDouble(), artPoints[2 * vertex + 1].toDouble()).let { (x, y) ->
				canvas[2 * vertex] = x.toFloat()
				canvas[2 * vertex + 1] = y.toFloat()
			}
			localAffine(artPoints[2 * vertex].toDouble(), artPoints[2 * vertex + 1].toDouble()).let { (x, y) ->
				local[2 * vertex] = x.toFloat()
				local[2 * vertex + 1] = y.toFloat()
			}
		}

		val mesh = DrawableMesh(canvas, local, RegenerationFixtures.storedUvsOf(atlas, artPoints), indices)

		return singleDrawableModel(mesh)
	}

	/**
	 * Asserts every new vertex lies on the fixture's affine canvas and base maps of its art point.
	 *
	 * @param PlanarTriangleMesh generated The generated mesh.
	 * @param DrawableMesh       newMesh   The regenerated mesh.
	 */
	private fun assertAffine(generated: PlanarTriangleMesh, newMesh: DrawableMesh) {
		for (vertex in 0 until generated.vertexCount) {
			val pointX = generated.positions[2 * vertex].toDouble()
			val pointY = generated.positions[2 * vertex + 1].toDouble()
			val (canvasX, canvasY) = canvasAffine(pointX, pointY)
			val (localX, localY) = localAffine(pointX, pointY)
			assertEquals(canvasX, newMesh.positions[2 * vertex].toDouble(), 1e-3, "canvas x of ($pointX, $pointY)")
			assertEquals(canvasY, newMesh.positions[2 * vertex + 1].toDouble(), 1e-3, "canvas y of ($pointX, $pointY)")
			assertEquals(localX, newMesh.localPositions[2 * vertex].toDouble(), 1e-5, "local x of ($pointX, $pointY)")
			assertEquals(localY, newMesh.localPositions[2 * vertex + 1].toDouble(), 1e-5, "local y of ($pointX, $pointY)")
		}
	}

	/**
	 * The fixture's canvas map: a shear, a scale, and a shift.
	 *
	 * @param Double x The art x.
	 * @param Double y The art y.
	 * @return Pair<Double, Double> The canvas point.
	 */
	private fun canvasAffine(x: Double, y: Double): Pair<Double, Double> = (1.5 * x + 0.5 * y + 200.0) to (-0.25 * x + 1.25 * y + 100.0)

	/**
	 * The fixture's base map, scaled down like a warp lattice's unit frame.
	 *
	 * @param Double x The art x.
	 * @param Double y The art y.
	 * @return Pair<Double, Double> The base point.
	 */
	private fun localAffine(x: Double, y: Double): Pair<Double, Double> = (0.015625 * x - 0.0078125 * y + 0.125) to (0.0078125 * x + 0.015625 * y - 0.25)

	/**
	 * The old grid triangle holding an art point and the point's barycentric weights in it, by area
	 * ratios - independent of the builder's locator.
	 *
	 * @param Double x The art x.
	 * @param Double y The art y.
	 * @return Pair<IntArray, DoubleArray> The triangle's corners and the weights.
	 */
	private fun oracleBarycentric(x: Double, y: Double): Pair<IntArray, DoubleArray> {
		for (triangle in 0 until gridIndices.size / 3) {
			val corners = IntArray(3) { corner -> gridIndices[3 * triangle + corner] }
			val cornerX = DoubleArray(3) { corner -> gridPoints[2 * corners[corner]].toDouble() }
			val cornerY = DoubleArray(3) { corner -> gridPoints[2 * corners[corner] + 1].toDouble() }
			val area = (cornerX[1] - cornerX[0]) * (cornerY[2] - cornerY[0]) - (cornerY[1] - cornerY[0]) * (cornerX[2] - cornerX[0])
			val weights =
				DoubleArray(3) { corner ->
					val next = (corner + 1) % 3
					val after = (corner + 2) % 3

					((cornerX[next] - x) * (cornerY[after] - y) - (cornerY[next] - y) * (cornerX[after] - x)) / area
				}

			if (weights.all { weight -> weight >= -1e-12 }) {
				return corners to weights
			}
		}

		fail("($x, $y) lies in no grid triangle")
	}
}