package org.umamo.ui.viewport.gizmo

import org.umamo.edit.EditorSession
import org.umamo.edit.NoticePlacement

/**
 * The geometry a keymap request can run against, or null after telling the rigger there is none.  Both
 * surfaces' Edit-mode request collectors gate on it; they differ only in what a mesh's geometry is and in
 * the notice that explains why an area has none.
 *
 * @param EditorSession session The session to emit the notice on.
 * @param List<TGeometry> geometries The geometry the asking area can edit.
 * @param String messageKey The notice to emit when there is none.
 * @return List<TGeometry>? The geometry, or null when it is empty.
 */
internal fun <TGeometry> editableGeometryOrNotice(session: EditorSession, geometries: List<TGeometry>, messageKey: String): List<TGeometry>? {
	if (geometries.isEmpty()) {
		session.emitNotice(messageKey, NoticePlacement.NearCursor)
		return null
	}
	return geometries
}