package org.umamo.ui.workspace

import io.github.vinceglb.filekit.absolutePath
import io.github.vinceglb.filekit.writeString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.umamo.storage.FilePicker
import org.umamo.storage.UmamoLog

/**
 * Writes the retained UmamoLog buffer to a file the person picks, as plain text with one line per entry -
 * the same lines the terminal printed, for someone who launched without one.  The buffer is read at write
 * time, so the file captures the log as of when the save is confirmed, not when the button was pressed.
 *
 * @param FilePicker     filePicker The native save dialog.
 * @param CoroutineScope scope      The scope the dialog and the write run in.
 */
internal fun exportLogToFile(filePicker: FilePicker, scope: CoroutineScope) {
	scope.launch {
		filePicker.saveFile("umamo-log", "txt")?.let { destination ->
			destination.writeString(UmamoLog.entries.value.joinToString("\n") { entry -> entry.message })
			UmamoLog.info("exported log to ${destination.absolutePath()}")
		}
	}
}