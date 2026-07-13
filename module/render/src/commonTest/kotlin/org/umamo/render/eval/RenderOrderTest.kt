package org.umamo.render.eval

import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.RenderDrawable
import org.umamo.runtime.model.RenderGroup
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Verifies [paintOrder]: drawOrder is the primary key, the parts-tree base order breaks ties. This is
 * the unit-test stand-in for the official editor (which we can't re-save under its EULA) - the
 * Layer68→600 case is `higherDrawOrderPaintsInFront`.
 */
class RenderOrderTest {
	private fun id(name: String) = DrawableId(name)

	@Test
	fun equalDrawOrderKeepsPartsTreeBaseOrder() {
		val base = listOf(id("a"), id("b"), id("c"))
		val order = paintOrder(base, base.associateWith { 500f })
		assertEquals(base, order)
	}

	@Test
	fun higherDrawOrderPaintsInFront() {
		// Layer68 → 600: a backmost drawable raised above the default 500s paints last (frontmost),
		// regardless of which part it lives in.
		val base = listOf(id("a"), id("b"), id("c"))
		val order = paintOrder(base, mapOf(id("a") to 600f, id("b") to 500f, id("c") to 500f))
		assertEquals(listOf(id("b"), id("c"), id("a")), order)
	}

	@Test
	fun lowerDrawOrderPaintsBehind() {
		val base = listOf(id("a"), id("b"), id("c"))
		val order = paintOrder(base, mapOf(id("a") to 500f, id("b") to 500f, id("c") to 400f))
		assertEquals(listOf(id("c"), id("a"), id("b")), order)
	}

	@Test
	fun missingDrawOrderTreatedAsCubismDefault() {
		val base = listOf(id("a"), id("b"))
		// b has no entry → CUBISM_DEFAULT_DRAW_ORDER (500) < a's 600, so b stays behind a.
		val order = paintOrder(base, mapOf(id("a") to 600f))
		assertEquals(listOf(id("b"), id("a")), order)
	}

	@Test
	fun subIntegerDrawOrderNoiseDoesNotReorder() {
		// The keyform blend `Σ wᵢ·500` lands just off 500.0 due to float-summation noise, so a
		// raw-float sort reshuffles drawables that should be tied. Quantising `+0.001`→int collapses
		// the noise to a true 500 tie, preserving the parts-tree base order. Without quantising,
		// `sortedBy` would order c (499.99997) < a (500.0) < b (500.00003) and lift c in front of the rest.
		val base = listOf(id("a"), id("b"), id("c"))
		val order = paintOrder(base, mapOf(id("a") to 500f, id("b") to 500.00003f, id("c") to 499.99997f))
		assertEquals(base, order)
	}

	@Test
	fun fractionalDrawOrderFloorsLikeCubism() {
		// Cubism floors `(int)(0.001 + order)`, so 500.6 and 500.9 are still 500 (tie → base order kept),
		// and only a full integer step (502) actually moves a drawable.
		val base = listOf(id("a"), id("b"), id("c"))
		val order = paintOrder(base, mapOf(id("a") to 500.6f, id("b") to 500.9f, id("c") to 502f))
		assertEquals(listOf(id("a"), id("b"), id("c")), order)
	}

	@Test
	fun drawOrderGroupConfinesChildrenToTheGroupSlot() {
		// An "eye" group (part draw order 600) holds two eye drawables; the root also holds
		// back/mid/front drawables. A flat global sort would slot mid (550) BETWEEN eyeLow (510) and
		// eyeHigh (590); the group must keep the eyes contiguous at the group's 600 slot, so mid stays
		// before the whole group. Within the group the eyes still sort by their own order (510 < 590).
		val root =
			RenderGroup(
				partId = null,
				drawOrder = 500,
				children =
					listOf(
						RenderDrawable(id("back")),
						RenderDrawable(id("mid")),
						RenderGroup(null, 600, listOf(RenderDrawable(id("eyeHigh")), RenderDrawable(id("eyeLow")))),
						RenderDrawable(id("front")),
					),
			)
		val drawOrders =
			mapOf(
				id("back") to 100f,
				id("mid") to 550f,
				id("eyeLow") to 510f,
				id("eyeHigh") to 590f,
				id("front") to 900f,
			)
		val order = renderOrder(root, drawOrders)
		assertEquals(listOf(id("back"), id("mid"), id("eyeLow"), id("eyeHigh"), id("front")), order)

		// A flat sort would interleave mid between the eyes - the divergence the group fixes.
		val flat = paintOrder(order, drawOrders).map { it.raw }
		assertEquals(listOf("back", "eyeLow", "mid", "eyeHigh", "front"), flat)
	}

	@Test
	fun flatGroupMatchesGlobalPaintOrder() {
		// A group-less model is one flat RenderGroup, so renderOrder reduces exactly to paintOrder.
		val ids = listOf(id("a"), id("b"), id("c"))
		val root = RenderGroup(null, 500, ids.map { RenderDrawable(it) })
		val drawOrders = mapOf(id("a") to 600f, id("b") to 500f, id("c") to 500f)
		assertEquals(paintOrder(ids, drawOrders), renderOrder(root, drawOrders))
	}

	@Test
	fun animatedPartDrawOrderMovesTheWholeGroup() {
		// A sub-group's sort key is its (parameter-driven) PART draw order, so the whole arm set swaps
		// front/back as the arm parameter moves it 400↔700, without re-sorting its members.
		val root =
			RenderGroup(
				partId = null,
				drawOrder = 500,
				children =
					listOf(
						RenderDrawable(id("back")),
						RenderGroup(PartId("arm"), 500, listOf(RenderDrawable(id("armA")), RenderDrawable(id("armB")))),
						RenderDrawable(id("front")),
					),
			)
		val drawOrders = mapOf(id("back") to 100f, id("armA") to 500f, id("armB") to 500f, id("front") to 900f)

		// Part order 300 → the arm group sits behind front (900); 950 → it jumps in front of everything.
		val behind = renderOrder(root, drawOrders, mapOf(PartId("arm") to 300f))
		assertEquals(listOf(id("back"), id("armA"), id("armB"), id("front")), behind)
		val front = renderOrder(root, drawOrders, mapOf(PartId("arm") to 950f))
		assertEquals(listOf(id("back"), id("front"), id("armA"), id("armB")), front)
	}
}
