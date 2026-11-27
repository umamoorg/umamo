package org.umamo.ui.action

/**
 * Android primary-modifier label: always "Ctrl" (a tablet with an attached keyboard uses Ctrl; there is
 * no macOS Android host).  Accelerator hints are a keyboard affordance - a keyboardless tablet shows
 * none, so this only surfaces when a hardware keyboard is present.
 *
 * @return String The primary modifier label.
 */
actual fun primaryModifierLabel(): String = "Ctrl"

/**
 * Android: Control is the primary modifier (a tablet keyboard has no Command key), so it is never a modifier
 * of its own.
 *
 * @return Boolean Always false.
 */
actual fun controlIsDistinctFromPrimary(): Boolean = false