package org.umamo.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.unit.dp
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.LocalUmamoShapes

/**
 * Whether this drag-and-drop event carries files at all - the question a drop target answers before it
 * joins a session.
 *
 * Separate from [droppedFilePaths] because of when each may be asked.  A platform may not hand over the
 * payload of a drag still in flight (AWT says so explicitly: a drag event's transfer data can be
 * unavailable until the drop), while what KINDS of data the drag carries is known from the start.  So this
 * inspects only the advertised shape, and the paths are read once the file has actually been let go.
 *
 * @return Boolean True when the drag advertises files this target could take.
 */
expect fun DragAndDropEvent.carriesFiles(): Boolean

/**
 * The stored paths a completed drop carries, or empty when it carries none.
 *
 * A path here is in the form the recent-files list stores - a file-system path on desktop, a `content://`
 * uri string on Android - so a caller hands one straight to the document loader.
 *
 * @return List The dropped files as stored paths, in the order the platform reported them.
 */
expect fun DragAndDropEvent.droppedFilePaths(): List<String>

/**
 * Wraps [content] in a surface that accepts files dropped on it from outside the application, framing
 * itself in the accent color while a drag it would accept is over it.
 *
 * Only a drag carrying files starts a session here ([carriesFiles]), so a text or image drag falls through
 * to whatever else in the hierarchy wants it.  Nothing is read or validated beyond that: [onDrop] is handed
 * the paths and decides what each one means, because the way a file is opened is the caller's business and
 * the file's contents are the loader's.
 *
 * The frame is drawn here rather than left to the caller so the over-state and the thing it paints cannot
 * drift apart, and it is drawn as an overlaid sibling so it neither re-lays-out [content] nor takes any
 * space from it.
 *
 * @param Function onDrop  Called with the dropped files' stored paths; never called with an empty list.
 * @param Function content The content the drop target covers.
 */
@Composable
fun FileDropTarget(onDrop: (List<String>) -> Unit, content: @Composable () -> Unit) {
	// The target node outlives recompositions, so it reads the caller's lambda through a holder rather
	// than capturing the first composition's.
	val currentOnDrop by rememberUpdatedState(onDrop)
	var dragOver by remember { mutableStateOf(false) }
	val target =
		remember {
			object : DragAndDropTarget {
				override fun onEntered(event: DragAndDropEvent) {
					dragOver = true
				}

				override fun onExited(event: DragAndDropEvent) {
					dragOver = false
				}

				// Clears on the session's end as well as on exit: a drag cancelled outside the window
				// (Escape, or a release over another application) sends no exit, and the frame would stay.
				override fun onEnded(event: DragAndDropEvent) {
					dragOver = false
				}

				override fun onDrop(event: DragAndDropEvent): Boolean {
					val paths = event.droppedFilePaths()
					if (paths.isEmpty()) {
						return false
					}
					currentOnDrop(paths)
					return true
				}
			}
		}
	Box(
		Modifier.fillMaxSize().dragAndDropTarget(
			shouldStartDragAndDrop = { startEvent -> startEvent.carriesFiles() },
			target = target,
		),
	) {
		content()
		if (dragOver) {
			Box(
				Modifier
					.fillMaxSize()
					.background(LocalUmamoColors.current.accent.copy(alpha = 0.5f), LocalUmamoShapes.current.large)
					.border(2.dp, LocalUmamoColors.current.accent, LocalUmamoShapes.current.large),
			)
		}
	}
}