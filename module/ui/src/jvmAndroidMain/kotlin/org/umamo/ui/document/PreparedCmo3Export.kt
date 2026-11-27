package org.umamo.ui.document

import org.umamo.format.cmo3.Cmo3Model
import org.umamo.interop.ExportReport

/**
 * A CMO3 export ready to write: the model to serialize plus what the lowering could not carry.
 *
 * The origins produce this the same way but mean different things by [model] - a CMO3-origin
 * document's is a working copy read fresh from its retained archive and reconciled, while every other
 * document's is a graph synthesized for the occasion.  Either way the model is the export's own: the
 * open document never reads it, and the document's own graph is left as it was opened.  The writer
 * does not care which, which is the point of the type.
 *
 * @property Cmo3Model    model  The model to serialize, owned by this export alone.
 * @property ExportReport report Everything unrepresentable; surfaced to the rigger, never swallowed.
 */
class PreparedCmo3Export(val model: Cmo3Model, val report: ExportReport)