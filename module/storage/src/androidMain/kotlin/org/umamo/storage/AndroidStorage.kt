package org.umamo.storage

import android.content.Context
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * Builds the Android [AppStorage] from a [Context]: app-private internal storage (`filesDir`), split into
 * `config/` and `data/` subdirectories. Android sandboxes each app, so there is no XDG-style global split -
 * [applicationName] is accepted only for API symmetry with `desktopAppStorage`.
 *
 * The [FilePicker] is not platform-specific: [FileKitFilePicker] (commonMain) drives the Storage
 * Access Framework through FileKit. The Android app only needs to call `FileKit.init(activity)` once in its
 * Activity (it owns the result registry); no separate Android picker implementation is required.
 *
 * @param Context context         The application context.
 * @param String  applicationName Unused on Android (sandboxed); for symmetry with desktop.
 * @return AppStorage The Android storage.
 */
@Suppress("UNUSED_PARAMETER")
fun androidAppStorage(context: Context, applicationName: String = "umamo"): AppStorage {
	val base = context.filesDir.absolutePath.toPath()
	return OkioAppStorage(FileSystem.SYSTEM, configDirectory = base / "config", dataDirectory = base / "data")
}
