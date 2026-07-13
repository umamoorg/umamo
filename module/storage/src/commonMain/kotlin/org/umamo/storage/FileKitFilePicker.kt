package org.umamo.storage

import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.openFilePicker
import io.github.vinceglb.filekit.dialogs.openFileSaver

/**
 * The [FilePicker] over FileKit's native dialogs. FileKit already abstracts each platform's picker (Win32 /
 * Cocoa / GTK on desktop, Storage Access Framework on Android), so this single commonMain implementation
 * serves every platform, including Android once the Activity calls `FileKit.init`.
 *
 * `FileKit.init` must run once at startup before any call here (desktop: in `main`; Android: in the Activity).
 */
class FileKitFilePicker : FilePicker {
	override suspend fun openFile(extensions: List<String>): PlatformFile? =
		FileKit.openFilePicker(type = fileKitType(extensions))

	override suspend fun saveFile(suggestedName: String, extension: String): PlatformFile? =
		// `defaultExtension` is the hint FileKit appends to the saved name (the `extension` arg is deprecated).
		FileKit.openFileSaver(suggestedName = suggestedName, defaultExtension = extension)

	/**
	 * Maps our extension allow-list to a FileKit type filter: a specific [FileKitType.File] when extensions
	 * are given, or the unfiltered "any file" type when the list is empty (an empty filter would otherwise
	 * mean "allow nothing").
	 *
	 * @param List<String> extensions Allowed extensions without the dot; empty = any.
	 * @return FileKitType The matching FileKit type filter.
	 */
	private fun fileKitType(extensions: List<String>): FileKitType =
		if (extensions.isEmpty()) {
			FileKitType.File()
		} else {
			FileKitType.File(extensions)
		}
}
