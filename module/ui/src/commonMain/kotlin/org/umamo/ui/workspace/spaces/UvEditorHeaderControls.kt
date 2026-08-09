package org.umamo.ui.workspace.spaces

import org.umamo.ui.kit.OverflowRowScope

/**
 * The UV editor's space-specific header strip (mounted via SpaceDescriptor.headerContent): the
 * vertex / edge / face select-mode buttons, the active mesh's name, the transform pivot dropdown, and
 * the proportional-editing controls - the shared EditHeaderControls.kt composables the 2D viewport's
 * header also mounts, so the two surfaces stay one behavior.  All of it drives the SHARED session
 * state (the selection and its select mode are one, Blender's UV sync selection): switching to face
 * mode here switches the viewport too, by design.  Everything but the pivot is Edit-mode only, since
 * the UV editor's Object-mode face is a read-only preview - each control gates itself, and one that
 * renders nothing measures zero and costs the strip nothing.
 */
internal fun OverflowRowScope.uvEditorHeaderControls() {
	item("selectMode") { MeshSelectModeButtons() }
	item("activeMesh") { ActiveMeshName() }
	item("pivot") { PivotModeDropdown() }
	item("proportional") { ProportionalEditControls() }
}