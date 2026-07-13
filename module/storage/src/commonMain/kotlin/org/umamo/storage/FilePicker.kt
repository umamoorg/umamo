package org.umamo.storage

import io.github.vinceglb.filekit.PlatformFile

/**
 * A native open/save file dialog - a platform service (not Compose UI), so it belongs in :storage with the
 * other file concerns. Backed by FileKit ([org.umamo.storage.FileKitFilePicker]), which already speaks each
 * platform's native dialog, so the single implementation covers desktop and Android alike.
 *
 * Returns a FileKit [PlatformFile] rather than an okio `Path`: on Android the Storage Access Framework hands
 * back a `content://` URI that has no real filesystem path, which a `Path` cannot represent - `PlatformFile`
 * abstracts that and exposes read/write directly. Suspending so the (potentially async) native flow is
 * awaited the same way on every platform.
 */
interface FilePicker {
	/**
	 * Prompts the user to choose an existing file to open.
	 *
	 * @param List<String> extensions Allowed extensions without the dot (e.g. `["cmo3", "psd"]`); empty = any.
	 * @return PlatformFile? The chosen file, or null if cancelled.
	 */
	suspend fun openFile(extensions: List<String>): PlatformFile?

	/**
	 * Prompts the user to choose a destination to save to.
	 *
	 * @param String suggestedName Pre-filled file name without extension (e.g. "MyModel").
	 * @param String extension     Target extension without the dot (e.g. "cmo3").
	 * @return PlatformFile? The chosen destination, or null if cancelled.
	 */
	suspend fun saveFile(suggestedName: String, extension: String): PlatformFile?
}
