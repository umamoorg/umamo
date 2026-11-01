package org.umamo.ui.viewport.gizmo

import org.umamo.edit.Selection
import org.umamo.edit.SelectionTarget
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.PuppetModel
import org.umamo.ui.viewport.ObjectPickCandidate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the object-pick decision tables ([resolveObjectClickSelection], [resolveAltOverlapPick]): the
 * Blender click semantics the 2D viewport and the UV editor's Object mode both ship verbatim, since
 * both feed the SAME session selection.
 */
class ObjectPickControllerTest {
	private val drawableA = SelectionTarget.Drawable(DrawableId("a"))
	private val drawableB = SelectionTarget.Drawable(DrawableId("b"))

	/** A plain click on a drawable replaces the whole selection with it. */
	@Test
	fun plainClickOnADrawableReplacesTheSelection() {
		val result = resolveObjectClickSelection(Selection(setOf(drawableA), drawableA), drawableB, toggleMembership = false)
		assertEquals(setOf<SelectionTarget>(drawableB), result.targets, "the hit replaces the previous selection")
		assertEquals(drawableB, result.active, "the hit becomes active")
	}

	/** A modified click on an unselected drawable toggles it into the selection and makes it active. */
	@Test
	fun modifiedClickTogglesAnUnselectedDrawableIn() {
		val result = resolveObjectClickSelection(Selection(setOf(drawableA), drawableA), drawableB, toggleMembership = true)
		assertEquals(setOf<SelectionTarget>(drawableA, drawableB), result.targets, "the hit joins the selection")
		assertEquals(drawableB, result.active, "the newly added target becomes active")
	}

	/** A second modified click on a selected drawable toggles it back out (Blender parity). */
	@Test
	fun modifiedClickTogglesASelectedDrawableOut() {
		val result =
			resolveObjectClickSelection(Selection(setOf(drawableA, drawableB), drawableB), drawableB, toggleMembership = true)
		assertEquals(setOf<SelectionTarget>(drawableA), result.targets, "the hit leaves the selection")
		assertEquals(drawableA, result.active, "the active target falls back to a remaining member")
	}

	/** A plain click on empty canvas clears the selection. */
	@Test
	fun plainClickOnEmptyCanvasClears() {
		val result = resolveObjectClickSelection(Selection(setOf(drawableA), drawableA), target = null, toggleMembership = false)
		assertTrue(result.isEmpty, "a plain empty-canvas click clears")
		assertNull(result.active, "no active target survives a clear")
	}

	/** A modified click on empty canvas keeps the selection untouched, active target included. */
	@Test
	fun modifiedClickOnEmptyCanvasKeepsTheSelection() {
		val current = Selection(setOf(drawableA, drawableB), drawableB)
		val result = resolveObjectClickSelection(current, target = null, toggleMembership = true)
		assertEquals(current, result, "a modified empty-canvas click changes nothing")
	}

	/** An Alt click over empty canvas resolves to nothing - the selection stays as-is. */
	@Test
	fun altPickOverEmptyCanvasResolvesToNone() {
		assertIs<AltPickResolution.None>(resolveAltOverlapPick(emptyList()), "no candidates means no action")
	}

	/** An Alt click over exactly one candidate selects it directly, no picker involved. */
	@Test
	fun altPickOverOneCandidateSelectsDirectly() {
		val resolution = resolveAltOverlapPick(listOf(ObjectPickCandidate(SelectionTarget.Drawable(DrawableId("a")), centrality = 0.5f)))
		val direct = assertIs<AltPickResolution.SelectSingle>(resolution, "a single candidate skips the picker")
		assertEquals(SelectionTarget.Drawable(DrawableId("a")), direct.target, "the sole candidate is the pick")
	}

	/** An Alt click over a stack opens the overlap picker with the candidates in their given order. */
	@Test
	fun altPickOverAStackShowsTheOverlapPickerInOrder() {
		val candidates =
			listOf(
				ObjectPickCandidate(SelectionTarget.Deformer(DeformerId("lattice"))),
				ObjectPickCandidate(SelectionTarget.Drawable(DrawableId("front")), centrality = 0.2f),
				ObjectPickCandidate(SelectionTarget.Drawable(DrawableId("back")), centrality = 0.9f),
			)
		val resolution = resolveAltOverlapPick(candidates)
		val overlap = assertIs<AltPickResolution.ShowOverlap>(resolution, "a stack needs the picker")
		assertEquals(candidates, overlap.candidates, "the stack's order is preserved")
	}

	/** Plain box replaces; additive extends with the last enclosed drawable active. */
	@Test
	fun boxSelectionReplacesOrExtends() {
		val drawableA = SelectionTarget.Drawable(DrawableId("a"))
		val drawableB = SelectionTarget.Drawable(DrawableId("b"))
		val replaced = resolveObjectBoxSelection(Selection(setOf(drawableA), drawableA), listOf(drawableB), additive = false)
		assertEquals(setOf<SelectionTarget>(drawableB), replaced.targets, "a plain box replaces the selection")
		assertEquals(drawableB, replaced.active, "the last enclosed drawable becomes active")
		val extended = resolveObjectBoxSelection(Selection(setOf(drawableA), drawableA), listOf(drawableB), additive = true)
		assertEquals(setOf<SelectionTarget>(drawableA, drawableB), extended.targets, "an additive box keeps the current targets")
		assertEquals(drawableB, extended.active, "the last enclosed drawable becomes active")
	}

	/** An additive box that enclosed nothing keeps the selection AND its active target. */
	@Test
	fun emptyAdditiveBoxKeepsTheSelection() {
		val drawableA = SelectionTarget.Drawable(DrawableId("a"))
		val current = Selection(setOf(drawableA), drawableA)
		val result = resolveObjectBoxSelection(current, emptyList(), additive = true)
		assertEquals(current, result, "nothing enclosed changes nothing")
	}

	/** A plain box that enclosed nothing clears (the viewport's box rule). */
	@Test
	fun emptyPlainBoxClears() {
		val drawableA = SelectionTarget.Drawable(DrawableId("a"))
		val result = resolveObjectBoxSelection(Selection(setOf(drawableA), drawableA), emptyList(), additive = false)
		assertTrue(result.isEmpty, "an empty plain box clears the selection")
		assertNull(result.active, "no active target survives")
	}

	/** A region passes over what cannot be selected and keeps the order it enclosed the rest in. */
	@Test
	fun selectableTargetsSkipTheUnselectableInOrder() {
		val model =
			PuppetModel(
				parameters = emptyList(),
				parts = emptyList(),
				deformers = emptyList(),
				drawables = listOf(drawableNamed("a"), drawableNamed("b", selectable = false), drawableNamed("c")),
				rootChildren = listOf("a", "b", "c").map { raw -> OrgChild.Drawable(DrawableId(raw)) },
				rootPartId = null,
			)

		val targets = selectableDrawableTargets(listOf(DrawableId("c"), DrawableId("b"), DrawableId("a")), model)

		assertEquals(listOf(DrawableId("c"), DrawableId("a")), targets.map { target -> target.id })
	}

	/**
	 * A mesh-less drawable for the selectable filter.
	 *
	 * @param String raw The drawable id.
	 * @param Boolean selectable Whether it can be selected.
	 * @return Drawable The drawable.
	 */
	private fun drawableNamed(raw: String, selectable: Boolean = true): Drawable =
		Drawable(
			id = DrawableId(raw),
			name = raw,
			parentDeformerId = null,
			blendMode = BlendMode.Normal,
			maskedBy = emptyList(),
			mesh = null,
			geometryGrid = null,
			isSelectable = selectable,
		)
}