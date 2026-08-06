package org.umamo.interop.moc3

import org.umamo.format.moc3.Moc3
import org.umamo.format.moc3.encode.MocEncoder
import org.umamo.format.moc3.moc.MocCodec
import org.umamo.format.moc3.moc.MocVersion
import org.umamo.format.moc3.model.ArtMesh
import org.umamo.format.moc3.model.RotationDeformer
import org.umamo.format.moc3.model.WarpDeformer
import org.umamo.interop.moc3.export.Moc3Export
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins that a DOWNGRADING export writes a document its target version can actually hold.
 *
 * The export strips the model before lowering it, and every other export gate exports at the model's
 * own version - or at V53, which is an upgrade.  On all of those `Moc3VersionDowngrade.strip` returns
 * the SAME `PuppetModel` instance it was handed, so a lowering that read the un-stripped model instead
 * of the stripped one would be a no-op and pass every one of them.  This is the only gate that makes
 * the strip observable without the C core.
 *
 * The floors asserted below are the section table's, not the editor's target-version dialog's - see
 * `Moc3VersionDowngrade`, which is deliberate about the two ladders differing.
 *
 * Skips gracefully without samples, so it covers nothing on CI - the corpus is gitignored.
 *
 * @see <a href="https://docs.umamo.org/format/MOC3.md">MOC3.md §version gating</a>
 */
class Moc3DowngradeContentTest {
	private val samplesDir: File? = System.getProperty("moc3.samples")?.let(::File)?.takeIf { it.isDirectory }

	private fun samples(): List<File> =
		samplesDir
			?.walkTopDown()
			// work/ holds our own bake outputs - same models, no new coverage.
			?.filter { it.isFile && it.extension == "moc3" && it.parentFile?.name != "work" }
			?.sortedBy { it.name }
			?.toList()
			.orEmpty()

	@Test
	fun aDowngradedExportCarriesNothingItsVersionCannotHold() {
		val files = samples()
		if (files.isEmpty()) {
			println("moc3.samples not present; skipping downgrade content gate")
			return
		}
		val failures = ArrayList<String>()
		var covered = 0
		var actuallyDowngraded = 0
		for (file in files) {
			val source = Moc3.decode(MocCodec.read(file.readBytes()))
			val puppet = Moc3Import.fromMocDocument(source, displayInfo = null)
			for (targetVersion in listOf(MocVersion.V30, MocVersion.V42)) {
				if (targetVersion.byteValue >= source.version.byteValue) {
					continue
				}
				actuallyDowngraded++
				val label = "${file.name} → ${targetVersion.name}"
				val exported = Moc3Export.toMocDocument(puppet, targetVersion).document
				covered++

				// Blend shapes arrived at moc v4, offscreen rendering at v6.
				if (targetVersion.byteValue < 4 && exported.blendShapes.isNotEmpty()) {
					failures.add("$label: carries ${exported.blendShapes.size} blend shapes below their v4 floor")
				}
				if (targetVersion.byteValue < 6 && exported.offscreens.isNotEmpty()) {
					failures.add("$label: carries ${exported.offscreens.size} offscreens below their v6 floor")
				}
				// Per-object multiply/screen color arrived at v4; below that the tables do not exist, so a
				// keyform must carry null rather than an identity or the lowering synthesizes a section the
				// version cannot address.
				if (targetVersion.byteValue < 4) {
					val coloredObject =
						exported.deformers.firstOrNull { deformer ->
							when (deformer) {
								is WarpDeformer -> deformer.keyforms.any { it.multiplyColor != null || it.screenColor != null }
								is RotationDeformer -> deformer.keyforms.any { it.multiplyColor != null || it.screenColor != null }
							}
						}?.id
							?: exported.artMeshes.firstOrNull { mesh ->
								mesh.keyforms.any { it.multiplyColor != null || it.screenColor != null }
							}?.id
					if (coloredObject != null) {
						failures.add("$label: $coloredObject keeps a color channel below the v4 color floor")
					}
					val typedParameter = exported.parameters.firstOrNull { it.type != null }
					if (typedParameter != null) {
						failures.add("$label: parameter ${typedParameter.id} keeps a type below the v4 Parameter.Types floor")
					}
				}
				// The packed extended blend is v6-only (MOC3 v6 §5.6 s153).
				if (targetVersion.byteValue < 6) {
					val extended: ArtMesh? = exported.artMeshes.firstOrNull { it.extendedBlend != 0 }
					if (extended != null) {
						failures.add("$label: art mesh ${extended.id} keeps extendedBlend=${extended.extendedBlend} below its v6 floor")
					}
				}
				// The document has to survive its own writer: a section whose declared count exceeds its
				// slice, or a table the version cannot address, fails here rather than in the official core.
				MocCodec.read(MocEncoder.bakeFresh(targetVersion, exported))
			}
		}
		assertTrue(covered > 0, "no corpus model was above V30/V42 to downgrade")
		// The whole point is the path where strip returns a DIFFERENT model; a run that only ever
		// re-targeted upward exercised nothing.
		assertTrue(actuallyDowngraded > 0, "no export in this run actually downgraded")
		println("[downgrade] $covered downgrading exports checked across ${files.size} models")
		assertEquals(emptyList(), failures.take(25), "a downgraded export kept what its version cannot hold")
	}
}
