package org.umamo.interop.moc3

import org.umamo.format.moc3.MocDocument
import org.umamo.format.moc3.moc.CanvasInfo
import org.umamo.format.moc3.moc.MocVersion
import org.umamo.format.moc3.model.ArtMesh
import org.umamo.format.moc3.model.ArtMeshKeyform
import org.umamo.format.moc3.model.KeyformBinding
import org.umamo.format.moc3.model.Part
import org.umamo.format.moc3.model.RenderOrderChild
import org.umamo.format.moc3.model.RenderOrderGroup
import org.umamo.interop.moc3.import.Moc3Import
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.PuppetModel
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Hand-built-document tests for where the MOC3 import places a part that has no panel index.
 *
 * A part's panel index is the smallest one among the drawables under it, so a part holding no drawables
 * has none, and the import places it by the part table instead of sorting it.  These pin each
 * placement: between sibling parts, directly above the next sibling part, directly below the last one,
 * in runs, and the fallbacks when no sibling part or no render tree gives an anchor.
 *
 * @see <a href="https://docs.umamo.org/format/MOC3.md">MOC3.md §5.6</a>
 */
class Moc3PartOrderImportTest {
	/**
	 * Builds a static part.
	 *
	 * @param String id              The part id.
	 * @param Int    parentPartIndex The s9 parent part index, -1 at the root.
	 * @return Part The part.
	 */
	private fun part(
		id: String,
		parentPartIndex: Int = -1,
	): Part = Part(id = id, parentPartIndex = parentPartIndex, keyformBindingIndex = 0, drawOrderKeyforms = floatArrayOf(500f))

	/**
	 * Builds a one-keyform, one-triangle art mesh parented to a part or the root, static on binding 0.
	 *
	 * @param String id              The s33 id.
	 * @param Int    parentPartIndex The s39 part index, -1 at the root.
	 * @return ArtMesh The drawable.
	 */
	private fun mesh(
		id: String,
		parentPartIndex: Int,
	): ArtMesh =
		ArtMesh(
			id = id,
			textureIndex = 0,
			constantFlags = 0,
			parentPartIndex = parentPartIndex,
			parentDeformerIndex = -1,
			vertexUvs = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f),
			triangleIndices = shortArrayOf(0, 1, 2),
			maskDrawableIndices = IntArray(0),
			keyformBindingIndex = 0,
			keyforms =
				listOf(
					ArtMeshKeyform(
						vertexPositions = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f),
						opacity = 1f,
						drawOrder = 500f,
						multiplyColor = null,
						screenColor = null,
					),
				),
		)

	/**
	 * Imports a document whose root render-order group draws the meshes in the reverse of [panelOrder],
	 * which is what makes [panelOrder] the reconstructed parts-panel order.
	 *
	 * @param List<Part>    parts      The part list, in file order.
	 * @param List<ArtMesh> artMeshes  The drawable list, in file order.
	 * @param List<String>  panelOrder The mesh ids front to back, or null for a document with no
	 *                                 render-order groups at all.
	 * @return PuppetModel The imported rig.
	 */
	private fun importOf(
		parts: List<Part>,
		artMeshes: List<ArtMesh>,
		panelOrder: List<String>?,
	): PuppetModel {
		val renderOrderGroups =
			panelOrder?.let { meshIds ->
				listOf(
					RenderOrderGroup(
						meshIds.asReversed().map { meshId ->
							RenderOrderChild(kind = 0, index = artMeshes.indexOfFirst { artMesh -> artMesh.id == meshId }, groupIndex = 0)
						},
					),
				)
			}

		return Moc3Import.fromMocDocument(
			MocDocument(
				version = MocVersion.V50,
				canvas = CanvasInfo(pixelsPerUnit = 1f, originX = 0f, originY = 0f, width = 100f, height = 100f),
				parameters = emptyList(),
				keyformBindings = mapOf(0 to KeyformBinding(index = 0, axes = emptyList())),
				parts = parts,
				deformers = emptyList(),
				artMeshes = artMeshes,
				glues = emptyList(),
				renderOrderGroups = renderOrderGroups.orEmpty(),
			),
			displayInfo = null,
		)
	}

	/**
	 * The ids of a container's children, parts and drawables alike, in org-tree order.
	 *
	 * @param PuppetModel puppet   The imported rig.
	 * @param String?     parentId The parent part's id, or null for the root.
	 * @return List<String> The child ids.
	 */
	private fun childIdsOf(
		puppet: PuppetModel,
		parentId: String?,
	): List<String> {
		val children = if (parentId == null) puppet.rootChildren else puppet.partById.getValue(PartId(parentId)).children

		return children.map { child ->
			when (child) {
				is OrgChild.Part -> child.id.raw
				is OrgChild.Drawable -> child.id.raw
			}
		}
	}

	@Test
	fun anEmptyPartKeepsItsPlaceAmongItsSiblingParts() {
		val puppet =
			importOf(
				parts = listOf(part("Parent"), part("PartA", 0), part("PartEmpty", 0), part("PartB", 0)),
				artMeshes = listOf(mesh("MeshA", 1), mesh("MeshB", 3)),
				panelOrder = listOf("MeshA", "MeshB"),
			)

		assertEquals(listOf("PartA", "PartEmpty", "PartB"), childIdsOf(puppet, "Parent"))
	}

	@Test
	fun anEmptyPartSitsDirectlyAboveTheNextSiblingPart() {
		val puppet =
			importOf(
				parts = listOf(part("PartA"), part("PartEmpty"), part("PartB")),
				artMeshes = listOf(mesh("MeshA", 0), mesh("MeshLoose", -1), mesh("MeshB", 2)),
				panelOrder = listOf("MeshA", "MeshLoose", "MeshB"),
			)

		assertEquals(listOf("PartA", "MeshLoose", "PartEmpty", "PartB"), childIdsOf(puppet, null))
	}

	@Test
	fun aLeadingEmptyPartSitsDirectlyAboveTheFirstSiblingPart() {
		val puppet =
			importOf(
				parts = listOf(part("PartEmpty"), part("PartA")),
				artMeshes = listOf(mesh("MeshTop", -1), mesh("MeshA", 1)),
				panelOrder = listOf("MeshTop", "MeshA"),
			)

		assertEquals(listOf("MeshTop", "PartEmpty", "PartA"), childIdsOf(puppet, null))
	}

	@Test
	fun aTrailingEmptyPartSitsDirectlyBelowTheLastSiblingPart() {
		val puppet =
			importOf(
				parts = listOf(part("PartA"), part("PartEmpty")),
				artMeshes = listOf(mesh("MeshA", 0), mesh("MeshLoose", -1)),
				panelOrder = listOf("MeshA", "MeshLoose"),
			)

		assertEquals(listOf("PartA", "PartEmpty", "MeshLoose"), childIdsOf(puppet, null))
	}

	@Test
	fun aRunOfEmptyPartsStaysTogetherInPartTableOrder() {
		val puppet =
			importOf(
				parts = listOf(part("PartA"), part("PartEmpty1"), part("PartEmpty2"), part("PartB")),
				artMeshes = listOf(mesh("MeshA", 0), mesh("MeshLoose", -1), mesh("MeshB", 3)),
				panelOrder = listOf("MeshA", "MeshLoose", "MeshB"),
			)

		assertEquals(listOf("PartA", "MeshLoose", "PartEmpty1", "PartEmpty2", "PartB"), childIdsOf(puppet, null))
	}

	@Test
	fun anEmptyPartWithNoSiblingPartFollowsTheDrawables() {
		val puppet =
			importOf(
				parts = listOf(part("PartEmpty")),
				artMeshes = listOf(mesh("MeshFirst", -1), mesh("MeshSecond", -1)),
				panelOrder = listOf("MeshSecond", "MeshFirst"),
			)

		assertEquals(listOf("MeshSecond", "MeshFirst", "PartEmpty"), childIdsOf(puppet, null))
	}

	@Test
	fun withoutRenderOrderGroupsPartsThenDrawablesKeepFileOrder() {
		val puppet =
			importOf(
				parts = listOf(part("PartA"), part("PartEmpty"), part("PartB")),
				artMeshes = listOf(mesh("MeshLoose", -1), mesh("MeshA", 0), mesh("MeshB", 2)),
				panelOrder = null,
			)

		assertEquals(listOf("PartA", "PartEmpty", "PartB", "MeshLoose"), childIdsOf(puppet, null))
	}
}