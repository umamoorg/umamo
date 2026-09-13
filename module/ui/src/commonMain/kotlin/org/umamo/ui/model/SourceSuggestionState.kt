package org.umamo.ui.model

import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.StateFlow
import org.umamo.reimport.LayerMatch
import org.umamo.runtime.model.ArtSourceId

/**
 * The pixel-scored suggestions the last operation that read a file published for its unresolved
 * bindings, keyed by file and lost layer key: what the Sources space prefers over the ranking it can
 * make from the model alone.  Read-only here; the app publishes into it after a reload, a replace, or
 * a Match Automatically, and clears it on a document swap.
 *
 * @property StateFlow suggestions The best candidate per lost binding, by (file, lost key).
 */
class SourceSuggestionState(
	val suggestions: StateFlow<Map<Pair<ArtSourceId, String>, LayerMatch>>,
)

/** The open document's published suggestions, or null when none were published (no document, or outside the app). */
val LocalSourceSuggestions = staticCompositionLocalOf<SourceSuggestionState?> { null }