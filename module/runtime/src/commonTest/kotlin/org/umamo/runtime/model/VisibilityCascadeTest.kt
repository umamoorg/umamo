package org.umamo.runtime.model

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Unit-tests [visibleDrawableIds] and [visibleDeformerIds]: the Parts-panel eyeball cascades down the org tree,
 * so a drawable is shown only when its own flag and every ancestor part are visible, and a deformer only when its
 * own flag, every deformer above it, and its part chain are.  Corpus-free - a hand-built tree exercises
 * own-hidden, direct-parent-hidden, ancestor-hidden, and root-level (no part) cases.
 */
class VisibilityCascadeTest {
	private fun drawable(id: String, isVisible: Boolean): Drawable =
		Drawable(
			id = DrawableId(id),
			name = id,
			parentDeformerId = null,
			blendMode = BlendMode.Normal,
			maskedBy = emptyList(),
			mesh = null,
			geometryGrid = null,
			isVisible = isVisible,
		)

	@Test
	fun cascadesEyeballThroughAncestorParts() {
		val parts =
			listOf(
				Part(PartId("root"), "Root", listOf(OrgChild.Part(PartId("hidden")), OrgChild.Part(PartId("shown"))), isVisible = true),
				Part(
					PartId("hidden"),
					"[Guide Image]",
					listOf(OrgChild.Part(PartId("grandchild")), OrgChild.Drawable(DrawableId("inHidden"))),
					isVisible = false,
				),
				Part(PartId("grandchild"), "Under Hidden", listOf(OrgChild.Drawable(DrawableId("underHidden"))), isVisible = true),
				Part(
					PartId("shown"),
					"Visible Folder",
					listOf(OrgChild.Drawable(DrawableId("inShown")), OrgChild.Drawable(DrawableId("ownHidden"))),
					isVisible = true,
				),
			)
		val drawables =
			listOf(
				drawable("inHidden", isVisible = true), // parent hidden → excluded
				drawable("underHidden", isVisible = true), // grandparent hidden → excluded
				drawable("inShown", isVisible = true), // all visible → shown
				drawable("ownHidden", isVisible = false), // own eyeball off → excluded
				drawable("rootLevel", isVisible = true), // no part → shown
			)
		// Top level: the "root" folder plus a loose root-level mesh.
		val rootChildren = listOf(OrgChild.Part(PartId("root")), OrgChild.Drawable(DrawableId("rootLevel")))
		val model = PuppetModel(emptyList(), parts, emptyList(), drawables, rootChildren, rootPartId = PartId("root"))

		val shown = model.visibleDrawableIds()

		assertEquals(setOf(DrawableId("inShown"), DrawableId("rootLevel")), shown)
	}

	/**
	 * A one-key warp in [partId] under [parent].
	 *
	 * @param String id The warp's id.
	 * @param String? parent Its parent deformer's id, or null at the root.
	 * @param String? partId Its part's id, or null.
	 * @param Boolean isVisible Its own eyeball.
	 * @return Deformer.Warp The warp.
	 */
	private fun warp(id: String, parent: String?, partId: String?, isVisible: Boolean = true): Deformer.Warp =
		Deformer.Warp(
			id = DeformerId(id),
			name = id,
			parent = parent?.let { parentId -> DeformerId(parentId) },
			partId = partId?.let { owner -> PartId(owner) },
			rows = 1,
			columns = 1,
			isQuadTransform = true,
			geometryGrid = null,
			isVisible = isVisible,
		)

	@Test
	fun aDeformerIsShownWhenItsOwnItsAncestorsAndItsPartChainAre() {
		val parts =
			listOf(
				Part(PartId("root"), "Root", listOf(OrgChild.Part(PartId("hidden")), OrgChild.Part(PartId("shown"))), isVisible = true),
				Part(PartId("hidden"), "Hidden", listOf(OrgChild.Part(PartId("under"))), isVisible = false),
				Part(PartId("under"), "Under Hidden", emptyList(), isVisible = true),
				Part(PartId("shown"), "Shown", emptyList(), isVisible = true),
				Part(PartId("stray"), "Off The Tree", emptyList(), isVisible = false),
			)
		val deformers =
			listOf(
				warp("body", parent = null, partId = "shown"),
				warp("head", parent = "body", partId = "shown"),
				warp("ownHidden", parent = "body", partId = "shown", isVisible = false),
				warp("underOwnHidden", parent = "ownHidden", partId = "shown"),
				warp("inHiddenPart", parent = null, partId = "under"),
				warp("childOfHiddenPart", parent = "inHiddenPart", partId = "shown"),
				warp("stray", parent = null, partId = "stray"),
				warp("partless", parent = null, partId = null),
				warp("orphan", parent = "missing", partId = "missing"),
			)
		val model = PuppetModel(emptyList(), parts, deformers, emptyList(), listOf(OrgChild.Part(PartId("root"))), rootPartId = PartId("root"))

		val shown = model.visibleDeformerIds()

		assertEquals(listOf("body", "head", "partless", "orphan").map { id -> DeformerId(id) }, shown.toList(), "in model order")
	}
}