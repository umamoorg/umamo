package org.umamo.ui.app

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Files the operating system asks the running editor to open: a double-clicked document macOS delivers as an
 * open-file event, a document an Android file manager hands over as a VIEW intent.  The host forwards each here,
 * and the shell opens it the way File > Open Recent opens a stored path - through the unsaved-changes check.
 *
 * Buffered without limit on purpose.  A cold launch's request arrives before the first composition exists to
 * collect it (macOS raises the event as the process starts, Android's intent is in hand in onCreate), so a request
 * has to wait for its collector rather than be dropped on the floor.  The volume is a rigger's double-clicks.
 *
 * A path here is what the recent-files list stores: a file-system path on desktop, a `content://` uri string on
 * Android.
 */
class HostOpenRequests {
	private val pending = Channel<String>(Channel.UNLIMITED)

	/** Each requested path, in the order the host asked, held until a collector takes it. */
	val requests: Flow<String> = pending.receiveAsFlow()

	/**
	 * Asks the shell to open [path].  Safe from any thread, and before the shell exists.
	 *
	 * @param String path The stored path or uri string of the file to open.
	 */
	fun request(path: String) {
		pending.trySend(path)
	}
}