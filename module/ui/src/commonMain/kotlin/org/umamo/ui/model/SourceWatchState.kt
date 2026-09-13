package org.umamo.ui.model

import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.StateFlow
import org.umamo.runtime.model.ArtSourceId

/**
 * What the open document's artwork watcher tells the Sources space: which files changed on disk and
 * await a reload (the header's alert glyph), and a serial that moves whenever a file's presence did
 * (the tree re-probes).  Read-only here; the app owns the watcher.
 *
 * @property StateFlow pending The files whose settled content differs from what the document last read.
 * @property StateFlow serial  Bumped on every presence change.
 */
class SourceWatchState(
	val pending: StateFlow<Set<ArtSourceId>>,
	val serial: StateFlow<Int>,
)

/** The open document's watch state, or null when nothing is watching (no document, or outside the app). */
val LocalSourceWatch = staticCompositionLocalOf<SourceWatchState?> { null }