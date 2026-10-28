package org.umamo.ui.sidebar

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import org.umamo.edit.EditorSession
import org.umamo.edit.UvCursor
import org.umamo.ui.properties.TexelPositionFields
import org.umamo.ui.resources.*
import org.umamo.ui.viewport.uv.LocalUvSidebarSurface
import org.umamo.ui.viewport.uv.TexelAxis

/**
 * The View tab's 2D Cursor panel in the UV editor: the UV cursor's X and Y on the shown page or source artwork, in
 * texels from its top-left with y down.  An unplaced cursor reads the stored origin, where the snaps take it to
 * rest (the page's top-left; over a source layer, wherever that layer's mapping puts it).  Typing or scrubbing a
 * value places it.  A cursor move is not an undo step, and Escape mid-scrub puts the cursor back where it was,
 * unplaced if it was.
 *
 * @param SidebarContext context The sidebar's context.
 */
@Composable
internal fun UvCursorPanel(context: SidebarContext) {
	val session = context.session ?: return
	val surface = LocalUvSidebarSurface.current ?: return
	val placed by session.uvCursor.collectAsState()
	val cursor = placed ?: UvCursor(0f, 0f)
	val frame = surface.frame
	val (texelX, texelY) = frame.texelAt(cursor.u, cursor.v)
	val scrub = remember(session) { CursorScrubMemory<UvCursor>() }

	/**
	 * Puts the cursor's [axis] coordinate on [texelValue], the other coordinate held.
	 *
	 * @param TexelAxis axis The axis set.
	 * @param Float texelValue The cursor's new coordinate on that axis, in y-down texels.
	 */
	fun moveCursor(axis: TexelAxis, texelValue: Float) {
		val (u, v) = frame.storedUvMovedTo(cursor.u, cursor.v, axis, texelValue)
		session.setUvCursor(u, v)
	}

	TexelPositionFields(
		texelX = texelX,
		texelY = texelY,
		xDescription = Res.string.sidebar_uv_cursor_x_description,
		yDescription = Res.string.sidebar_uv_cursor_y_description,
		// A typed value, a chevron step, or a scrub's release moves the cursor for good.
		onCommit = { axis, texelValue ->
			scrub.start = null
			moveCursor(axis, texelValue)
		},
		// Each frame of a scrub moves it too, remembering where it stood when the scrub began.
		onPreview = { axis, texelValue ->
			if (scrub.start == null) {
				scrub.start = CursorScrubStart(session.uvCursor.value)
			}

			moveCursor(axis, texelValue)
		},
		onScrubCancel = {
			val start = scrub.start

			if (start != null) {
				scrub.start = null
				restoreUvCursor(session, start.cursor)
			}
		},
	)
}

/**
 * Puts the UV cursor back to [cursor], or takes it off the surface when it was unplaced.
 *
 * @param EditorSession session The session holding the cursor.
 * @param UvCursor? cursor Where the cursor stood, or null when it was unplaced.
 */
private fun restoreUvCursor(session: EditorSession, cursor: UvCursor?) {
	if (cursor == null) {
		session.clearUvCursor()

		return
	}

	session.setUvCursor(cursor.u, cursor.v)
}