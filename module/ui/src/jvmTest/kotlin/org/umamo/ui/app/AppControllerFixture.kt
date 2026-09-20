package org.umamo.ui.app

import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.CoroutineScope
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import org.umamo.edit.EditorSession
import org.umamo.settings.Settings
import org.umamo.storage.FilePicker
import org.umamo.storage.OkioAppStorage
import org.umamo.ui.action.Command
import org.umamo.ui.action.CommandRegistry
import org.umamo.ui.document.Document
import org.umamo.ui.document.DocumentFile
import org.umamo.ui.document.PuppetDocument
import org.umamo.ui.workspace.AreaViewStates

/**
 * The app controllers' collaborators for a test, with no composition and no dialogs: in-memory settings,
 * a picker that is always cancelled, and a registry whose shell commands record what they were asked
 * instead of raising an overlay.
 *
 * The open document is a plain variable the controllers read through [EditorAppServices.current], the way
 * the app's holder hands them the live composition's context - so a test swaps the document by assigning
 * [context], exactly as opening one does.
 *
 * @param CoroutineScope scope The scope the controllers' work runs in (a test's own scope).
 */
internal class AppControllerFixture(
	scope: CoroutineScope,
) {
	/** Every command the controllers dispatched, in order, with its argument. */
	val invocations = ArrayList<Pair<String, Any?>>()

	/** Every document the controllers swapped in, in order. */
	val opened = ArrayList<Document>()

	/** The settings the controllers write recent files to. */
	val settings: Settings

	/** The registry, holding a recorder for each shell command a controller raises. */
	val registry = CommandRegistry()

	/** The open document's context as the controllers see it; starts with no document, like a launch. */
	var context: OpenDocumentContext = contextFor(null)

	/** The collaborators the controllers under test are built over. */
	val services: EditorAppServices

	init {
		val fileSystem = FakeFileSystem()
		val configDirectory = "/config".toPath()
		fileSystem.createDirectories(configDirectory)
		settings = Settings.load(OkioAppStorage(fileSystem, configDirectory, "/data".toPath()), "{}")
		for (commandId in listOf("document.confirmReplace", "document.confirmExit", "document.openFailed", "document.alert")) {
			registry.register(Command(commandId, title = null) { argument -> invocations.add(commandId to argument) })
		}
		services =
			EditorAppServices(
				settings = settings,
				scope = scope,
				filePicker = CancelledFilePicker,
				commandRegistry = registry,
				current = { context },
				onOpen = { document -> opened.add(document) },
				untitledName = { "Untitled" },
			)
	}

	/**
	 * The arguments [commandId] was dispatched with, in order.
	 *
	 * @param String commandId The command.
	 * @return List Its arguments, one per dispatch.
	 */
	fun argumentsOf(commandId: String): List<Any?> = invocations.filter { (id, _) -> id == commandId }.map { (_, argument) -> argument }

	companion object {
		/**
		 * The context the app would build for [document]: its session and its save target, no resolved pages
		 * (the fallback is the document's own), and empty area state.
		 *
		 * @param Document? document The open document, or null.
		 * @return OpenDocumentContext The context.
		 */
		fun contextFor(document: Document?): OpenDocumentContext {
			val session = (document as? PuppetDocument)?.let { puppetDocument -> EditorSession(puppetDocument.puppet, puppetDocument.liveParams.values) }
			return OpenDocumentContext(document, session, document?.let { origin -> DocumentFile(origin) }, atlasPages = null, areaViewStates = AreaViewStates(null))
		}
	}
}

/** A picker whose every dialog is cancelled: the tests here never reach a file. */
private object CancelledFilePicker : FilePicker {
	override suspend fun openFile(extensions: List<String>): PlatformFile? = null

	override suspend fun saveFile(suggestedName: String, extension: String): PlatformFile? = null
}