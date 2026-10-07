package org.umamo.ui.viewport.gizmo

import kotlinx.coroutines.CoroutineDispatcher
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.CoroutineContext

/**
 * The drive dispatcher the gizmo fixtures provide (LocalModalDriveDispatcher): open, it runs a drive's
 * compute in place, so the worker publishes on the effect's next turn and a case reads the result after
 * waitForIdle as it always could; held, it queues every compute until [release], so a case can act while
 * a drive is in flight and pin what lands, and what does not, once it finishes.
 */
internal class HeldDriveDispatcher : CoroutineDispatcher() {
	@Volatile
	private var held = false
	private val queued = ConcurrentLinkedQueue<Runnable>()

	/** How many computes are waiting for [release]. */
	val queuedCount: Int
		get() = queued.size

	/**
	 * Whether a compute must leave the caller: only while held.
	 *
	 * @param CoroutineContext context The compute's context.
	 * @return Boolean True while held.
	 */
	override fun isDispatchNeeded(context: CoroutineContext): Boolean = held

	/**
	 * Queues the block while held, and runs it in place otherwise.
	 *
	 * @param CoroutineContext context The block's context.
	 * @param Runnable block The block.
	 */
	override fun dispatch(context: CoroutineContext, block: Runnable) {
		if (held) {
			queued.add(block)
		} else {
			block.run()
		}
	}

	/** Holds every compute from now until [release]. */
	fun hold() {
		held = true
	}

	/** Opens the dispatcher again and runs every compute it held, in order, on the calling thread. */
	fun release() {
		held = false
		while (true) {
			val block = queued.poll() ?: break
			block.run()
		}
	}
}