package org.umamo.reimport

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchKey
import java.nio.file.WatchService
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * A [SourceWatcher] over java.nio's WatchService, for the desktop JVM and Android alike (minSdk 26
 * has it; a SAF uri has no path and is refused).
 *
 * Directories are watched, not files: an art program saves through a temporary file and a rename,
 * which a watch on the file itself never sees, so each watched file's parent directory is registered
 * once (a refcount per directory) and events are matched to the files under it by name.  An overflow
 * - the platform dropped events - is taken as every watched file in that directory having changed;
 * the coordinator's hash check then sorts out which did.
 *
 * One reader loop polls the service on the IO dispatcher and hands each change to its listener in
 * [scope]'s own dispatcher (or [deliverOn] when given), so a listener can touch state confined to
 * that scope.  Never Dispatchers.Main by name: the desktop classpath carries no Main dispatcher
 * module (Compose Desktop's UI scope runs on its own frame clock), and naming it would fail at run
 * time there.  Closing the watcher ends the loop.
 *
 * @param CoroutineScope   scope     The scope the reader loop and the deliveries run in.
 * @param CoroutineContext deliverOn Extra context for the deliveries; empty by default, so they run on the scope's dispatcher.
 */
class NioSourceWatcher(
	private val scope: CoroutineScope,
	private val deliverOn: CoroutineContext = EmptyCoroutineContext,
) : SourceWatcher, AutoCloseable {
	/** One registered directory: its key and the listeners of the files under it, by file name. */
	private class DirectoryWatch(val key: WatchKey) {
		val listenersByFileName = HashMap<String, MutableList<Registration>>()
	}

	/** One watch() call. */
	private class Registration(val path: String, val listener: SourceWatcher.ChangeListener)

	private val lock = Any()
	private val service: WatchService = FileSystems.getDefault().newWatchService()
	private val directories = HashMap<Path, DirectoryWatch>()
	private val reader: Job = scope.launch(Dispatchers.IO) { readLoop() }

	/**
	 * Begins watching [path]'s directory for changes to its file name.
	 *
	 * @param String         path     The file to watch; a uri or a path with no parent gets a no-op handle.
	 * @param ChangeListener listener Told in the scope whenever the file is written, replaced, created, or deleted.
	 * @return AutoCloseable The subscription.
	 */
	override fun watch(path: String, listener: SourceWatcher.ChangeListener): AutoCloseable {
		if (path.contains("://")) {
			return AutoCloseable {}
		}
		val file = runCatching { Paths.get(path).toAbsolutePath() }.getOrNull() ?: return AutoCloseable {}
		val directory = file.parent ?: return AutoCloseable {}
		val fileName = file.fileName?.toString() ?: return AutoCloseable {}
		val registration = Registration(path, listener)
		synchronized(lock) {
			val watch =
				directories.getOrPut(directory) {
					val key =
						runCatching {
							directory.register(service, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_DELETE)
						}.getOrNull() ?: return AutoCloseable {}
					DirectoryWatch(key)
				}
			watch.listenersByFileName.getOrPut(fileName) { ArrayList() }.add(registration)
		}
		return AutoCloseable { unwatch(directory, fileName, registration) }
	}

	/** Stops the reader loop and the service; every subscription is dead after this. */
	override fun close() {
		reader.cancel()
		synchronized(lock) {
			directories.clear()
		}
		runCatching { service.close() }
	}

	/**
	 * Removes one registration, releasing the directory's key when it was the last.
	 *
	 * @param Path         directory    The registered directory.
	 * @param String       fileName     The watched file's name under it.
	 * @param Registration registration The subscription being closed.
	 */
	private fun unwatch(directory: Path, fileName: String, registration: Registration) {
		synchronized(lock) {
			val watch = directories[directory] ?: return
			val listeners = watch.listenersByFileName[fileName] ?: return
			listeners.remove(registration)
			if (listeners.isEmpty()) {
				watch.listenersByFileName.remove(fileName)
			}
			if (watch.listenersByFileName.isEmpty()) {
				watch.key.cancel()
				directories.remove(directory)
			}
		}
	}

	/** Takes keys until cancelled, forwarding each event to the listeners of the file it names. */
	private suspend fun readLoop() {
		while (scope.isActive && reader.isActive) {
			val key = runCatching { service.poll(POLL_MILLIS, TimeUnit.MILLISECONDS) }.getOrNull() ?: continue
			val directory = key.watchable() as? Path
			val toDeliver = ArrayList<Registration>()
			synchronized(lock) {
				val watch = directory?.let { registered -> directories[registered] }
				if (watch != null) {
					for (event in key.pollEvents()) {
						if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
							watch.listenersByFileName.values.forEach { listeners -> toDeliver.addAll(listeners) }
							continue
						}
						val changed = (event.context() as? Path)?.fileName?.toString() ?: continue
						watch.listenersByFileName[changed]?.let { listeners -> toDeliver.addAll(listeners) }
					}
				} else {
					key.pollEvents()
				}
			}
			key.reset()
			if (toDeliver.isNotEmpty()) {
				// De-duplicated per registration: a save fires several events for one file.
				val unique = LinkedHashSet(toDeliver)
				scope.launch(deliverOn) {
					for (registration in unique) {
						registration.listener.onChanged(registration.path)
					}
				}
			}
		}
	}

	private companion object {
		/** How long one poll of the service blocks the IO thread before cancellation is re-checked. */
		const val POLL_MILLIS = 250L
	}
}