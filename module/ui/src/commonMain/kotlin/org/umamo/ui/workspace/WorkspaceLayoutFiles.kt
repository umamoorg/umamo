package org.umamo.ui.workspace

import io.github.vinceglb.filekit.absolutePath
import io.github.vinceglb.filekit.readString
import io.github.vinceglb.filekit.writeString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.umamo.settings.Settings
import org.umamo.storage.FilePicker
import org.umamo.storage.UmamoLog
import org.umamo.ui.action.CommandRegistry

/**
 * The workspace layout's trips to and from a file: export the active workspace, export the whole layout,
 * and import either.  None of it touches a document, so it sits with the layout it moves rather than with
 * the app's document flows.
 *
 * An export writes the PERSISTED layout - the saved JSON from settings, which is what a rigger means by
 * "my layout" and what another install will load.  An import never edits the layout itself: it parses the
 * file and dispatches workspace.applyLayout or workspace.appendWorkspace, so the shell's live layout state
 * stays the single source of truth and a whole-layout replace goes through the one confirm.
 *
 * @property Settings        settings        The settings the persisted layout is read from.
 * @property FilePicker      filePicker      The native open and save dialogs.
 * @property CoroutineScope  scope           The scope the dialogs and the file IO run in.
 * @property CommandRegistry commandRegistry The registry an imported layout or workspace is applied through.
 */
internal class WorkspaceLayoutFiles(
	private val settings: Settings,
	private val filePicker: FilePicker,
	private val scope: CoroutineScope,
	private val commandRegistry: CommandRegistry,
) {
	/** Exports the whole interface.layout, pretty-printed, to a file; does nothing when no layout has been saved yet. */
	fun exportAllWorkspaces() {
		val text = exportLayoutText(settings) ?: return
		scope.launch {
			filePicker.saveFile("workspaces", "json")?.let { destination ->
				destination.writeString(text)
				UmamoLog.info("exported all workspaces to ${destination.absolutePath()}")
			}
		}
	}

	/** Exports the active workspace of the persisted layout to a file; its display name (or id) seeds the suggested filename. */
	fun exportThisWorkspace() {
		val active = settings.get(INTERFACE_LAYOUT_KEY)?.let { element -> decodeLayout(element) }?.activeWorkspace() ?: return
		val text = exportWorkspaceText(active)
		scope.launch {
			filePicker.saveFile(active.name ?: active.id, "json")?.let { destination ->
				destination.writeString(text)
				UmamoLog.info("exported workspace to ${destination.absolutePath()}")
			}
		}
	}

	/**
	 * Imports a layout or workspace file, detected by shape: a whole layout overwrites every workspace (the
	 * shell confirms first), a single workspace is appended as a new tab, and anything else is rejected with
	 * a log line and the current layout untouched.
	 */
	fun importWorkspace() {
		scope.launch {
			filePicker.openFile(listOf("json"))?.let { picked ->
				val text = picked.readString()
				val importedLayout = decodeLayoutText(text)
				if (importedLayout != null) {
					commandRegistry.invoke("workspace.applyLayout", importedLayout)
				} else {
					val importedWorkspace = decodeWorkspaceText(text)
					if (importedWorkspace != null) {
						commandRegistry.invoke("workspace.appendWorkspace", importedWorkspace)
					} else {
						UmamoLog.warn("invalid workspace file: ${picked.absolutePath()}")
					}
				}
			}
		}
	}
}