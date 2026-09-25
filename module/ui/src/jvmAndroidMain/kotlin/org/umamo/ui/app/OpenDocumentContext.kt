package org.umamo.ui.app

import kotlinx.coroutines.CoroutineScope
import org.umamo.edit.EditorSession
import org.umamo.edit.seed.ParameterTemplate
import org.umamo.interop.art.ArtworkAnchor
import org.umamo.interop.art.SourceArtImportOptions
import org.umamo.settings.Settings
import org.umamo.storage.FilePicker
import org.umamo.ui.action.CommandRegistry
import org.umamo.ui.document.Document
import org.umamo.ui.document.DocumentFile
import org.umamo.ui.document.PuppetDocument
import org.umamo.ui.document.artworkImportOptions
import org.umamo.ui.model.SessionAtlasPages
import org.umamo.ui.settings.IMPORT_ALIGNMENT_KEY
import org.umamo.ui.settings.IMPORT_PARAMETER_TEMPLATE_KEY
import org.umamo.ui.viewport.AtlasPageBinding
import org.umamo.ui.viewport.PuppetViewportService
import org.umamo.ui.workspace.AreaViewStates

/**
 * An open puppet document with the session that edits it and the page set that session resolves to: the
 * three an edit-bearing operation needs TOGETHER.  Holding them as one value is what keeps a save or an
 * export from pairing one document's rig with another's atlas pages.
 *
 * @property PuppetDocument     document   The open puppet document.
 * @property EditorSession      session    Its editing session.
 * @property SessionAtlasPages? atlasPages The session's resolved atlas pages (repack and undo aware), or null.
 */
internal class OpenPuppet(
	val document: PuppetDocument,
	val session: EditorSession,
	val atlasPages: SessionAtlasPages?,
) {
	/**
	 * The page set the session resolves to right now: the document's own pages until a repack composed a new
	 * set, which is the identity gate a CMO3 export's page patch and a save's stored pages both key on.  Read
	 * at the moment of the call, since a repack or an undo swaps it.
	 *
	 * @return AtlasPageBinding The atlas value and the page pixels it denotes.
	 */
	fun pageBinding(): AtlasPageBinding = atlasPages?.binding?.value ?: AtlasPageBinding(document.puppet.atlas, document.textures)
}

/**
 * Everything the app derives from the open document, as one immutable value built where the document is
 * read.  A controller that lives for one document holds the value it was made with; a controller that
 * outlives documents asks for the current one at each call and then works from that single answer, so no
 * operation ever mixes one document's parts with another's.
 *
 * @property Document?          document       The open document, or null.
 * @property EditorSession?     session        The open document's editing session (non-null for a puppet document).
 * @property DocumentFile?      file           Where the document saves, or null with no document.
 * @property SessionAtlasPages? atlasPages     The session's resolved atlas pages, or null with no puppet document.
 * @property AreaViewStates     areaViewStates Every area's view state for this document.
 * @property DocumentViewportSlot viewport     The document's render service while one is live.
 */
internal class OpenDocumentContext(
	val document: Document?,
	val session: EditorSession?,
	val file: DocumentFile?,
	val atlasPages: SessionAtlasPages?,
	val areaViewStates: AreaViewStates,
	val viewport: DocumentViewportSlot = DocumentViewportSlot(),
) {
	/** The puppet document with its session and pages, or null unless a puppet document is open with its session. */
	val puppet: OpenPuppet? =
		if (document is PuppetDocument && session != null) {
			OpenPuppet(document, session, atlasPages)
		} else {
			null
		}
}

/**
 * Where the operations that use the renderer outside a viewport area - a save's cameras and thumbnail, Export
 * Image - find the open document's render service.
 *
 * The service is built inside the document's composition, after the context that the controllers hold, so it
 * is handed over here rather than through the constructor: the viewport wiring fills the slot while the
 * service lives and clears it when the service goes, so nothing ever asks a disposed engine.  Empty on a
 * platform without a puppet renderer, where those operations fall back or hide.
 */
internal class DocumentViewportSlot {
	/** The live render service, or null while there is none. */
	var service: PuppetViewportService? = null
}

/**
 * The collaborators every app controller shares, for the application's life.
 *
 * @property Settings        settings        The settings store.
 * @property CoroutineScope  scope           The app composable's scope the controllers' work runs in.
 * @property FilePicker      filePicker      The native open and save dialogs.
 * @property CommandRegistry commandRegistry The registry the shell's dialogs and alerts are raised through.
 * @property Function        current         The open document's context as of the call.  A controller registered once
 *   reads the document through this rather than holding one, which is what keeps a handler registered at
 *   launch from asking about whichever document was open then - none at all, on a normal launch.
 * @property Function        onOpen          Swaps a newly opened document in.
 * @property Function        untitledName    The localized name a never-saved document's Save As suggests.
 * @property HostHeap?       hostHeap        The memory limit the host started the editor with, which an export
 *   that runs out of memory names; null for a host that has no say in it.
 */
internal class EditorAppServices(
	val settings: Settings,
	val scope: CoroutineScope,
	val filePicker: FilePicker,
	val commandRegistry: CommandRegistry,
	val current: () -> OpenDocumentContext,
	val onOpen: (Document) -> Unit,
	val untitledName: () -> String,
	val hostHeap: HostHeap? = null,
) {
	/** The model export running now, which a second export, a quit, and a document replace all defer to. */
	val modelExports: ModelExportGate = ModelExportGate()

	/**
	 * What an artwork import seeds with and where it places a later file, read at the moment the import
	 * runs so the preferences rows apply to the next import without a restart.
	 *
	 * @return SourceArtImportOptions The import options under the configured parameter template and anchor.
	 */
	fun configuredArtworkImportOptions(): SourceArtImportOptions =
		artworkImportOptions(
			ParameterTemplate.fromKey(settings.getString(IMPORT_PARAMETER_TEMPLATE_KEY)),
			ArtworkAnchor.fromKey(settings.getString(IMPORT_ALIGNMENT_KEY)),
		)
}