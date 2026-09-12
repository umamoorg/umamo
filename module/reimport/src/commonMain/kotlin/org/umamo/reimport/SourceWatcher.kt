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
	 * Begins watching [path]; close the returned handle to stop.
	 *
	 * @param String         path     The file to watch.
	 * @param ChangeListener listener Told whenever the file is written, replaced, created, or deleted.
	 * @return AutoCloseable The subscription; closing it stops the notifications.
	 */
	fun watch(path: String, listener: ChangeListener): AutoCloseable
}