package org.umamo.ui.action

/**
 * Desktop primary-modifier label: the Apple Command glyph on macOS, "Ctrl" on Windows and Linux.  The
 * OS is read from the "os.name" system property, the standard JVM way to branch on the host platform.
 *
 * デスクトップの主修飾ラベル。macOS は "⌘"、Windows / Linux は "Ctrl"。
 *
 * @return String The primary modifier label.
 */
actual fun primaryModifierLabel(): String =
	if (System.getProperty("os.name").orEmpty().startsWith("Mac")) {
		"⌘"
	} else {
		"Ctrl"
	}
