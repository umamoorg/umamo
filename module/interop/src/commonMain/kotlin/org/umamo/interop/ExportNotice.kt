package org.umamo.interop

/**
 * One advisory finding from a CMO3 export.
 *
 * An export ALWAYS writes; notices only tell the user what the written file does not carry or what
 * a Cubism-side edit could do to it.  Nothing is ever silently dropped: every edit the lowering
 * cannot yet (or can never) express in CMO3 surfaces as an [UnsupportedChange].
 */
sealed interface ExportNotice {
	/**
	 * An edit the export lowering did not persist into the CMO3 graph.
	 *
	 * @property String category The entity category ("parameter", "part", "deformer", "drawable",
	 *                           "glue", "document", "keyform").
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
}

/**
 * The advisory outcome of one CMO3 export: what the written file does not carry.
 *
 * @property List notices The findings, empty for a fully-lowered export.
 */
data class Cmo3ExportReport(val notices: List<ExportNotice>) {
	/** True when the export lowered everything with nothing to warn about. */
	val isEmpty: Boolean get() = notices.isEmpty()
}
