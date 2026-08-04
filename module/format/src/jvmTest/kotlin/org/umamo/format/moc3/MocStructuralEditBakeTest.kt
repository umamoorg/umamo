package org.umamo.format.moc3

import org.umamo.format.moc3.encode.MocEncoder
import org.umamo.format.moc3.moc.MocCodec
import org.umamo.format.moc3.moc.Section
import org.umamo.format.moc3.moc.Sections
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A bake must survive a change to an OBJECT COUNT, not just to values.
 *
 * This is the case that was broken until CountInfo was synthesized rather than carried from the
 * reference container: adding an object grew the per-object arrays while section 0 still declared the
 * old count, so the re-decode either dropped the new object or read past an array it had just sized.
 * Nothing exercised it - every existing bake test re-bakes an UNEDITED document, where a stale
 * CountInfo happens to be correct.
 *
 * Parts are the vehicle because a part is the cheapest object to synthesize honestly: an id, a parent,
 * a keyform binding, and one draw-order value per grid cell.  Adding one moves CountInfo fields 0
 * (parts) and 6 (part forms) plus nine per-part sections, which is enough to catch a stale count.
 *
 * @see <a href="https://docs.umamo.org/format/MOC3.md">MOC3.md §5.1</a>
 */
class MocStructuralEditBakeTest {
	private val samplesDir: File? = System.getProperty("moc3.samples")?.let(::File)?.takeIf { it.isDirectory }

	private fun samples(): List<File> =
		samplesDir?.walkTopDown()?.filter { it.isFile && it.extension == "moc3" }?.sortedBy { it.name }?.toList()
			?: emptyList()

	/**
	 * Rebuilds [document] with [parts] substituted, leaving everything else identical.
	 *
	 * @param MocDocument document The decoded source.
	 * @param List        parts    The replacement part list.
	 * @return MocDocument The edited document.
	 */
	private fun withParts(document: MocDocument, parts: List<org.umamo.format.moc3.model.Part>): MocDocument =
		MocDocument(
			version = document.version,
			canvas = document.canvas,
			parameters = document.parameters,
			keyformBindings = document.bindings.associateBy { binding -> binding.index },
			parts = parts,
			deformers = document.deformers,
			artMeshes = document.artMeshes,
			glues = document.glues,
			renderOrderGroups = document.renderOrderGroups,
			blendShapes = document.blendShapes,
			offscreens = document.offscreens,
			keyPositionsHasParameterUnion = document.keyPositionsHasParameterUnion,
		)

	@Test
	fun addingAPartKeepsCountInfoAndTheDecodeConsistent() {
		val files = samples()
		if (files.isEmpty()) {
			println("moc3.samples not present; skipping structural edit bake test")
			return
		}
		var exercised = 0
		val skippedForPartBlends = ArrayList<String>()
		for (file in files) {
			val original = MocCodec.read(file.readBytes())
			val document = Moc3.decode(original)
			val lastPart = document.parts.lastOrNull() ?: continue

			// KNOWN LIMITATION, not a property of this edit: a part-target blend record's delta rows are
			// appended to the SAME part draw-order table (section 58) the main grid uses, and the record
			// carries a `recordBase` into it.  Adding a part inserts a main-grid row ahead of those
			// deltas, so every stored base past the insertion point is off by one - and the base is
			// carried on the decoded model rather than recomputed at bake time.  Editing a model with
			// part blend shapes therefore needs the blend layout re-derived, which is export-phase work.
			// modelC is the only corpus sample with such a record.
			if (document.blendShapes.any { it.target == org.umamo.format.moc3.model.BlendShapeTarget.PART }) {
				skippedForPartBlends.add(file.name)
				continue
			}
			// An appended ROOT part with the same binding as one that already works: its draw-order run
			// simply extends section 58, and nothing else in the file refers to it by index.
			val addedPart = lastPart.copy(id = "UmamoAddedPart", parentPartIndex = -1)
			val edited = withParts(document, document.parts + addedPart)

			val baked = MocCodec.read(MocEncoder.bake(original, edited))
			val redecoded = Moc3.decode(baked)

			assertEquals(
				document.parts.size + 1,
				redecoded.parts.size,
				"${file.name}: the added part survived the bake",
			)
			assertEquals(
				document.parts.size + 1,
				baked.countInfo.getOrElse(Sections.CI_PARTS) { 0 },
				"${file.name}: CountInfo part count tracks the edit",
			)
			assertEquals(edited.parts, redecoded.parts, "${file.name}: parts round-trip through the edit")
			// Every per-part column must have grown with the list, or the runtime reads past one of them.
			for (section in listOf(Section.PART_ID, Section.PART_PARENT, Section.PART_KEYFORM_BINDING)) {
				assertEquals(
					document.parts.size + 1,
					baked.sections.elementCount(section),
					"${file.name}: $section resized with the part list",
				)
			}
			// The edit must not disturb anything else - a stale count elsewhere shows up here first.
			assertEquals(document.deformers, redecoded.deformers, "${file.name}: deformers unchanged")
			assertEquals(document.artMeshes, redecoded.artMeshes, "${file.name}: art meshes unchanged")
			assertEquals(document.blendShapes, redecoded.blendShapes, "${file.name}: blend shapes unchanged")
			assertEquals(document.glues, redecoded.glues, "${file.name}: glues unchanged")
			exercised++
		}
		assertTrue(exercised > 0, "no corpus model had a part to duplicate")
		println("[structural-edit] added a part to $exercised models; CountInfo and every per-part column tracked it")
		if (skippedForPartBlends.isNotEmpty()) {
			println("[structural-edit] skipped (part-target blend records index the table this edit shifts): $skippedForPartBlends")
		}
	}
}
