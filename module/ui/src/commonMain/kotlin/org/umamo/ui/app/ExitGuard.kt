package org.umamo.ui.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * The host's route into the shell's quit guard.
 *
 * The host owns the ways out that the shell never sees: the window's close button, the OS's own quit, and
 * Android's back gesture.  Each hands its exit to [request], and the shell, once composed, installs the
 * guard that decides whether the exit runs at once or waits on the unsaved-changes prompt.  Before the
 * shell installs anything, and after it is gone, an exit runs directly, because there is no document
 * whose edits could be lost.
 */
class ExitGuard {
	private var installedGuard: ((() -> Unit) -> Unit)? = null

	/**
	 * Asks to exit: the installed guard receives the exit and runs it when the rigger agrees, or the exit
	 * runs at once when no guard is installed.
	 *
	 * @param Function exit Closes the application.
	 */
	fun request(exit: () -> Unit) {
		val guard = installedGuard
		if (guard == null) {
			exit()
		} else {
			guard(exit)
		}
	}

	/**
	 * Installs the guard every later request passes through.
	 *
	 * The returned cleanup removes this guard only while it is still the installed one, so a replacement
	 * installed before the previous cleanup ran is left in place.
	 *
	 * @param Function guard Receives each requested exit and decides whether and when it runs.
	 * @return Function The cleanup that uninstalls this guard.
	 */
	fun install(guard: (() -> Unit) -> Unit): () -> Unit {
		installedGuard = guard
		return {
			if (installedGuard === guard) {
				installedGuard = null
			}
		}
	}
}

/**
 * Remembers one [ExitGuard] for the host's composition, shared by the host's exits and the shell it mounts.
 *
 * @return ExitGuard The guard.
 */
@Composable
fun rememberExitGuard(): ExitGuard = remember { ExitGuard() }