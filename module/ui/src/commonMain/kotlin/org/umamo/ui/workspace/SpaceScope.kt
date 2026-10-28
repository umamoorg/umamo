package org.umamo.ui.workspace

/**
 * The editor spaces something belongs to: a command, a sidebar panel, or a rule that holds over the work
 * surfaces alone.  Held once so every consumer that means "the work surfaces" reads the same set.
 *
 * What belonging means is the consumer's:
 * - A command's scope is the "where" beside its availability's "when": the command palette hides a command
 *   outside its spaces and the status bar suggests it only inside them.  Dispatch never reads it.
 * - A sidebar panel's scope names the work surfaces whose sidebar shows the panel.
 */
sealed interface SpaceScope {
	/**
	 * Reports whether something with this scope belongs to a surface of [kind].
	 *
	 * @param SpaceKind? kind The space in question, or null when there is none (the pointer has touched no
	 *   surface yet).
	 * @return Boolean True when [kind] is in the scope.
	 */
	fun appliesIn(kind: SpaceKind?): Boolean

	/** Belongs to every space, and to no space at all - the scope of a command that routes by nothing. */
	data object Everywhere : SpaceScope {
		/**
		 * Always applies.
		 *
		 * @param SpaceKind? kind The space in question, or null when there is none.
		 * @return Boolean Always true.
		 */
		override fun appliesIn(kind: SpaceKind?): Boolean = true
	}

	/**
	 * Belongs to the listed spaces alone.
	 *
	 * @property Set<SpaceKind> kinds The spaces in the scope; never empty, so a scope that is allowed nowhere
	 *   cannot be declared.
	 */
	data class Only(val kinds: Set<SpaceKind>) : SpaceScope {
		init {
			require(kinds.isNotEmpty()) { "A scope over no space could never be shown; use Everywhere or name a space" }
		}

		/**
		 * Applies when [kind] is one of [kinds].  A null kind matches nothing: the pointer has touched no
		 * surface, so a command's handler would resolve no area to act in either.
		 *
		 * @param SpaceKind? kind The space in question, or null when there is none.
		 * @return Boolean True when [kind] is listed.
		 */
		override fun appliesIn(kind: SpaceKind?): Boolean = kind != null && kind in kinds
	}

	companion object {
		/** The kinds behind [WorkSurfaces], held once so every set built on the work surfaces follows it. */
		private val workSurfaceKinds = setOf(SpaceKind.Viewport2D, SpaceKind.UvEditor)

		/** The 2D viewport alone: world space. */
		val Viewport2D: SpaceScope = Only(setOf(SpaceKind.Viewport2D))

		/** The UV editor alone: a page or source layer's texture coordinates. */
		val UvEditor: SpaceScope = Only(setOf(SpaceKind.UvEditor))

		/**
		 * The two work surfaces, the 2D viewport and the UV editor: the spaces with a camera, the modal
		 * transforms, the select tools, a sidebar, and the operation strip.  A new camera-bearing or
		 * transform-hosting space joins here, and the commands, sidebar panels, and strip scoped to the work
		 * surfaces follow it.
		 */
		val WorkSurfaces: SpaceScope = Only(workSurfaceKinds)

		/**
		 * The work surfaces plus the keyform sheet: the commands that mean one thing over a work surface
		 * and the sheet's own version of it over a sheet (Frame All, Box Select).  Derived from the work
		 * surfaces, so a space that joins them is covered here too.
		 */
		val WorkSurfacesAndSheet: SpaceScope = Only(workSurfaceKinds + SpaceKind.KeyformSheet)

		/**
		 * The surfaces showing keyable properties, the keyform sheet's lanes and the Properties rows: the
		 * insert / delete pair that writes to whichever keyable the pointer is over.
		 */
		val KeyableSurfaces: SpaceScope = Only(setOf(SpaceKind.KeyformSheet, SpaceKind.Properties))

		/**
		 * Builds a scope whose spaces match none of the named sets.
		 *
		 * @param SpaceKind kinds The spaces in the scope; at least one.
		 * @return SpaceScope The scope over exactly those spaces.
		 */
		fun of(vararg kinds: SpaceKind): SpaceScope = Only(kinds.toSet())
	}
}