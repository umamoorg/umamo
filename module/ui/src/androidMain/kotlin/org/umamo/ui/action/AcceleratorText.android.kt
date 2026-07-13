package org.umamo.ui.action

/**
 * Android primary-modifier label: always "Ctrl" (a tablet with an attached keyboard uses Ctrl; there is
 * no macOS Android host).  Accelerator hints are a keyboard affordance - a keyboardless tablet shows
 * none, so this only surfaces when a hardware keyboard is present.
 *
 * Android の主修飾ラベル。常に "Ctrl"（物理キーボード接続時のみ表示される）。
 *
 * @return String The primary modifier label.
 */
actual fun primaryModifierLabel(): String = "Ctrl"
