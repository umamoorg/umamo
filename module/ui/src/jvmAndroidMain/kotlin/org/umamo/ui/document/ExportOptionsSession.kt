package org.umamo.ui.document

import org.umamo.interop.moc3.Moc3ExportOptions
import org.umamo.interop.moc3.export.Moc3Export
import org.umamo.runtime.model.PuppetModel

/**
 * The MOC3 export dialog's session memory: what the rigger last confirmed, held for the life of the
 * application and deliberately NEVER persisted to settings.
 *
 * Two lifetimes live in one object.  The toggles are sticky across documents - a rigger who exports
 * without hidden parts wants that for the next model too.  The scale is sticky only per document:
 * pixels per unit is a property of one rig's bake (its default is the canvas width), so carrying a
 * confirmed scale onto a DIFFERENT document would silently bake it at the previous model's size -
 * on a document change it re-seeds from the model instead.
 *
 * A plain class rather than Compose state on purpose: the dialog copies the returned value into its
 * own editing state, so nothing observes this between exports and it stays unit-testable.
 */
class Moc3ExportSessionOptions {
	private var confirmed: Moc3ExportOptions? = null
	private var confirmedDocumentPath: String? = null

	/**
	 * The options the export dialog should open with for the document at [documentPath].
	 *
	 * First open of the session returns the dialog defaults, which match the official editor's bake
	 * (hidden objects and guides dropped) rather than [Moc3ExportOptions.Default]'s carry-everything
	 * API contract.  After a confirm, returns the confirmed toggles, with the scale re-seeded from
	 * [puppet] whenever [documentPath] is not the document the confirm happened on.
	 *
	 * @param String      documentPath The current document's identity.
	 * @param PuppetModel puppet       The model the scale seeds from on a document change.
	 * @return Moc3ExportOptions The options to open the dialog with.
	 */
	fun dialogOptionsFor(documentPath: String, puppet: PuppetModel): Moc3ExportOptions {
		val remembered = confirmed ?: moc3ExportDialogDefaults()
		val seededScale =
			if (confirmedDocumentPath == documentPath) {
				remembered.pixelsPerUnitOverride ?: Moc3Export.mocPixelsPerUnitFor(puppet)
			} else {
				Moc3Export.mocPixelsPerUnitFor(puppet)
			}
		return remembered.copy(pixelsPerUnitOverride = seededScale)
	}

	/**
	 * Records what the rigger confirmed, making it the seed for the next dialog open.
	 *
	 * Called on dialog confirm rather than after the write, so the choices survive a cancelled file
	 * picker: deciding the options and choosing a destination are separate steps.
	 *
	 * @param String documentPath The document the confirm happened on.
	 * @param Moc3ExportOptions options The confirmed options.
	 */
	fun recordConfirmed(documentPath: String, options: Moc3ExportOptions) {
		confirmed = options
		confirmedDocumentPath = documentPath
	}
}

/**
 * The export dialog's first-launch defaults, matching the official editor's bake: hidden objects
 * and guides are DROPPED unless ticked, every sidecar is included, and the scale seeds from the
 * model.
 *
 * Deliberately not [Moc3ExportOptions.Default]: that value is the API's compatibility contract for
 * options-less calls (carry everything, exactly as the lowering behaved before options existed),
 * while this is what a rigger who never touched the dialog gets - the editor-matching bake.
 *
 * @return Moc3ExportOptions The dialog defaults.
 */
fun moc3ExportDialogDefaults(): Moc3ExportOptions =
	Moc3ExportOptions(
		exportHiddenParts = false,
		exportHiddenDrawables = false,
		exportGuideImageParts = false,
	)