package org.umamo.format.art

import org.umamo.format.clip.ClipReader
import org.umamo.format.kra.KraReader
import org.umamo.format.psd.PsdReader
import java.io.File

/**
 * Locates every layered corpus sample (PSD, CLIP, KRA) with the reader that decodes it, walking up
 * from the working directory to find test/corpus.
 *
 * Shared by every corpus-gated layered-art test, so a new corpus subdirectory or a new reader reaches
 * all of them at once: a private copy that missed the addition would keep its test printing "checked
 * N samples" while never seeing the new format.
 *
 * @return List<Pair<File, ArtReader>> The samples, empty when no corpus is present.
 */
internal fun locateLayeredCorpusSamples(): List<Pair<File, ArtReader>> {
	var directory: File? = File(System.getProperty("user.dir"))
	while (directory != null) {
		val corpus = File(directory, "test/corpus")
		if (corpus.isDirectory) {
			val samples = mutableListOf<Pair<File, ArtReader>>()
			for ((subdirectory, reader) in listOf("psd" to PsdReader, "clip" to ClipReader, "krita" to KraReader)) {
				File(corpus, subdirectory).listFiles { file -> file.isFile }
					?.sortedBy { file -> file.name }
					?.forEach { file -> samples.add(file to reader) }
			}
			return samples
		}
		directory = directory.parentFile
	}
	return emptyList()
}