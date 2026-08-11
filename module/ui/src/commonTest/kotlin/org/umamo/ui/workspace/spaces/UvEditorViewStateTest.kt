package org.umamo.ui.workspace.spaces

import org.umamo.edit.EditorMode
import org.umamo.edit.MeshSelection
import org.umamo.edit.MeshTopology
import org.umamo.edit.Selection
import org.umamo.edit.SelectionTarget
import org.umamo.edit.UvPageKind
import org.umamo.render.AtlasPlacement
import org.umamo.render.DecodedImage
import org.umamo.render.DrawableLayerBinding
import org.umamo.render.LayerTextures
import org.umamo.render.PuppetTextures
import org.umamo.render.SourceLayerEntry
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
 * Pins the UV editor's pure view-state derivations ([resolveUvEditorPage], [uvPageSelectionAfter],
 * [shownUvDrawables], [uvGizmoGeometries]): the pin-first page resolution with its follow-chain
 * fallback, the active-drawable precedence chain, the untextured 1x1 fallback, the page-cycle
 * transition, the Edit / Object candidate rules with the malformed-mesh and page filters, and the
 * display-space projection (texel units, v-flip).
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
		assertEquals(1, resolved.pageIndex, "the page follows the Edit-mode active mesh")
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
		assertEquals(1, resolved.pageIndex, "the object selection's active drawable is the second choice")
	}

	/** With nothing selected at all, the first MESHED drawable drives the page (unmeshed ones skipped). */
	@Test
	fun fallsBackToTheFirstMeshedDrawable() {
		val model = modelOf(bareDrawable("c"), meshedDrawable("a"), meshedDrawable("b"))
		val resolved =
			resolveUvEditorPage(model = model, meshSelection = MeshSelection(), objectSelection = Selection(), textures = twoPageTextures())
		assertNotNull(resolved, "the fallback keeps the space non-blank")
		assertEquals(0, resolved.pageIndex, "the first meshed drawable's page is the fallback")
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

	/** A pin the textures can satisfy beats the whole follow chain. */
	@Test
	fun validPinShowsThePinnedPageFirst() {
		val model = modelOf(meshedDrawable("a"), meshedDrawable("b"))
		val resolved =
			resolveUvEditorPage(
				model = model,
				meshSelection = MeshSelection(drawableIds = listOf(DrawableId("a")), activeDrawableId = DrawableId("a")),
				objectSelection = Selection(),
				textures = twoPageTextures(),
				textureSelection = UvTextureSelection.PinnedPage(1),
			)
		assertNotNull(resolved, "a valid pin resolves a page")
		assertEquals(1, resolved.pageIndex, "the pin beats the active drawable's page 0")
		assertEquals(16, resolved.pageWidth, "the pinned page's width")
		assertEquals(16, resolved.pageHeight, "the pinned page's height")
	}

	/** A valid pin needs no meshed drawable: the empty page is still reviewable. */
	@Test
	fun pinnedPageResolvesWithNoMeshedDrawable() {
		val resolved =
			resolveUvEditorPage(
				model = modelOf(bareDrawable("c")),
				meshSelection = MeshSelection(),
				objectSelection = Selection(),
				textures = twoPageTextures(),
				textureSelection = UvTextureSelection.PinnedPage(0),
			)
		assertNotNull(resolved, "the pinned page shows even with nothing meshed (contrast resolvesNullWithNothingMeshed)")
		assertEquals(0, resolved.pageIndex, "the pinned page shows")
		assertEquals(64, resolved.pageWidth, "the pinned page's width")
	}

	/** A pin the textures cannot satisfy falls back to the follow chain without clearing anything. */
	@Test
	fun outOfRangePinFallsBackToFollow() {
		val model = modelOf(meshedDrawable("a"), meshedDrawable("b"))
		val resolved =
			resolveUvEditorPage(
				model = model,
				meshSelection = MeshSelection(drawableIds = listOf(DrawableId("a")), activeDrawableId = DrawableId("a")),
				objectSelection = Selection(),
				textures = twoPageTextures(),
				textureSelection = UvTextureSelection.PinnedPage(7),
			)
		assertNotNull(resolved, "the follow chain still resolves")
		assertEquals(0, resolved.pageIndex, "the stale pin defers to the active drawable's page")
	}

	/** With no textures a pin cannot resolve: the follow chain runs, untextured fallback included. */
	@Test
	fun pinWithNoTexturesFallsBackToFollow() {
		val resolved =
			resolveUvEditorPage(
				model = modelOf(meshedDrawable("a")),
				meshSelection = MeshSelection(),
				objectSelection = Selection(),
				textures = null,
				textureSelection = UvTextureSelection.PinnedPage(0),
			)
		assertNotNull(resolved, "the follow chain still resolves")
		assertNull(resolved.pageIndex, "no textures means the untextured fallback, pin or not")
		assertEquals(1, resolved.pageWidth, "the 1x1 fallback")
		assertNull(
			resolveUvEditorPage(
				model = modelOf(bareDrawable("c")),
				meshSelection = MeshSelection(),
				objectSelection = Selection(),
				textures = null,
				textureSelection = UvTextureSelection.PinnedPage(0),
			),
			"no textures and nothing meshed still resolves null (the placeholder)",
		)
	}

	/** Next pins the following page, wrapping past the last back to the first. */
	@Test
	fun nextPinsTheFollowingPageWithWrap() {
		assertEquals(
			UvTextureSelection.PinnedPage(1),
			uvPageSelectionAfter(UvPageKind.NextPage, UvTextureSelection.FollowSelection, effectivePageIndex = 0, pageCount = 2),
			"next from page 0 pins page 1",
		)
		assertEquals(
			UvTextureSelection.PinnedPage(0),
			uvPageSelectionAfter(UvPageKind.NextPage, UvTextureSelection.PinnedPage(1), effectivePageIndex = 1, pageCount = 2),
			"next from the last page wraps to the first",
		)
	}

	/** Previous pins the preceding page, wrapping past the first back to the last. */
	@Test
	fun previousPinsThePrecedingPageWithWrap() {
		assertEquals(
			UvTextureSelection.PinnedPage(1),
			uvPageSelectionAfter(UvPageKind.PreviousPage, UvTextureSelection.FollowSelection, effectivePageIndex = 0, pageCount = 2),
			"previous from page 0 wraps to the last",
		)
		assertEquals(
			UvTextureSelection.PinnedPage(0),
			uvPageSelectionAfter(UvPageKind.PreviousPage, UvTextureSelection.PinnedPage(1), effectivePageIndex = 1, pageCount = 2),
			"previous from page 1 pins page 0",
		)
	}

	/** From the untextured fallback (null effective page) cycling lands on an end page. */
	@Test
	fun cycleFromTheUntexturedFallbackPinsAnEndPage() {
		assertEquals(
			UvTextureSelection.PinnedPage(0),
			uvPageSelectionAfter(UvPageKind.NextPage, UvTextureSelection.FollowSelection, effectivePageIndex = null, pageCount = 2),
			"next from the fallback pins the first page",
		)
		assertEquals(
			UvTextureSelection.PinnedPage(1),
			uvPageSelectionAfter(UvPageKind.PreviousPage, UvTextureSelection.FollowSelection, effectivePageIndex = null, pageCount = 2),
			"previous from the fallback pins the last page",
		)
	}

	/** With no pages there is nothing to pin: cycling keeps the selection unchanged from either state. */
	@Test
	fun cycleNoOpsWithNoPages() {
		assertEquals(
			UvTextureSelection.FollowSelection,
			uvPageSelectionAfter(UvPageKind.NextPage, UvTextureSelection.FollowSelection, effectivePageIndex = null, pageCount = 0),
			"next with no pages keeps following",
		)
		assertEquals(
			UvTextureSelection.PinnedPage(3),
			uvPageSelectionAfter(UvPageKind.PreviousPage, UvTextureSelection.PinnedPage(3), effectivePageIndex = null, pageCount = 0),
			"previous with no pages keeps the stored (stale) pin",
		)
	}

	/** The FollowSelection kind clears any pin. */
	@Test
	fun followSelectionClearsThePin() {
		assertEquals(
			UvTextureSelection.FollowSelection,
			uvPageSelectionAfter(UvPageKind.FollowSelection, UvTextureSelection.PinnedPage(1), effectivePageIndex = 1, pageCount = 2),
			"follow clears the pin",
		)
	}

	/** A store fixture: one layer, sized, bound to the given drawables, with no placement (unpacked). */
	private fun layerStoreOf(
		layerKey: String = "layer0",
		width: Int = 64,
		height: Int = 32,
		boundDrawableIds: List<String> = listOf("a"),
		placement: AtlasPlacement? = null,
	): LayerTextures =
		LayerTextures(
			layers = listOf(SourceLayerEntry(layerKey, "Art", width, height, boundDrawableIds, null)),
			bindingsByDrawableId =
				boundDrawableIds.associateWith { DrawableLayerBinding(layerKey, placement, 128, 128) },
		) { null }

	/** The layer view follows the active drawable, exactly as the page chain does. */
	@Test
	fun layerViewFollowsTheActiveDrawable() {
		val model = modelOf(meshedDrawable("a"), meshedDrawable("b"))
		val resolved =
			resolveUvEditorLayer(
				model = model,
				meshSelection = MeshSelection(drawableIds = listOf(DrawableId("a")), activeDrawableId = DrawableId("a")),
				objectSelection = Selection(),
				layers = layerStoreOf(),
			)
		assertNotNull(resolved, "the active drawable's layer resolves")
		assertEquals("layer0", resolved.layerKey, "the bound layer shows")
		assertEquals(64, resolved.width, "the layer's own width")
		assertEquals(32, resolved.height, "the layer's own height")
	}

	/** A drawable this document retains no artwork for resolves nothing, so the space keeps its page view. */
	@Test
	fun layerViewResolvesNothingWithoutABinding() {
		val model = modelOf(meshedDrawable("a"), meshedDrawable("b"))
		assertNull(
			resolveUvEditorLayer(
				model = model,
				meshSelection = MeshSelection(drawableIds = listOf(DrawableId("b")), activeDrawableId = DrawableId("b")),
				objectSelection = Selection(),
				layers = layerStoreOf(boundDrawableIds = listOf("a")),
			),
			"a drawable with no binding has no layer to show",
		)
		assertNull(
			resolveUvEditorLayer(modelOf(meshedDrawable("a")), MeshSelection(), Selection(), null),
			"no store means no layer view",
		)
		assertNull(
			resolveUvEditorLayer(modelOf(meshedDrawable("a")), MeshSelection(), Selection(), LayerTextures.EMPTY),
			"an empty store means no layer view",
		)
	}

	/** In OBJECT mode every drawable sharing one piece of art draws over it together; unbound ones do not. */
	@Test
	fun objectModeListsEverySharerOfTheArt() {
		val model = modelOf(meshedDrawable("a"), meshedDrawable("b"), meshedDrawable("a2"))
		val shown =
			shownLayerDrawables(
				model,
				EditorMode.Object,
				MeshSelection(),
				layerStoreOf(boundDrawableIds = listOf("a", "a2")),
				"layer0",
			)
		assertEquals(
			listOf(DrawableId("a"), DrawableId("a2")),
			shown.map { drawable -> drawable.id },
			"both users of the art are click targets, in model order",
		)
	}

	/**
	 * In EDIT mode a layer shows only the meshes the SESSION is editing, exactly as a page does.
	 *
	 * Duplicated art means several drawables draw over one layer, but an operator can only move the
	 * ones the session has under edit - drawing the rest as though they were editable offers vertices
	 * that refuse to move and answers a drag with "no editable UVs".
	 */
	@Test
	fun editModeListsOnlyTheSessionMeshesOverTheLayer() {
		val model = modelOf(meshedDrawable("a"), meshedDrawable("a2"))
		val store = layerStoreOf(boundDrawableIds = listOf("a", "a2"))
		val shown =
			shownLayerDrawables(
				model,
				EditorMode.Edit,
				MeshSelection(drawableIds = listOf(DrawableId("a")), activeDrawableId = DrawableId("a")),
				store,
				"layer0",
			)
		assertEquals(listOf(DrawableId("a")), shown.map { drawable -> drawable.id }, "only the edited mesh is shown")

		val none = shownLayerDrawables(model, EditorMode.Edit, MeshSelection(), store, "layer0")
		assertTrue(none.isEmpty(), "editing nothing shows nothing over the layer")
	}

	/** The layer's frame comes from the layer, so narrowing the shown set in Edit mode cannot shift it. */
	@Test
	fun layerBindingResolvesFromTheLayerNotTheShownSet() {
		val store = layerStoreOf(boundDrawableIds = listOf("a", "a2"))
		val binding = store.bindingForLayer("layer0")
		assertNotNull(binding, "a layer with users resolves a representative binding")
		assertEquals("layer0", binding.layerKey, "and it is that layer's")
		assertNull(store.bindingForLayer("missing"), "an unknown layer resolves nothing")
	}

	/**
	 * The layer projection is the same texel display mapping the page view uses, over the LAYER's own
	 * size - an unpacked binding passes its uvs straight through, so the mapping is uv times layer size
	 * with the v-flip.  One projection serves both views; only the uvs handed to it differ.
	 */
	@Test
	fun layerGeometriesProjectIntoTheLayerFrame() {
		val model = modelOf(meshedDrawable("a"))
		val store = layerStoreOf(width = 64, height = 32)
		val shown = shownLayerDrawables(model, EditorMode.Object, MeshSelection(), store, "layer0")
		val layerView = UvEditorLayer("layer0", 64, 32)
		val geometries = uvGizmoGeometries(shown, shownSurfaceUvs(shown, store, layerView), 64, 32)
		assertEquals(1, geometries.size, "one geometry per bound drawable")
		// The same triangleUvs the page test uses: (0.25, 0.5) -> (16, 16) on a 64x32 frame, v flipped.
		val expectedPositions = floatArrayOf(16f, 16f, 48f, 16f, 16f, 24f)
		for (componentIndex in expectedPositions.indices) {
			assertEquals(expectedPositions[componentIndex], geometries.first().positions[componentIndex], 1e-4f, "component $componentIndex")
		}
	}

	/** Cycling pages from the layer view leaves it, and asking to follow returns to following. */
	@Test
	fun pageCyclingLeavesTheLayerView() {
		assertEquals(
			UvTextureSelection.PinnedPage(0),
			uvPageSelectionAfter(UvPageKind.NextPage, UvTextureSelection.SourceLayer, effectivePageIndex = null, pageCount = 2),
			"next from the layer view pins a page, leaving it",
		)
		assertEquals(
			UvTextureSelection.FollowSelection,
			uvPageSelectionAfter(UvPageKind.FollowSelection, UvTextureSelection.SourceLayer, effectivePageIndex = null, pageCount = 2),
			"follow returns to following the selection",
		)
		assertEquals(
			UvTextureSelection.SourceLayer,
			uvPageSelectionAfter(UvPageKind.NextPage, UvTextureSelection.SourceLayer, effectivePageIndex = null, pageCount = 0),
			"with no pages to cycle to, the layer view holds",
		)
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
		val geometries =
			uvGizmoGeometries(
				listOf(drawable),
				shownSurfaceUvs(listOf(drawable), layers = null, layerView = null),
				displayWidth = 64,
				displayHeight = 32,
			)
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
		val bare = listOf(bareDrawable("c"))
		assertTrue(
			uvGizmoGeometries(bare, shownSurfaceUvs(bare, layers = null, layerView = null), 64, 32).isEmpty(),
			"a drawable with no mesh yields no geometry",
		)
	}
}