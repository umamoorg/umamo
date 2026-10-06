package org.umamo.ui.viewport.gizmo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/*
 * The modal drive off the UI thread: a gesture's pointer events submit requests, a worker computes the
 * latest one on a background dispatcher, and the result publishes back on the UI thread.  Every modal
 * transform target splits its drive the same way - a request resolved on the UI thread (the parameters,
 * which advance the rotation tracker per event, and snapshots of everything the UI thread can still
 * change), a pure compute, and a UI-thread publish - so the pointer handler spends microseconds per event
 * however heavy the drive.
 */

/** The weight a parallel chunk carries at least: below it the chunking costs more than it saves. */
internal const val DRIVE_MIN_CHUNK_WEIGHT = 4096

/** How many chunks a parallel compute aims for, so the dispatcher can balance them over its threads. */
private const val DRIVE_TARGET_CHUNKS = 64

/**
 * The dispatcher a modal drive computes on.  The default dispatcher in the app; a test fixture provides one
 * it can hold, so a case can pin what happens while a drive is in flight.
 */
internal val LocalModalDriveDispatcher = staticCompositionLocalOf<CoroutineDispatcher> { Dispatchers.Default }

/**
 * Runs [worker] for as long as the composition holds it, computing on the drive dispatcher in scope.  An
 * overlay mounts it beside the transform it holds, inside the same guards, so the worker lives exactly as
 * long as the transform; leaving composition cancels it, and no result publishes after that.
 *
 * @param ModalDriveWorker worker The transform's drive.
 */
@Composable
internal fun ModalDriveEffect(worker: ModalDriveWorker<*, *>) {
	val dispatcher = LocalModalDriveDispatcher.current
	LaunchedEffect(worker, dispatcher) {
		worker.run(dispatcher)
	}
}

/**
 * One modal transform's drive: requests submitted on the UI thread, computed off it, published back on it.
 *
 * The rules, all kept on the UI thread so no field needs a lock:
 *  - The latest request wins: submitted requests ride a conflated channel, the worker computes one at a
 *    time, and it skips a request a newer one has already superseded by the time it gets to it, so at most
 *    one compute is in flight and a burst of pointer events costs one compute, not one each.
 *  - A result publishes only while its gesture is the current one ([ModalGestureState.epoch], bumped as a
 *    gesture begins and ends) and nothing newer has published, so a result landing after a cancel, a
 *    teardown's resync, or a confirm is dropped.
 *  - [settle] brings the latest request up to date synchronously, which is what a confirm commits from: the
 *    result for where the pointer is now, whether or not the worker has caught up.
 *  - With no worker attached (a unit test, the perf probe, or the moment before the effect starts) a submit
 *    computes and publishes inline.
 *
 * @param ModalGestureState gesture The gesture whose epoch gates the publishes.
 * @param Function computeSequential The pure compute, run inline (detached, settling) on the caller's thread.
 * @param Function computeOffThread The compute the worker runs on the drive dispatcher; the sequential one
 *   by default, a parallel one where the drive is heavy.
 * @param Function publish Lands a result on the UI thread.
 */
internal class ModalDriveWorker<TRequest : Any, TResult : Any>(
	private val gesture: ModalGestureState<*>,
	private val computeSequential: (TRequest) -> TResult,
	private val computeOffThread: suspend (TRequest) -> TResult = { request -> computeSequential(request) },
	private val publish: (TRequest, TResult) -> Unit,
) {
	/**
	 * One submitted request as the worker receives it.
	 *
	 * @property Int epoch The gesture epoch it was submitted in.
	 * @property Long sequence Its submission ordinal.
	 * @property TRequest request The request.
	 */
	private class Ticket<TRequest>(
		val epoch: Int,
		val sequence: Long,
		val request: TRequest,
	)

	private var submittedSequence = 0L
	private var doneSequence = 0L
	private var latest: TRequest? = null
	private var latestEpoch = 0
	private var attachments = 0
	private val tickets = Channel<Ticket<TRequest>>(Channel.CONFLATED)

	/** Whether the latest request of the current gesture has not published yet. */
	val isPending: Boolean
		get() = latest != null && latestEpoch == gesture.epoch && doneSequence < submittedSequence

	/**
	 * Submits a request: the worker's next compute when one is attached, else computed and published here.
	 * Never suspends, so a pointer handler can call it.
	 *
	 * @param TRequest request The request, resolved on the UI thread.
	 */
	fun submit(request: TRequest) {
		submittedSequence++
		latest = request
		latestEpoch = gesture.epoch
		if (attachments == 0) {
			publishResult(request, computeSequential(request), submittedSequence)
			return
		}
		tickets.trySend(Ticket(gesture.epoch, submittedSequence, request))
	}

	/**
	 * Brings the latest request up to date: computes and publishes it here when it has not published yet in
	 * the current gesture, and does nothing otherwise.  A worker finishing that request (or an older one)
	 * afterwards publishes nothing.
	 */
	fun settle() {
		val request = latest ?: return
		if (latestEpoch != gesture.epoch || doneSequence >= submittedSequence) {
			return
		}
		publishResult(request, computeSequential(request), submittedSequence)
	}

	/**
	 * The worker loop: takes the latest submitted request, computes it on [dispatcher], and publishes it when
	 * it is still current.  Runs on the caller's (UI) dispatcher between computes, so every check and every
	 * publish is a UI-thread task; returns only by cancellation.
	 *
	 * @param CoroutineDispatcher dispatcher Where the computes run.
	 */
	suspend fun run(dispatcher: CoroutineDispatcher) {
		attachments++
		try {
			for (ticket in tickets) {
				// A waiting worker is handed a ticket the moment it is sent, so by the time it runs, newer
				// ones may have been submitted behind it; the newest is next in the channel, and it is the
				// one worth computing.
				if (!isCurrent(ticket) || ticket.sequence != submittedSequence) {
					continue
				}
				val result = withContext(dispatcher) { computeOffThread(ticket.request) }
				if (isCurrent(ticket)) {
					publishResult(ticket.request, result, ticket.sequence)
				}
			}
		} finally {
			attachments--
		}
	}

	/**
	 * Whether a ticket may still publish: submitted in the current gesture, and newer than what published.
	 *
	 * @param Ticket ticket The ticket.
	 * @return Boolean True when it may.
	 */
	private fun isCurrent(ticket: Ticket<TRequest>): Boolean = ticket.epoch == gesture.epoch && ticket.sequence > doneSequence

	/**
	 * Publishes one result and records it as the newest landed.
	 *
	 * @param TRequest request The request it answers.
	 * @param TResult result The result.
	 * @param Long sequence The request's submission ordinal.
	 */
	private fun publishResult(request: TRequest, result: TResult, sequence: Long) {
		doneSequence = sequence
		publish(request, result)
	}
}

/**
 * Maps [items] in order, in parallel chunks on the caller's dispatcher: the runs [balancedChunks] plans, each
 * one an `async`, the results concatenated back in item order.  A load that plans as one chunk maps inline
 * with no coroutine at all.  Each chunk checks for cancellation as it starts.
 *
 * @param List<TItem> items The items.
 * @param Function weightOf An item's cost, in any unit that grows with its work.
 * @param Int minChunkWeight The least weight a chunk carries.
 * @param Int targetChunks How many chunks a heavy load aims for.
 * @param Function transform The pure per-item work.
 * @return List<TResult> The results, in item order.
 */
internal suspend fun <TItem, TResult> mapInBalancedChunks(
	items: List<TItem>,
	weightOf: (TItem) -> Int,
	minChunkWeight: Int = DRIVE_MIN_CHUNK_WEIGHT,
	targetChunks: Int = DRIVE_TARGET_CHUNKS,
	transform: (TItem) -> TResult,
): List<TResult> {
	val chunks = balancedChunks(items, weightOf, minChunkWeight, targetChunks)
	if (chunks.size <= 1) {
		return items.map(transform)
	}
	return coroutineScope {
		chunks
			.map { chunk ->
				async {
					ensureActive()
					chunk.map { itemIndex -> transform(items[itemIndex]) }
				}
			}.awaitAll()
			.flatten()
	}
}

/**
 * Plans [items] into contiguous runs of roughly equal total weight.  The chunk weight is the larger of
 * [minChunkWeight] and the total over [targetChunks], so a light load is one run, and a heavy one splits into
 * about [targetChunks] pieces a dispatcher can balance over its threads (common code has no core count to
 * size them by).  A run closes as its weight reaches the chunk weight; the last takes what is left.
 *
 * @param List<TItem> items The items.
 * @param Function weightOf An item's cost.
 * @param Int minChunkWeight The least weight a chunk carries.
 * @param Int targetChunks How many chunks a heavy load aims for.
 * @return List<IntRange> The runs of item indices, in order, covering every item once; empty for no items.
 */
internal fun <TItem> balancedChunks(
	items: List<TItem>,
	weightOf: (TItem) -> Int,
	minChunkWeight: Int = DRIVE_MIN_CHUNK_WEIGHT,
	targetChunks: Int = DRIVE_TARGET_CHUNKS,
): List<IntRange> {
	if (items.isEmpty()) {
		return emptyList()
	}
	var totalWeight = 0L
	for (item in items) {
		totalWeight += weightOf(item)
	}
	val chunkWeight = maxOf(minChunkWeight.toLong(), (totalWeight + targetChunks - 1) / targetChunks)
	if (totalWeight <= chunkWeight) {
		return listOf(items.indices)
	}
	val chunks = ArrayList<IntRange>()
	var chunkStart = 0
	var runningWeight = 0L
	for (itemIndex in items.indices) {
		runningWeight += weightOf(items[itemIndex])
		if (runningWeight >= chunkWeight) {
			chunks.add(chunkStart..itemIndex)
			chunkStart = itemIndex + 1
			runningWeight = 0L
		}
	}
	if (chunkStart < items.size) {
		chunks.add(chunkStart until items.size)
	}
	return chunks
}