package org.umamo.ui.kit

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.awtTransferable
import org.umamo.storage.UmamoLog
import java.awt.datatransfer.DataFlavor
import java.io.File

/**
 * Whether the AWT drag carries a file list.
 *
 * Asks the transferable which flavors it offers, never for the data: a drag still in flight may not be
 * able to produce its contents yet, but it always says what it holds.  A drag Compose routed here from
 * something other than AWT has no transferable at all, which is not an error - it is a drag this target
 * does not want.
 *
 * @return Boolean True when the drag offers a file list.
 */
@OptIn(ExperimentalComposeUiApi::class)
actual fun DragAndDropEvent.carriesFiles(): Boolean =
	runCatching { awtTransferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor) }.getOrDefault(false)

/**
 * The absolute paths of the files an AWT drop carries.
 *
 * Reads [DataFlavor.javaFileListFlavor] directly rather than going through Compose's `dragData()`, which
 * hands back percent-encoded `file:` uris that would then have to be decoded - a step that gets non-ASCII
 * names wrong, and there is no reason to take it when AWT already offers the files themselves.
 *
 * Directories are dropped here rather than passed on: a caller would read one as a file, fail, and report
 * an unreadable file for something the rigger never claimed was one.
 *
 * @return List The dropped files' absolute paths, in the order AWT reported them.
 */
@OptIn(ExperimentalComposeUiApi::class)
actual fun DragAndDropEvent.droppedFilePaths(): List<String> =
	runCatching {
		val transferred = awtTransferable.getTransferData(DataFlavor.javaFileListFlavor) as List<*>
		transferred.filterIsInstance<File>().filter { dropped -> dropped.isFile }.map { dropped -> dropped.absolutePath }
	}.getOrElse { failure ->
		UmamoLog.warn("drag and drop: the dropped file list could not be read (${failure.message})")
		emptyList()
	}