package org.umamo.ui.transform

import org.umamo.edit.BrushSettings
import org.umamo.edit.ProportionalFalloff
import org.umamo.edit.transform.BrushStroke
import org.umamo.render.eval.warpLatticeAt
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.ElementOwner
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.meshOf
import org.umamo.ui.viewport.viewport2d.LATTICE_RIG_ART
import org.umamo.ui.viewport.viewport2d.LATTICE_RIG_BODY
import org.umamo.ui.viewport.viewport2d.latticeRigModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pins the deform brush's landing: a stroke over a drawable's vertices moves the ones the brush covered and
 * leaves the rest, and the art's shape changes where the brush went; a stroke over a warp smears the cage.
 */
class BrushMeshLandingTest {
	// How close a landed point must be, in world units.
	private val tolerance = 1e-3f

	/** The second layer of art, whose lower-left corner the brush also reaches. */
	private val secondArt = DrawableId("art2")

	/** The brush's configuration: the smooth falloff at a fifteen-unit radius and full strength. */
	private val settings = BrushSettings(ProportionalFalloff.Smooth, 15f, 1f)

	/**
	 * A stroke that walks the brush along the mesh's lower edge, from the left of its lower-left corner
	 * to the right of it.
	 *
	 * @return BrushStroke The stroke.
	 */
	private fun stroke(): BrushStroke = BrushStroke.starting(-10f, 0f, 15f).stampedTo(10f, 0f, 15f)

	/**
	 * A stroke over the rig's art, as the mesh the brush smears.
	 *
	 * @return ElementOwner The art's owner.
	 */
	private fun artOwner(): ElementOwner = ElementOwner.Drawable(LATTICE_RIG_ART)

	/**
	 * The rig's model with a second layer of art beside its own, so a stroke can reach both.
	 *
	 * @return PuppetModel The model.
	 */
	private fun twoLayerModel(): PuppetModel {
		val base = latticeRigModel()
		val layer =
			Drawable(
				id = secondArt,
				name = "art2",
				parentDeformerId = null,
				blendMode = BlendMode.Normal,
				maskedBy = emptyList(),
				mesh = DrawableMesh.withLocalEqualToCanvas(floatArrayOf(5f, 0f, 25f, 0f, 25f, 20f, 5f, 20f), FloatArray(8), intArrayOf(0, 1, 2, 0, 2, 3)),
				geometryGrid = null,
			)

		return base.copy(
			drawables = base.drawables + layer,
			rootChildren = base.rootChildren + OrgChild.Drawable(secondArt),
		)
	}

	/**
	 * A stroke over a mesh's lower edge moves the vertex the brush passed over and leaves the rest of the
	 * mesh where it was.
	 */
	@Test
	fun aStrokeOverAMeshMovesTheVerticesItCoversAndLeavesTheRest() {
		val model = latticeRigModel()
		val before = model.meshOf(LATTICE_RIG_ART)!!.positions
		val geometry = captureBrushGeometry(model, listOf(artOwner()))
		val landing = brushLanding(geometry, stroke(), settings)

		val meshes = landing as BrushLanding.Meshes
		assertEquals(setOf(LATTICE_RIG_ART), meshes.rest.keys, "the brush smeared the mesh")
		assertEquals(listOf(0), meshes.movedIndices.getValue(LATTICE_RIG_ART), "the vertex the brush passed over moved")

		val after = meshes.rest.getValue(LATTICE_RIG_ART).positions
		assertTrue(after[0] != before[0], "the vertex under the brush changed place")

		for (vertexIndex in 1 until before.size / 2) {
			assertEquals(before[vertexIndex * 2], after[vertexIndex * 2], tolerance, "vertex $vertexIndex stayed on x")
			assertEquals(before[vertexIndex * 2 + 1], after[vertexIndex * 2 + 1], tolerance, "vertex $vertexIndex stayed on z")
		}
	}

	/**
	 * The art's shape changes where the brush went: the mesh's rest shape is not the one it was.
	 */
	@Test
	fun theArtChangesShapeWhereTheBrushWent() {
		val model = latticeRigModel()
		val before = model.meshOf(LATTICE_RIG_ART)!!.positions
		val geometry = captureBrushGeometry(model, listOf(artOwner()))
		val landing = brushLanding(geometry, stroke(), settings)

		val after = (landing as BrushLanding.Meshes).rest.getValue(LATTICE_RIG_ART).positions

		assertTrue(after.toList() != before.toList(), "the art's shape changed")
	}

	/**
	 * A stroke over a warp smears the cage, and the art under it rests where it rested.
	 */
	@Test
	fun aStrokeOverAWarpSmearsTheCage() {
		val model = latticeRigModel()
		val geometry = captureBrushGeometry(model, listOf(ElementOwner.Deformer(LATTICE_RIG_BODY)))
		val landing = brushLanding(geometry, stroke(), settings)

		val cages = landing as BrushLanding.Cages
		assertEquals(listOf(LATTICE_RIG_BODY), cages.warpIds, "the brush smeared the cage")
		val moved = cages.world.getValue(LATTICE_RIG_BODY)
		val before = warpLatticeAt(model, emptyMap(), LATTICE_RIG_BODY)!!.world
		assertTrue(moved[0] != before[0], "the cage's corner moved")
	}

	/**
	 * A stroke over two layers of art moves a vertex in BOTH of them, and the landing names each.
	 */
	@Test
	fun aStrokeOverTwoMeshesMovesVerticesInBoth() {
		val model = twoLayerModel()
		val artBefore = model.meshOf(LATTICE_RIG_ART)!!.positions
		val layerBefore = model.meshOf(secondArt)!!.positions
		val geometry = captureBrushGeometry(model, listOf(artOwner(), ElementOwner.Drawable(secondArt)))
		val landing = brushLanding(geometry, stroke(), settings)

		val meshes = landing as BrushLanding.Meshes
		assertEquals(setOf(LATTICE_RIG_ART, secondArt), meshes.rest.keys, "the brush smeared both layers")
		assertEquals(listOf(0), meshes.movedIndices.getValue(LATTICE_RIG_ART), "the first layer's corner moved")
		assertEquals(listOf(0), meshes.movedIndices.getValue(secondArt), "and the second layer's corner moved too")

		assertTrue(meshes.rest.getValue(LATTICE_RIG_ART).positions[0] != artBefore[0], "the first layer's vertex changed place")
		assertTrue(meshes.rest.getValue(secondArt).positions[0] != layerBefore[0], "and the second layer's vertex too")
	}

	/**
	 * The preview carries the model the stroke would leave behind, because the renderer draws the art and a
	 * previewed rest shape alone does not reach it.
	 */
	@Test
	fun thePreviewCarriesTheModelTheStrokeWouldLeaveBehind() {
		val model = latticeRigModel()
		val before = model.meshOf(LATTICE_RIG_ART)!!.positions
		val geometry = captureBrushGeometry(model, listOf(artOwner()))
		val preview = brushPreview(geometry, stroke(), settings)

		val smearedModel = assertNotNull(preview.smearedModel, "the preview carries a model")
		val smeared = assertNotNull(smearedModel.meshOf(LATTICE_RIG_ART), "the model carries the art").positions
		assertTrue(smeared.toList() != before.toList(), "and it carries the smeared art")
	}
}