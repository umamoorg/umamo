package org.umamo.ui.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.umamo.ui.resources.Res
import org.umamo.ui.resources.alert_log_folder_failed
import org.umamo.ui.workspace.AlertRequest

/**
 * Help > Open Log Folder: hands the session-log folder to the host's file manager and, when the host could not,
 * raises an alert naming the folder so the rigger can still copy its path.  The host's call can wait on the file
 * manager, so it runs off the UI thread.
 *
 * @param EditorAppServices services           The app's shared collaborators: the scope the call runs in and the
 *   registry the alert goes through.
 * @param Function          hostOpensLogFolder The host's opener, returning null once the folder was handed over,
 *   else the folder's path.
 */
internal fun openLogFolderOrAlert(services: EditorAppServices, hostOpensLogFolder: () -> String?) {
	services.scope.launch {
		val unopenedFolder = withContext(Dispatchers.IO) { hostOpensLogFolder() }

		if (unopenedFolder != null) {
			services.commandRegistry.invoke("document.alert", AlertRequest(Res.string.alert_log_folder_failed, listOf(unopenedFolder)))
		}
	}
}