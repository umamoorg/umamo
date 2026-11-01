package org.umamo.interop.cmo3

import org.umamo.format.cmo3.Cmo3
import org.umamo.format.cmo3.Cmo3Model
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.format.png.PngCodec
import org.umamo.format.raster.RasterImage
import org.umamo.interop.ExportNotice
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.Glue
import org.umamo.runtime.model.GluePair
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.PuppetModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A glue between two drawables whose topology both change in one export re-binds against both new
 * vertex-id tables: the first drawable's pass leaves the glue to its partner's, whose table is still
 * the old one until that pass runs, so a pair past the old table raises no notice and the written glue
 * binds the new vertices.  Pinned on a synthetic pair of glued quads, both re-meshed with a center
 * vertex in front, so each glued corner moves past the four-entry table its quad was written with.
 */
class Cmo3GlueTopologyExportTest {
	@Test
	fun aGlueBetweenTwoRemeshedDrawablesRebindsWithoutANotice() {
		val target = freshGraphOf(gluedQuads())
		val written = Cmo3Import.fromModelSource(target.root as CModelSource)
		val edited =
			written.copy(
				drawables = written.drawables.map { drawable -> drawable.copy(mesh = centeredFanOf(drawable.mesh!!)) },
				glues = written.glues.map { glue -> glue.copy(pairs = glue.pairs.map { pair -> GluePair(pair.indexA + 1, pair.indexB + 1, pair.weightA, pair.weightB) }) },
			)

		val report = Cmo3Export.apply(edited, target)

		assertTrue(report.notices.none { notice -> notice is ExportNotice.UnsupportedChange }, "the glue re-binds: ${report.notices}")
		val reread = Cmo3Import.fromModelSource(Cmo3.read(Cmo3.write(target)).root as CModelSource)
		assertEquals(listOf(4 to 4), reread.glues.single().pairs.map { pair -> pair.indexA to pair.indexB }, "the glue binds the corners at their new indices")
	}

	/**
	 * Two quads side by side, each wound counter-clockwise, the left one's bottom-right corner glued to
	 * the right one's bottom-left - the last vertex of each, so whichever pass runs first finds its
	 * partner's glued corner past the partner's old table.
	 *
	 * @return PuppetModel The rig.
	 */
	private fun gluedQuads(): PuppetModel {
		val left = quadDrawable("ArtMesh1", floatArrayOf(4f, 4f, 0f, 4f, 0f, 0f, 4f, 0f))
		val right = quadDrawable("ArtMesh2", floatArrayOf(8f, 0f, 8f, 4f, 4f, 4f, 4f, 0f))

		return PuppetModel(
			parameters = emptyList(),
			parts = emptyList(),
			deformers = emptyList(),
			drawables = listOf(left, right),
			rootChildren = listOf(OrgChild.Drawable(left.id), OrgChild.Drawable(right.id)),
			rootPartId = null,
			canvasWidth = 8f,
			canvasHeight = 8f,
			glues = listOf(Glue(left.id, right.id, listOf(GluePair(3, 3, 0.5f, 0.5f)))),
		)
	}

	/**
	 * An unkeyed quad drawable at the root, its texture coordinates its corners over the 8 px canvas.
	 *
	 * @param String     id        The drawable id.
	 * @param FloatArray positions The four corners, counter-clockwise.
	 * @return Drawable The drawable.
	 */
	private fun quadDrawable(id: String, positions: FloatArray): Drawable =
		Drawable(
			id = DrawableId(id),
			name = id,
			parentDeformerId = null,
			blendMode = BlendMode.Normal,
			maskedBy = emptyList(),
			mesh = DrawableMesh.withLocalEqualToCanvas(positions, FloatArray(positions.size) { component -> positions[component] / 8f }, intArrayOf(0, 1, 2, 0, 2, 3)),
			geometryGrid = null,
		)

	/**
	 * A mesh re-meshed as a fan around its center, the center vertex first so every old vertex moves one
	 * index up.
	 *
	 * @param DrawableMesh mesh The old mesh.
	 * @return DrawableMesh The fan.
	 */
	private fun centeredFanOf(mesh: DrawableMesh): DrawableMesh {
		val vertexCount = mesh.vertexCount
		val positions = FloatArray(2 * (vertexCount + 1))
		val uvs = FloatArray(2 * (vertexCount + 1))

		for (vertex in 0 until vertexCount) {
			for (axis in 0 until 2) {
				positions[2 * (vertex + 1) + axis] = mesh.positions[2 * vertex + axis]
				uvs[2 * (vertex + 1) + axis] = mesh.uvs[2 * vertex + axis]
				positions[axis] += mesh.positions[2 * vertex + axis] / vertexCount
				uvs[axis] += mesh.uvs[2 * vertex + axis] / vertexCount
			}
		}

		val indices =
			IntArray(3 * vertexCount) { corner ->
				val triangle = corner / 3

				when (corner % 3) {
					0 -> 0
					1 -> triangle + 1
					else -> (triangle + 1) % vertexCount + 1
				}
			}

		return DrawableMesh.withLocalEqualToCanvas(positions, uvs, indices)
	}

	/**
	 * A rig written to a fresh graph over one opaque page.
	 *
	 * @param PuppetModel puppet The rig.
	 * @return Cmo3Model The graph.
	 */
	private fun freshGraphOf(puppet: PuppetModel): Cmo3Model {
		val page = PngCodec.write(RasterImage(8, 8, ByteArray(8 * 8 * 4) { index -> if (index % 4 == 3) 0xFF.toByte() else 0x40 }))

		return Cmo3Conversion.freshCmo3(
			puppet = puppet,
			pages = listOf(Cmo3Conversion.AtlasPage(page, 8, 8)),
			pageIndexByDrawableId = puppet.drawables.associate { drawable -> drawable.id.raw to 0 },
			modelName = "glued",
			nowMillis = 0L,
			obfuscateKey = 0,
		).model
	}
}