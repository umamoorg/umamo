package org.umamo.ui.action

import org.jetbrains.compose.resources.StringResource

/**
 * Receives a command invocation. A `fun interface` (Kotlin SAM) so call sites can pass a lambda -
 * `CommandHandler { argument -> … }` - instead of an anonymous object.
 *
 * コマンド実行を受け取る関数型インターフェース。ラムダで渡せる。
 */
fun interface CommandHandler {
	/**
	 * Runs the command.
	 *
	 * @param Any? argument Optional context the caller passes (e.g. a target area id); null for nullary commands.
	 */
	fun run(argument: Any?)
}

/**
 * Reports whether a command currently applies - the context filter behind the command palette (an
 * Edit-mode select-mode command is hidden in Object mode) and the dispatch guard in
 * [CommandRegistry.invoke].  A `fun interface` so registration sites pass a lambda closing over live
 * session state, evaluated fresh at each query rather than captured once.
 *
 * コマンドが現在のコンテキストで有効かを返す。パレットの絞り込みとディスパッチのガードに使う。
 */
fun interface CommandAvailability {
	/**
	 * Reports whether the command currently applies.
	 *
	 * @return Boolean True when the command may be shown and invoked.
	 */
	fun isAvailable(): Boolean

	companion object {
		/** The default availability: always applicable (context-free commands like palette.toggle). */
		val Always: CommandAvailability = CommandAvailability { true }
	}
}

/**
 * A named, invokable editor operation - the atom the action registry dispatches. Every operation
 * (split an area, open a file, fit the view) is one of these, addressed by a stable dotted [id]
 * ("area.split.leftRight", "file.open"). Menus, the area header, the command palette, and key
 * bindings all resolve to a Command and run its [handler]; nothing hardcodes a handler, so rebinding
 * a key or relabelling a menu changes one place.
 *
 * 名前付きの実行可能な操作。メニュー・ヘッダ・パレット・キーバインドはすべてこれを介して実行する。
 *
 * @property String id The stable dotted identifier used to look the command up and to bind keys to it.
 * @property StringResource? title The localized menu/palette label, or null for commands never shown in UI (e.g. test-only).
 * @property CommandAvailability availability Whether the command currently applies (palette filter +
 *   dispatch guard); defaults to always.
 * @property CommandHandler handler The work to perform when invoked.
 */
class Command(
	val id: String,
	val title: StringResource?,
	val availability: CommandAvailability = CommandAvailability.Always,
	val handler: CommandHandler,
)
