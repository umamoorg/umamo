package org.umamo.ui.sidebar

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.stringResource
import org.umamo.edit.Cursor2d
import org.umamo.edit.EditorSession
import org.umamo.runtime.model.originRelativeX
import org.umamo.runtime.model.originRelativeZ
import org.umamo.runtime.model.worldXFromOriginRelative
import org.umamo.runtime.model.worldZFromOriginRelative
import org.umamo.ui.kit.field.FieldStack
import org.umamo.ui.kit.field.NumberField
import org.umamo.ui.kit.field.PropertyFieldRow
import org.umamo.ui.properties.UNBOUNDED_RANGE
import org.umamo.ui.resources.*

/**
 * Where the 2D cursor stood when a scrub of one of its fields began, held so a cancelled scrub can put it
 * back, unplaced included.
 *
 * @property Cursor2d? cursor The cursor before the scrub, or null when it was unplaced.
 */
private class CursorScrubStart(val cursor: Cursor2d?)

/**
 * The scrub in flight on the cursor's fields, if any.
 */
private class CursorScrubMemory {
	/** Where the cursor stood when the scrub in flight began, or null with no scrub in flight. */
	var start: CursorScrubStart? = null
}

/**
 * The View tab's 2D Cursor panel: the cursor's X and Z, measured from the world axes as every shown
 * coordinate is.  An unplaced cursor reads the world origin, where every operation takes it to rest; typing
 * or scrubbing a value places it.  A cursor move is not an undo step, and Escape mid-scrub puts the cursor
 * back where it was, unplaced if it was.
 *
 * @param SidebarContext context The sidebar's context.
 */
@Composable
internal fun CursorPanel(context: SidebarContext) {
	val session = context.session ?: return
	val placed by session.cursor2d.collectAsState()
	val puppet = context.puppet
	val cursor = placed ?: Cursor2d(puppet.worldOriginX, puppet.worldOriginZ)
	val scrub = remember(session) { CursorScrubMemory() }

	/**
	 * Moves the cursor for one frame of a scrub, remembering where it stood when the scrub began.
	 *
	 * @param Float worldX The cursor's new world x.
	 * @param Float worldZ The cursor's new world z.
	 */
	fun previewAt(worldX: Float, worldZ: Float) {
		if (scrub.start == null) {
			scrub.start = CursorScrubStart(session.cursor2d.value)
		}

		session.setCursor2d(worldX, worldZ)
	}

	/**
	 * Moves the cursor for good: a typed value, a chevron step, or a scrub's release.
	 *
	 * @param Float worldX The cursor's new world x.
	 * @param Float worldZ The cursor's new world z.
	 */
	fun commitAt(worldX: Float, worldZ: Float) {
		scrub.start = null
		session.setCursor2d(worldX, worldZ)
	}

	/**
	 * Puts the cursor back where it stood when the cancelled scrub began.
	 */
	fun cancelScrub() {
		val start = scrub.start ?: return
		scrub.start = null
		restoreCursor(session, start.cursor)
	}

	// Only the edited axis converts: the other passes its world value through untouched, since a subtract
	// then add can land one float step off.
	FieldStack(
		listOf(
			{ position ->
				PropertyFieldRow(
					label = stringResource(Res.string.properties_field_position_x),
					description = stringResource(Res.string.sidebar_cursor_x_description),
				) {
					NumberField(
						value = puppet.originRelativeX(cursor.worldX),
						onValueChange = { newX -> commitAt(puppet.worldXFromOriginRelative(newX), cursor.worldZ) },
						onPreview = { newX -> previewAt(puppet.worldXFromOriginRelative(newX), cursor.worldZ) },
						onScrubCancel = { cancelScrub() },
						modifier = Modifier.fillMaxWidth(),
						range = UNBOUNDED_RANGE,
						decimals = 1,
						stackPosition = position,
					)
				}
			},
			{ position ->
				PropertyFieldRow(
					label = stringResource(Res.string.properties_field_position_z),
					description = stringResource(Res.string.sidebar_cursor_z_description),
				) {
					NumberField(
						value = puppet.originRelativeZ(cursor.worldZ),
						onValueChange = { newZ -> commitAt(cursor.worldX, puppet.worldZFromOriginRelative(newZ)) },
						onPreview = { newZ -> previewAt(cursor.worldX, puppet.worldZFromOriginRelative(newZ)) },
						onScrubCancel = { cancelScrub() },
						modifier = Modifier.fillMaxWidth(),
						range = UNBOUNDED_RANGE,
						decimals = 1,
						stackPosition = position,
					)
				}
			},
		),
	)
}

/**
 * Puts the 2D cursor back to [cursor], or takes it off the canvas when it was unplaced.
 *
 * @param EditorSession session The session holding the cursor.
 * @param Cursor2d? cursor Where the cursor stood, or null when it was unplaced.
 */
private fun restoreCursor(session: EditorSession, cursor: Cursor2d?) {
	if (cursor == null) {
		session.clearCursor2d()

		return
	}

	session.setCursor2d(cursor.worldX, cursor.worldZ)
}