package org.umamo.reimport

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.umamo.runtime.model.ArtSourceId

/*
 * The policy between a file watcher and a reload: which files a document watches, when a burst of
 * write events has settled, whether the settled file actually changed (its hash against what the
 * document last read), and when the editor is idle enough to take the reload.  Pure over injected
 * time and hashing, so the whole of it runs under a test clock; the watcher and the hash are the only
 * things that touch a disk.
 *
 * Every field is confined to the scope's dispatcher: the watcher delivers on it, the app calls on
 * it, and the timers run on it.  Nothing here is thread-safe by any other means.
 */

/**
 * One listed artwork file the coordinator tracks.
 *
 * @property ArtSourceId sourceId     The file's record in the model.
 * @property String      path         The path the document recorded for it.
 * @property String?     recordedHash The content hash the document last read, or null when it never read bytes.
 */
class WatchedSource(
	val sourceId: ArtSourceId,
	val path: String,
	val recordedHash: String?,
)

/** What the coordinator tells the app. */
sealed interface SourceWatchEvent {
	/** Changed files have settled and the editor is idle: reload these as one operation. */
	data class ReloadDue(val sourceIds: Set<ArtSourceId>) : SourceWatchEvent

	/** Changed files have settled; the mode is notify, so a person decides. */
	data class ChangedOnDisk(val sourceIds: Set<ArtSourceId>) : SourceWatchEvent

	/** Files differed from their recorded hash when tracking began - edited while the document was closed. */
	data class StaleAtOpen(val sourceIds: Set<ArtSourceId>) : SourceWatchEvent

	/** A tracked file is no longer where the document recorded it. */
	data class Missing(val sourceId: ArtSourceId) : SourceWatchEvent
}

/** How a reload the coordinator asked for ended, so it knows whether to wait, retry, or let go. */
enum class WatchedReloadResult {
	/** The reload landed; the model's recorded hashes refresh through [SourceWatchCoordinator.track]. */
	Applied,

	/** The files were read and nothing in them changed the document; the hashes are acknowledged. */
	NothingChanged,

	/** An edit landed while the reload was planned; the coordinator tries again after a settle. */
	Superseded,

	/** The reload was refused or could not read the files; the person was told, so let go. */
	Abandoned,
}

/** One tracked file's live state. */
private class TrackedSource(
	var source: WatchedSource,
	var handle: AutoCloseable,
) {
	/** The hash last read from the disk, or null when the file could not be read. */
	var observedHash: String? = null

	/** A disk hash a reload found nothing to change for; dropped rather than reported again. */
	var acknowledgedHash: String? = null

	/** Whether the last read found the file absent, so its return counts as a change. */
	var missing: Boolean = false

	/** The settle timer, restarted by every event. */
	var settle: Job? = null
}

/**
 * The reload policy over a [SourceWatcher].  See the file comment.
 *
 * @param CoroutineScope scope          The scope everything runs in; its dispatcher confines the state.
 * @param SourceWatcher  watcher        Where change notifications come from.
 * @param Function       hashOf         The content hash of the file at a path, or null when unreadable.
 * @param Function       exists         Whether a path exists, or null when the platform cannot say.
 * @param Function       isIdle         Whether the editor can take a reload right now.
 * @param Function       mode           The watch mode, read live at each decision.
 * @param Long           settleMillis   How long after the last event a file is left alone before it is hashed.
 * @param Long           idlePollMillis How often the idle gate is re-asked while a reload waits.
 */
class SourceWatchCoordinator(
	private val scope: CoroutineScope,
	private val watcher: SourceWatcher,
	private val hashOf: suspend (String) -> String?,
	private val exists: suspend (String) -> Boolean?,
	private val isIdle: () -> Boolean,
	private val mode: () -> WatchMode,
	private val settleMillis: Long = DEFAULT_SETTLE_MILLIS,
	private val idlePollMillis: Long = DEFAULT_IDLE_POLL_MILLIS,
) {
	private val tracked = LinkedHashMap<ArtSourceId, TrackedSource>()
	private val mutablePending = MutableStateFlow<Set<ArtSourceId>>(emptySet())
	private val mutableSerial = MutableStateFlow(0)
	private val mutableEvents = MutableSharedFlow<SourceWatchEvent>(extraBufferCapacity = EVENT_BUFFER)
	private var dueWait: Job? = null

	/** The files whose settled content differs from what the document last read, awaiting a reload. */
	val pending: StateFlow<Set<ArtSourceId>> = mutablePending.asStateFlow()

	/** Bumped whenever a file's presence changed, so a presence probe re-runs. */
	val serial: StateFlow<Int> = mutableSerial.asStateFlow()

	/** What the app acts on. */
	val events: SharedFlow<SourceWatchEvent> = mutableEvents.asSharedFlow()

	/**
	 * Tracks exactly [sources]: new files begin watching and are checked once against their recorded
	 * hash (a file edited while the document was closed is reported as stale), files no longer listed
	 * stop, and a file whose recorded hash caught up with the disk leaves the pending set.  Called with
	 * the model's source list whenever it changes; with the mode off, tracks nothing.
	 *
	 * @param List<WatchedSource> sources The listed files with real paths.
	 */
	fun track(sources: List<WatchedSource>) {
		if (mode() == WatchMode.Off) {
			stopAll()
			return
		}
		val wanted = sources.associateBy { source -> source.sourceId }
		for (sourceId in tracked.keys.toList()) {
			val entry = tracked.getValue(sourceId)
			val next = wanted[sourceId]
			if (next == null || next.path != entry.source.path) {
				stop(sourceId)
			}
		}
		val toCheck = ArrayList<TrackedSource>()
		for (source in sources) {
			val existing = tracked[source.sourceId]
			if (existing != null) {
				existing.source = source
				// A reload landed: the record caught up with the disk, and this file is no longer owed one.
				if (source.recordedHash != null && source.recordedHash == existing.observedHash) {
					setPending(source.sourceId, false)
				}
				continue
			}
			val entry = TrackedSource(source, watcher.watch(source.path) { onChanged(source.sourceId) })
			tracked[source.sourceId] = entry
			toCheck.add(entry)
		}
		if (toCheck.isNotEmpty()) {
			scope.launch { checkAtOpen(toCheck) }
		}
	}

	/**
	 * Records how the reload asked for by a [SourceWatchEvent.ReloadDue] ended, for the files it named.
	 *
	 * @param Set                 sourceIds The files the reload covered.
	 * @param WatchedReloadResult result    How it ended.
	 */
	fun reloadFinished(sourceIds: Set<ArtSourceId>, result: WatchedReloadResult) {
		when (result) {
			// The model's new hashes arrive through track(); nothing to do until then.
			WatchedReloadResult.Applied -> Unit
			WatchedReloadResult.NothingChanged, WatchedReloadResult.Abandoned -> {
				for (sourceId in sourceIds) {
					val entry = tracked[sourceId] ?: continue
					entry.acknowledgedHash = entry.observedHash
					setPending(sourceId, false)
				}
			}
			WatchedReloadResult.Superseded -> {
				for (sourceId in sourceIds) {
					if (tracked.containsKey(sourceId)) {
						onChanged(sourceId)
					}
				}
			}
		}
	}

	/** Stops watching everything and clears the pending set; the coordinator is done. */
	fun close() {
		stopAll()
		dueWait?.cancel()
		dueWait = null
	}

	/**
	 * A change notification for [sourceId]: restarts its settle timer.
	 *
	 * @param ArtSourceId sourceId The changed file.
	 */
	private fun onChanged(sourceId: ArtSourceId) {
		val entry = tracked[sourceId] ?: return
		entry.settle?.cancel()
		entry.settle = scope.launch { settle(entry) }
	}

	/**
	 * Waits for the writes to stop, then reads the file's hash and decides: unreadable while present
	 * means the editor is still writing (wait again, up to a limit), absent means missing, a hash the
	 * document already holds or already acknowledged means nothing to do, and anything else is a change.
	 *
	 * @param TrackedSource entry The file.
	 */
	private suspend fun settle(entry: TrackedSource) {
		var attempts = 0
		while (true) {
			delay(settleMillis)
			val hash = hashOf(entry.source.path)
			if (hash != null) {
				val returned = entry.missing
				entry.missing = false
				entry.observedHash = hash
				if (returned) {
					mutableSerial.value = mutableSerial.value + 1
				}
				decide(entry, hash)
				return
			}
			if (exists(entry.source.path) == false) {
				if (!entry.missing) {
					entry.missing = true
					entry.observedHash = null
					setPending(entry.source.sourceId, false)
					mutableSerial.value = mutableSerial.value + 1
					mutableEvents.tryEmit(SourceWatchEvent.Missing(entry.source.sourceId))
				}
				return
			}
			attempts++
			if (attempts >= MAX_SETTLE_ATTEMPTS) {
				return
			}
		}
	}

	/**
	 * A settled file's hash against what the document holds.
	 *
	 * @param TrackedSource entry The file.
	 * @param String        hash  Its content hash as just read.
	 */
	private fun decide(entry: TrackedSource, hash: String) {
		val sourceId = entry.source.sourceId
		if (hash == entry.source.recordedHash || hash == entry.acknowledgedHash) {
			setPending(sourceId, false)
			return
		}
		setPending(sourceId, true)
		when (mode()) {
			WatchMode.Auto -> requestReload()
			WatchMode.Notify -> mutableEvents.tryEmit(SourceWatchEvent.ChangedOnDisk(setOf(sourceId)))
			WatchMode.Off -> Unit
		}
	}

	/**
	 * Arranges one reload of everything pending once the editor is idle; a wait already under way
	 * picks the new files up when it fires.
	 */
	private fun requestReload() {
		if (dueWait?.isActive == true) {
			return
		}
		dueWait =
			scope.launch {
				while (!isIdle()) {
					delay(idlePollMillis)
				}
				val due = mutablePending.value
				if (due.isNotEmpty()) {
					mutableEvents.tryEmit(SourceWatchEvent.ReloadDue(due))
				}
			}
	}

	/**
	 * The one-time check a newly tracked file gets: its disk hash against the recorded one, so a file
	 * edited while the document was closed is reported before anyone asks.  Batched per track call.
	 *
	 * @param List<TrackedSource> entries The files that just began tracking.
	 */
	private suspend fun checkAtOpen(entries: List<TrackedSource>) {
		val stale = LinkedHashSet<ArtSourceId>()
		for (entry in entries) {
			if (!tracked.containsValue(entry)) {
				continue
			}
			val hash = hashOf(entry.source.path)
			if (hash == null) {
				entry.missing = exists(entry.source.path) == false
				continue
			}
			entry.observedHash = hash
			val recorded = entry.source.recordedHash
			if (recorded != null && hash != recorded) {
				stale.add(entry.source.sourceId)
				setPending(entry.source.sourceId, true)
			}
		}
		if (stale.isNotEmpty()) {
			mutableEvents.tryEmit(SourceWatchEvent.StaleAtOpen(stale))
		}
	}

	/**
	 * Adds or removes [sourceId] from the pending set.
	 *
	 * @param ArtSourceId sourceId The file.
	 * @param Boolean     pending  Whether it awaits a reload.
	 */
	private fun setPending(sourceId: ArtSourceId, pending: Boolean) {
		val current = mutablePending.value
		val next = if (pending) current + sourceId else current - sourceId
		if (next != current) {
			mutablePending.value = next
		}
	}

	/**
	 * Stops watching one file.
	 *
	 * @param ArtSourceId sourceId The file.
	 */
	private fun stop(sourceId: ArtSourceId) {
		val entry = tracked.remove(sourceId) ?: return
		entry.settle?.cancel()
		entry.handle.close()
		setPending(sourceId, false)
	}

	/** Stops watching every file. */
	private fun stopAll() {
		for (sourceId in tracked.keys.toList()) {
			stop(sourceId)
		}
	}

	private companion object {
		/** How long a file is left alone after its last event before it is read - past the pause a save's writes leave. */
		const val DEFAULT_SETTLE_MILLIS = 750L

		/** How often the idle gate is re-asked while a reload waits for a gesture to end. */
		const val DEFAULT_IDLE_POLL_MILLIS = 250L

		/** How many settle windows an unreadable, present file is retried over before it is left for the next event. */
		const val MAX_SETTLE_ATTEMPTS = 8

		/** Events buffered for a slow collector before the newest are dropped. */
		const val EVENT_BUFFER = 64
	}
}