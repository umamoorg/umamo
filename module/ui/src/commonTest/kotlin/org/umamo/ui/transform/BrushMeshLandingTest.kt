package org.umamo.ui.transform

import org.umamo.edit.BrushSettings
import org.umamo.edit.ProportionalFalloff
import org.umamo.edit.transform.BrushStroke
import org.umamo.render.eval.warpLatticeAt
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.ElementOwner
import org.umamo.runtime.model.meshOf
import org.umamo.ui.viewport.viewport2d.LATTICE_RIG_ART
import org.umamo.ui.viewport.viewport2d.LATTICE_RIG_BODY
import org.umamo.ui.viewport.viewport2d.latticeRigModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the deform brush's landing: a stroke over a drawable's vertices moves the ones the brush covered and
 * leaves the rest, and the art's shape changes where the brush went; a stroke over a warp smears the cage.
 */
class BrushMeshLandingTest {
	// How close a landed point must be, in world units.
	private val tolerance = 1e-3f

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
	 * A stroke over a mesh's lower edge moves the vertex the brush passed over and leaves the rest of the
	 * mesh where it was.
	 */
	@Test
	fun aStrokeOverAMeshMovesTheVerticesItCoversAndLeavesTheRest() {
		val model = latticeRigModel()
		val before = model.meshOf(LATTICE_RIG_ART)!!.positions
		val landing = brushLanding(model, stroke(), settings, listOf(artOwner()))

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
		val landing = brushLanding(model, stroke(), settings, listOf(artOwner()))

		val after = (landing as BrushLanding.Meshes).rest.getValue(LATTICE_RIG_ART).positions

		assertTrue(after.toList() != before.toList(), "the art's shape changed")
	}

	/**
	 * A stroke over a warp smears the cage, and the art under it rests where it rested.
	 */
	@Test
	fun aStrokeOverAWarpSmearsTheCage() {
		val model = latticeRigModel()
		val landing = brushLanding(model, stroke(), settings, listOf(ElementOwner.Deformer(LATTICE_RIG_BODY)))

		val cages = landing as BrushLanding.Cages
		assertEquals(listOf(LATTICE_RIG_BODY), cages.warpIds, "the brush smeared the cage")
		val moved = cages.world.getValue(LATTICE_RIG_BODY)
		val before = warpLatticeAt(model, emptyMap(), LATTICE_RIG_BODY)!!.world
		assertTrue(moved[0] != before[0], "the cage's corner moved")
	}
}