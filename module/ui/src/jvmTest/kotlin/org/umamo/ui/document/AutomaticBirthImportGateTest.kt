package org.umamo.ui.document

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.umamo.format.art.LayerRaster
import org.umamo.format.art.alphaChannel
import org.umamo.format.cmo3.Cmo3
import org.umamo.geometry.predicate.orient2d
import org.umamo.interop.art.ArtSourceDescriptor
import org.umamo.interop.art.BirthMeshMode
import org.umamo.interop.art.SourceArtImport
import org.umamo.interop.art.SourceArtImportNotice
import org.umamo.interop.art.SourceArtImportOptions
import org.umamo.interop.art.mesh.ArtMeshPreset
import org.umamo.interop.art.mesh.meshCoversOpaquePixels
import org.umamo.runtime.model.applyAffine
import org.umamo.runtime.model.storedToArtAffineForTile
import org.umamo.ui.model.artwork.resolveBirths
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The Automatic birth mesh over real artwork: Erica's PSD (`psd.sample`) and LimeBirb.psd beside it, each
 * opened the way the app opens artwork - its births meshed in parallel, packed at open - and against the
 * same file opened with quads.  Every drawable is born a generated mesh or says why it kept its quad,
 * every triangle keeps the quad's winding, every art pixel at the import's threshold lies inside its mesh,
 * the atlas takes at most one page more than the quads', and the open stays under a time ceiling
 * (`-DautomaticBirth.ceilingMs` overrides it).  Each phase's time is printed.  Each document is also
 * exported to build/auto-mesh for Cmo3ShapeGateTest and the official editor.  Self-skips without
 * `psd.sample`.
 */
class AutomaticBirthImportGateTest {
	private val sample: File? = System.getProperty("psd.sample")?.let(::File)?.takeIf { file -> file.isFile }
	private val ceilingMillis: Long = System.getProperty("automaticBirth.ceilingMs")?.toLongOrNull() ?: DEFAULT_CEILING_MILLIS
	private val quadOptions = SourceArtImportOptions()
	private val automaticOptions = SourceArtImportOptions(birthMesh = BirthMeshMode.Automatic(ArtMeshPreset.Standard.settings))

	@Test
	fun artworkOpensWithGeneratedMeshesThatCoverEveryPixel() =
		runBlocking {
			val first = sample

			if (first == null) {
				println("psd.sample not present; skipping the automatic birth gate")

				return@runBlocking
			}

			val files = listOf(first) + listOfNotNull(File(first.parentFile, "LimeBirb.psd").takeIf { file -> file.isFile })

			for (file in files) {
				gate(file)
			}
		}

	/**
	 * Opens one file both ways and checks the Automatic open.
	 *
	 * @param File file The artwork file.
	 */
	private suspend fun gate(file: File) {
		val bytes = file.readBytes()
		var started = System.nanoTime()
		val read = assertNotNull(readArtwork(bytes, file.name), "${file.name} reads as artwork")
		val parseMillis = millisSince(started)

		started = System.nanoTime()
		val quadPages = quadPageCount(read, file)
		val quadMillis = millisSince(started)

		started = System.nanoTime()
		val named = SourceArtImport.fromSourceArt(read.art, ArtSourceDescriptor(file.name, file.path, read.kind.extension), automaticOptions)
		withContext(Dispatchers.Default) { resolveBirths(named.pendingBirths, cache = null, progress = null) }
		val meshMillis = millisSince(started)

		started = System.nanoTime()
		val load = withContext(Dispatchers.Default) { buildArtDocumentConcurrently(read.art, read.kind, file.name, file.path, automaticOptions, read.contentHash, null, progress = null) }
		val automaticMillis = millisSince(started)
		val document = assertIs<ArtDocument>(assertIs<DocumentLoad.Loaded>(load).document)
		val puppet = document.puppet
		val fellBack = document.importNotices.filterIsInstance<SourceArtImportNotice.BirthMeshFellBackToQuad>()
		val fellBackNames = fellBack.mapTo(HashSet()) { notice -> notice.layerName }
		var vertexTotal = 0
		var flipped = 0

		for (drawable in puppet.drawables) {
			val mesh = assertNotNull(drawable.mesh)
			val tileId = assertNotNull(drawable.atlasTileId)
			val tile = puppet.atlas.tileById.getValue(tileId)
			vertexTotal += mesh.vertexCount
			assertTrue(mesh.vertexCount > 4 || tile.name in fellBackNames, "'${tile.name}' is born a generated mesh or says why not")

			for (triangle in 0 until mesh.triangleCount) {
				val corners = IntArray(3) { corner -> mesh.indices[3 * triangle + corner] }
				val area =
					orient2d(
						mesh.positions[2 * corners[0]].toDouble(),
						mesh.positions[2 * corners[0] + 1].toDouble(),
						mesh.positions[2 * corners[1]].toDouble(),
						mesh.positions[2 * corners[1] + 1].toDouble(),
						mesh.positions[2 * corners[2]].toDouble(),
						mesh.positions[2 * corners[2] + 1].toDouble(),
					)

				if (area <= 0.0) {
					flipped++
				}
			}

			val decoded = assertNotNull(document.artRasters.decodeRaster(tileId))
			val raster = LayerRaster(decoded.width, decoded.height, decoded.rgba)
			val artUvs = applyAffine(mesh.uvs, assertNotNull(puppet.atlas.storedToArtAffineForTile(tileId)))
			val artPoints = FloatArray(artUvs.size) { component -> artUvs[component] * if (component % 2 == 0) raster.width else raster.height }
			assertTrue(meshCoversOpaquePixels(raster.alphaChannel(), automaticOptions.alphaThreshold, artPoints, mesh.indices), "'${tile.name}' covers its art")
		}

		println(
			"[automatic-birth] ${file.name}: ${puppet.drawables.size} drawable(s), ${puppet.drawables.size - fellBack.size} generated, ${fellBack.size} kept the quad" +
				" (${fellBack.groupingBy { notice -> notice.reason }.eachCount()}), $vertexTotal vertices; pages quad $quadPages -> automatic ${puppet.atlas.pages.size};" +
				" parse $parseMillis ms, quad open $quadMillis ms, meshing alone $meshMillis ms, automatic open $automaticMillis ms",
		)
		assertEquals(0, flipped, "every triangle winds like the quad")
		assertTrue(puppet.atlas.pages.size <= quadPages + 1, "the looser pack takes at most one page more: $quadPages -> ${puppet.atlas.pages.size}")
		assertTrue(automaticMillis <= ceilingMillis, "${file.name} opens in $automaticMillis ms, past the $ceilingMillis ms ceiling")

		val exported = prepareCmo3Export(document, puppet, document.textures, file.nameWithoutExtension, 0L, 0)
		val exportFile = File("build/auto-mesh/${file.nameWithoutExtension}AutomaticBirthExportTest.cmo3")
		exportFile.parentFile.mkdirs()
		exportFile.writeBytes(Cmo3.write(exported.model))
		println("[automatic-birth]   wrote ${exportFile.absolutePath}; export notices ${exported.report.notices.groupingBy { notice -> notice::class.simpleName }.eachCount()}")
	}

	/**
	 * How many pages the file packs onto with quads: the baseline the generated meshes' looser pack is held to.
	 * Its own function, so the quad document is gone before the Automatic one is built.
	 *
	 * @param ReadArtwork read The read file.
	 * @param File        file The file.
	 * @return Int The page count.
	 */
	private fun quadPageCount(read: ReadArtwork, file: File): Int {
		val load = buildArtDocument(read.art, read.kind, file.name, file.path, quadOptions, read.contentHash)

		return assertIs<ArtDocument>(assertIs<DocumentLoad.Loaded>(load).document).puppet.atlas.pages.size
	}

	/**
	 * The milliseconds since [started].
	 *
	 * @param Long started A System.nanoTime reading.
	 * @return Long The milliseconds.
	 */
	private fun millisSince(started: Long): Long = (System.nanoTime() - started) / 1_000_000

	companion object {
		/** The open's ceiling: about twice the 4.4 s LimeBirb.psd's 330 layers took on the development machine. */
		private const val DEFAULT_CEILING_MILLIS: Long = 10_000L
	}
}