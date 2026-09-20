package org.umamo.ui.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path.Companion.toPath
import org.umamo.edit.EditorSession
import org.umamo.edit.NoticePlacement
import org.umamo.reimport.PollingSourceWatcher
import org.umamo.reimport.SourceWatchCoordinator
import org.umamo.reimport.SourceWatchEvent
import org.umamo.reimport.WatchMode
import org.umamo.reimport.WatchedSource
import org.umamo.settings.Settings
import org.umamo.storage.UmamoLog
import org.umamo.storage.contentHashOfFile
import org.umamo.ui.action.CommandRegistry
import org.umamo.ui.document.Document
import org.umamo.ui.document.PuppetDocument
import org.umamo.ui.document.fileModifiedAtMillis
import org.umamo.ui.document.isFileSystemPath
import org.umamo.ui.document.systemSourceFilePresence
import org.umamo.ui.model.SourceWatchState
import org.umamo.ui.settings.IMPORT_WATCH_MODE_KEY
import org.umamo.ui.workspace.commands.ReloadScope

/**
 * One open document's artwork watcher: the platform watcher and the policy over it, closed together.
 *
 * @property PollingSourceWatcher   watcher     The file poller.
 * @property SourceWatchCoordinator coordinator The settle-hash-idle policy the app's events come from.
 */
internal class DocumentWatch(
	val watcher: PollingSourceWatcher,
	val coordinator: SourceWatchCoordinator,
) {
	/**
	 * What the watcher tells the Sources space, as ONE instance for the watcher's life: it is handed to a
	 * static composition local, where a fresh wrapper per recomposition would invalidate the whole shell.
	 */
	val state: SourceWatchState = SourceWatchState(coordinator.pending, coordinator.serial)

	/** Stops the policy and the watcher. */
	fun close() {
		coordinator.close()
		watcher.close()
	}
}

/**
 * The open document's artwork watcher, with its whole life: one per open puppet document, closed when
 * the document goes, following the model's source list and landing its events on the session.
 *
 * The watcher runs over the CALLER's scope rather than one of its own, because that scope's dispatcher
 * is what confines the coordinator's state.  The watch mode is read live from settings at every
 * decision, so the Import setting applies at once.  A due reload goes THROUGH the command registry, so
 * the operation strip shows in the last work surface exactly as a pressed Reload does; the rest are
 * notices.  Real file-system paths only: a platform uri has nothing to poll.
 *
 * @param Document?       document        The open document, or null.
 * @param EditorSession?  session         The open document's session, or null.
 * @param CoroutineScope  scope           The app composable's scope the watcher and the coordinator run in.
 * @param Settings        settings        The settings the watch mode is read from.
 * @param CommandRegistry commandRegistry The registry a due reload dispatches through.
 * @return DocumentWatch? The watcher, or null with no puppet document open.
 */
@Composable
internal fun rememberDocumentWatch(
	document: Document?,
	session: EditorSession?,
	scope: CoroutineScope,
	settings: Settings,
	commandRegistry: CommandRegistry,
): DocumentWatch? {
	val documentWatch: DocumentWatch? =
		remember(document, session) {
			val activeSession = session
			if (document is PuppetDocument && activeSession != null) {
				val watcher = PollingSourceWatcher(scope, FileSystem.SYSTEM, statContext = Dispatchers.IO)
				DocumentWatch(
					watcher,
					SourceWatchCoordinator(
						scope = scope,
						watcher = watcher,
						hashOf = { path -> withContext(Dispatchers.IO) { contentHashOfFile(FileSystem.SYSTEM, path.toPath()) } },
						exists = { path -> systemSourceFilePresence(path) },
						modifiedAtOf = { path -> withContext(Dispatchers.IO) { fileModifiedAtMillis(path) } },
						isIdle = { activeSession.isQuiescent },
						mode = { WatchMode.fromKey(settings.getString(IMPORT_WATCH_MODE_KEY)) },
					),
				)
			} else {
				null
			}
		}
	DisposableEffect(documentWatch) {
		onDispose { documentWatch?.close() }
	}
	LaunchedEffect(documentWatch, session) {
		val watch = documentWatch ?: return@LaunchedEffect
		val activeSession = session ?: return@LaunchedEffect

		fun trackCurrentSources() {
			watch.coordinator.track(
				activeSession.model.value.sources.mapNotNull { source ->
					val path = source.path?.takeIf { candidate -> isFileSystemPath(candidate) } ?: return@mapNotNull null
					WatchedSource(source.id, path, source.contentHash, source.lastModified)
				},
			)
		}
		launch { activeSession.model.collect { trackCurrentSources() } }
		launch {
			settings.changes.collect { changedKey ->
				if (changedKey == IMPORT_WATCH_MODE_KEY) {
					trackCurrentSources()
				}
			}
		}
		watch.coordinator.events.collect { event ->
			when (event) {
				is SourceWatchEvent.ReloadDue -> commandRegistry.invoke("document.reloadArtwork", ReloadScope(event.sourceIds))
				is SourceWatchEvent.ChangedOnDisk ->
					activeSession.emitNotice("notice.watch.changed", NoticePlacement.StatusBar, listOf(event.sourceIds.size.toString()))
				is SourceWatchEvent.StaleAtOpen ->
					activeSession.emitNotice("notice.watch.staleAtOpen", NoticePlacement.StatusBar, listOf(event.sourceIds.size.toString()))
				is SourceWatchEvent.Missing -> {
					val name = activeSession.model.value.sources.firstOrNull { source -> source.id == event.sourceId }?.name ?: event.sourceId.raw
					UmamoLog.warn("watch artwork: '$name' is no longer where the document read it")
					activeSession.emitNotice("notice.watch.missing", NoticePlacement.StatusBar, listOf(name))
				}
			}
		}
	}
	return documentWatch
}