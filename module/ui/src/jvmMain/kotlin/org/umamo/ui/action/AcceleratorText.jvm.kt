package org.umamo.ui.action

/**
 * Whether the desktop host is macOS, read from the "os.name" system property, the standard JVM way to branch
 * on the host platform.
 *
 * @return Boolean True on macOS.
 */
private fun hostIsMac(): Boolean = System.getProperty("os.name").orEmpty().startsWith("Mac")

/**
 * Desktop primary-modifier label: the Apple Command glyph on macOS, "Ctrl" on Windows and Linux.
 *
 * デスクトップの主修飾ラベル。macOS は "⌘"、Windows / Linux は "Ctrl"。
 *
 * @return String The primary modifier label.
 */
actual fun primaryModifierLabel(): String =
	if (hostIsMac()) {
		"⌘"
	} else {
		"Ctrl"
	}

/**
 * Desktop: Control is a modifier of its own on macOS, where Command is primary, and IS the primary modifier
 * on Windows and Linux.
 *
 * @return Boolean True on macOS.
 */
actual fun controlIsDistinctFromPrimary(): Boolean = hostIsMac()