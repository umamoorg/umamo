package org.umamo.ui.document

import org.umamo.interop.moc3.Moc3Sidecars
import org.umamo.render.PuppetTextures
import org.umamo.render.canvasToParentSpaceFor
import org.umamo.render.encodeAtlasPng
import org.umamo.render.withTexturePagesFrom
import org.umamo.runtime.model.PuppetModel

/*
 * Decides WHAT a MOC3 export writes; Moc3DocumentWriter decides where the bytes land.
 *
 * The split is what makes the rules testable: everything here is pure, so the page naming, the
 * verbatim-vs-re-encode choice, and the family's shape can be exercised from a hand-built document
 * without a picker or a filesystem.  The app layer keeps only the picker call, the write, and the
 * report.
 */

/**
 * Builds the exported MOC3 family for [document] at the picked file name.
 *
 * @param PuppetDocument document        The document being exported (its decoded atlas set, and - for
 *                                       a MOC3 origin - its source manifest and page bytes).
 * @param PuppetModel    edited          The model to write; see [exportedModelFor].
 * @param String         destinationName The picked file's own name, which the family is named after.
 * @return Bundle The named byte arrays to write, plus the report of anything unrepresentable.
 */
fun prepareMoc3Export(
	document: PuppetDocument,
	edited: PuppetModel,
	destinationName: String,
): Moc3Sidecars.Bundle {
	// The page binding comes from the decoded atlas set, not the model: a CMO3-origin document has no
	// page index of its own (see withTexturePagesFrom).
	val bound = withTexturePagesFrom(edited, document.textures)
	// FileKit appends the extension, so the destination's own name is the family's base name.
	val basename = Moc3Sidecars.basenameFor(destinationName)
	val moc3Document = document as? Moc3Document
	return Moc3Sidecars.bundle(
		puppet = bound,
		basename = basename,
		pages = atlasPagesFor(document.textures, moc3Document, basename),
		sidecars = passThroughSidecars(moc3Document),
		source = moc3Document?.manifest,
		canvasToParentSpace = canvasToParentSpaceFor(bound),
	)
}

/**
 * The atlas pages the family writes, in the decoded set's page order.
 *
 * Page names come from the SOURCE manifest when there is one, so a re-export lands the family in the
 * shape (and the subdirectory) the model already used.  A CMO3-origin document has no manifest, so its
 * pages are named after the export instead.  Bytes are the source PNGs verbatim when the document
 * retains them - both faster and lossless - and a re-encode of the decoded RGBA only when it does not.
 *
 * Driven by the DECODED set rather than the retained bytes: that set is what the model's page indices
 * were resolved against, so a source whose manifest lists more pages than decoded cannot silently
 * emit a page nothing samples.
 *
 * @param PuppetTextures textures     The document's decoded atlas set, which drives the page list.
 * @param Moc3Document?  moc3Document The document when it came from a moc, else null (a CMO3 origin,
 *                                    which has neither source names nor source bytes).
 * @param String         basename     The family base name, for synthesized page names.
 * @return List The pages to write.
 */
internal fun atlasPagesFor(
	textures: PuppetTextures,
	moc3Document: Moc3Document?,
	basename: String,
): List<Moc3Sidecars.AtlasPage> =
	textures.atlases.mapIndexed { pageIndex, atlas ->
		val sourceName = moc3Document?.manifest?.fileReferences?.textures?.getOrNull(pageIndex)
		Moc3Sidecars.AtlasPage(
			fileName = sourceName ?: "$basename.$pageIndex.png",
			bytes = moc3Document?.atlasPages?.getOrNull(pageIndex) ?: encodeAtlasPng(atlas),
		)
	}
