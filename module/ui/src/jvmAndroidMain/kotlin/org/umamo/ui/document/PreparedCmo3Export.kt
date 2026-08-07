package org.umamo.ui.document

import org.umamo.format.cmo3.Cmo3Model
import org.umamo.interop.ExportReport

/**
 * A CMO3 export ready to write: the model to serialize plus what the lowering could not carry.
 *
 * The two origins produce this the same way but mean different things by [model] - a CMO3-origin
 * document's is its own retained graph, reconciled in place, while a MOC3-origin document's is a graph
 * synthesized for the occasion.  The writer does not care which, which is the point of the type.
 *
 * @property Cmo3Model    model  The model to serialize.
 * @property ExportReport report Everything unrepresentable; surfaced to the rigger, never swallowed.
 */
class PreparedCmo3Export(val model: Cmo3Model, val report: ExportReport)
