package org.umamo.interop.moc3.export

import org.umamo.format.moc3.moc.Sections
import org.umamo.interop.ExportFormat
import org.umamo.interop.ExportNotice
import org.umamo.interop.ExportReport
import org.umamo.runtime.model.DrawableId

/**
 * The advisory findings a lowering accumulates, in the order a rigger will read them.
 *
 * An export ALWAYS writes; anything it cannot express lands here instead of being silently dropped.
 * The order is user-visible - the editor logs the report as a list and shows it in the export panel -
 * so the two positional decisions are stated rather than inherited from whatever order the producers
 * happen to run in: the version strip's own notices come FIRST (they describe the rig the rest of the
 * lowering then worked on), and the dropped-drawable flush comes LAST.
 *
 * Mutable on purpose, and passed to each producer explicitly rather than hidden inside the export
 * context, so a producer's signature says whether it can report.
 */
internal class Moc3ExportNotices {
	private val collected = ArrayList<ExportNotice>()

	/** Everything reported so far, in report order. */
	val notices: List<ExportNotice> get() = collected

	/**
	 * The report to hand back with the document.
	 *
	 * @return ExportReport The advisory findings; empty for a fully-lowered export.
	 */
	fun report(): ExportReport = ExportReport(ExportFormat.Moc3, collected.toList())

	/**
	 * Adopts the version strip's notices, which describe losses taken before any lowering ran.
	 *
	 * @param List<ExportNotice> stripNotices The strip's findings.
	 */
	fun addStripNotices(stripNotices: List<ExportNotice>) {
		collected.addAll(stripNotices)
	}

	/**
	 * Records a notice for something the lowering could not express.
	 *
	 * @param String category The entity category.
	 * @param String subject  The entity's id.
	 * @param String detail   What was not lowered.
	 */
	fun unsupported(category: String, subject: String, detail: String) {
		collected.add(ExportNotice.UnsupportedChange(category, subject, detail))
	}

	/**
	 * The ID as a MOC3 can actually store it, reporting anything that had to be cut.
	 *
	 * MOC3 §5.4 makes every id a fixed 64-byte record, so an id whose UTF-8 form does not fit with
	 * room for the terminator cannot be written.  The writer's own precondition rejects one, and an
	 * export that let that reach the caller would throw straight past the report and out of the file
	 * write - a crash where every other unrepresentable condition here produces a notice and a file.
	 * CMO3 places no width limit on these ids, and 22 CJK characters already exceed the record.
	 *
	 * @param String category The entity category, for the notice.
	 * @param String id       The id the model carries.
	 * @return String The id to write.
	 */
	fun mocId(category: String, id: String): String {
		if (id.encodeToByteArray().size < Sections.ID_STRIDE) {
			return id
		}
		// Trimmed by CHARACTER so the result stays valid UTF-8; cutting at a byte offset could land
		// mid-sequence and write a broken code point into the record.
		var fitted = id
		while (fitted.isNotEmpty() && fitted.encodeToByteArray().size >= Sections.ID_STRIDE) {
			fitted = fitted.substring(0, fitted.length - 1)
		}
		unsupported(
			category,
			id,
			"the id does not fit a moc's ${Sections.ID_STRIDE}-byte id record, so it was written " +
				"truncated to \"$fitted\"; shorten it if another object now shares that name",
		)
		return fitted
	}

	/**
	 * Reports every channel a bundle had to drop to its static.
	 *
	 * @param String              category The entity category.
	 * @param String              subject  The entity's id.
	 * @param Moc3ObjectKeyforms? keyforms The lowered keyforms, or null when unrepresentable.
	 */
	fun reportDemotions(category: String, subject: String, keyforms: Moc3ObjectKeyforms?) {
		for (channel in keyforms?.demotedChannels.orEmpty()) {
			unsupported(
				category,
				subject,
				"$channel is keyed over a narrower span than the object's grid, so it was written " +
					"as a constant (MOC3 stores one grid per object)",
			)
		}
	}

	/**
	 * Reports every drawable the eligibility pass left out, with the reason it gave.
	 *
	 * Called LAST by the orchestrator, so drop notices trail the per-object findings rather than
	 * leading them - the eligibility pass runs first, but its findings read better after the rest.
	 *
	 * @param Map<DrawableId, String> droppedDrawables Each omitted drawable and why.
	 */
	fun reportDroppedDrawables(droppedDrawables: Map<DrawableId, String>) {
		for ((drawableId, reason) in droppedDrawables) {
			unsupported("drawable", drawableId.raw, reason)
		}
	}
}
