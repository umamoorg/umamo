package org.umamo.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Whether the Quick Setup modal is showing.  Held for the application's life rather than by the shell, which
 * is rebuilt for every document: a first run that opens a file while the modal is up keeps the modal.  The
 * app makes one - open on a first run - and provides it through [LocalQuickSetup]; the shell's overlay state
 * reads and writes it like its other overlay flags.
 *
 * @param Boolean visible Whether the modal starts open.
 */
class QuickSetupState(visible: Boolean) {
	/** Whether the modal is showing. */
	var visible: Boolean by mutableStateOf(visible)
}

/** The app's [QuickSetupState], or null where no app provides one (the standalone shell, tests). */
val LocalQuickSetup = staticCompositionLocalOf<QuickSetupState?> { null }