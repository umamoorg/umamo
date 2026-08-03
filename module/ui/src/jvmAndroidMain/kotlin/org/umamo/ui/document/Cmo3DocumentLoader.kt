package org.umamo.ui.document

import org.umamo.format.cmo3.Cmo3Model
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.interop.cmo3.Cmo3Import
import org.umamo.render.PuppetTextures
import org.umamo.render.cmo3PuppetTextures
import org.umamo.runtime.model.PuppetModel
import org.umamo.storage.UmamoLog
import org.umamo.ui.viewport.LiveParams
import org.umamo.ui.viewport.initialLiveParams

/**
 * A loaded `.cmo3`: the format model (the retained graph Export CMO3 reconciles the session's edits
 * onto - Cmo3Export.apply), the runtime puppet + textures (for render), live params.
 */
class Cmo3Document(
	override val path: String,
	val cmo3: Cmo3Model,
	override val puppet: PuppetModel,
	override val textures: PuppetTextures,
	override val liveParams: LiveParams,
) : PuppetDocument

/**
 * Assembles a [Cmo3Document] from an already-parsed CMO3.  Unlike the MOC3 loader this needs no
 * sibling files at all: a `.cmo3` carries its own pixels, so the whole document comes out of the one
 * parsed graph.
 *
 * Throws nothing of its own - the byte-level [loadDocument] wraps this call, so a malformed-but-
 * parseable graph that trips the import or the atlas walk surfaces as ParseFailed there.
 *
 * @param Cmo3Model cmo3 The parsed CMO3.
 * @param String    name The file name (the failure display name).
 * @param String    path The stored path or URI string recorded on the document.
 * @return DocumentLoad The loaded document, or the failure reason.
 */
internal fun buildCmo3Document(cmo3: Cmo3Model, name: String, path: String): DocumentLoad {
	val root = cmo3.root as? CModelSource
	if (root == null) {
		UmamoLog.error("failed to open $path: the CMO3 has no model source")
		return DocumentLoad.Failed(DocumentOpenFailure(DocumentOpenError.ParseFailed, name))
	}
	val puppet = Cmo3Import.fromModelSource(root)
	// The atlas walk takes the root plus a pixel lookup rather than the Cmo3Model itself, which is what
	// lets it live in :render's commonMain; the archive-backed lookup is the only JVM-bound half and it
	// is supplied from here.
	val textures = cmo3PuppetTextures(root, cmo3::extractLayerPng)
	return DocumentLoad.Loaded(Cmo3Document(path, cmo3, puppet, textures, initialLiveParams(puppet)))
}
