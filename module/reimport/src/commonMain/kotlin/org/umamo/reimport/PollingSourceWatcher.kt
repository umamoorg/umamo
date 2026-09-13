package org.umamo.reimport

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * A [SourceWatcher] that polls: every [pollMillis] each watched file is stat-ed through okio, and a
 * change of size or modification time - or of existence - is a change.  Polling rather than a
 * platform notification API on purpose: okio's file system is the one that runs on every Kotlin
 * target, JVM, Android, and Native alike, and a document watches a handful of files, so a stat per
 * file per second is nothing.  The coordinator above settles and hashes, so a poll only has to say
 * "something happened"; an art program's temp-file-then-rename save changes the watched file's
 * metadata like any other write.
 *
 * A file's baseline stamp is taken the moment it is watched, not at the loop's next tick: a caller
 * that hashes the file right after subscribing (the coordinator's open-time check) waits on the
 * subscription's [SourceWatcher.Subscription.awaitArmed], so the stamp precedes the hash and a save
 * landing between the two is a change against the stamp rather than the stamp itself.
 *
 * Listeners are called in [scope]'s own dispatcher; the stats run under [statContext] (an IO
 * dispatcher from the app), so a slow network drive never holds the UI.
 *
 * @param CoroutineScope   scope       The scope the poll loop and the deliveries run in.
 * @param FileSystem       fileSystem  The file system the paths live on.
 * @param Long             pollMillis  How often every watched file is stat-ed.
 * @param CoroutineContext statContext Extra context for the stats; empty runs them in the scope.
 */
class PollingSourceWatcher(
	private val scope: CoroutineScope,
	private val fileSystem: FileSystem,
	private val pollMillis: Long = DEFAULT_POLL_MILLIS,
	private val statContext: CoroutineContext = EmptyCoroutineContext,
) : SourceWatcher, AutoCloseable {
	/** What a stat sees of a file: absent, or its size and modification time. */
	private data class Stamp(val size: Long?, val modifiedAtMillis: Long?)

	/**
	 * One watch() call, the stamp it last saw, and the signal that it has seen one.
	 *
	 * @property String         path     The path as the caller gave it, echoed to the listener.
	 * @property Path           filePath The path as okio reads it.
	 * @property ChangeListener listener Who hears the changes.
	 */
	private inner class Registration(val path: String, val filePath: Path, val listener: SourceWatcher.ChangeListener) : SourceWatcher.Subscription {
		/** The stamp last seen, or null until the baseline stat lands. */
		var last: Stamp? = null

		/** Completed once [last] is set, or on close, so a waiter is never left hanging. */
		val armed = CompletableDeferred<Unit>()

		override suspend fun awaitArmed() {
			armed.await()
		}

		override fun close() {
			registrations.remove(this)
			armed.complete(Unit)
			if (registrations.isEmpty()) {
				loop?.cancel()
				loop = null
			}
		}
	}

	/** A subscription for a path that cannot be polled: armed from the start, hears nothing, closes to nothing. */
	private object NoOpSubscription : SourceWatcher.Subscription {
		override suspend fun awaitArmed() = Unit

		override fun close() = Unit
	}

	private val registrations = ArrayList<Registration>()
	private var loop: Job? = null

	/**
	 * Begins polling [path]: its baseline stamp is taken at once (off the caller's thread), and every
	 * later tick compares against it.
	 *
	 * @param String         path     The file to watch; a uri gets a no-op subscription.
	 * @param ChangeListener listener Told in the scope whenever the file's size, modification time, or existence changes.
	 * @return Subscription The subscription.
	 */
	override fun watch(path: String, listener: SourceWatcher.ChangeListener): SourceWatcher.Subscription {
		if (path.contains("://")) {
			return NoOpSubscription
		}
		val filePath = runCatching { path.toPath() }.getOrNull() ?: return NoOpSubscription
		val registration = Registration(path, filePath, listener)
		registrations.add(registration)
		scope.launch { baseline(registration) }
		if (loop == null) {
			loop = scope.launch { pollLoop() }
		}
		return registration
	}

	/** Stops polling; every subscription is dead after this. */
	override fun close() {
		for (registration in registrations.toList()) {
			registration.armed.complete(Unit)
		}
		registrations.clear()
		loop?.cancel()
		loop = null
	}

	/**
	 * Takes a registration's baseline stamp and arms it.  A tick that stat-ed the file first has already
	 * set the baseline; that one stands, since a change between the two stats must then be reported
	 * against it rather than folded into a later one.
	 *
	 * @param Registration registration The registration to baseline.
	 */
	private suspend fun baseline(registration: Registration) {
		val stamp = withContext(statContext) { stampOf(registration.filePath) }
		if (registration.last == null) {
			registration.last = stamp
		}
		registration.armed.complete(Unit)
	}

	/**
	 * Stats every watched file each period and reports the ones whose stamp moved.  A registration
	 * still waiting on its baseline takes this stat as it - a file is not "changed" by being watched.
	 */
	private suspend fun pollLoop() {
		while (true) {
			val snapshot = registrations.toList()
			val stamps = withContext(statContext) { snapshot.map { registration -> stampOf(registration.filePath) } }
			for ((registration, stamp) in snapshot.zip(stamps)) {
				val previous = registration.last
				registration.last = stamp
				if (previous != null && previous != stamp && registration in registrations) {
					registration.listener.onChanged(registration.path)
				}
			}
			delay(pollMillis)
		}
	}

	/**
	 * The stamp of one file now.
	 *
	 * @param Path filePath The file.
	 * @return Stamp Its size and modification time, both null when absent or unreadable.
	 */
	private fun stampOf(filePath: Path): Stamp {
		val metadata = runCatching { fileSystem.metadataOrNull(filePath) }.getOrNull()
		return Stamp(metadata?.size, metadata?.lastModifiedAtMillis)
	}

	private companion object {
		/** How often the watched files are stat-ed; the coordinator's settle window sits on top of this. */
		const val DEFAULT_POLL_MILLIS = 1_000L
	}
}