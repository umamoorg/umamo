package org.umamo.interop.cmo3.export

import org.umamo.interop.ExportEntityCategory
import org.umamo.interop.ExportFormat
import org.umamo.interop.ExportNotice
import org.umamo.interop.ExportNoticeReason
import org.umamo.interop.ExportReport

/**
 * The advisory findings a CMO3 export accumulates, in the order its passes report them.
 *
 * An export always writes; an edit it cannot represent lands here instead of being silently dropped.
 * Mutable on purpose, and handed to each lowering explicitly, so a lowering's constructor says that it
 * can report.
 */
internal class Cmo3ExportNotices {
	private val collected = ArrayList<ExportNotice>()

	/**
	 * The report to hand back with the export.
	 *
	 * @return ExportReport The advisory findings; empty for a fully-lowered export.
	 */
	fun report(): ExportReport = ExportReport(ExportFormat.Cmo3, collected.toList())

	/**
	 * Records a finding.
	 *
	 * @param ExportNotice notice The finding.
	 */
	fun add(notice: ExportNotice) {
		collected.add(notice)
	}

	/**
	 * Records an edit this export cannot represent.
	 *
	 * @param ExportEntityCategory category The kind of entity the edit is on.
	 * @param String? subject The entity's id, or null.
	 * @param ExportNoticeReason reason Why it cannot be written.
	 */
	fun unsupported(
		category: ExportEntityCategory,
		subject: String?,
		reason: ExportNoticeReason,
	) {
		collected.add(ExportNotice.UnsupportedChange(category, subject, reason))
	}
}