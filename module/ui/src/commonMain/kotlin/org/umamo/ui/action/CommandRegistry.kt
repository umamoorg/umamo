package org.umamo.ui.action

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The central table of named [Command]s and the single dispatch point for invoking them. Menus, the
 * area header, the command palette, and the keymap all go through here, so an operation has exactly
 * one implementation regardless of how it was triggered.
 *
 * Insertion order is preserved (a LinkedHashMap) so the command palette can list commands in a
 * stable, registration order rather than a hash-shuffled one.
 *
 * 名前付きコマンドの登録表と唯一の実行窓口。メニュー・ヘッダ・パレット・キーマップはすべてここを通る。
 */
class CommandRegistry {
	private val commandsById = LinkedHashMap<String, Command>()

	/**
	 * Registers (or replaces) a command under its [Command.id].
	 *
	 * @param Command command The command to register.
	 */
	fun register(command: Command) {
		commandsById[command.id] = command
	}

	/**
	 * Removes a command by id, if present.
	 *
	 * @param String id The command id to remove.
	 */
	fun unregister(id: String) {
		commandsById.remove(id)
	}

	/**
	 * Looks up a command by id without invoking it (e.g. to read its title for a menu).
	 *
	 * @param String id The command id.
	 * @return Command? The command, or null if none is registered.
	 */
	operator fun get(id: String): Command? = commandsById[id]

	/**
	 * Every registered command in registration order - the source for the command palette listing.
	 *
	 * @return List the registered commands.
	 */
	fun all(): List<Command> = commandsById.values.toList()

	/**
	 * Invokes the command bound to [id], passing [argument] to its handler.
	 *
	 * Returns whether a command was found AND currently applicable, rather than throwing, so a stray
	 * key chord or a menu item whose command was unregistered fails quietly instead of crashing the
	 * editor - and a chord bound to a command outside its context (an Edit-mode operator pressed in
	 * Object mode) falls through unconsumed rather than running a guaranteed no-op.
	 *
	 * @param String id The command id to invoke.
	 * @param Any? argument Optional context forwarded to the handler (default null).
	 * @return Boolean true if a command was found, applicable, and run; false otherwise.
	 */
	fun invoke(id: String, argument: Any? = null): Boolean {
		val command = commandsById[id] ?: return false
		if (!command.availability.isAvailable()) {
			return false
		}
		command.handler.run(argument)
		return true
	}
}

/**
 * The active [CommandRegistry] for the composition. `static` because the registry instance is stable
 * for the app's lifetime (commands are registered into it, not swapped); descendants read
 * `LocalCommands.current` to dispatch. The default errors loudly so a missing provider is caught at
 * the first dispatch rather than silently no-op'ing.
 *
 * コンポジション内で有効なコマンドレジストリ。アプリ生存期間中インスタンスは不変なので static。
 */
val LocalCommands =
	staticCompositionLocalOf<CommandRegistry> {
		error("LocalCommands not provided — wrap the editor in a CommandRegistry provider")
	}
