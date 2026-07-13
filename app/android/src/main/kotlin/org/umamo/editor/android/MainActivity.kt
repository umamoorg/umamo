package org.umamo.editor.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.dialogs.init
import org.umamo.storage.androidAppStorage
import org.umamo.ui.ProvideSettings
import org.umamo.ui.app.EditorApp
import org.umamo.ui.app.rememberEditorSessionFor
import org.umamo.ui.document.Document
import org.umamo.ui.theme.ProvideAppThemeFromSettings
import org.umamo.ui.theme.UmamoTheme

/**
 * Android entrypoint.  Mounts the same shared [EditorApp] shell desktop runs - the full File / Edit /
 * Workspace / Help menu bar, document open/save through the SAF picker, the per-document editing
 * session, area tree, command palette, and the Preferences window - over the Android storage/settings
 * foundation.  The 2D GL viewport is the one piece still platform-deferred: viewport areas render
 * placeholders (viewportServiceFactory = null) until the GLES sibling of the desktop render service
 * lands; the platform split stays confined to the viewport, as intended.
 *
 * Android 起動点。デスクトップと同じ共有 EditorApp をマウントする。ビューポートだけ後回し（仮表示）。
 */
class MainActivity : ComponentActivity() {
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
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
						var document by remember { mutableStateOf<Document?>(null) }
						val session = rememberEditorSessionFor(document)
						EditorApp(
							document = document,
							session = session,
							onOpen = { document = it },
							onExit = { finish() },
							// The GLES puppet render service is the remaining platform work; until it lands the
							// shared shell runs fully (menus, document, panels, thumbnails) with placeholder viewports.
							viewportServiceFactory = null,
						)
					}
				}
			}
		}
	}
}
