package org.umamo.ui.properties

import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the pure candidate set behind the composite mask editor's add menu: the drawables not already
 * masking, in model order, with the empty-current and empty-model edges.
 */
class MaskCandidatesTest {
	private fun drawable(id: String): Drawable =
		Drawable(
			id = DrawableId(id),
			name = id,
			parentDeformerId = null,
			blendMode = BlendMode.Normal,
			maskedBy = emptyList(),
			mesh = null,
			keyforms = null,
		)

	@Test
	fun excludesAlreadyAppliedMasksPreservingOrder() {
		val candidates = maskCandidates(listOf(drawable("a"), drawable("b"), drawable("c")), listOf(DrawableId("b")))
		assertEquals(listOf(DrawableId("a"), DrawableId("c")), candidates.map { it.id })
	}

	@Test
	fun emptyCurrentReturnsAllAndEmptyModelReturnsNone() {
		assertEquals(listOf(DrawableId("a")), maskCandidates(listOf(drawable("a")), emptyList()).map { it.id })
		assertTrue(maskCandidates(emptyList(), listOf(DrawableId("a"))).isEmpty())
	}
}
