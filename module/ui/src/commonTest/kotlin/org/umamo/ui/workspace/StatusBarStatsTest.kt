package org.umamo.ui.workspace

import org.umamo.edit.Selection
import org.umamo.edit.SelectionTarget
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

/**
 * Pins the status bar's pure mesh-stat derivations: whole-model totals skip meshless drawables, and
 * selection totals count directly selected drawables, expand selected parts to their nested
 * descendants, and ignore deformer targets.
 */
class StatusBarStatsTest {
	/**
	 * A drawable with a mesh of [triangleCount] triangle-fan triangles ([triangleCount] + 2 vertices),
	 * or no mesh at all when [triangleCount] is null.
	 *
	 * @param String id The drawable id (doubles as its name).
	 * @param Int? triangleCount The triangle count, or null for a meshless drawable.
	 * @return Drawable The fixture drawable.
	 */
	private fun drawable(id: String, triangleCount: Int?): Drawable {
		val mesh =
			triangleCount?.let { count ->
				val vertexCount = count + 2
				DrawableMesh(
					positions = FloatArray(vertexCount * 2),
					uvs = FloatArray(vertexCount * 2),
					indices = IntArray(count * 3) { indexSlot -> indexSlot % vertexCount },
				)
			}
		return Drawable(
			id = DrawableId(id),
			name = id,
			parentDeformerId = null,
			blendMode = BlendMode.Normal,
			maskedBy = emptyList(),
			mesh = mesh,
			keyforms = null,
		)
	}

	/**
	 * A model with [drawables] at the root plus one part ("bag") nesting a sub-part ("bagInner") that
	 * owns the "nested" drawable - the shape the part-expansion walk must traverse.
	 *
	 * @param List drawables The root drawables.
	 * @return PuppetModel The fixture model.
	 */
	private fun model(drawables: List<Drawable>): PuppetModel {
		val nested = drawable("nested", triangleCount = 4)
		val innerPart = Part(PartId("bagInner"), "Bag Inner", listOf(OrgChild.Drawable(nested.id)))
		val outerPart = Part(PartId("bag"), "Bag", listOf(OrgChild.Part(innerPart.id)))
		return PuppetModel(
			parameters = emptyList(),
			parts = listOf(outerPart, innerPart),
			deformers = emptyList(),
			drawables = drawables + nested,
			rootChildren = drawables.map { rootDrawable -> OrgChild.Drawable(rootDrawable.id) } + OrgChild.Part(outerPart.id),
			rootPartId = null,
		)
	}

	/** Whole-model totals sum every mesh and skip meshless drawables. */
	@Test
	fun meshTotalsSkipMeshlessDrawables() {
		val puppet = model(listOf(drawable("face", triangleCount = 10), drawable("anchor", triangleCount = null)))
		// face: 10 tris / 12 verts; anchor: no mesh; nested: 4 tris / 6 verts.
		assertEquals(MeshTotals(vertexCount = 18, triangleCount = 14), meshTotals(puppet))
	}

	/** A directly selected drawable contributes exactly its own mesh. */
	@Test
	fun selectedTotalsCountDirectDrawable() {
		val puppet = model(listOf(drawable("face", triangleCount = 10)))
		val selection = Selection(targets = setOf(SelectionTarget.Drawable(DrawableId("face"))))
		assertEquals(MeshTotals(vertexCount = 12, triangleCount = 10), selectedMeshTotals(puppet, selection))
	}

	/** A selected part expands to its nested descendants (through sub-parts). */
	@Test
	fun selectedTotalsExpandPartToNestedDescendants() {
		val puppet = model(listOf(drawable("face", triangleCount = 10)))
		val selection = Selection(targets = setOf(SelectionTarget.Part(PartId("bag"))))
		assertEquals(MeshTotals(vertexCount = 6, triangleCount = 4), selectedMeshTotals(puppet, selection))
	}

	/** Deformer targets contribute nothing (influence is not containment). */
	@Test
	fun selectedTotalsIgnoreDeformerTargets() {
		val puppet = model(listOf(drawable("face", triangleCount = 10)))
		val selection =
			Selection(
				targets =
					setOf(
						SelectionTarget.Deformer(org.umamo.runtime.model.DeformerId("warp1")),
						SelectionTarget.Drawable(DrawableId("face")),
					),
			)
		assertEquals(MeshTotals(vertexCount = 12, triangleCount = 10), selectedMeshTotals(puppet, selection))
	}
}
