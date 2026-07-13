package org.umamo.ui.workspace

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import org.umamo.settings.Settings
import org.umamo.ui.LocalSettings
import org.umamo.ui.action.Command
import org.umamo.ui.action.CommandRegistry
import org.umamo.ui.action.loadKeymap
import org.umamo.ui.kit.TopLevelMenu
import org.umamo.ui.resources.*

/** How long to coalesce rapid layout edits (a splitter drag) before writing to disk. */
private const val PERSIST_DEBOUNCE_MS = 400L

/**
 * The settings-backed [EditorShell]: loads the persisted layout at startup (seeding defaults on first
 * run), drives the UI language from the localization.locale setting (reacting to changes), and
 * debounce-persists layout edits back to settings so a splitter drag does not hammer the disk.
 *
 * Kept separate from [EditorShell] so the shell itself stays Settings-free and unit-testable; this is
 * the thin wrapper apps mount.
 *
 * 設定に連動した EditorShell。起動時にレイアウトを読み込み（初回は既定を種に）、言語を設定から駆動し、
 * レイアウト編集をデバウンスして保存する。
 *
 * @param ViewportHost? viewportHost The platform GL viewport injector, or null for placeholders.
 * @param Map spaceOverrides Per-kind space descriptors layered over the base registry.
 * @param CommandRegistry commandRegistry The action registry (the app may pre-register commands).
 * @param List appMenu The application menu-bar contents, forwarded to the shell (empty renders no bar).
 */
@OptIn(FlowPreview::class)
@Composable
fun PersistentEditorShell(
	viewportHost: ViewportHost? = null,
	spaceOverrides: Map<SpaceKind, SpaceDescriptor> = emptyMap(),
	commandRegistry: CommandRegistry = remember { CommandRegistry() },
	appMenu: List<TopLevelMenu> = emptyList(),
) {
	val settings = LocalSettings.current
	val initialLayout = remember { loadLayout(settings) }
	var latestLayout by remember { mutableStateOf(initialLayout) }

	// The active locale follows the localization.locale setting and updates live when it changes.
	val locale by produceState(initialValue = settings.getString("localization.locale") ?: "en", settings) {
		settings.changes.collect { changedKey ->
			if (changedKey == "localization.locale") {
				value = settings.getString("localization.locale") ?: "en"
			}
		}
	}

	// The active keymap is resolved from the selected preset + user overrides and re-resolved whenever any
	// input.keybinding setting changes, so a preset switch or a rebind in the settings window takes effect
	// across menus, the palette, and live dispatch at once.
	val keymap by produceState(initialValue = loadKeymap(settings), settings) {
		settings.changes.collect { changedKey ->
			if (changedKey.startsWith("input.keybinding")) {
				value = loadKeymap(settings)
			}
		}
	}

	// Persist layout edits, debounced: snapshotFlow observes the latest layout, drop(1) skips the
	// initial value, and debounce coalesces a flurry of splitter drags into one disk write.
	LaunchedEffect(settings) {
		snapshotFlow { latestLayout }
			.drop(1)
			.debounce(PERSIST_DEBOUNCE_MS)
			.collect { layout -> saveLayout(settings, layout) }
	}

	// The viewport chrome flags follow their settings keys reactively, so the toggle commands (and any
	// external write) take effect across every viewport at once.
	val viewportChrome by produceState(initialValue = loadViewportChrome(settings), settings) {
		settings.changes.collect { changedKey ->
			if (changedKey == SHOW_TOOLBAR_SETTINGS_KEY || changedKey == SHOW_SIDEBAR_SETTINGS_KEY) {
				value = loadViewportChrome(settings)
			}
		}
	}

	// The chrome toggles live here rather than in EditorShell because they write settings, and the shell
	// itself stays Settings-free (its documented contract); the standalone shell simply lacks them, the
	// same division as the app-registered File commands.
	DisposableEffect(settings, commandRegistry) {
		commandRegistry.register(
			Command("view.toggleToolbar", title = Res.string.cmd_view_toggle_toolbar) {
				settings.setBoolean(SHOW_TOOLBAR_SETTINGS_KEY, !(settings.getBoolean(SHOW_TOOLBAR_SETTINGS_KEY) ?: true))
			},
		)
		commandRegistry.register(
			Command("view.toggleSidebar", title = Res.string.cmd_view_toggle_sidebar) {
				settings.setBoolean(SHOW_SIDEBAR_SETTINGS_KEY, !(settings.getBoolean(SHOW_SIDEBAR_SETTINGS_KEY) ?: false))
			},
		)
		onDispose {
			commandRegistry.unregister("view.toggleToolbar")
			commandRegistry.unregister("view.toggleSidebar")
		}
	}

	CompositionLocalProvider(LocalViewportChrome provides viewportChrome) {
		EditorShell(
			initialLayout = initialLayout,
			viewportHost = viewportHost,
			spaceOverrides = spaceOverrides,
			commandRegistry = commandRegistry,
			appMenu = appMenu,
			languageTag = locale,
			keymap = keymap,
			onLayoutChange = { layout -> latestLayout = layout },
		)
	}
}

/**
 * Reads the viewport chrome flags from settings (bundled defaults included), falling back to the
 * standalone defaults for a missing key.
 *
 * @param Settings settings The merged settings tree.
 * @return ViewportChromeState The current chrome visibility flags.
 */
private fun loadViewportChrome(settings: Settings): ViewportChromeState =
	ViewportChromeState(
		showToolbar = settings.getBoolean(SHOW_TOOLBAR_SETTINGS_KEY) ?: true,
		showSidebar = settings.getBoolean(SHOW_SIDEBAR_SETTINGS_KEY) ?: false,
	)
