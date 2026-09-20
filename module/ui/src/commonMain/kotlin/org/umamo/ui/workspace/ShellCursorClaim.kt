package org.umamo.ui.workspace

/**
 * What the shell root is saying about the pointer, window-wide, over every cursor a panel or gizmo would
 * otherwise choose for itself.  A whole-window claim is how a mode announces itself: the pointer changes
 * the moment the mode begins, wherever the hand happens to be.
 */
internal enum class ShellCursorClaim {
	/** No mode is running; each panel, splitter, and gizmo keeps its own cursor. */
	None,

	/** A relation pick is armed: the OS pointer is hidden and the shell draws the eyedropper itself. */
	Hidden,

	/**
	 * Text entry is live: the platform's own text pointer, everywhere, until the field lets go.  The
	 * platform's rather than one of ours, because this is the one cursor every OS already draws and every
	 * user already reads - and it is the cursor Foundation puts on a text field, so hovering one and
	 * typing in one show the same thing.
	 */
	TextEdit,
}

/**
 * Whether this claim must beat the cursors its descendants ask for.  Every real claim does - a mode that
 * let a splitter edge or a scrub zone keep its own pointer would stop reading as a mode at all - and
 * [ShellCursorClaim.None] must not, since with nothing claimed the descendants are the whole answer.
 */
internal val ShellCursorClaim.overridesDescendants: Boolean
	get() = this != ShellCursorClaim.None

/**
 * Resolves the one cursor the shell root claims, from the modes that can be running.
 *
 * A pick outranks text entry because the shell DRAWS the eyedropper at the pointer: a visible I-beam
 * under it would read as two cursors.  The two cannot overlap in practice anyway - arming a pick is a
 * click, which ends text entry - so the order is here to be stated rather than to be relied on.
 *
 * @param Boolean relationPickArmed Whether a relation pick is waiting for its target.
 * @param Boolean textEntryActive   Whether a text editor has parked its cancel hook.
 * @return ShellCursorClaim The claim the root applies to the whole window.
 */
internal fun shellCursorClaim(
	relationPickArmed: Boolean,
	textEntryActive: Boolean,
): ShellCursorClaim =
	when {
		relationPickArmed -> ShellCursorClaim.Hidden
		textEntryActive -> ShellCursorClaim.TextEdit
		else -> ShellCursorClaim.None
	}