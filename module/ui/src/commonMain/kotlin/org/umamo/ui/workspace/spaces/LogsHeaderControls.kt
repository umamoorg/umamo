package org.umamo.ui.workspace.spaces

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import org.umamo.storage.UmamoLog
import org.umamo.ui.action.LocalCommands
import org.umamo.ui.kit.button.IconButton
import org.umamo.ui.kit.button.IconButtonAppearance
import org.umamo.ui.resources.*
import org.umamo.ui.theme.LocalUmamoIcons
import org.umamo.ui.theme.LocalUmamoShapes

/**
 * The logs panel's area-header controls (mounted via SpaceDescriptor.headerContent): a flexible gap
 * then two trailing buttons - Copy the whole log to the clipboard, and Export it to a file.  Unlike the
 * parameter/outliner headers this never early-returns on a missing document: the log exists from startup,
 * independent of any open puppet, so its controls are always live.
 *
 * Copy stays an inline handler (the clipboard is a composition-local reachable here) rather than a
 * registry command - the same direct-manipulation rationale ParametersHeaderControls documents, since no
 * shortcut or menu needs to reach it.  Export is the registry command logs.export instead, because the
 * FilePicker it writes through lives at the app layer (jvmAndroidMain), out of reach of this commonMain
 * header; dispatching the command crosses that seam and reuses EditorApp's file-write path.
 */
@Composable
internal fun RowScope.LogsHeaderControls() {
	// LocalClipboardManager / setText are deprecated in favour of the suspend LocalClipboard, but the
	// replacement needs a platform-built ClipEntry and a coroutine scope - overkill for a one-shot button.
	@Suppress("DEPRECATION")
	val clipboard = LocalClipboardManager.current
	val commands = LocalCommands.current
	Spacer(modifier = Modifier.weight(1f))
	IconButton(
		icon = LocalUmamoIcons.copy,
		onClick = {
			@Suppress("DEPRECATION")
			clipboard.setText(AnnotatedString(UmamoLog.entries.value.joinToString("\n") { entry -> entry.message }))
		},
		contentDescription = stringResource(Res.string.logs_copy),
		appearance = IconButtonAppearance.Filled(LocalUmamoShapes.current.small),
	)
	Spacer(modifier = Modifier.width(4.dp))
	IconButton(
		icon = LocalUmamoIcons.floppy,
		onClick = { commands.invoke("logs.export") },
		contentDescription = stringResource(Res.string.logs_export),
		appearance = IconButtonAppearance.Filled(LocalUmamoShapes.current.small),
	)
	Spacer(modifier = Modifier.width(4.dp))
}
