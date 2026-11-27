package org.umamo.ui.workspace.spaces.outliner

import org.umamo.edit.SelectionTarget

/**
 * Tells the reveal which change of the active selection is the outliner's own.  A selection made anywhere
 * else is revealed: its branches open and the list scrolls to it.  A click on a row selects without
 * scrolling, so the list does not move under the pointer.
 *
 * A click marks the active target it is about to produce, and the reveal asks whether the change it woke
 * for is that one.  The mark is a value, not a flag.  A click can leave the active target as it was (a
 * click on the row that is active already), and then no reveal wakes to take a flag down: a flag left
 * standing would silence the next selection made elsewhere instead.  A mark that does not match the
 * change is simply dropped.
 */
internal class OutlinerRevealStandDown {
	/**
	 * The active target a click was about to produce.  A class of its own, because null is a target a
	 * click can produce: a toggle that empties the selection.
	 *
	 * @property SelectionTarget? active The active target after the click.
	 */
	private class Mark(val active: SelectionTarget?)

	private var mark: Mark? = null

	/**
	 * Marks a click inside the outliner, before it sets the selection.
	 *
	 * @param SelectionTarget? producing The active target the click is about to produce.
	 */
	fun clicked(producing: SelectionTarget?) {
		mark = Mark(producing)
	}

	/**
	 * Whether the change of the active selection to [active] is a click's own, which is not revealed.
	 * Takes the mark down either way, so it answers for one change.
	 *
	 * @param SelectionTarget? active The active target the selection changed to.
	 * @return Boolean True when a click inside the outliner produced this change.
	 */
	fun claims(active: SelectionTarget?): Boolean {
		val standing = mark
		mark = null

		return standing != null && standing.active == active
	}
}