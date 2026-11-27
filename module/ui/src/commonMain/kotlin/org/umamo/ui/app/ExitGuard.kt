package org.umamo.ui.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * The host's route into the shell's quit guard.
 *
 * The host owns the ways out that the shell never sees: the window's close button, the OS's own quit, and
 * Android's back gesture.  Each hands its exit to [request], and the shell, once composed, installs the
 * guard that decides whether the exit runs at once or waits on the unsaved-changes prompt.  Before the
 * shell installs anything, and after it is gone, an exit runs directly, because there is no document
 * whose edits could be lost.
 *
 * The guard also tells the host whether work is running that an exit must wait for ([workRunning]), so a host
 * that lets some exits bypass the guard - Android keeps the platform's own back behavior for a clean document -
 * routes them through it while that work runs.
 */
class ExitGuard {
	private var installedGuard: ((() -> Unit) -> Unit)? = null

	/** The installed guard's probe for running work, as snapshot state so [workRunning] recomposes with it. */
	private var installedWorkRunning: (() -> Boolean)? by mutableStateOf(null)

	/**
	 * Whether the installed guard reports work an exit must wait for, such as a model export being written.
	 * Read in composition, it follows the work: the probe reads snapshot state.
	 */
	val workRunning: Boolean
		get() = installedWorkRunning?.invoke() == true

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
	 * @param Function workRunning Whether work is running that an exit must wait for; read through [workRunning].
	 * @param Function guard       Receives each requested exit and decides whether and when it runs.
	 * @return Function The cleanup that uninstalls this guard.
	 */
	fun install(workRunning: () -> Boolean = { false }, guard: (() -> Unit) -> Unit): () -> Unit {
		installedGuard = guard
		installedWorkRunning = workRunning

		return {
			if (installedGuard === guard) {
				installedGuard = null
				installedWorkRunning = null
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