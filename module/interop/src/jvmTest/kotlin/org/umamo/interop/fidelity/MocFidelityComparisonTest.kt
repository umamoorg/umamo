package org.umamo.interop.fidelity

import org.umamo.format.moc3.Moc3
import org.umamo.format.moc3.MocDocument
import org.umamo.format.moc3.moc.CanvasInfo
import org.umamo.format.moc3.moc.MocVersion
import org.umamo.format.moc3.model.ArtMesh
import org.umamo.format.moc3.model.Deformer
import org.umamo.format.moc3.model.KeyformBinding
import org.umamo.format.moc3.model.Part
import org.umamo.format.moc3.model.RotationDeformer
import org.umamo.format.moc3.model.WarpDeformer
import org.umamo.testkit.skipTest
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The comparator the fidelity gates share catches what they exist to catch: a dropped object, a grid that
 * evaluates differently, drift past the tolerance, a changed attribute, and reordered sibling parts, while
 * passing drift within it and a part table laid out differently around the same sibling order.
 *
 * Each case edits one real corpus document and compares the edit with the original, so the gates' own
 * runs never have to be broken to show they would fail.  The sibling part order cases build small
 * parts-only documents instead, since reordering a real part table means remapping every part index in it.
 */
class MocFidelityComparisonTest {
	/**
	 * A corpus document with an art mesh whose first two keyforms differ along its first axis, and that mesh.
	 *
	 * @property MocDocument document The decoded document.
	 * @property ArtMesh mesh The keyed mesh the cases edit.
	 */
	private class Subject(val document: MocDocument, val mesh: ArtMesh)

	/**
	 * The smallest corpus moc fit for the cases, or a skip when the corpus holds none.
	 *
	 * @return Subject The document and its keyed mesh.
	 */
	private fun subject(): Subject {
		val samplesDirectory = System.getProperty("moc3.samples")?.let(::File)?.takeIf { directory -> directory.isDirectory } ?: skipTest("moc3.samples not present")
		val files =
			samplesDirectory
				.walkTopDown()
				.filter { file -> file.isFile && file.extension == "moc3" && file.parentFile?.name != "work" }
				.sortedBy { file -> file.length() }

		for (file in files) {
			val document = Moc3.read(file.readBytes())

			if (document.deformers.isEmpty()) {
				continue
			}

			val mesh =
				document.artMeshes.firstOrNull { candidate ->
					val axes = document.keyformBinding(candidate.keyformBindingIndex)?.axes.orEmpty()
					axes.isNotEmpty() &&
						axes[0].keyPositions.size >= 2 &&
						candidate.keyforms.size >= 2 &&
						!candidate.keyforms[0].vertexPositions.contentEquals(candidate.keyforms[1].vertexPositions)
				}

			if (mesh != null) {
				return Subject(document, mesh)
			}
		}

		skipTest("no corpus moc has a deformer and a mesh keyed along its first axis")
	}

	/**
	 * A copy of [document] with some of its lists replaced.
	 *
	 * @param MocDocument document The original.
	 * @param List deformers The deformers to carry.
	 * @param List artMeshes The art meshes to carry.
	 * @param List bindings The keyform bindings to carry.
	 * @return MocDocument The copy.
	 */
	private fun rebuilt(
		document: MocDocument,
		deformers: List<Deformer> = document.deformers,
		artMeshes: List<ArtMesh> = document.artMeshes,
		bindings: List<KeyformBinding> = document.bindings,
	): MocDocument =
		MocDocument(
			document.version,
			document.canvas,
			document.parameters,
			bindings.associateBy { binding -> binding.index },
			document.parts,
			deformers,
			artMeshes,
			document.glues,
			document.renderOrderGroups,
			document.blendShapes,
			document.offscreens,
			document.keyPositionsHasParameterUnion,
		)

	/**
	 * [subject]'s document with the mesh's second keyform moved by [move] at its first position component.
	 *
	 * @param Subject subject The document and mesh.
	 * @param Function move The new value of the component, given the old one.
	 * @return MocDocument The edited copy.
	 */
	private fun withNudgedKeyform(subject: Subject, move: (Float) -> Float): MocDocument {
		val keyforms = subject.mesh.keyforms.toMutableList()
		val positions = keyforms[1].vertexPositions.copyOf()
		positions[0] = move(positions[0])
		keyforms[1] = keyforms[1].copy(vertexPositions = positions)
		val meshes = subject.document.artMeshes.map { mesh -> if (mesh.id == subject.mesh.id) mesh.copy(keyforms = keyforms) else mesh }

		return rebuilt(subject.document, artMeshes = meshes)
	}

	@Test
	fun aDocumentMatchesItselfAndItsOwnWrite() {
		val document = subject().document
		assertEquals(emptyList(), compareMocDocuments(document, document).map { divergence -> divergence.describe() })
		assertEquals(emptyList(), compareMocDocuments(document, Moc3.read(Moc3.write(document))).map { divergence -> divergence.describe() })
	}

	@Test
	fun aDroppedArtMeshIsMissing() {
		val document = subject().document
		val dropped = document.artMeshes.last()
		val divergences = compareMocDocuments(document, rebuilt(document, artMeshes = document.artMeshes.dropLast(1)))
		assertTrue(
			MocDivergence(MocObjectKind.ART_MESH, dropped.id, DivergenceAspect.Missing) in divergences,
			divergences.joinToString("\n") { divergence -> divergence.describe() },
		)
	}

	@Test
	fun driftPastTheToleranceIsAValueDivergence() {
		val subject = subject()
		val divergences = compareMocDocuments(subject.document, withNudgedKeyform(subject) { value -> value + 1e-3f * maxOf(1f, abs(value)) })
		assertTrue(
			divergences.any { divergence ->
				val aspect = divergence.aspect
				divergence.kind == MocObjectKind.ART_MESH && divergence.id == subject.mesh.id && aspect is DivergenceAspect.Value && aspect.channel == MocChannel.POSITIONS
			},
			divergences.joinToString("\n") { divergence -> divergence.describe() },
		)
	}

	@Test
	fun driftWithinTheToleranceIsNothing() {
		val subject = subject()
		val divergences = compareMocDocuments(subject.document, withNudgedKeyform(subject) { value -> Math.nextUp(Math.nextUp(value)) })
		assertEquals(emptyList(), divergences.map { divergence -> divergence.describe() })
	}

	@Test
	fun aMovedKeyThatChangesTheGridIsAValueDivergence() {
		val subject = subject()
		val binding = subject.document.keyformBinding(subject.mesh.keyformBindingIndex) ?: error("the subject mesh has no binding")
		val firstAxis = binding.axes[0]
		val keys = firstAxis.keyPositions.copyOf()
		// The second key moves halfway toward the first.  The mesh's first two cells differ, so at the new
		// key the old grid is between them while the new one has already reached the second.
		keys[1] = (keys[0] + keys[1]) / 2f
		val moved = binding.copy(axes = listOf(firstAxis.copy(keyPositions = keys)) + binding.axes.drop(1))
		val bindings = subject.document.bindings.map { existing -> if (existing.index == binding.index) moved else existing }
		val divergences = compareMocDocuments(subject.document, rebuilt(subject.document, bindings = bindings))
		assertTrue(
			divergences.any { divergence -> divergence.kind == MocObjectKind.ART_MESH && divergence.id == subject.mesh.id && divergence.aspect is DivergenceAspect.Value },
			divergences.joinToString("\n") { divergence -> divergence.describe() },
		)
	}

	@Test
	fun aDeformerShownVisibleIsAnAttributeDivergence() {
		val document = subject().document
		val target = document.deformers.first()
		val flipped =
			when (target) {
				is WarpDeformer -> target.copy(isVisible = !target.isVisible)
				is RotationDeformer -> target.copy(isVisible = !target.isVisible)
			}
		val divergences = compareMocDocuments(document, rebuilt(document, deformers = listOf(flipped) + document.deformers.drop(1)))
		val kind = if (target is WarpDeformer) MocObjectKind.WARP else MocObjectKind.ROTATION
		assertEquals(
			listOf(MocDivergence(kind, target.id, DivergenceAspect.Attribute(MocAttribute.VISIBLE, target.isVisible, !target.isVisible))),
			divergences,
		)
	}

	/**
	 * A document of static parts and nothing else.
	 *
	 * @param Array parts Each part's id and its parent's index in this list, -1 at the root, in part-table order.
	 * @return MocDocument The document.
	 */
	private fun partsOnly(vararg parts: Pair<String, Int>): MocDocument =
		MocDocument(
			version = MocVersion.V50,
			canvas = CanvasInfo(pixelsPerUnit = 1f, originX = 0f, originY = 0f, width = 100f, height = 100f),
			parameters = emptyList(),
			keyformBindings = mapOf(0 to KeyformBinding(index = 0, axes = emptyList())),
			parts =
				parts.map { (id, parentPartIndex) ->
					Part(id = id, parentPartIndex = parentPartIndex, keyformBindingIndex = 0, drawOrderKeyforms = floatArrayOf(500f))
				},
			deformers = emptyList(),
			artMeshes = emptyList(),
			glues = emptyList(),
			renderOrderGroups = emptyList(),
		)

	/**
	 * Each divergence's kind, id, and aspect, without the detail text.
	 *
	 * @param List divergences The comparison's result.
	 * @return List The triples, in result order.
	 */
	private fun summarized(divergences: List<MocDivergence>): List<Triple<MocObjectKind, String, DivergenceAspect>> =
		divergences.map { divergence -> Triple(divergence.kind, divergence.id, divergence.aspect) }

	@Test
	fun swappedSiblingPartsAreAPartOrderDivergenceOnTheirParent() {
		val reference = partsOnly("Parent" to -1, "PartA" to 0, "PartEmpty" to 0, "PartB" to 0)
		val candidate = partsOnly("Parent" to -1, "PartA" to 0, "PartB" to 0, "PartEmpty" to 0)
		assertEquals(
			listOf(Triple(MocObjectKind.PART_TREE, "part:Parent", DivergenceAspect.PartOrder)),
			summarized(compareMocDocuments(reference, candidate)),
		)
	}

	@Test
	fun swappedRootPartsAreAPartOrderDivergenceOnTheRoot() {
		val divergences = compareMocDocuments(partsOnly("PartA" to -1, "PartB" to -1), partsOnly("PartB" to -1, "PartA" to -1))
		assertEquals(listOf(Triple(MocObjectKind.PART_TREE, ROOT_PART_TREE, DivergenceAspect.PartOrder)), summarized(divergences))
	}

	@Test
	fun aTableLaidOutDifferentlyWithTheSameSiblingOrderMatches() {
		// The editor's bake lists the parts level by level; Umamo's export lists each part before its children.
		val levelByLevel = partsOnly("Head" to -1, "Body" to -1, "Eye" to 0, "Arm" to 1)
		val parentFirst = partsOnly("Head" to -1, "Eye" to 0, "Body" to -1, "Arm" to 2)
		assertEquals(emptyList(), compareMocDocuments(levelByLevel, parentFirst).map { divergence -> divergence.describe() })
	}

	@Test
	fun aMovedPartIsReportedAsItsParentAndNotAsAReorder() {
		val reference = partsOnly("PartOne" to -1, "PartTwo" to -1, "PartA" to 0, "PartB" to 0)
		val candidate = partsOnly("PartOne" to -1, "PartTwo" to -1, "PartB" to 0, "PartA" to 1)
		assertEquals(
			listOf(Triple(MocObjectKind.PART, "PartA", DivergenceAspect.Attribute(MocAttribute.PARENT_PART, "PartOne", "PartTwo"))),
			summarized(compareMocDocuments(reference, candidate)),
		)
	}

	@Test
	fun aPartOnlyOneSideHasDoesNotReorderItsSiblings() {
		val reference = partsOnly("Parent" to -1, "PartA" to 0, "PartHidden" to 0, "PartB" to 0)
		val candidate = partsOnly("Parent" to -1, "PartA" to 0, "PartB" to 0)
		assertEquals(
			listOf(Triple(MocObjectKind.PART, "PartHidden", DivergenceAspect.Missing)),
			summarized(compareMocDocuments(reference, candidate)),
		)
	}
}