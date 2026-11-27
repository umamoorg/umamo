package org.umamo.ui.viewport.viewport2d

import org.umamo.edit.SelectionTarget
import org.umamo.render.eval.warpLatticeAt
import org.umamo.runtime.model.ElementOwner
import org.umamo.runtime.model.LatticeDepth
import org.umamo.runtime.model.WarpLatticeForm
import org.umamo.ui.transform.ElementEditStage
import org.umamo.ui.viewport.OverlaySurface
import org.umamo.ui.viewport.ViewportOverlayState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins the lattice geometry a click is picked over: its front-first view marks what faces away at the depth the
 * stage shows, its front-only view marks it hidden, its plain view marks nothing, and a lattice with no back shares
 * the plain view under every rule.  An area's rule follows how it draws the back.
 */
class EditLatticeGeometryTest {
	private val owner = ElementOwner.Deformer(LATTICE_RIG_BODY)
	private val pose = mapOf(LATTICE_RIG_PARAMETER to 0.5f)

	/**
	 * The body lattice's edit geometry at [pose] with [pending] standing in for its grid.
	 *
	 * @param WarpLatticeForm pending The pending form.
	 * @return EditLatticeGeometry The geometry.
	 */
	private fun geometryWith(pending: WarpLatticeForm): EditLatticeGeometry {
		val model = latticeRigSession(listOf(SelectionTarget.Deformer(LATTICE_RIG_BODY))).model.value

		return assertIs<EditLatticeGeometry>(editOwnerGeometries(model, listOf(owner), ElementEditStage.Posed(pose, mapOf(LATTICE_RIG_BODY to pending))).single())
	}

	/**
	 * The body lattice's grid points at [pose].
	 *
	 * @return FloatArray The points, in its parent's space.
	 */
	private fun grid(): FloatArray {
		val model = latticeRigSession(listOf(SelectionTarget.Deformer(LATTICE_RIG_BODY))).model.value

		return assertNotNull(warpLatticeAt(model, pose, LATTICE_RIG_BODY)).gridLocal
	}

	@Test
	fun aDeepMirroredLatticeMarksItsBackForTheFrontFirstPick() {
		val points = grid()
		val mirrored = FloatArray(points.size) { componentIndex -> if (componentIndex % 2 == 0) 40f - points[componentIndex] else points[componentIndex] }
		val geometry = geometryWith(WarpLatticeForm(mirrored, LatticeDepth.PerPoint(FloatArray(4) { 10f })))

		val back = assertNotNull(geometry.gizmoFor(BackFacingPick.FrontFirst).backFacing, "the front-first view marks the back")
		assertTrue(back.vertices.all { pointBack -> pointBack } && back.edges.all { edgeBack -> edgeBack } && back.faces.all { cellBack -> cellBack })
		assertFalse(back.hidden, "for a click to pass over, not to skip")
		assertTrue(assertNotNull(geometry.gizmoFor(BackFacingPick.FrontOnly).backFacing).hidden, "the front-only view marks it hidden")
		assertNull(geometry.gizmoFor(BackFacingPick.Plain).backFacing, "the plain view, an area that does not dim, marks nothing")
		assertSame(geometry.gizmo, geometry.gizmoFor(BackFacingPick.Plain))
	}

	@Test
	fun aLatticeWithNoBackSharesThePlainView() {
		val geometry = geometryWith(WarpLatticeForm(grid(), LatticeDepth.PerPoint(FloatArray(4) { 10f })))

		assertSame(geometry.gizmo, geometry.gizmoFor(BackFacingPick.FrontFirst))
		assertSame(geometry.gizmo, geometry.gizmoFor(BackFacingPick.FrontOnly))
	}

	@Test
	fun anAreasRuleFollowsHowItDrawsTheBack() {
		val state = ViewportOverlayState(OverlaySurface.Viewport2D)
		assertEquals(BackFacingPick.FrontFirst, state.backFacingPick(), "a fresh area dims the back")
		assertEquals(BackFacingPick.FrontFirst, (null as ViewportOverlayState?).backFacingPick(), "and so does no area at all")

		state.backFacingOpacity = 0f
		assertEquals(BackFacingPick.FrontOnly, state.backFacingPick(), "a back dimmed to nothing never picks")

		state.dimBackFacing = false
		assertEquals(BackFacingPick.Plain, state.backFacingPick(), "an area that does not dim picks the back as the rest")

		state.dimBackFacing = true
		state.showOverlays = false
		assertEquals(BackFacingPick.Plain, state.backFacingPick(), "nor does one whose overlays are off")
	}
}