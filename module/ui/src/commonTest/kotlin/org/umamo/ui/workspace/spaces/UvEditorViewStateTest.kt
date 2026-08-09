package org.umamo.ui.workspace.spaces

import org.umamo.edit.EditorMode
import org.umamo.edit.MeshSelection
import org.umamo.edit.MeshTopology
import org.umamo.edit.Selection
import org.umamo.edit.SelectionTarget
import org.umamo.render.DecodedImage
import org.umamo.render.PuppetTextures
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.Part
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.PuppetModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the UV editor's pure view-state derivations ([resolveUvEditorPage], [shownUvDrawables],
 * [uvGizmoGeometries]): the active-drawable precedence chain, the untextured 1x1 fallback, the
 * Edit / Object candidate rules with the malformed-mesh and page filters, and the display-space
 * projection (texel units, v-flip).
 *
 * Two atlas pages are used throughout: page 0 is 64x32 (drawable "a"), page 1 is 16x16 (drawable
 * "b"); drawable "c" is unmeshed.
 */
class UvEditorViewStateTest {
	/** The default triangle's uvs: display (16, 16), (48, 16), (16, 24) on the 64x32 page. */
	private val triangleUvs = floatArrayOf(0.25f, 0.5f, 0.75f, 0.5f, 0.25f, 0.25f)

	private fun meshedDrawable(rawId: String, uvs: FloatArray = triangleUvs.copyOf(), isVisible: Boolean = true): Drawable =
		Drawable(
			id = DrawableId(rawId),
			name = rawId,
			parentDeformerId = null,
			blendMode = BlendMode.Normal,
			maskedBy = emptyList(),
			mesh = DrawableMesh(floatArrayOf(0f, 0f, 2f, 0f, 0f, 2f), uvs, intArrayOf(0, 1, 2)),
			geometryGrid = null,
			isVisible = isVisible,
		)

	private fun bareDrawable(rawId: String): Drawable =
		Drawable(
			id = DrawableId(rawId),
			name = rawId,
			parentDeformerId = null,
			blendMode = BlendMode.Normal,
			maskedBy = emptyList(),
			mesh = null,
			geometryGrid = null,
		)

	// Every drawable gets a root org-tree entry: visibleDrawableIds walks rootChildren, so a drawable
	// absent from the tree would read as hidden.
	private fun modelOf(vararg drawables: Drawable): PuppetModel =
		PuppetModel(
			parameters = emptyList(),
			parts = emptyList(),
			deformers = emptyList(),
			drawables = drawables.toList(),
			rootChildren = drawables.map { drawable -> OrgChild.Drawable(drawable.id) },
			rootPartId = null,
		)

	private fun twoPageTextures(): PuppetTextures =
		PuppetTextures(
			atlases = listOf(DecodedImage(ByteArray(64 * 32 * 4), 64, 32), DecodedImage(ByteArray(16 * 16 * 4), 16, 16)),
			atlasIndexByDrawableId = mapOf("a" to 0, "a2" to 0, "b" to 1),
			premultipliedAlpha = false,
		)

	/** The mesh selection's active drawable outranks the object selection's. */
	@Test
	fun meshSelectionActiveDrawableWins() {
		val model = modelOf(meshedDrawable("a"), meshedDrawable("b"))
		val resolved =
			resolveUvEditorPage(
				model = model,
				meshSelection = MeshSelection(drawableIds = listOf(DrawableId("b")), activeDrawableId = DrawableId("b")),
				objectSelection = Selection(setOf(SelectionTarget.Drawable(DrawableId("a"))), SelectionTarget.Drawable(DrawableId("a"))),
				textures = twoPageTextures(),
			)
		assertNotNull(resolved, "a meshed active drawable resolves a page")
		assertEquals(DrawableId("b"), resolved.activeDrawable.id, "the Edit-mode active mesh wins")
		assertEquals(1, resolved.pageIndex, "the page follows the winner")
		assertEquals(16, resolved.pageWidth, "page 1's width")
		assertEquals(16, resolved.pageHeight, "page 1's height")
	}

	/** With no mesh-selection active drawable, the object selection's active drawable drives the page. */
	@Test
	fun objectSelectionActiveDrawableIsSecond() {
		val model = modelOf(meshedDrawable("a"), meshedDrawable("b"))
		val resolved =
			resolveUvEditorPage(
				model = model,
				meshSelection = MeshSelection(),
				objectSelection = Selection(setOf(SelectionTarget.Drawable(DrawableId("b"))), SelectionTarget.Drawable(DrawableId("b"))),
				textures = twoPageTextures(),
			)
		assertNotNull(resolved, "an object-selected meshed drawable resolves a page")
		assertEquals(DrawableId("b"), resolved.activeDrawable.id, "the object selection's active drawable is the second choice")
	}

	/** With nothing selected at all, the first MESHED drawable drives the page (unmeshed ones skipped). */
	@Test
	fun fallsBackToTheFirstMeshedDrawable() {
		val model = modelOf(bareDrawable("c"), meshedDrawable("a"), meshedDrawable("b"))
		val resolved =
			resolveUvEditorPage(model = model, meshSelection = MeshSelection(), objectSelection = Selection(), textures = twoPageTextures())
		assertNotNull(resolved, "the fallback keeps the space non-blank")
		assertEquals(DrawableId("a"), resolved.activeDrawable.id, "the first meshed drawable is the fallback")
		assertEquals(0, resolved.pageIndex, "the fallback's page shows")
	}

	/** With no meshed drawable anywhere the resolution is null (the space shows its placeholder). */
	@Test
	fun resolvesNullWithNothingMeshed() {
		assertNull(
			resolveUvEditorPage(model = modelOf(bareDrawable("c")), meshSelection = MeshSelection(), objectSelection = Selection(), textures = twoPageTextures()),
			"no meshed drawable means no page",
		)
	}

	/** A meshed drawable absent from the atlas map resolves the untextured 1x1 fallback page. */
	@Test
	fun untexturedDrawableGetsTheUnitPage() {
		val model = modelOf(meshedDrawable("loose"))
		val resolved =
			resolveUvEditorPage(model = model, meshSelection = MeshSelection(), objectSelection = Selection(), textures = twoPageTextures())
		assertNotNull(resolved, "an untextured drawable still resolves")
		assertNull(resolved.pageIndex, "no atlas entry means no page index")
		assertEquals(1, resolved.pageWidth, "the 1x1 fallback turns the display mapping into the unit square")
		assertEquals(1, resolved.pageHeight, "the 1x1 fallback turns the display mapping into the unit square")
	}

	/** Edit mode lists the session's meshes mapped to the shown page; other pages' meshes are excluded. */
	@Test
	fun editModeListsTheSessionMeshesOnThePage() {
		val model = modelOf(meshedDrawable("a"), meshedDrawable("b"))
		val shown =
			shownUvDrawables(
				model = model,
				mode = EditorMode.Edit,
				meshSelection = MeshSelection(drawableIds = listOf(DrawableId("a"), DrawableId("b")), activeDrawableId = DrawableId("a")),
				textures = twoPageTextures(),
				pageIndex = 0,
			)
		assertEquals(listOf(DrawableId("a")), shown.map { drawable -> drawable.id }, "only the shown page's meshes draw")
	}

	/** Object mode lists EVERY visible meshed drawable mapped to the shown page, in model order. */
	@Test
	fun objectModeListsEveryVisibleMeshOnThePage() {
		val model = modelOf(meshedDrawable("a"), meshedDrawable("b"), meshedDrawable("a2"))
		val shown =
			shownUvDrawables(
				model = model,
				mode = EditorMode.Object,
				meshSelection = MeshSelection(),
				textures = twoPageTextures(),
				pageIndex = 0,
			)
		assertEquals(
			listOf(DrawableId("a"), DrawableId("a2")),
			shown.map { drawable -> drawable.id },
			"every visible page-0 mesh draws, in model order, selection playing no part",
		)
	}

	/** Object mode excludes hidden islands: the drawable's own eyeball and a hidden ancestor part alike. */
	@Test
	fun objectModeExcludesHiddenIslands() {
		val visible = meshedDrawable("a")
		val hiddenSelf = meshedDrawable("hiddenSelf", isVisible = false)
		val underHiddenPart = meshedDrawable("underHiddenPart")
		val hiddenPart =
			Part(
				id = PartId("hiddenPart"),
				name = "hiddenPart",
				children = listOf(OrgChild.Drawable(underHiddenPart.id)),
				isVisible = false,
			)
		val model =
			PuppetModel(
				parameters = emptyList(),
				parts = listOf(hiddenPart),
				deformers = emptyList(),
				drawables = listOf(visible, hiddenSelf, underHiddenPart),
				rootChildren =
					listOf(
						OrgChild.Drawable(visible.id),
						OrgChild.Drawable(hiddenSelf.id),
						OrgChild.Part(hiddenPart.id),
					),
				rootPartId = null,
			)
		val shown =
			shownUvDrawables(
				model = model,
				mode = EditorMode.Object,
				meshSelection = MeshSelection(),
				// Null textures reduce the filter to visibility alone (no page filter).
				textures = null,
				pageIndex = null,
			)
		assertEquals(listOf(DrawableId("a")), shown.map { drawable -> drawable.id }, "hidden islands neither draw nor pick")
	}

	/** Meshes with an empty or size-mismatched UV array are excluded everywhere. */
	@Test
	fun excludesMalformedMeshes() {
		val emptyUvs = meshedDrawable("emptyUvs", uvs = FloatArray(0))
		val mismatched = meshedDrawable("mismatched", uvs = floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f))
		val model = modelOf(emptyUvs, mismatched, meshedDrawable("a"))
		val shown =
			shownUvDrawables(
				model = model,
				mode = EditorMode.Edit,
				meshSelection =
					MeshSelection(
						drawableIds = listOf(DrawableId("emptyUvs"), DrawableId("mismatched"), DrawableId("a")),
						activeDrawableId = DrawableId("a"),
					),
				// Null textures reduce the filter to the UV-shape checks alone (no page filter).
				textures = null,
				pageIndex = null,
			)
		assertEquals(listOf(DrawableId("a")), shown.map { drawable -> drawable.id }, "only the well-formed mesh survives")
	}

	/** The projection is the texel display mapping with the v-flip; indices and edges carry over. */
	@Test
	fun projectsUvsIntoDisplaySpace() {
		val drawable = meshedDrawable("a")
		val geometries = uvGizmoGeometries(listOf(drawable), pageWidth = 64, pageHeight = 32)
		assertEquals(1, geometries.size, "one geometry per meshed drawable")
		val geometry = geometries.first()
		assertEquals(DrawableId("a"), geometry.drawableId, "the geometry names its drawable")
		// uv (0.25, 0.5) -> (16, 16), (0.75, 0.5) -> (48, 16), (0.25, 0.25) -> (16, 24): u scales by the
		// width, v flips then scales by the height.
		val expectedPositions = floatArrayOf(16f, 16f, 48f, 16f, 16f, 24f)
		for (componentIndex in expectedPositions.indices) {
			assertEquals(expectedPositions[componentIndex], geometry.positions[componentIndex], 1e-4f, "display component $componentIndex")
		}
		assertEquals(MeshTopology.uniqueEdges(intArrayOf(0, 1, 2)), geometry.edges, "edges derive from the triangle indices")
	}

	/** Unmeshed drawables project no geometry. */
	@Test
	fun skipsUnmeshedDrawables() {
		assertTrue(
			uvGizmoGeometries(listOf(bareDrawable("c")), pageWidth = 64, pageHeight = 32).isEmpty(),
			"a drawable with no mesh yields no geometry",
		)
	}
}