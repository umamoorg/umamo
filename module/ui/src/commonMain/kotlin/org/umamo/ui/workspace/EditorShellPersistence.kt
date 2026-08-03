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
import org.umamo.ui.action.CommandRegistry
import org.umamo.ui.action.loadKeymap
import org.umamo.ui.kit.TopLevelMenu
import org.umamo.ui.resources.*
import org.umamo.ui.workspace.commands.registerAll
import org.umamo.ui.workspace.commands.viewportChromeCommands

/** How long to coalesce rapid layout edits (structural bursts) before writing to disk. */
private const val PERSIST_DEBOUNCE_MS = 400L

/**
 * The settings-backed [EditorShell]: loads the persisted layout at startup (seeding defaults on first
 * run), drives the UI language from the localization.locale setting (reacting to changes), and
 * debounce-persists layout edits back to settings.  Splitter drags are paced separately through a
 * [LayoutSavePacer]: no write happens while the drag is held (even across a mid-drag pause longer
 * than the debounce), and the release commits exactly one write.
 *
 * Kept separate from [EditorShell] so the shell itself stays Settings-free and unit-testable; this is
 * the thin wrapper apps mount.
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
	val savePacer = remember(settings) { LayoutSavePacer(initialLayout) { layout -> saveLayout(settings, layout) } }

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
	// initial value, and debounce coalesces a structural burst into one disk write.  The pacer holds
	// the write while a splitter drag is live and suppresses the duplicate after a drag-end commit.
	LaunchedEffect(settings) {
		snapshotFlow { latestLayout }
			.drop(1)
			.debounce(PERSIST_DEBOUNCE_MS)
			.collect { layout -> savePacer.saveDebounced(layout) }
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

	// The chrome toggles are registered here rather than in EditorShell because they write settings, and
	// the shell itself stays Settings-free (its documented contract); the standalone shell simply lacks
	// them, the same division as the app-registered File commands.
	DisposableEffect(settings, commandRegistry) {
		val cleanup = commandRegistry.registerAll(viewportChromeCommands(settings))
		onDispose { cleanup() }
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
			onLayoutDragChange = { dragActive -> savePacer.setDragActive(dragActive, latestLayout) },
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
