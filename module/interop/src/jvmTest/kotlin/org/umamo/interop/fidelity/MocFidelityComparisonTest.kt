package org.umamo.interop.fidelity

import org.umamo.format.moc3.Moc3
import org.umamo.format.moc3.MocDocument
import org.umamo.format.moc3.model.ArtMesh
import org.umamo.format.moc3.model.Deformer
import org.umamo.format.moc3.model.KeyformBinding
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
 * evaluates differently, drift past the tolerance, and a changed attribute, while passing drift within it.
 *
 * Each case edits one real corpus document and compares the edit with the original, so the gates' own
 * runs never have to be broken to show they would fail.
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
}