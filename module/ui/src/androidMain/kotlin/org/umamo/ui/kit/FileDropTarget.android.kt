package org.umamo.ui.kit

import androidx.compose.ui.draganddrop.DragAndDropEvent

/*
 * Android takes no file drops yet, so both halves of the seam answer "nothing here" and the drop target
 * never joins a session - no frame is drawn and no drop is claimed, which leaves a drag to whatever else
 * on screen wants it.
 *
 * What is missing is not the payload read - a drop's ClipData carries the uris plainly enough.  It is the
 * grant behind them: a uri from another application is readable only under the DragAndDropPermissions the
 * Activity takes for the drop, and that grant has to outlive the read, which here is an asynchronous
 * document load.  Getting that wrong leaks a grant or releases it mid-read, and neither failure is visible
 * without a tablet to run it on.  It lands with the rest of Android's file story (the SAF reader and
 * watcher the artwork reload still wants), not ahead of it.
 */

/**
 * Whether the drag carries files: always false, so the drop target stays out of every session on Android.
 *
 * @return Boolean False.
 */
actual fun DragAndDropEvent.carriesFiles(): Boolean = false

/**
 * The dropped files' paths: always empty, since no drop is ever claimed here.
 *
 * @return List An empty list.
 */
actual fun DragAndDropEvent.droppedFilePaths(): List<String> = emptyList()