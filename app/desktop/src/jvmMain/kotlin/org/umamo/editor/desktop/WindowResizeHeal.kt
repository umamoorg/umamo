package org.umamo.editor.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import java.awt.event.ComponentEvent
import javax.swing.JFrame
import javax.swing.Timer

/*
 * On Windows, canceling an interactive resize with Escape restores the original window rect without
 * a resize event: AWT silently syncs the frame's cached bounds (~750 ms later) but dispatches no
 * componentResized and runs no layout.  Compose derives its scene size, its layout, and
 * WindowState.size from that missing event, so the content stays frozen at the dragged size inside
 * the restored native window.  This watchdog detects the desync and replays the pipeline the lost
 * event would have driven.
 */

/** How often the heal watchdog compares the frame's bounds against its laid-out root pane. */
private const val HEAL_CHECK_INTERVAL_MILLIS = 250

/**
 * Heals the window when the OS changes its bounds behind AWT's event stream (Escape-canceled
 * resize).  After every healthy resize the root pane is laid out to exactly the frame's bounds
 * minus its insets, so a persistent mismatch is proof of a lost resize notification.  On a
 * mismatch this re-validates the component tree, which resizes the ComposeWindowPanel and with it
 * the Compose scene, then dispatches a synthetic COMPONENT_RESIZED so Compose's own window
 * listener refreshes WindowState.size.  While bounds and layout agree - the permanent state on
 * every platform outside this Windows quirk - each tick is two int comparisons on the EDT, so the
 * watchdog runs unconditionally rather than behind an OS gate.
 *
 * This composable emits no UI - it only owns the watchdog timer's lifecycle.
 *
 * @param JFrame window The host window to watch and heal.
 */
@Composable
fun WindowResizeHeal(window: JFrame) {
	DisposableEffect(window) {
		val healTimer =
			Timer(HEAL_CHECK_INTERVAL_MILLIS) {
				if (!window.isShowing) {
					return@Timer
				}
				val insets = window.insets
				val expectedWidth = window.width - insets.left - insets.right
				val expectedHeight = window.height - insets.top - insets.bottom
				val rootPane = window.rootPane
				if (expectedWidth > 0 &&
					expectedHeight > 0 &&
					(rootPane.width != expectedWidth || rootPane.height != expectedHeight)
				) {
					// Replay what a real resize event does: layout first so the scene and every Swing
					// child match the true bounds, then the window-level event so Compose's listener
					// re-reads the (already correct) width/height into WindowState.
					window.invalidate()
					window.validate()
					window.dispatchEvent(ComponentEvent(window, ComponentEvent.COMPONENT_RESIZED))
				}
			}
		healTimer.start()
		onDispose { healTimer.stop() }
	}
}