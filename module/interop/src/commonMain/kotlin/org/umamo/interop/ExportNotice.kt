package org.umamo.interop

import org.umamo.runtime.model.RuntimeFeature

/**
 * One advisory finding from an export, CMO3 or MOC3.
 *
 * An export ALWAYS writes; notices only tell the user what the written file does not carry or what
 * a Cubism-side edit could do to it.  Nothing is ever silently dropped: every edit a lowering cannot
 * yet (or can never) express in the target format surfaces as an [UnsupportedChange], and every
 * feature the chosen target version predates surfaces as a [FeatureStripped].
 */
sealed interface ExportNotice {
	/**
	 * An edit the export lowering did not persist into the written file.
	 *
	 * @property String category The entity category ("parameter", "parameter group", "part",
	 *                           "deformer", "drawable", "glue", "document", "keyform").
	 * @property String subject  The affected entity's id, or the document field name.
	 * @property String detail   Diagnostic English text describing what was not lowered.
	 */
	data class UnsupportedChange(
		val category: String,
		val subject: String,
		val detail: String,
	) : ExportNotice

	/**
	 * Drawables whose base geometry or UVs diverged from the imported geometry/UV weld.  The export
	 * writes them as authored (the official editor renders the same displaced result Umamo shows),
	 * but Cubism couples geometry and UVs at the neutral form, so its own mesh-edit / re-atlas
	 * operations may re-derive and corrupt the mapping.
	 *
	 * @property List drawableNames The affected drawables' display names.
	 */
	data class WeldDivergence(val drawableNames: List<String>) : ExportNotice

	/**
	 * A feature the export target's runtime cannot load, removed from the written file.
	 *
	 * Distinct from [UnsupportedChange], which reports what the lowering could not express: this one
	 * reports what the lowering deliberately took OUT because the chosen version predates it.  The
	 * document itself keeps the feature - the strip runs on a copy - so re-exporting at a higher
	 * version brings it back.
	 *
	 * @property RuntimeFeature feature  The stripped feature.
	 * @property List           subjects The affected entities' display names, in document order.
	 */
	data class FeatureStripped(
		val feature: RuntimeFeature,
		val subjects: List<String>,
	) : ExportNotice

	/**
	 * The document has no source artwork, so the CMO3 was built around a fabricated one.
	 *
	 * A CMO3 is organised around the layered art it was imported from; a MOC3 carries only packed
	 * atlas pages, so the export reconstructs a stand-in source document by slicing each drawable's
	 * patch back out of the atlas.  The result opens in the official Cubism Editor with the right
	 * hierarchy, parameters, and atlas, but the puppet does NOT render there - a documented
	 * functionality gap that reconciling the ORIGINAL layered art (PSD/CLIP/KRA) into the document
	 * resolves.  The export still writes: the file is a faithful carrier of the rig, and Umamo
	 * itself reads it back losslessly.
	 *
	 * @property Int pageCount The number of atlas pages the stand-in source was built from.
	 */
	data class MissingSourceArt(val pageCount: Int) : ExportNotice
}

/**
 * The advisory outcome of one export: what the written file does not carry.
 *
 * Format-neutral because the contract is: an export ALWAYS writes, and notices only say what the
 * written file does not carry.  That holds identically for CMO3 and MOC3, so both report through this
 * rather than through parallel types the UI would have to handle twice.
 *
 * @property List notices The findings, empty for a fully-lowered export.
 */
data class ExportReport(val notices: List<ExportNotice>) {
	/** True when the export lowered everything with nothing to warn about. */
	val isEmpty: Boolean get() = notices.isEmpty()
}
