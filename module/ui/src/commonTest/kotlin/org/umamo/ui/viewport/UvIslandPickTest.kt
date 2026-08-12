package org.umamo.ui.viewport

import androidx.compose.ui.unit.IntSize
import org.umamo.edit.Selection
import org.umamo.edit.SelectionTarget
import org.umamo.render.DecodedImage
import org.umamo.render.ViewportCamera
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.RenderDrawable
import org.umamo.runtime.model.RenderGroup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the UV editor's island-pick adapters (UvIslandPick.kt): the static rest-pose front rank, the
 * page alpha sampler, the alpha-gated front-most / stack picks over display-space islands, the
 * any-vertex box rule, and the box-selection decision table.  The shared pick internals
 * (pickDrawable / pickAllDrawables) are :render-tested; these tests cover OUR bindings of them.
 *
 * The pick scene: two islands sharing one display-space triangle (0,4)-(4,4)-(0,0) on a 4x4 page
 * whose texels are all opaque except (1, 1).  The islands differ only in their stored uvs, so the
 * alpha gate samples a different texel per island at the same click.
 */
class UvIslandPickTest {
	private val frontId = DrawableId("front")
	private val backId = DrawableId("back")

	/** The shared display triangle: one island's on-page footprint. */
	private val displayTriangle = floatArrayOf(0f, 4f, 4f, 4f, 0f, 0f)

	/** Uvs whose barycentric blend at display (1, 3) lands on texel (1, 1) - the transparent one. */
	private val uvsHittingTransparentTexel = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f)

	/** Uvs pinned to uv (0, 0) - texel (0, 0), opaque - regardless of where the click lands. */
	private val uvsHittingOpaqueTexel = floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f)

	/** A 4x4 page, fully opaque except texel (1, 1). */
	private fun fourByFourPage(): DecodedImage {
		val rgba = ByteArray(4 * 4 * 4) { byteIndex -> if (byteIndex % 4 == 3) 0xFF.toByte() else 0 }
		rgba[(1 * 4 + 1) * 4 + 3] = 0
		return DecodedImage(rgba, 4, 4)
	}

	private fun stackedIslandsPick(frontUvs: FloatArray, backUvs: FloatArray): UvIslandPick =
		UvIslandPick(
			displayPositionsById = mapOf(frontId to displayTriangle, backId to displayTriangle),
			indicesById = mapOf(frontId to intArrayOf(0, 1, 2), backId to intArrayOf(0, 1, 2)),
			meshUvsById = mapOf(frontId to frontUvs, backId to backUvs),
			frontRankById = mapOf(frontId to 1f, backId to 0f),
			sampleAlpha = pageAlphaSampler(fourByFourPage()),
			atlasSizeOf = { 4 to 4 },
		)

	private fun rankDrawable(rawId: String, drawOrder: Float): Drawable =
		Drawable(
			id = DrawableId(rawId),
			name = rawId,
			parentDeformerId = null,
			blendMode = BlendMode.Normal,
			maskedBy = emptyList(),
			mesh = null,
			geometryGrid = null,
			drawOrder = drawOrder,
		)

	/** A grouped model ranks by the render-order group sort: the higher draw order ranks more front. */
	@Test
	fun restFrontRankFollowsTheGroupedRenderOrder() {
		val higher = rankDrawable("higher", drawOrder = 600f)
		val lower = rankDrawable("lower", drawOrder = 400f)
		val model =
			PuppetModel(
				parameters = emptyList(),
				parts = emptyList(),
				deformers = emptyList(),
				drawables = listOf(higher, lower),
				rootChildren = emptyList(),
				rootPartId = null,
				renderRoot = RenderGroup(null, 500, listOf(RenderDrawable(higher.id), RenderDrawable(lower.id))),
			)
		val rank = restFrontRank(model)
		assertEquals(0f, rank[lower.id], "the lower draw order paints first (back)")
		assertEquals(1f, rank[higher.id], "the higher draw order paints last (front)")
	}

	/** A renderRoot-less model takes the flat paintOrder fallback; ties keep model order. */
	@Test
	fun restFrontRankFallsBackToPaintOrderWithoutAGroupTree() {
		val front = rankDrawable("front", drawOrder = 600f)
		val backFirst = rankDrawable("backFirst", drawOrder = 500f)
		val backSecond = rankDrawable("backSecond", drawOrder = 500f)
		val model =
			PuppetModel(
				parameters = emptyList(),
				parts = emptyList(),
				deformers = emptyList(),
				drawables = listOf(front, backFirst, backSecond),
				rootChildren = emptyList(),
				rootPartId = null,
			)
		val rank = restFrontRank(model)
		assertEquals(2f, rank[front.id], "the higher draw order ranks most front")
		assertEquals(0f, rank[backFirst.id], "tied draw orders keep model order")
		assertEquals(1f, rank[backSecond.id], "tied draw orders keep model order")
	}

	/** The sampler reads the alpha byte at the uv's texel; out-of-range coordinates clamp to the edge. */
	@Test
	fun pageAlphaSamplerReadsTexelsAndClamps() {
		// A 2x1 page: texel (0, 0) opaque, texel (1, 0) transparent.
		val rgba = byteArrayOf(0, 0, 0, 0xFF.toByte(), 0, 0, 0, 0)
		val sampler = pageAlphaSampler(DecodedImage(rgba, 2, 1))
		assertEquals(1f, sampler(frontId, 0.25f, 0.5f), "the opaque texel samples 1")
		assertEquals(0f, sampler(frontId, 0.75f, 0.5f), "the transparent texel samples 0")
		assertEquals(0f, sampler(frontId, 1.5f, 0.5f), "past the right edge clamps to the edge texel")
		assertEquals(1f, sampler(frontId, -0.5f, 0.5f), "past the left edge clamps to the edge texel")
	}

	/** A null page (the untextured 1x1 fallback) samples fully opaque, so untextured islands stay clickable. */
	@Test
	fun pageAlphaSamplerTreatsANullPageAsOpaque() {
		assertEquals(1f, pageAlphaSampler(null)(frontId, 0.5f, 0.5f), "no page means no alpha gate")
	}

	/** With both islands opaque at the click, the higher front rank wins. */
	@Test
	fun topmostPickResolvesByFrontRank() {
		val pick = stackedIslandsPick(frontUvs = uvsHittingOpaqueTexel, backUvs = uvsHittingOpaqueTexel)
		assertEquals(frontId, pick.topmostAt(1f, 3f), "the front island takes the click")
	}

	/** A click on the front island's transparent texel falls through to the opaque island beneath. */
	@Test
	fun transparentOverhangFallsThroughToTheIslandBeneath() {
		val pick = stackedIslandsPick(frontUvs = uvsHittingTransparentTexel, backUvs = uvsHittingOpaqueTexel)
		assertEquals(backId, pick.topmostAt(1f, 3f), "the alpha gate rejects the front island")
	}

	/** A click outside every island misses. */
	@Test
	fun topmostPickMissesOutsideEveryIsland() {
		val pick = stackedIslandsPick(frontUvs = uvsHittingOpaqueTexel, backUvs = uvsHittingOpaqueTexel)
		assertNull(pick.topmostAt(3.5f, 0.5f), "below the hypotenuse nothing is hit")
	}

	/** The stack query lists every opaque island under the click, front-to-back. */
	@Test
	fun stackListsOpaqueIslandsFrontToBack() {
		val bothOpaque = stackedIslandsPick(frontUvs = uvsHittingOpaqueTexel, backUvs = uvsHittingOpaqueTexel)
		assertEquals(listOf(frontId, backId), bothOpaque.stackAt(1f, 3f).map { candidate -> candidate.id }, "front first")
		val frontTransparent = stackedIslandsPick(frontUvs = uvsHittingTransparentTexel, backUvs = uvsHittingOpaqueTexel)
		assertEquals(listOf(backId), frontTransparent.stackAt(1f, 3f).map { candidate -> candidate.id }, "the alpha gate filters the stack too")
	}

	/** An island is box-enclosed when ANY of its vertices falls inside; result keeps geometries order. */
	@Test
	fun boxEnclosesIslandsByAnyVertex() {
		val camera = ViewportCamera(centerX = 0f, centerY = 0f, zoom = 1f)
		val size = IntSize(200, 200)
		val nearIsland = GizmoMeshGeometry(DrawableId("near"), intArrayOf(0, 1, 2), emptyList(), floatArrayOf(0f, 0f, 10f, 0f, 0f, 10f))
		val farIsland = GizmoMeshGeometry(DrawableId("far"), intArrayOf(0, 1, 2), emptyList(), floatArrayOf(80f, 80f, 90f, 80f, 80f, 90f))
		// A box around the near island's (0, 10) vertex alone: one enclosed vertex takes the island.
		val cornerA = worldToScreen(-1f, 11f, camera, size)
		val cornerB = worldToScreen(1f, 9f, camera, size)
		assertEquals(
			listOf(DrawableId("near")),
			uvIslandsInBox(listOf(farIsland, nearIsland), cornerA, cornerB, camera, size),
			"one enclosed vertex selects the island; the far island stays out",
		)
		// A box around everything keeps the geometries' list order.
		val allCornerA = worldToScreen(-5f, 95f, camera, size)
		val allCornerB = worldToScreen(95f, -5f, camera, size)
		assertEquals(
			listOf(DrawableId("far"), DrawableId("near")),
			uvIslandsInBox(listOf(farIsland, nearIsland), allCornerA, allCornerB, camera, size),
			"the result preserves geometries order",
		)
	}

	/** Plain box replaces; additive extends with the last enclosed island active. */
	@Test
	fun boxSelectionReplacesOrExtends() {
		val islandA = SelectionTarget.Drawable(DrawableId("a"))
		val islandB = SelectionTarget.Drawable(DrawableId("b"))
		val replaced = resolveIslandBoxSelection(Selection(setOf(islandA), islandA), listOf(islandB), additive = false)
		assertEquals(setOf<SelectionTarget>(islandB), replaced.targets, "a plain box replaces the selection")
		assertEquals(islandB, replaced.active, "the last enclosed island becomes active")
		val extended = resolveIslandBoxSelection(Selection(setOf(islandA), islandA), listOf(islandB), additive = true)
		assertEquals(setOf<SelectionTarget>(islandA, islandB), extended.targets, "an additive box keeps the current targets")
		assertEquals(islandB, extended.active, "the last enclosed island becomes active")
	}

	/** An additive box that enclosed nothing keeps the selection AND its active target. */
	@Test
	fun emptyAdditiveBoxKeepsTheSelection() {
		val islandA = SelectionTarget.Drawable(DrawableId("a"))
		val current = Selection(setOf(islandA), islandA)
		val result = resolveIslandBoxSelection(current, emptyList(), additive = true)
		assertEquals(current, result, "nothing enclosed changes nothing")
	}

	/** A plain box that enclosed nothing clears (the viewport's box rule). */
	@Test
	fun emptyPlainBoxClears() {
		val islandA = SelectionTarget.Drawable(DrawableId("a"))
		val result = resolveIslandBoxSelection(Selection(setOf(islandA), islandA), emptyList(), additive = false)
		assertTrue(result.isEmpty, "an empty plain box clears the selection")
		assertNull(result.active, "no active target survives")
	}

	/**
	 * The builder serves BOTH surfaces from one construction: it gates on whichever image the editor is
	 * showing, indexed by whichever mapping addresses it.  This is what lets one Object overlay cover
	 * the page view and the source-layer view - the alpha gate follows the surface, so a click through
	 * transparent art falls through on the artwork exactly as it does on the page.
	 */
	@Test
	fun builderGatesOnWhicheverImageIsShown() {
		val geometries =
			listOf(
				GizmoMeshGeometry(frontId, intArrayOf(0, 1, 2), emptyList(), displayTriangle),
				GizmoMeshGeometry(backId, intArrayOf(0, 1, 2), emptyList(), displayTriangle),
			)
		val frontRank = mapOf(frontId to 1f, backId to 0f)

		// Front island mapped onto the transparent texel, back island onto an opaque one: the gate must
		// reject the front and fall through, whatever the image happens to be.
		val gated =
			uvIslandPick(
				geometries = geometries,
				frontRank = frontRank,
				uvsById = mapOf(frontId to uvsHittingTransparentTexel, backId to uvsHittingOpaqueTexel),
				image = fourByFourPage(),
			)
		assertEquals(backId, gated.topmostAt(1f, 3f), "the shown image's alpha decides the pick")

		// The SAME geometry with both mapped onto opaque art: front rank decides instead.
		val ungated =
			uvIslandPick(
				geometries = geometries,
				frontRank = frontRank,
				uvsById = mapOf(frontId to uvsHittingOpaqueTexel, backId to uvsHittingOpaqueTexel),
				image = fourByFourPage(),
			)
		assertEquals(frontId, ungated.topmostAt(1f, 3f), "with no alpha rejection the front island wins")

		// No image at all (an untextured surface): everything reads opaque, so islands stay clickable.
		val imageless =
			uvIslandPick(
				geometries = geometries,
				frontRank = frontRank,
				uvsById = mapOf(frontId to uvsHittingTransparentTexel, backId to uvsHittingOpaqueTexel),
				image = null,
			)
		assertEquals(frontId, imageless.topmostAt(1f, 3f), "with no image there is nothing to gate on")
	}
}