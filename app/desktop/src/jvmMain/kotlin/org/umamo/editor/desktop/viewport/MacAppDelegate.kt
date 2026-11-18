package org.umamo.editor.desktop.viewport

import com.sun.jna.Function
import com.sun.jna.NativeLibrary
import org.umamo.storage.UmamoLog

/*
 * GLFW's Cocoa init makes its own GLFWApplicationDelegate the NSApplication delegate, unconditionally and for the
 * rest of the run.  AWT receives the operating system's open-file and quit requests through the delegate it
 * installed, the JDK's ApplicationDelegate, so once the viewport starts GLFW, a .uma double-clicked in Finder ends
 * in AppKit's "cannot open files in this format" alert and the Dock's Quit never reaches the unsaved-changes
 * guard.  The offscreen context's hidden 1x1 window needs nothing GLFW's delegate does, so the viewport hands the
 * delegate back right after glfwInit.
 */

/** The Objective-C runtime library that holds every entry point below. */
private const val OBJC_LIBRARY = "objc"

/** The framework that exports the NSApp global. */
private const val APPKIT_LIBRARY = "/System/Library/Frameworks/AppKit.framework/AppKit"

/**
 * Whether the running OS is macOS, the one OS whose GLFW replaces the application delegate and the one whose
 * LWJGL loads the glfw_async build.
 *
 * @return Boolean True on macOS.
 */
internal fun isMacOs(): Boolean {
	val osName = System.getProperty("os.name").orEmpty().lowercase()

	return osName.contains("mac") || osName.contains("darwin")
}

/**
 * Sends an Objective-C message whose result is an object or a pointer.  objc_msgSend is called with fixed
 * arguments, never as a variadic function, which arm64's calling convention requires.
 *
 * @param Long receiver The receiving object or class.
 * @param String selectorName The selector, as Objective-C spells it (`setDelegate:`).
 * @param Array arguments The message's own arguments: Long for an object or selector, Boolean for a BOOL.
 * @return Long The result as a pointer-width value; 0 is nil.
 */
private fun sendMessage(receiver: Long, selectorName: String, vararg arguments: Any): Long {
	val messageSend = Function.getFunction(OBJC_LIBRARY, "objc_msgSend")

	return messageSend.invokeLong(arrayOf<Any>(receiver, selector(selectorName), *arguments))
}

/**
 * Looks up a selector by name.
 *
 * @param String selectorName The selector, as Objective-C spells it.
 * @return Long The registered selector.
 */
private fun selector(selectorName: String): Long = Function.getFunction(OBJC_LIBRARY, "sel_registerName").invokeLong(arrayOf<Any>(selectorName))

/**
 * The application instance, read from AppKit's NSApp global rather than asked for with `sharedApplication`, which
 * would create one on the calling thread when none exists.  A process with no AWT, such as a test that starts the
 * viewport alone, leaves creating it to GLFW on the main thread: an NSApplication created on the render thread
 * makes GLFW's own `[NSApp run]` trap on AppKit's main-event-queue assertion.
 *
 * @return Long The NSApplication instance; 0 while nothing has created one.
 */
private fun existingApplication(): Long = NativeLibrary.getInstance(APPKIT_LIBRARY).getGlobalVariableAddress("NSApp").getLong(0)

/**
 * The class name of an Objective-C object, for the log.
 *
 * @param Long instance The object; 0 for nil.
 * @return String The class name, or "nil".
 */
internal fun className(instance: Long): String {
	if (instance == 0L) {
		return "nil"
	}

	return Function.getFunction(OBJC_LIBRARY, "object_getClassName").invokeString(arrayOf<Any>(instance), false) ?: "unknown"
}

/**
 * The NSApplication delegate as it stands, to give back after something replaces it.
 *
 * @return Long The current delegate; 0 off macOS, before anything has created the application, when it has no
 *   delegate, or when the Objective-C runtime cannot be called.
 */
internal fun currentAppKitDelegate(): Long {
	if (!isMacOs()) {
		return 0L
	}

	return try {
		val application = existingApplication()

		if (application == 0L) 0L else sendMessage(application, "delegate")
	} catch (linkFailure: LinkageError) {
		UmamoLog.warn("[GL] the NSApplication delegate could not be read: $linkFailure")
		0L
	}
}

/**
 * Makes [previousDelegate] the NSApplication delegate again when something has replaced it.  The setter runs on the
 * AppKit thread without waiting for it, so the render thread never blocks on the main thread.
 *
 * @param Long previousDelegate The delegate [currentAppKitDelegate] returned before the replacement; 0 does nothing.
 */
internal fun restoreAppKitDelegate(previousDelegate: Long) {
	if (!isMacOs() || previousDelegate == 0L) {
		return
	}

	try {
		val application = existingApplication()

		if (application == 0L) {
			return
		}

		val replacement = sendMessage(application, "delegate")

		if (replacement == previousDelegate) {
			return
		}

		sendMessage(application, "performSelectorOnMainThread:withObject:waitUntilDone:", selector("setDelegate:"), previousDelegate, false)
		UmamoLog.info("[GL] NSApplication delegate restored to ${className(previousDelegate)} in place of ${className(replacement)}")
	} catch (linkFailure: LinkageError) {
		UmamoLog.warn("[GL] the NSApplication delegate could not be restored, so files opened from Finder will not reach Umamo: $linkFailure")
	}
}