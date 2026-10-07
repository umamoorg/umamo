package org.umamo.ui.properties

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import org.umamo.runtime.model.PuppetModel
import org.umamo.ui.model.LocalPuppetRenderSync
import org.umamo.ui.model.PuppetRenderSync

/**
 * A property field's scrub, carried to the renderer while it is in flight: each drag frame's model is
 * pushed as a transient preview, and the release commits once and hands the renderer back to the session.
 *
 * The session never sees a preview frame, so a whole scrub stays one undo step - the same split the
 * modal transforms use.  The preview rides PuppetRenderSync, the seam the UV editor's modal gestures
 * stream through, so every 2D area follows the drag.
 *
 * Only a scrub that previewed resyncs.  A chevron step or a typed entry commits without previewing, and
 * resyncing for it would stomp whatever another surface happened to be previewing.
 *
 * @param Function renderSync The composition's current render-sync handle, read at each use (null with no
 *   renderer, which leaves the field committing on release alone).
 */
internal class FieldScrubPreview(private val renderSync: () -> PuppetRenderSync?) {
	private var previewing = false

	/**
	 * Shows [model] in the renderer as the scrub's current frame.  A null model - the value the field is on
	 * would change nothing - puts the committed model back instead.
	 *
	 * @param PuppetModel? model The model the scrub's value would commit, or null when it would commit nothing.
	 */
	fun preview(model: PuppetModel?) {
		if (model == null) {
			end()
			return
		}
		val sync = renderSync() ?: return
		previewing = true
		sync.previewModel(model)
	}

	/**
	 * Commits the field's value through [write], then ends the preview.  The write goes first, so the
	 * resync hands the renderer the NEW committed model rather than flashing back to the one the scrub
	 * started from; a refused or no-op write leaves the committed model as it was, which the resync restores.
	 *
	 * @param Function write The session write the field's value commits (one undo step).
	 */
	fun commit(write: () -> Unit) {
		write()
		end()
	}

	/** Ends the preview, returning the renderer to the session's committed model if this scrub moved it. */
	fun end() {
		if (!previewing) {
			return
		}
		previewing = false
		renderSync()?.resync()
	}
}

/**
 * Remembers a [FieldScrubPreview] for one property row, ended whenever its scrub can no longer commit.
 *
 * Two ways a scrub stops without a release: the row leaves the composition mid-drag (the selection
 * changed, the area switched space), and the field turns disabled mid-drag (NumberField drops its draft
 * without committing, as when Edit mode is left on a posed rig).  Either way the preview it left must go,
 * or the renderer would keep showing an edit that never happened.
 *
 * @param Boolean enabled Whether the row's fields accept input.
 * @return FieldScrubPreview The row's scrub preview.
 */
@Composable
internal fun rememberFieldScrubPreview(enabled: Boolean): FieldScrubPreview {
	val renderSync = rememberUpdatedState(LocalPuppetRenderSync.current)
	val scrub = remember { FieldScrubPreview { renderSync.value } }
	DisposableEffect(scrub) {
		onDispose {
			scrub.end()
		}
	}
	LaunchedEffect(scrub, enabled) {
		if (!enabled) {
			scrub.end()
		}
	}
	return scrub
}