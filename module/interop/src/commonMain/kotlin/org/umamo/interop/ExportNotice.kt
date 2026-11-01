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
	 * @property ExportEntityCategory category The kind of thing the finding is about.
	 * @property String?              subject  The affected entity's id, or null for a document-level
	 *                                         finding, whose [reason] names the field itself.
	 * @property ExportNoticeReason   reason   Why the lowering could not carry the edit.
	 */
	data class UnsupportedChange(
		val category: ExportEntityCategory,
		val subject: String?,
		val reason: ExportNoticeReason,
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
	 * Not every drawable's art came from a real source layer, so the CMO3 was built around a
	 * fabricated source document - the whole file, for a MOC3-origin document, which has no source
	 * artwork at all.
	 *
	 * The stand-in slices each drawable's patch back out of the atlas pages, so the file opens and
	 * renders in the official Cubism Editor, but its layers cannot be redrawn upstream and refreshed,
	 * and their quality is capped by the page they were cut from.  Reconciling the original layered art
	 * (PSD/CLIP/KRA) into the document replaces them.
	 *
	 * @property Int pageCount The number of atlas pages the stand-in source was built from.
	 */
	data class MissingSourceArt(val pageCount: Int) : ExportNotice
}

/**
 * One log line for an export notice: what the editor's log and the command-line tool's report both print,
 * the headless-visible mirror of the editor's report alert.
 *
 * Deliberately English and deliberately structural: the log is a diagnostic surface, read off a bug
 * report rather than by a rigger mid-edit, so it wants text that is stable across locales and greps
 * straight back to a call site.  Printing the reason itself gives that for free and, unlike a second
 * hand-written copy of the alert's prose, cannot drift from the case list it describes.
 *
 * @param ExportNotice notice The notice to describe.
 * @return String The log text.
 */
fun describeExportNotice(notice: ExportNotice): String =
	when (notice) {
		is ExportNotice.UnsupportedChange -> {
			if (notice.subject == null) {
				"[${notice.category}] ${notice.reason}"
			} else {
				"[${notice.category}] ${notice.subject}: ${notice.reason}"
			}
		}

		is ExportNotice.WeldDivergence -> {
			"weld divergence on ${notice.drawableNames.joinToString()}"
		}

		is ExportNotice.FeatureStripped -> {
			"${notice.feature} is not in the exported moc version; removed from " +
				notice.subjects.take(8).joinToString() +
				if (notice.subjects.size > 8) " (+${notice.subjects.size - 8} more)" else ""
		}

		is ExportNotice.MissingSourceArt -> {
			"no source artwork: the CMO3 was built around a stand-in document rebuilt from ${notice.pageCount} atlas page(s), " +
				"so its layers are atlas slices rather than the original artwork"
		}
	}

/**
 * The kind of thing an [ExportNotice.UnsupportedChange] is about, naming the panel a rigger would
 * look in to find it.
 *
 * An enum so the compiler enforces the vocabulary and the label can be localized.  [Keyform] is the
 * odd member - a keyform is not an entity in its own right - but the notice's subject there is the
 * owning drawable or part, so the rendered line still reads as a place to look.
 */
enum class ExportEntityCategory {
	Parameter,
	ParameterGroup,
	Part,
	Deformer,
	Drawable,
	Glue,
	Document,
	Keyform,
}

/**
 * The format one export wrote, naming which file family a report's findings are about.
 *
 * Deliberately narrower than [org.umamo.format.FileKind], which spans every family Umamo reads
 * (art sources, sidecars, raster pages) - only the ones an export path writes belong here, so a
 * `when` over an [ExportReport]'s format stays exhaustive without an `else` that would silently
 * swallow a new export target.
 */
enum class ExportFormat {
	Cmo3,
	Moc3,
}

/**
 * The advisory outcome of one export: what the written file does not carry.
 *
 * CMO3 and MOC3 both report through this one type, so the UI handles notices once; [format] keeps the
 * report attributable, since no notice names the file family it is about.
 *
 * @property ExportFormat format  The format the export wrote.
 * @property List         notices The findings, empty for a fully-lowered export.
 */
data class ExportReport(val format: ExportFormat, val notices: List<ExportNotice>) {
	/** True when the export lowered everything with nothing to warn about. */
	val isEmpty: Boolean get() = notices.isEmpty()
}