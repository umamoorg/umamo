package org.umamo.interop.cmo3.export

import org.umamo.format.cmo3.Cmo3GraphEditor
import org.umamo.format.cmo3.model.gen.ACParameterControllableSource

/*
 * The ACParameterControllableSource fields more than one entity's lowering writes: the display name,
 * the visibility, and the lock.
 */

/**
 * CMO3: ACParameterControllableSource field localName - the user-facing display name.
 *
 * @param Cmo3GraphEditor editor The editor that records the field's slot.
 * @param ACParameterControllableSource source The entity's graph source.
 * @param String name The display name to write.
 */
internal fun lowerLocalName(editor: Cmo3GraphEditor, source: ACParameterControllableSource, name: String) {
	source.localName = name
	editor.ensureChildSlot(source, "ACParameterControllableSource", "localName", "isVisible")
}

/**
 * CMO3: ACParameterControllableSource field isVisible - the Parts-panel eyeball.
 *
 * @param Cmo3GraphEditor editor The editor that records the field's slot.
 * @param ACParameterControllableSource source The entity's graph source.
 * @param Boolean isVisible Whether the entity is shown.
 */
internal fun lowerIsVisible(editor: Cmo3GraphEditor, source: ACParameterControllableSource, isVisible: Boolean) {
	source.isVisible = isVisible
	editor.ensureChildSlot(source, "ACParameterControllableSource", "isVisible", "isLocked")
}

/**
 * CMO3: ACParameterControllableSource field isLocked (inverted: Cubism lock = not selectable).
 *
 * @param Cmo3GraphEditor editor The editor that records the field's slot.
 * @param ACParameterControllableSource source The entity's graph source.
 * @param Boolean isSelectable Whether the entity can be selected; the stored lock is its inverse.
 */
internal fun lowerIsLocked(editor: Cmo3GraphEditor, source: ACParameterControllableSource, isSelectable: Boolean) {
	source.isLocked = !isSelectable
	editor.ensureChildSlot(source, "ACParameterControllableSource", "isLocked", "parentGuid")
}