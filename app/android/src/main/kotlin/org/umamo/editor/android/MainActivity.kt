package org.umamo.editor.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.dialogs.init
import org.umamo.storage.androidAppStorage
import org.umamo.ui.ProvideSettings
import org.umamo.ui.app.EditorApp
import org.umamo.ui.app.HostOpenRequests
import org.umamo.ui.app.rememberDocumentFileFor
import org.umamo.ui.app.rememberEditorSessionFor
import org.umamo.ui.app.rememberExitGuard
import org.umamo.ui.document.Document
import org.umamo.ui.document.newBlankDocument
import org.umamo.ui.theme.ProvideAppThemeFromSettings
import org.umamo.ui.theme.UmamoTheme

/**
 * Android entrypoint.  Mounts the same shared [EditorApp] shell desktop runs - the full File / Edit /
 * Workspace / Help menu bar, document open/save through the SAF picker, the per-document editing
 * session, area tree, command palette, and the Preferences window - over the Android storage/settings
 * foundation.  The 2D GL viewport is the one piece still platform-deferred: viewport areas render
 * placeholders (viewportServiceFactory = null) until the GLES sibling of the desktop render service
 * lands; the platform split stays confined to the viewport, as intended.
 */
class MainActivity : ComponentActivity() {
	/**
	 * Documents another app hands over - a `.uma` tapped in a file manager arrives as a VIEW intent - waiting for
	 * the shell to open them.  Held by the activity so an intent delivered to the running editor (onNewIntent)
	 * reaches the same shell as the one that launched it.
	 */
	private val openRequests = HostOpenRequests()

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		// Only a fresh launch reads the launching intent: a recreated activity (a rotation the manifest does not
		// absorb, a process restore) would otherwise reopen the file over whatever the rigger has open by then.
		if (savedInstanceState == null) {
			openFrom(intent)
		}
		// FileKit owns the activity-result registry its open/save pickers dispatch through, so it must be
		// initialised once with the Activity before any file dialog (or a .clip read) runs.
		FileKit.init(this)
		val storage = androidAppStorage(this)
		setContent {
			// ProvideSettings loads settings asynchronously and gates the tree until they are ready; Android
			// has no zero-window hazard (that is desktop-only), so the async load is safe here.  UmamoTheme
			// wraps the whole content (matching desktop's Main) so LocalUmamoColors resolves to the active
			// scheme for everything created here, including the future GLES viewport host.
			ProvideSettings(storage) {
				ProvideAppThemeFromSettings {
					UmamoTheme {
						// The editor always has a document: it starts in a new, empty one.
						var document by remember { mutableStateOf<Document?>(newBlankDocument()) }
						val session = rememberEditorSessionFor(document)
						val documentFile = rememberDocumentFileFor(document)
						val exitGuard = rememberExitGuard()
						// Back leaves the app (on Android 8 to 11 it destroys the root activity, edits and all), so
						// while the document is dirty it asks through the same guard as File > Exit - and while a
						// save is being written it goes through the guard too, which waits for the file to land.
						// A clean, idle document keeps the platform's own back behavior.
						val dirty = session?.dirty?.collectAsState()?.value == true
						BackHandler(enabled = dirty || documentFile?.saving == true) {
							exitGuard.request { finish() }
						}
						EditorApp(
							document = document,
							session = session,
							documentFile = documentFile,
							onOpen = { document = it },
							onExit = { finish() },
							exitGuard = exitGuard,
							// The GLES puppet render service is the remaining platform work; until it lands the
							// shared shell runs fully (menus, document, panels, thumbnails) with placeholder viewports.
							viewportServiceFactory = null,
							openRequests = openRequests,
						)
					}
				}
			}
		}
	}

	/**
	 * A VIEW intent delivered while the editor is already running: the manifest makes the activity singleTask, so
	 * a second document opens here - through the shell's unsaved-changes check - instead of in a second activity.
	 *
	 * @param Intent intent The new intent.
	 */
	override fun onNewIntent(intent: Intent) {
		super.onNewIntent(intent)
		setIntent(intent)
		openFrom(intent)
	}

	/**
	 * Forwards the document a VIEW intent names to the shell.  The uri string is the same form the recent-files
	 * list stores, so the shell opens it the way it opens a recent file.  The grant behind it is usually read-only
	 * and never persistable: a Save to it can fail into the save alert (Save As works), and the recent entry it
	 * leaves can fail later as the soft miss a stored uri already is.
	 *
	 * @param Intent? intent The intent to read.
	 */
	private fun openFrom(intent: Intent?) {
		if (intent?.action == Intent.ACTION_VIEW) {
			intent.data?.let { documentUri -> openRequests.request(documentUri.toString()) }
		}
	}
}