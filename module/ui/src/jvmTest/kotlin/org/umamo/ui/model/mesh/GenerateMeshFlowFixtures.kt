package org.umamo.ui.model.mesh

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.umamo.edit.EditorMode
import org.umamo.edit.EditorSession
import org.umamo.edit.OperatorParameter
import org.umamo.edit.SelectionOps
import org.umamo.edit.SelectionTarget
import org.umamo.edit.floatValue
import org.umamo.edit.intValue
import org.umamo.edit.structure.rename
import org.umamo.edit.withParameter
import org.umamo.geometry.locate.TriangleLocator
import org.umamo.interop.art.mesh.ArtMeshPreset
import org.umamo.render.DecodedImage
import org.umamo.render.SourceArtRasters
import org.umamo.runtime.model.AtlasPage
import org.umamo.runtime.model.AtlasPlacement
import org.umamo.runtime.model.AtlasTile
import org.umamo.runtime.model.AtlasTileId
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.Glue
import org.umamo.runtime.model.GluePair
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.PuppetAtlas
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.applyUvAffine
import org.umamo.runtime.model.invertUvAffine
import org.umamo.runtime.model.storedToArtAffineForTile
import org.umamo.ui.document.blendedAt
import org.umamo.ui.model.repack.MAX_IMPORT_PAGE_SIZE
import org.umamo.ui.progress.ProgressLabels
import org.umamo.ui.progress.ProgressTask
import org.umamo.ui.progress.ProgressTracker
import java.util.Collections
import java.util.concurrent.CountDownLatch
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The synthetic documents, art, and hosts the mesh generation flow tests share: two 64 px tiles, two
 * drawables, quads over art rectangles, discs and bars of art, and a host whose strip reruns land in a
 * given scope.  A canvas is the art moved by ([ORIGIN_X], [ORIGIN_Y]), as a birth quad's is.
 */
internal object GenerateMeshFlowFixtures {
	/** The first tile, 64 px square (64 x 32 under a bar). */
	val tileA = AtlasTileId("tileA")

	/** The second tile. */
	val tileB = AtlasTileId("tileB")

	/** The first drawable. */
	val drawableA = DrawableId("a")

	/** The second drawable. */
	val drawableB = DrawableId("b")

	/** Waits for every rerun the strip launched in the test's scope. */
	suspend fun CoroutineScope.joinChildren() {
		coroutineContext.job.children.toList().joinAll()
	}

	/**
	 * The rows with one float row's value replaced.
	 *
	 * @param String key   The row.
	 * @param Float  value The value.
	 * @return List<OperatorParameter> The rows.
	 */
	fun List<OperatorParameter>.withFloat(key: String, value: Float): List<OperatorParameter> =
		map { row -> if (row.key == key) (row as OperatorParameter.FloatParameter).copy(value = value) else row }

	/**
	 * A host over a session and some art, the strip's reruns in [scope].
	 *
	 * @param EditorSession    session  The session.
	 * @param SourceArtRasters rasters  The art.
	 * @param CoroutineScope   scope    Where reruns run.
	 * @param ProgressTracker? progress Where the run shows while it works, or null.
	 * @param Function         remember Receives the landed options.
	 * @return GenerateMeshHost The host.
	 */
	fun host(session: EditorSession, rasters: SourceArtRasters, scope: CoroutineScope, progress: ProgressTracker? = null, remember: (MeshRecipe) -> Unit = {}): GenerateMeshHost =
		GenerateMeshHost(session, rasters, sessionAtlasPages = null, premultipliedAlpha = false, scope = scope, progress = progress, rememberOptions = remember)

	/**
	 * A raster store over fixed art.
	 *
	 * @param Array<Pair<AtlasTileId, DecodedImage>> art The art by tile.
	 * @return SourceArtRasters The store.
	 */
	fun rasters(vararg art: Pair<AtlasTileId, DecodedImage>): SourceArtRasters {
		val byTile = art.toMap()

		return SourceArtRasters { tileId -> byTile[tileId] }
	}

	/**
	 * A 64 px raster holding a disc of radius 20 in its middle (its trim runs 12..52).
	 *
	 * @param Int alpha The disc's alpha; opaque by default.
	 * @return DecodedImage The art.
	 */
	fun disc(alpha: Int = 255): DecodedImage =
		raster(64, 64, alpha) { column, row ->
			val offsetX = column + 0.5 - 32.0
			val offsetY = row + 0.5 - 32.0
			offsetX * offsetX + offsetY * offsetY <= 400.0
		}

	/**
	 * A 64 x 32 raster holding an opaque bar from x 4 to 59 and y 4 to 27.
	 *
	 * @return DecodedImage The art.
	 */
	fun bar(): DecodedImage = raster(64, 32) { column, row -> column in 4..59 && row in 4..27 }

	/**
	 * A fully transparent 64 px raster.
	 *
	 * @return DecodedImage The art.
	 */
	fun transparent(): DecodedImage = raster(64, 64) { _, _ -> false }

	/**
	 * A raster painted where a predicate says so.
	 *
	 * @param Int                   width  The width.
	 * @param Int                   height The height.
	 * @param Int                   alpha  The alpha of the pixels the predicate marks.
	 * @param (Int, Int) -> Boolean opaque Whether a pixel is opaque.
	 * @return DecodedImage The raster.
	 */
	fun raster(width: Int, height: Int, alpha: Int = 255, opaque: (Int, Int) -> Boolean): DecodedImage {
		val rgba = ByteArray(width * height * 4)

		for (row in 0 until height) {
			for (column in 0 until width) {
				if (opaque(column, row)) {
					rgba[(row * width + column) * 4 + 3] = alpha.toByte()
				}
			}
		}

		return DecodedImage(rgba, width, height)
	}

	/**
	 * An atlas of unplaced tiles.
	 *
	 * @param List<AtlasTileId> tiles  The tiles.
	 * @param Int               height The tiles' height.
	 * @return PuppetAtlas The atlas.
	 */
	fun unplacedAtlas(tiles: List<AtlasTileId>, height: Int = 64): PuppetAtlas = PuppetAtlas(tiles = tiles.map { tileId -> AtlasTile(tileId, tileId.raw, 64, height) })

	/**
	 * An upright placement on page 0.
	 *
	 * @param Float x The left edge, page pixels.
	 * @param Float y The top edge.
	 * @return AtlasPlacement The placement.
	 */
	fun placement(x: Float, y: Float): AtlasPlacement = AtlasPlacement(0, x, y, 1f, 1f, 0f)

	/**
	 * Drawable a over tile A and an unused tile B, both placed on a 256 px page, a's quad spanning the
	 * disc's trim widened by a margin (0 widens it to the whole raster).
	 *
	 * @param List<Pair<DrawableId, Float>> quads      The drawable and its quad's margin past the trim.
	 * @param AtlasPlacement                aPlacement Tile A's placement.
	 * @param AtlasPlacement                bPlacement Tile B's placement.
	 * @param Boolean                       pinA       Whether tile A is pinned.
	 * @return PuppetModel The model.
	 */
	fun placedModel(quads: List<Pair<DrawableId, Float>>, aPlacement: AtlasPlacement, bPlacement: AtlasPlacement, pinA: Boolean = false): PuppetModel {
		val atlas =
			PuppetAtlas(
				pages = listOf(AtlasPage(256, 256)),
				tiles = listOf(AtlasTile(tileA, "A", 64, 64, placement = aPlacement, pinned = pinA), AtlasTile(tileB, "B", 64, 64, placement = bPlacement)),
			)
		val artToStored = invertUvAffine(atlas.storedToArtAffineForTile(tileA)!!)!!
		val drawables =
			quads.map { (drawableId, margin) ->
				val low = if (margin == 0f) 0f else 12f - margin
				val high = if (margin == 0f) 64f else 52f + margin
				val unplaced = quad(drawableId, tileA, low, low, high, high)
				val mesh = unplaced.mesh!!

				unplaced.copy(mesh = DrawableMesh.withLocalEqualToCanvas(mesh.positions, applyUvAffine(mesh.uvs, artToStored), mesh.indices))
			}

		return model(drawables, atlas)
	}

	/**
	 * A quad drawable over an art rectangle of a tile (UVs in the art frame), its canvas the art moved by
	 * the origin, as a birth quad is.
	 *
	 * @param DrawableId   id      The drawable.
	 * @param AtlasTileId? tileId  The tile, or null.
	 * @param Float        left    The left edge, art pixels.
	 * @param Float        top     The top edge.
	 * @param Float        right   The right edge.
	 * @param Float        bottom  The bottom edge.
	 * @param Float        originX    The canvas x of the art's left edge.
	 * @param Float        tileHeight The tile's height in pixels (every tile here is 64 wide).
	 * @return Drawable The drawable.
	 */
	fun quad(id: DrawableId, tileId: AtlasTileId?, left: Float, top: Float, right: Float, bottom: Float, originX: Float = ORIGIN_X, tileHeight: Float = 64f): Drawable {
		val art = floatArrayOf(left, top, right, top, right, bottom, left, bottom)
		val canvas = FloatArray(art.size) { component -> art[component] + if (component % 2 == 0) originX else ORIGIN_Y }
		val uvs = FloatArray(art.size) { component -> art[component] / if (component % 2 == 0) 64f else tileHeight }

		return Drawable(
			id = id,
			name = id.raw,
			parentDeformerId = null,
			blendMode = BlendMode.Normal,
			maskedBy = emptyList(),
			mesh = DrawableMesh.withLocalEqualToCanvas(canvas, uvs, intArrayOf(0, 1, 2, 0, 2, 3)),
			geometryGrid = null,
			atlasTileId = tileId,
		)
	}

	/**
	 * A quad over the disc art whose first corner is a vertex twice, its two triangles each using one
	 * copy - a duplicated seam vertex, as a split mesh welded back together carries.
	 *
	 * @param DrawableId  id      The drawable.
	 * @param AtlasTileId tileId  The tile.
	 * @param Float       originX The canvas x the art's left lands on.
	 * @return Drawable The drawable.
	 */
	fun seamQuad(id: DrawableId, tileId: AtlasTileId, originX: Float = ORIGIN_X): Drawable {
		val art = floatArrayOf(10f, 10f, 54f, 10f, 54f, 54f, 10f, 54f, 10f, 10f)
		val canvas = FloatArray(art.size) { component -> art[component] + if (component % 2 == 0) originX else ORIGIN_Y }
		val uvs = FloatArray(art.size) { component -> art[component] / 64f }

		return quad(id, tileId, 10f, 10f, 54f, 54f, originX).copy(mesh = DrawableMesh.withLocalEqualToCanvas(canvas, uvs, intArrayOf(0, 1, 2, 4, 2, 3)))
	}

	/**
	 * A model of drawables over an atlas.
	 *
	 * @param List<Drawable> drawables The drawables.
	 * @param PuppetAtlas    atlas     The atlas.
	 * @return PuppetModel The model.
	 */
	fun model(drawables: List<Drawable>, atlas: PuppetAtlas): PuppetModel =
		PuppetModel(
			parameters = emptyList(),
			parts = emptyList(),
			deformers = emptyList(),
			drawables = drawables,
			rootChildren = drawables.map { drawable -> OrgChild.Drawable(drawable.id) },
			rootPartId = null,
			atlas = atlas,
		)

	/**
	 * A drawable's mesh.
	 *
	 * @param PuppetModel model      The model.
	 * @param DrawableId  drawableId The drawable.
	 * @return DrawableMesh The mesh.
	 */
	fun meshOf(model: PuppetModel, drawableId: DrawableId): DrawableMesh = model.drawables.first { drawable -> drawable.id == drawableId }.mesh!!

	/**
	 * A drawable's vertex count.
	 *
	 * @param PuppetModel model      The model.
	 * @param DrawableId  drawableId The drawable.
	 * @return Int The count.
	 */
	fun vertexCountOf(model: PuppetModel, drawableId: DrawableId): Int = meshOf(model, drawableId).vertexCount

	/**
	 * A drawable's vertices' x in its tile's art pixels.
	 *
	 * @param PuppetModel model      The model.
	 * @param DrawableId  drawableId The drawable.
	 * @return List<Float> The x of every vertex.
	 */
	fun artXsOf(model: PuppetModel, drawableId: DrawableId): List<Float> {
		val drawable = model.drawables.first { candidate -> candidate.id == drawableId }
		val artUvs = applyUvAffine(drawable.mesh!!.uvs, model.atlas.storedToArtAffineForTile(drawable.atlasTileId!!)!!)

		return (0 until artUvs.size / 2).map { vertex -> artUvs[2 * vertex] * 64f }
	}

	/** The canvas x of a quad's art origin. */
	const val ORIGIN_X: Float = 200f

	/** The canvas y of a quad's art origin. */
	const val ORIGIN_Y: Float = 100f
}