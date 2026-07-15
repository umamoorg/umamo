package org.umamo.editor.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
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
import org.umamo.ui.document.Cmo3Document
import org.umamo.ui.document.DocumentLoad
import org.umamo.ui.document.addRecentFile
import org.umamo.ui.document.loadDocument
import org.umamo.ui.l10n.applyAppLocale
import org.umamo.ui.resources.Res
import org.umamo.ui.resources.app_icon
import org.umamo.ui.theme.ProvideAppThemeFromSettings
import org.umamo.ui.theme.UmamoTheme
import org.umamo.ui.viewport.LiveParams

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
 * Desktop entrypoint. Opens a single editor window over the storage/settings foundation: window state
 * (size/position) and the recent-files list restore from `:settings`, and File → Open/Save-As use the
 * native `:storage` dialogs. An initial document may come from a `.cmo3` argument or `-Dumamo.testCmo3`;
 * otherwise the window opens to an "Open a file" prompt.
 * `UMAMO_DUMP_PNG` still dumps the first frame headlessly (the WSL verification path).
 *
 * Settings load synchronously here (the bundled default is a Compose resource, read via `runBlocking`)
 * so the window state is ready before the window opens and the window is unconditional - `application {}`
 * exits if it ever has zero windows, which an async settings gate would briefly cause.
 *
 * @param Array<String> args Optional: a `.cmo3` path.
 */
fun main(args: Array<String>) {
	// FileKit's native dialogs need a one-time init; `appId` names the per-OS data/cache dirs it uses.
	FileKit.init(appId = "umamo")
	val initialPath =
		args.firstOrNull { arg ->
			// Pick the first .cmo3 argument; loadDocument then does the real magic-byte detection
			// once the file is actually read.
			arg.endsWith(".${FileKind.Cmo3.extension}", ignoreCase = true)
		}
			?: System.getProperty("umamo.testCmo3")
	// Synchronous load, like the settings below: the window opens with the document already in hand.
	// A failed initial load falls back to an empty shell (the failure is in the log); the in-app alert
	// only covers interactive opens, since the shell is not up yet to show it here.
	val initialDocument =
		initialPath?.let { path ->
			(runBlocking { loadDocument(platformFileFromSavedPath(path)) } as? DocumentLoad.Loaded)?.document
		}
	// The dump-params override applies only to the initially-opened document - it exists for the
	// headless first-frame dump, which always opens via argv / -Dumamo.testCmo3.
	(initialDocument as? Cmo3Document)?.let { applyDumpParamOverrides(it.liveParams) }
	val storage = desktopAppStorage("umamo")
	val settings = runBlocking { Settings.load(storage, defaultSettingsJson()) }
	// Apply the UI language before the window opens so the menu bar (which lives outside the shell's
	// own locale scope) and every other stringResource resolve to the configured locale from the start.
	applyAppLocale(settings.getString("localization.locale") ?: "en")
	UmamoLog.info("config=${storage.configDirectory}")

	application {
		var document by remember { mutableStateOf(initialDocument) }
		val session = rememberEditorSessionFor(document)
		// Mirror the session's dirty flag for the title's unsaved marker; produceState runs unconditionally.
		val dirty by produceState(false, session) {
			val activeSession = session
			if (activeSession == null) {
				value = false
			} else {
				activeSession.dirty.collect { value = it }
			}
		}
		val windowState = remember { settings.savedWindowState() }
		// A file opened from the command line is a real "open" - record it in recent files too.
		LaunchedEffect(Unit) { initialDocument?.let { settings.addRecentFile(it.path) } }

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
