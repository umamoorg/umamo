package org.umamo.ui.viewport

import org.umamo.edit.Selection
import org.umamo.edit.SelectionTarget
import org.umamo.render.pick.PickCandidate
import org.umamo.runtime.model.DrawableId
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
		val resolution = resolveAltOverlapPick(listOf(PickCandidate(DrawableId("a"), frontRank = 1f, centrality = 0.5f)))
		val direct = assertIs<AltPickResolution.SelectSingle>(resolution, "a single candidate skips the picker")
		assertEquals(DrawableId("a"), direct.id, "the sole candidate is the pick")
	}

	/** An Alt click over a stack opens the overlap picker with the candidates in their given order. */
	@Test
	fun altPickOverAStackShowsTheOverlapPickerInOrder() {
		val candidates =
			listOf(
				PickCandidate(DrawableId("front"), frontRank = 2f, centrality = 0.2f),
				PickCandidate(DrawableId("back"), frontRank = 1f, centrality = 0.9f),
			)
		val resolution = resolveAltOverlapPick(candidates)
		val overlap = assertIs<AltPickResolution.ShowOverlap>(resolution, "two candidates need the picker")
		assertEquals(candidates, overlap.candidates, "the front-to-back order is preserved")
	}
}