package org.umamo.reimport

/**
 * Watches a source-art file for on-disk changes - the trigger of the headline "file refreshes"
 * workflow.
 *
 * Modelled with a `fun interface` (SAM) listener and an [AutoCloseable] subscription so the contract
 * carries no coroutine/Flow dependency at this layer.  [PollingSourceWatcher] is the one
 * implementation, over okio so it runs on every Kotlin target; a platform notification API could
 * back another, but none is needed.  The policy above it - settling a burst of events, hashing to
 * skip a save that changed nothing, waiting for the editor to go idle - is [SourceWatchCoordinator]'s,
 * so an implementation only has to say "this path changed".
 */
interface SourceWatcher {
	/** Receives change notifications for a watched path. */
	fun interface ChangeListener {
		fun onChanged(path: String)
	}

	/**
	 * One watch of one file: close it to stop the notifications, and wait on [awaitArmed] before
	 * reading the file for a baseline of your own.
	 */
	interface Subscription : AutoCloseable {
		/**
		 * Suspends until the watcher holds the file's baseline, from which point every change is
		 * reported.  A change the watcher first sees while taking that baseline is folded into it, so a
		 * caller that reads the file BEFORE this returns and takes what it read as current could have the
		 * file change under it, unreported, in between.  Returns at once for a closed subscription.
		 */
		suspend fun awaitArmed()
	}

	/**
	 * Begins watching [path]; close the returned subscription to stop.
	 *
	 * @param String         path     The file to watch.
	 * @param ChangeListener listener Told whenever the file is written, replaced, created, or deleted.
	 * @return Subscription The subscription; closing it stops the notifications.
	 */
	fun watch(path: String, listener: ChangeListener): Subscription
}