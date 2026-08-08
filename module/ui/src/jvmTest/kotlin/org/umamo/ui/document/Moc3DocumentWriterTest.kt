package org.umamo.ui.document

import org.umamo.interop.ExportFormat
import org.umamo.interop.ExportReport
import org.umamo.interop.moc3.Moc3Sidecars
import org.umamo.storage.platformFileFromSavedPath
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The pre-export overwrite check: which of the family's files already sit at the destination.
 *
 * On a real (temporary) directory rather than a fake filesystem, because the function under test
 * resolves the picked handle's parent through the same path FileKit hands the writer - the platform
 * seam is the thing being exercised.
 */
class Moc3DocumentWriterTest {
	/**
	 * A bundle whose family is a moc, a manifest, and one subfoldered texture page.
	 *
	 * @param String basename The family base name.
	 * @return Bundle The bundle; contents are placeholders, only the names matter here.
	 */
	private fun bundleOf(basename: String): Moc3Sidecars.Bundle {
		val mocFileName = "$basename.moc3"
		return Moc3Sidecars.Bundle(
			files =
				listOf(
					Moc3Sidecars.BundleFile(mocFileName, byteArrayOf(1)),
					Moc3Sidecars.BundleFile("$basename.model3.json", byteArrayOf(2)),
					Moc3Sidecars.BundleFile("$basename.2048/texture_00.png", byteArrayOf(3)),
				),
			mocFileName = mocFileName,
			report = ExportReport(ExportFormat.Moc3, emptyList()),
		)
	}

	@Test
	fun anEmptyDestinationReportsNothingToOverwrite() {
		val directory = Files.createTempDirectory("umamo-export")
		val destination = platformFileFromSavedPath(directory.resolve("Model.moc3").toString())

		assertEquals(emptyList(), existingBundleFiles(destination, bundleOf("Model")))
	}

	@Test
	fun everyExistingFamilyFileIsReported() {
		// A prior export's family, the subfoldered texture included - the case the native save dialog
		// cannot warn about, because the rigger only picked the moc.
		val directory = Files.createTempDirectory("umamo-export")
		directory.resolve("Model.moc3").writeBytes(byteArrayOf(9))
		directory.resolve("Model.model3.json").writeBytes(byteArrayOf(9))
		directory.resolve("Model.2048").createDirectories()
		directory.resolve("Model.2048/texture_00.png").writeBytes(byteArrayOf(9))
		val destination = platformFileFromSavedPath(directory.resolve("Model.moc3").toString())

		assertEquals(
			listOf("Model.moc3", "Model.model3.json", "Model.2048/texture_00.png"),
			existingBundleFiles(destination, bundleOf("Model")),
			"every colliding family member is named, in bundle order",
		)
	}

	@Test
	fun anUnrelatedNeighborIsNotReported() {
		val directory = Files.createTempDirectory("umamo-export")
		directory.resolve("Other.model3.json").writeBytes(byteArrayOf(9))
		val destination = platformFileFromSavedPath(directory.resolve("Model.moc3").toString())

		assertEquals(emptyList(), existingBundleFiles(destination, bundleOf("Model")))
	}
}