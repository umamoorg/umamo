package org.umamo.ui.document

import org.umamo.interop.moc3.Moc3ExportOptions
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
 * @param PuppetDocument document        The document being exported (for a MOC3 origin, its source
 *                                       manifest and page bytes).
 * @param PuppetModel    edited          The model to write; see [exportedModelFor].
 * @param PuppetTextures effectiveTextures The SESSION's page set - the document's own for an
 *                                       unedited atlas, the recomposed set after a repack - so the
 *                                       written pages match what the viewport shows.
 * @param String         destinationName The picked file's own name, which the family is named after.
 * @param Moc3ExportOptions options      What the rigger chose to include; the default is the
 *                                       options-less behavior.
 * @return Bundle The named byte arrays to write, plus the report of anything unrepresentable.
 */
fun prepareMoc3Export(
	document: PuppetDocument,
	edited: PuppetModel,
	effectiveTextures: PuppetTextures,
	destinationName: String,
	options: Moc3ExportOptions = Moc3ExportOptions.Default,
): Moc3Sidecars.Bundle {
	// The page binding comes from the decoded atlas set, not the model: a CMO3-origin document has no
	// page index of its own (see withTexturePagesFrom).
	val bound = withTexturePagesFrom(edited, effectiveTextures)
	// FileKit appends the extension, so the destination's own name is the family's base name.
	val basename = Moc3Sidecars.basenameFor(destinationName)
	val moc3Document = document as? Moc3Document
	return Moc3Sidecars.bundle(
		puppet = bound,
		basename = basename,
		pages = atlasPagesFor(effectiveTextures, moc3Document, basename),
		sidecars = exportedSidecarsFor(passThroughSidecars(moc3Document), options),
		source = moc3Document?.manifest,
		canvasToParentSpace = canvasToParentSpaceFor(bound),
		options = options,
	)
}

/**
 * The pass-through sidecars an export carries under [options].
 *
 * Physics and user data are the two a rigger can opt out of; every other kind always rides along.
 * Filtered HERE, before the bundle, because the manifest derives its physics/userData references
 * from the sidecar list it is handed - dropping a sidecar upstream drops its reference with it,
 * so the two cannot disagree.
 *
 * @param List sidecars The retained pass-through sidecars.
 * @param Moc3ExportOptions options What the rigger chose to include.
 * @return List The sidecars to bundle.
 */
internal fun exportedSidecarsFor(
	sidecars: List<Moc3Sidecars.PassThroughSidecar>,
	options: Moc3ExportOptions,
): List<Moc3Sidecars.PassThroughSidecar> =
	sidecars.filter { sidecar ->
		when (sidecar.kind) {
			Moc3Sidecars.SidecarKind.Physics -> options.includePhysics
			Moc3Sidecars.SidecarKind.UserData -> options.includeUserData
			Moc3Sidecars.SidecarKind.Pose,
			Moc3Sidecars.SidecarKind.Expression,
			Moc3Sidecars.SidecarKind.Motion,
			-> true
		}
	}

/**
 * The atlas pages the family writes, in the decoded set's page order.
 *
 * Page names come from the SOURCE manifest when there is one, so a re-export lands the family in the
 * shape (and the subdirectory) the model already used.  A CMO3-origin document has no manifest, so
 * its pages are named the way the official editor's bake names them - a `Basename.Resolution/`
 * subfolder holding `texture_NN.png` files (two-digit minimum, underscore separated); every corpus
 * manifest follows exactly this shape, and runtimes' tooling expects it.  Bytes are the source PNGs
 * verbatim when the document retains them - both faster and lossless - and a re-encode of the
 * decoded RGBA only when it does not.
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
): List<Moc3Sidecars.AtlasPage> {
	val textureFolder = "$basename.${atlasResolutionFor(textures)}"
	return textures.atlases.mapIndexed { pageIndex, atlas ->
		val sourceName = moc3Document?.manifest?.fileReferences?.textures?.getOrNull(pageIndex)
		Moc3Sidecars.AtlasPage(
			fileName = sourceName ?: "$textureFolder/texture_${paddedPageIndex(pageIndex)}.png",
			// The verbatim-bytes preference is only reachable for a MOC3-origin document, which the
			// repack's availability gate excludes (no atlas tiles) - so a recomposed effective set can
			// never be shadowed by stale retained bytes here.
			bytes = moc3Document?.atlasPages?.getOrNull(pageIndex) ?: encodeAtlasPng(atlas),
		)
	}
}

/**
 * The single per-project resolution the texture subfolder is named with.
 *
 * The official layout carries ONE resolution for the whole family (`Azxiana.4096/`,
 * `modelF.16384/`), so a set whose pages differ still has to pick one number - the largest
 * dimension across every page, which is the size the export target actually needed.
 *
 * @param PuppetTextures textures The decoded atlas set.
 * @return Int The folder resolution; 0 only for a set with no pages, which names no files.
 */
private fun atlasResolutionFor(textures: PuppetTextures): Int =
	textures.atlases.maxOfOrNull { atlas -> maxOf(atlas.width, atlas.height) } ?: 0

/**
 * A page index in the official texture file naming: at least two digits, so page 3 is
 * `texture_03.png` while page 100 keeps all its digits.
 *
 * @param Int pageIndex The page's index in the decoded set.
 * @return String The padded index.
 */
private fun paddedPageIndex(pageIndex: Int): String = pageIndex.toString().padStart(2, '0')