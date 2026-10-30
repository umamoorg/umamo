package org.umamo.edit.mesh

import org.umamo.geometry.mesh.PlanarTriangleMesh
import org.umamo.geometry.triangulation.triangulate
import org.umamo.runtime.model.AtlasPage
import org.umamo.runtime.model.AtlasPlacement
import org.umamo.runtime.model.AtlasTile
import org.umamo.runtime.model.AtlasTileId
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.BlendShapeBinding
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.Glue
import org.umamo.runtime.model.GluePair
import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.MeshDeltaForm
import org.umamo.runtime.model.MeshForm
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetAtlas
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.applyAffine
import org.umamo.runtime.model.invertAffine
import org.umamo.runtime.model.storedToArtAffineForTile

/**
 * Models for the mesh regeneration tests: a drawable over a 64 px tile with a 3x3 grid mesh (eight
 * triangles, the generated meshes' winding), a non-affine canvas, a separate keyform-space base, two
 * keyform cells, and a blend shape, glued to a second drawable at two of its vertices - plus the
 * "generated" meshes the tests hand to the builder, triangulated from point sets with no raster.
 */
internal object RegenerationFixtures {
	val drawableId = DrawableId("art")
	val partnerId = DrawableId("partner")
	val tileId = AtlasTileId("tile")
	val parameterId = ParameterId("A")

	/** The tile's side in art pixels. */
	const val TILE_SIZE: Int = 64

	/** The old grid's art points, row-major from the top-left: (0, 0), (32, 0), (64, 0), (0, 32), ... */
	val gridPoints: FloatArray = FloatArray(18) { component -> 32f * if (component % 2 == 0) (component / 2) % 3 else (component / 2) / 3 }

	/** The old grid's triangles, two per cell, each positively oriented in art pixels. */
	val gridIndices: IntArray = intArrayOf(0, 1, 4, 0, 4, 3, 1, 2, 5, 1, 5, 4, 3, 4, 7, 3, 7, 6, 4, 5, 8, 4, 8, 7)

	/** The old vertices the glue names on the regenerated drawable's side: (64, 0) and (32, 32). */
	val gluedVertices: IntArray = intArrayOf(2, 4)

	/**
	 * An atlas holding only the tile, unplaced, so stored UVs are art UVs.
	 *
	 * @return PuppetAtlas The atlas.
	 */
	fun unplacedAtlas(): PuppetAtlas = PuppetAtlas(tiles = listOf(AtlasTile(tileId, "tile", TILE_SIZE, TILE_SIZE)))

	/**
	 * An atlas with the tile placed on a 256 px page, rotated a quarter turn, so stored UVs address the page.
	 *
	 * @return PuppetAtlas The atlas.
	 */
	fun placedAtlas(): PuppetAtlas =
		PuppetAtlas(
			pages = listOf(AtlasPage(256, 256)),
			tiles = listOf(AtlasTile(tileId, "tile", TILE_SIZE, TILE_SIZE, placement = AtlasPlacement(0, 160f, 40f, 1f, 1f, 90f))),
		)

	/**
	 * A canvas position bent away from any affine map of the art point.
	 *
	 * @param Float x The art x.
	 * @param Float y The art y.
	 * @return FloatArray The canvas x and y.
	 */
	fun bentCanvasOf(x: Float, y: Float): FloatArray = floatArrayOf(x + 100f + x * y / 256f, y + 50f - x * x / 512f)

	/**
	 * A keyform-space base: an affine map of the art point, as a deformer's frame would hold it.
	 *
	 * @param Float x The art x.
	 * @param Float y The art y.
	 * @return FloatArray The base x and y.
	 */
	fun localOf(x: Float, y: Float): FloatArray = floatArrayOf(0.5f * x - 0.25f * y + 3f, 0.25f * x + 0.5f * y - 1f)

	/**
	 * Stored UVs for art points under an atlas.
	 *
	 * @param PuppetAtlas atlas     The atlas.
	 * @param FloatArray  artPoints The points in art pixels.
	 * @return FloatArray The stored UVs.
	 */
	fun storedUvsOf(atlas: PuppetAtlas, artPoints: FloatArray): FloatArray {
		val artUvs = FloatArray(artPoints.size) { component -> artPoints[component] / TILE_SIZE }
		val artToStored = invertAffine(atlas.storedToArtAffineForTile(tileId)!!)!!

		return applyAffine(artUvs, artToStored)
	}

	/**
	 * A mesh over art points with a bent canvas and either a separate base or one shared array.
	 *
	 * @param PuppetAtlas atlas      The atlas the UVs address.
	 * @param FloatArray  artPoints  The points in art pixels.
	 * @param IntArray    indices    The triangles.
	 * @param Boolean     sharedBase Whether the base is the canvas array itself.
	 * @return DrawableMesh The mesh.
	 */
	fun meshOver(atlas: PuppetAtlas, artPoints: FloatArray, indices: IntArray, sharedBase: Boolean = false): DrawableMesh {
		val vertexCount = artPoints.size / 2
		val canvas = FloatArray(artPoints.size)
		val local = FloatArray(artPoints.size)

		for (vertex in 0 until vertexCount) {
			bentCanvasOf(artPoints[2 * vertex], artPoints[2 * vertex + 1]).copyInto(canvas, 2 * vertex)
			localOf(artPoints[2 * vertex], artPoints[2 * vertex + 1]).copyInto(local, 2 * vertex)
		}

		val uvs = storedUvsOf(atlas, artPoints)

		return if (sharedBase) DrawableMesh.withLocalEqualToCanvas(canvas, uvs, indices) else DrawableMesh(canvas, local, uvs, indices)
	}

	/**
	 * Per-vertex deltas with a distinct value for every component, one of them negative zero.
	 *
	 * @param Int vertexCount The vertex count.
	 * @param Int seed        Shifts the values so cells differ.
	 * @return FloatArray The deltas.
	 */
	fun deltasFor(vertexCount: Int, seed: Int): FloatArray =
		FloatArray(2 * vertexCount) { component -> if (component == 1) -0f else (component * 7 + seed * 13) % 23 - 11f }

	/**
	 * The rigged drawable: the given mesh, two keyform cells, and a blend shape.
	 *
	 * @param DrawableMesh mesh The mesh.
	 * @return Drawable The drawable.
	 */
	fun riggedDrawable(mesh: DrawableMesh): Drawable =
		Drawable(
			id = drawableId,
			name = "art",
			parentDeformerId = null,
			blendMode = BlendMode.Normal,
			maskedBy = emptyList(),
			mesh = mesh,
			geometryGrid =
				KeyformGrid(
					listOf(KeyformAxis(parameterId, floatArrayOf(0f, 1f))),
					listOf(
						KeyformCell(intArrayOf(0), MeshDeltaForm(deltasFor(mesh.vertexCount, 1))),
						KeyformCell(intArrayOf(1), MeshDeltaForm(deltasFor(mesh.vertexCount, 2))),
					),
				),
			atlasTileId = tileId,
			blendShapes =
				listOf(
					BlendShapeBinding(
						parameterId = parameterId,
						keys = floatArrayOf(0f, 1f),
						neutralIndex = 0,
						forms = listOf(null, MeshForm(deltasFor(mesh.vertexCount, 3), drawOrder = 510f, opacity = 0.5f)),
					),
				),
		)

	/**
	 * The partner drawable the rigged one is glued to: three vertices, no tile.
	 *
	 * @return Drawable The drawable.
	 */
	fun partnerDrawable(): Drawable =
		Drawable(
			id = partnerId,
			name = "partner",
			parentDeformerId = null,
			blendMode = BlendMode.Normal,
			maskedBy = emptyList(),
			mesh = DrawableMesh.withLocalEqualToCanvas(floatArrayOf(0f, 0f, 10f, 0f, 0f, 10f), FloatArray(6), intArrayOf(0, 1, 2)),
			geometryGrid = null,
		)

	/**
	 * The default glue: the rigged drawable's vertices 2 and 4 to the partner's 0 and 1.
	 *
	 * @return Glue The glue.
	 */
	fun partnerGlue(): Glue = Glue(drawableId, partnerId, listOf(GluePair(2, 0, 0.25f, 0.75f), GluePair(4, 1, 0.5f, 0.5f)), id = "seam")

	/**
	 * A model holding drawables and glues over an atlas.
	 *
	 * @param List<Drawable> drawables The drawables.
	 * @param List<Glue>     glues     The glues.
	 * @param PuppetAtlas    atlas     The atlas.
	 * @return PuppetModel The model.
	 */
	fun modelOf(drawables: List<Drawable>, glues: List<Glue>, atlas: PuppetAtlas): PuppetModel =
		PuppetModel(
			parameters = listOf(Parameter(parameterId, "A", 0f, 1f, 0f)),
			parts = emptyList(),
			deformers = emptyList(),
			drawables = drawables,
			rootChildren = drawables.map { drawable -> OrgChild.Drawable(drawable.id) },
			rootPartId = null,
			glues = glues,
			atlas = atlas,
		)

	/**
	 * The standard fixture: the rigged grid drawable glued to its partner.
	 *
	 * @param PuppetAtlas atlas      The atlas.
	 * @param Boolean     sharedBase Whether the rigged mesh's base is its canvas array.
	 * @return PuppetModel The model.
	 */
	fun model(atlas: PuppetAtlas = unplacedAtlas(), sharedBase: Boolean = false): PuppetModel =
		modelOf(listOf(riggedDrawable(meshOver(atlas, gridPoints, gridIndices, sharedBase)), partnerDrawable()), listOf(partnerGlue()), atlas)

	/**
	 * A "generated" mesh: the Delaunay triangulation of some points plus the pins, pins last.
	 *
	 * @param DoubleArray points    The free points, art pixels.
	 * @param DoubleArray pinPoints The pins, art pixels.
	 * @return PlanarTriangleMesh The mesh, its pin vertices naming the pins.
	 */
	fun generated(points: DoubleArray, pinPoints: DoubleArray): PlanarTriangleMesh {
		val all = points + pinPoints
		val triangulation = triangulate(all)
		val pinVertices = IntArray(pinPoints.size / 2) { pin -> triangulation.canonicalIndex[points.size / 2 + pin] }

		return PlanarTriangleMesh(FloatArray(all.size) { component -> all[component].toFloat() }, triangulation.triangles, pinVertices)
	}

	/**
	 * A square grid of points.
	 *
	 * @param Double start The first coordinate on each axis.
	 * @param Double step  The spacing.
	 * @param Int    count The points per axis.
	 * @return DoubleArray The points, row-major.
	 */
	fun pointGrid(start: Double, step: Double, count: Int): DoubleArray = DoubleArray(2 * count * count) { component -> start + step * if (component % 2 == 0) (component / 2) % count else (component / 2) / count }

	/**
	 * The ready input for the rigged drawable, failing the test when it is refused.
	 *
	 * @param PuppetModel model The model.
	 * @return RegenerationInput.Ready The input.
	 */
	fun readyInput(model: PuppetModel): RegenerationInput.Ready = model.regenerationInputFor(drawableId) as RegenerationInput.Ready

	/**
	 * Builds the rigged drawable's regeneration, failing the test when it is refused.
	 *
	 * @param PuppetModel        model     The model.
	 * @param PlanarTriangleMesh generated The generated mesh.
	 * @return MeshRegeneration.Built The built edit.
	 */
	fun built(model: PuppetModel, generated: PlanarTriangleMesh): MeshRegeneration.Built {
		val result = model.meshRegeneration(readyInput(model), generated)

		return result as? MeshRegeneration.Built ?: error("regeneration refused: $result")
	}
}