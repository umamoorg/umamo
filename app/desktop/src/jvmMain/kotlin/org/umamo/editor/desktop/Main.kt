package org.umamo.editor.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import io.github.vinceglb.filekit.FileKit
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.painterResource
import org.umamo.editor.desktop.viewport.OffscreenPuppetService
import org.umamo.format.FileKind
import org.umamo.runtime.model.ParameterId
import org.umamo.settings.Settings
import org.umamo.storage.UmamoLog
import org.umamo.storage.desktopAppStorage
import org.umamo.storage.platformFileFromSavedPath
import org.umamo.ui.LocalSettings
import org.umamo.ui.app.EditorApp
import org.umamo.ui.app.rememberEditorSessionFor
import org.umamo.ui.defaultSettingsJson
import org.umamo.ui.document.Document
import org.umamo.ui.document.DocumentLoad
import org.umamo.ui.document.PuppetDocument
import org.umamo.ui.document.addRecentFile
import org.umamo.ui.document.loadDocument
import org.umamo.ui.l10n.applyAppLocale
import org.umamo.ui.resources.Res
import org.umamo.ui.resources.app_icon
import org.umamo.ui.theme.ProvideAppThemeFromSettings
import org.umamo.ui.theme.UmamoTheme
import org.umamo.ui.viewport.LiveParams
import java.util.concurrent.atomic.AtomicReference

/**
 * Applies the `UMAMO_DUMP_PARAMS` environment override (e.g. `ParamAngleX=30,ParamAngleY=-10`) to the
 * given live params, so a deformed pose can be dumped headless. A desktop dev affordance - the shared
 * initialLiveParams stays environment-free.
 *
 * @param LiveParams liveParams The initial document's parameter hand-off to rewrite.
 */
private fun applyDumpParamOverrides(liveParams: LiveParams) {
	val spec = System.getenv("UMAMO_DUMP_PARAMS") ?: return
	val values = liveParams.values.toMutableMap()
	spec.split(",").forEach { pair ->
		val parts = pair.split("=")
		val value = parts.getOrNull(1)?.trim()?.toFloatOrNull()
		if (parts.size == 2 && value != null) {
			values[ParameterId(parts[0].trim())] = value
		}
	}
	liveParams.values = values.toMap()
}

/**
 * Loads the initial document from a `.cmo3`/`.moc3` path and applies the dump-param overrides.
 *
 * A function rather than inline code in [main] on purpose: main's frame lives for the whole run
 * (application {} blocks), and an interpreted frame keeps every local reachable - a document held
 * in one would stay pinned, retained CMO3 graph and decoded atlas included, long after another
 * file replaces it.  Loading in a popped frame leaves the holder in [main] as the only reference.
 *
 * @param String? initialPath The argv / -Dumamo.testCmo3 path, or null for none.
 * @return Document? The loaded document, or null when there is no path or the load fails.
 */
private fun loadInitialDocument(initialPath: String?): Document? {
	val path = initialPath ?: return null
	// A failed initial load falls back to an empty shell (the failure is in the log); the in-app
	// alert only covers interactive opens, since the shell is not up yet to show it here.
	val document = (runBlocking { loadDocument(platformFileFromSavedPath(path)) } as? DocumentLoad.Loaded)?.document
	// The dump-params override applies only to the initially-opened document - it exists for the
	// headless first-frame dump, which always opens via argv / -Dumamo.testCmo3.  PuppetDocument
	// covers both CMO3 and MOC3 documents, so a .moc3 argv dump poses correctly too.
	(document as? PuppetDocument)?.let { applyDumpParamOverrides(it.liveParams) }
	return document
}

/**
 * Desktop entrypoint. Opens a single editor window over the storage/settings foundation: window state
 * (size/position) and the recent-files list restore from `:settings`, and File → Open/Save-As use the
 * native `:storage` dialogs. An initial document may come from a `.cmo3`/`.moc3` argument or
 * `-Dumamo.testCmo3`; otherwise the window opens to an "Open a file" prompt.
 * `UMAMO_DUMP_PNG` still dumps the first frame headlessly (the WSL verification path).
 *
 * Settings load synchronously here (the bundled default is a Compose resource, read via `runBlocking`)
 * so the window state is ready before the window opens and the window is unconditional - `application {}`
 * exits if it ever has zero windows, which an async settings gate would briefly cause.
 *
 * @param Array<String> args Optional: a `.cmo3` or `.moc3` path.
 */
fun main(args: Array<String>) {
	// FileKit's native dialogs need a one-time init; `appId` names the per-OS data/cache dirs it uses.
	FileKit.init(appId = "umamo")
	val initialPath =
		args.firstOrNull { arg ->
			// Pick the first .cmo3 or .moc3 argument; loadDocument then does the real magic-byte
			// detection once the file is actually read (a .moc3 routes to the sidecar-discovering loader).
			arg.endsWith(".${FileKind.Cmo3.extension}", ignoreCase = true) ||
				arg.endsWith(".${FileKind.Moc3.extension}", ignoreCase = true)
		}
			?: System.getProperty("umamo.testCmo3")
	// Synchronous load, like the settings below: the window opens with the document already in hand.
	// The document reaches the first composition through a one-shot holder rather than a plain local:
	// the application closure lives for the whole run, so a direct capture would keep the first
	// document reachable after the user opens something else.  remember empties the holder on first
	// composition; only the path string stays behind for the recent-files record.
	val initialDocumentHolder = AtomicReference(loadInitialDocument(initialPath))
	val initialDocumentPath = initialDocumentHolder.get()?.path
	val storage = desktopAppStorage("umamo")
	val settings = runBlocking { Settings.load(storage, defaultSettingsJson()) }
	// Apply the UI language before the window opens so the menu bar (which lives outside the shell's
	// own locale scope) and every other stringResource resolve to the configured locale from the start.
	applyAppLocale(settings.getString("localization.locale") ?: "en")
	UmamoLog.info("config=${storage.configDirectory}")

	application {
		var document by remember { mutableStateOf(initialDocumentHolder.getAndSet(null)) }
		// The title's unsaved marker, mirrored from the session's dirty flag by the window content below,
		// which is where the session lives.
		var dirty by remember { mutableStateOf(false) }
		val windowState = remember { settings.savedWindowState() }
		// A file opened from the command line is a real "open" - record it in recent files too.
		LaunchedEffect(Unit) { initialDocumentPath?.let { settings.addRecentFile(it) } }

		fun closeApp() {
			settings.saveWindowState(windowState)
			exitApplication()
		}
		Window(
			onCloseRequest = { closeApp() },
			state = windowState,
			// Window + taskbar/dock icon.  painterResource decodes the bundled app_icon PNG (the same
			// mascot the packaged installer icons derive from); regenerate via docs/design/appicon/generate.sh.
			icon = painterResource(Res.drawable.app_icon),
			title = "Umamo" + (document?.let { " - ${it.displayName}${if (dirty) " *" else ""}" }.orEmpty()),
		) {
			// The session is derived HERE, in the composition that reads the document, and not in the
			// application scope above.  Window content is its own composition: it reads the document
			// state directly, but sees a value from the outer scope only through the content lambda it
			// was last handed.  A session derived outside reaches EditorApp one frame late, so a newly
			// opened document composes once with the previous document's session - and the export
			// command, the File menu, and the page resolver registered in that frame keep the stale
			// pair.  Deriving both in one place keeps them consistent in every frame.
			val session = rememberEditorSessionFor(document)
			LaunchedEffect(session) {
				val activeSession = session
				if (activeSession == null) {
					dirty = false
				} else {
					activeSession.dirty.collect { dirty = it }
				}
			}
			CompositionLocalProvider(LocalSettings provides settings) {
				ProvideAppThemeFromSettings {
					// Run the whole window content inside UmamoTheme so LocalUmamoColors resolves to the active
					// scheme for everything created here - the Windows title-bar tint AND the GL viewport host,
					// which reads the themed grid-backdrop colors at creation (it lives outside the shell's
					// own UmamoTheme).  The shell re-applies the same theme to its subtree, an idempotent,
					// cheap re-provide.  Without this wrap LocalUmamoColors falls back to its static dark default,
					// so the viewport grid never tracked the theme.
					UmamoTheme {
						WindowsTitleBarTint(window)
						WindowResizeHeal(window)
						EditorApp(
							document = document,
							session = session,
							onOpen = { document = it },
							onExit = { closeApp() },
							viewportServiceFactory = { puppet, textures, liveParams ->
								OffscreenPuppetService(puppet, textures, liveParams).also { it.start() }
							},
						)
					}
				}
			}
		}
	}
}