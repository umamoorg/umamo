package org.umamo.ui.model

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Whether an artwork file is still where the document last read it: true when present, false when
 * missing, null when the platform cannot say (a content uri, a path it will not probe).
 *
 * @param String path The advisory path the model recorded.
 * @return Boolean? The answer, or null for unknown.
 */
typealias SourceFilePresence = (path: String) -> Boolean?

/**
 * The app's file-presence probe for the Sources space, or null on a platform without one.  A probe
 * that answers null for a path leaves that source's status Unknown; without a probe every source
 * reads Unknown, never Missing - the space must not accuse a file it cannot check.
 */
val LocalSourceFilePresence = staticCompositionLocalOf<SourceFilePresence?> { null }