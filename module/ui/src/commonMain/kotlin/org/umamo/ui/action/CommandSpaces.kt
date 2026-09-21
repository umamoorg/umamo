package org.umamo.ui.action

import org.umamo.ui.workspace.SpaceKind

/**
 * The editor spaces a [Command] belongs to - the "where" beside [CommandAvailability]'s "when".
 *
 * A declaration about VISIBILITY and SUGGESTION only: the command palette hides a command outside its
 * spaces and the status bar suggests it only inside them.  Dispatch never reads it -
 * [CommandRegistry.invoke] and the keymap run a command wherever the pointer is, and its handler goes on
 * deciding for itself what a press means there.
 *
 * The set is read off what the handler's routing actually does, never narrower: a handler that reaches
 * back to a surface the pointer has left (the lone open keyform sheet, the last work surface touched)
 * works from anywhere, and so it is [Everywhere].  Narrowing a scope past its handler hides a command that
 * would have run.
 */
sealed interface CommandSpaces {
	/**
	 * Reports whether a command with these spaces belongs to a surface of [kind].
	 *
	 * @param SpaceKind? kind The space the pointer last touched, or null before it touched any.
	 * @return Boolean True when the command is shown and suggested there.
	 */
	fun appliesIn(kind: SpaceKind?): Boolean

	/** Belongs to every space, and to no space at all - the scope of a command that routes by nothing. */
	data object Everywhere : CommandSpaces {
		/**
		 * Always applies.
		 *
		 * @param SpaceKind? kind The space the pointer last touched, or null before it touched any.
		 * @return Boolean Always true.
		 */
		override fun appliesIn(kind: SpaceKind?): Boolean = true
	}

	/**
	 * Belongs to the listed spaces alone.
	 *
	 * @property Set<SpaceKind> kinds The spaces the command acts in; never empty, so a command that is
	 *   allowed nowhere cannot be declared.
	 */
	data class Only(val kinds: Set<SpaceKind>) : CommandSpaces {
		init {
			require(kinds.isNotEmpty()) { "A command scoped to no space could never be shown; use Everywhere or name a space" }
		}

		/**
		 * Applies when [kind] is one of [kinds].  A null kind matches nothing: the pointer has touched no
		 * surface, so the handler would resolve no area to act in either.
		 *
		 * @param SpaceKind? kind The space the pointer last touched, or null before it touched any.
		 * @return Boolean True when [kind] is listed.
		 */
		override fun appliesIn(kind: SpaceKind?): Boolean = kind != null && kind in kinds
	}

	companion object {
		/** The kinds behind [WorkSurfaces], held once so every set built on the work surfaces follows it. */
		private val workSurfaceKinds = setOf(SpaceKind.Viewport2D, SpaceKind.UvEditor)

		/** The 2D viewport alone: world-space operations whose overlay is the only collector. */
		val Viewport2D: CommandSpaces = Only(setOf(SpaceKind.Viewport2D))

		/** The UV editor alone: texture-coordinate operations. */
		val UvEditor: CommandSpaces = Only(setOf(SpaceKind.UvEditor))

		/**
		 * The two work surfaces, the 2D viewport and the UV editor: the modal transforms, the select
		 * tools, and the camera commands.  A new camera-bearing or transform-hosting space joins here.
		 */
		val WorkSurfaces: CommandSpaces = Only(workSurfaceKinds)

		/**
		 * The work surfaces plus the keyform sheet: the commands that mean one thing over a work surface
		 * and the sheet's own version of it over a sheet (Frame All, Box Select).  Derived from the work
		 * surfaces, so a space that joins them is covered here too.
		 */
		val WorkSurfacesAndSheet: CommandSpaces = Only(workSurfaceKinds + SpaceKind.KeyformSheet)

		/**
		 * The surfaces showing keyable properties, the keyform sheet's lanes and the Properties rows: the
		 * insert / delete pair that writes to whichever keyable the pointer is over.
		 */
		val KeyableSurfaces: CommandSpaces = Only(setOf(SpaceKind.KeyformSheet, SpaceKind.Properties))

		/**
		 * Builds the scope of a command whose spaces match none of the named sets.
		 *
		 * @param SpaceKind kinds The spaces the command acts in; at least one.
		 * @return CommandSpaces The scope over exactly those spaces.
		 */
		fun of(vararg kinds: SpaceKind): CommandSpaces = Only(kinds.toSet())
	}
}