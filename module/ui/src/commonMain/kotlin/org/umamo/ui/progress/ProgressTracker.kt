package org.umamo.ui.progress

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update

/*
 * The long operations running now, for the status bar to show: opening a document, adding or reloading
 * artwork, generating meshes, packing the atlas.  The tracker lives for the application's life - an open
 * runs before the document it opens has a session, and its progress must outlast the swap - and every
 * update is a compare-and-set on one state flow, so a worker thread can report as freely as the UI thread.
 */

/** The label keys a task shows, which the status bar maps to its strings. */
object ProgressLabels {
	/** Opening a document; one argument, the file's name. */
	const val DOCUMENT_OPENING = "progress.document.opening"

	/** Adding an artwork file to the document; one argument, the file's name. */
	const val ARTWORK_ADDING = "progress.artwork.adding"

	/** Reloading the document's artwork files. */
	const val ARTWORK_RELOADING = "progress.artwork.reloading"

	/** Generating meshes from art, counted by drawable. */
	const val MESH_GENERATING = "progress.mesh.generating"

	/** Packing the atlas. */
	const val ATLAS_PACKING = "progress.atlas.packing"
}

/**
 * One running task as the status bar shows it.
 *
 * @property Long         id        The task's identity, unique for the tracker's life.
 * @property String       labelKey  What it is doing, one of [ProgressLabels].
 * @property List<String> arguments The label's arguments.
 * @property Int          completed How many of its [total] steps are done.
 * @property Int?         total     How many steps it has, or null when it cannot say (an indeterminate task).
 */
@Immutable
data class ProgressTask(val id: Long, val labelKey: String, val arguments: List<String>, val completed: Int, val total: Int?)

/** The running tasks, oldest first. */
class ProgressTracker {
	private val running = MutableStateFlow<List<ProgressTask>>(emptyList())
	private val nextId = MutableStateFlow(1L)

	/** The running tasks, oldest first. */
	val tasks: StateFlow<List<ProgressTask>> = running.asStateFlow()

	/**
	 * Starts a task.  Its handle reports its steps and must be finished, which [track] does for a block.
	 *
	 * @param String       labelKey  What it is doing, one of [ProgressLabels].
	 * @param List<String> arguments The label's arguments.
	 * @param Int?         total     How many steps it has, or null when it cannot say.
	 * @return ProgressHandle The task's handle.
	 */
	fun begin(labelKey: String, arguments: List<String> = emptyList(), total: Int? = null): ProgressHandle {
		val id = nextId.getAndUpdate { current -> current + 1 }
		running.update { tasks -> tasks + ProgressTask(id, labelKey, arguments, 0, total) }

		return ProgressHandle(this, id)
	}

	/**
	 * Applies [change] to the task [id], when it is still running.
	 *
	 * @param Long     id     The task.
	 * @param Function change What becomes of it.
	 */
	internal fun change(id: Long, change: (ProgressTask) -> ProgressTask) {
		running.update { tasks -> tasks.map { task -> if (task.id == id) change(task) else task } }
	}

	/**
	 * Removes the task [id]; a task already gone stays gone.
	 *
	 * @param Long id The task.
	 */
	internal fun remove(id: Long) {
		running.update { tasks -> tasks.filterNot { task -> task.id == id } }
	}
}

/**
 * One running task's handle: what reports its steps and ends it.  Safe to call from any thread.
 *
 * @property ProgressTracker tracker The tracker the task runs in.
 * @property Long            id      The task.
 */
class ProgressHandle internal constructor(private val tracker: ProgressTracker, val id: Long) {
	/**
	 * Counts steps done.
	 *
	 * @param Int count How many.
	 */
	fun advance(count: Int = 1) {
		tracker.change(id) { task -> task.copy(completed = task.completed + count) }
	}

	/**
	 * Moves the task on to its next stage: a new label, its steps counted afresh.
	 *
	 * @param String       labelKey  What it is doing now, one of [ProgressLabels].
	 * @param List<String> arguments The label's arguments.
	 * @param Int?         total     How many steps the stage has, or null when it cannot say.
	 */
	fun stage(labelKey: String, arguments: List<String> = emptyList(), total: Int? = null) {
		tracker.change(id) { task -> task.copy(labelKey = labelKey, arguments = arguments, completed = 0, total = total) }
	}

	/** Ends the task; ending it again does nothing. */
	fun finish() {
		tracker.remove(id)
	}
}

/**
 * Runs [block] as a task of this tracker, ending the task however the block ends.  With no tracker the
 * block runs with no handle, so a caller that has none reports nothing.
 *
 * @param String       labelKey  What it is doing, one of [ProgressLabels].
 * @param List<String> arguments The label's arguments.
 * @param Int?         total     How many steps it has, or null when it cannot say.
 * @param Function     block     The work, handed the task's handle.
 * @return T What the block returns.
 */
inline fun <T> ProgressTracker?.track(labelKey: String, arguments: List<String> = emptyList(), total: Int? = null, block: (ProgressHandle?) -> T): T {
	val handle = this?.begin(labelKey, arguments, total)

	try {
		return block(handle)
	} finally {
		handle?.finish()
	}
}

/** The app's [ProgressTracker], or null where no app provides one (the standalone shell, tests). */
val LocalProgressTracker = staticCompositionLocalOf<ProgressTracker?> { null }