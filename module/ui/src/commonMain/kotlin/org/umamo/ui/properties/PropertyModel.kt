package org.umamo.ui.properties

import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.StringResource
import org.umamo.edit.EditorSession
import org.umamo.edit.Selection
import org.umamo.edit.SelectionTarget
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.Part
import org.umamo.runtime.model.PuppetModel
import org.umamo.ui.theme.UmamoIcon

/**
 * The immutable snapshot a property tab and its sections read from.  Rebuilt each recomposition from the
 * composition locals ([org.umamo.ui.model.LocalPuppet] / [org.umamo.ui.model.LocalSelection]) so sections
 * stay declarative - they render a function of this context rather than collecting their own flows.
 *
 * @property PuppetModel puppet The open document's model (never null - the space guards on it first).
 * @property Selection selection The current object-mode selection.
 * @property SelectionTarget? activeTarget The single active item that drives the per-item tabs, or null
 *   when nothing is selected or several items are (per-item tabs need one focused item).
 * @property EditorSession? session The editing session an editable control writes through, or null when
 *   no document is open (the controls then render disabled / inert).  Rebuilt from the composition local
 *   each recomposition alongside the read-only fields.
 */
class PropertyContext(
	val puppet: PuppetModel,
	val selection: Selection,
	val activeTarget: SelectionTarget?,
	val session: EditorSession?,
)

/**
 * The active target as a [Drawable], resolved against the model, or null when the active target is not a
 * drawable (or no longer exists in the model).
 *
 * @return Drawable? The selected drawable, or null.
 */
fun PropertyContext.activeDrawable(): Drawable? {
	val target = activeTarget as? SelectionTarget.Drawable ?: return null
	return puppet.drawables.firstOrNull { drawable -> drawable.id == target.id }
}

/**
 * The active target as a [Deformer], resolved against the model, or null when the active target is not a
 * deformer (or no longer exists in the model).
 *
 * @return Deformer? The selected deformer, or null.
 */
fun PropertyContext.activeDeformer(): Deformer? {
	val target = activeTarget as? SelectionTarget.Deformer ?: return null
	return puppet.deformers.firstOrNull { deformer -> deformer.id == target.id }
}

/**
 * The active target as a [Part], resolved against the model, or null when the active target is not a part
 * (or no longer exists in the model).
 *
 * @return Part? The selected part, or null.
 */
fun PropertyContext.activePart(): Part? {
	val target = activeTarget as? SelectionTarget.Part ?: return null
	return puppet.parts.firstOrNull { part -> part.id == target.id }
}

/**
 * The stable identity of a property tab.  Fixed slots for the first cut; the registry keying off this
 * (rather than a hardcoded switch) is what lets the set grow without touching the render loop.
 */
enum class PropertyTabId {
	/** Document-wide properties (canvas, runtime compatibility) - always shown. */
	Document,

	/** The active item's universal properties (transform, relations) - shown when one item is active. */
	Object,

	/** The active item's type-specific data - icon and sections resolve from the item's kind. */
	Data,
}

/**
 * One row within a section: the localized [terms] it exposes to the header search (its own label, plus
 * any extra labels a stacked group folds in) and the [content] that draws it from a [PropertyContext].
 * A section is a list of these, so the header search can hide the individual non-matching rows rather
 * than the whole section.  A visually fused stack (a FieldStack) is one row whose terms cover every field
 * in it, so the group stays intact under filtering.
 *
 * @property List terms The localized labels this row exposes to the header search (arg-free).
 * @property Function content The composable that draws this one row.
 */
class PropertyRow(
	val terms: List<StringResource>,
	val content: @Composable (PropertyContext) -> Unit,
)

/**
 * One collapsible section within a tab: a localized [title] and the [rows] it draws from a
 * [PropertyContext].  A section's [id] keys both its expanded/collapsed state and its search haystack, so
 * it must be stable and unique across the whole catalog.  The search index is the union of the rows'
 * terms, so there is no separate term list to keep in sync with what the rows render.
 *
 * @property String id The stable, catalog-unique section key.
 * @property StringResource title The localized section heading.
 * @property Function rows The section's rows for the given context, top to bottom.
 */
class PropertySection(
	val id: String,
	val title: StringResource,
	val rows: (PropertyContext) -> List<PropertyRow>,
)

/**
 * One tab in the left icon strip.  [isVisible] gates whether it appears for the current context (Document
 * is always visible; the per-item tabs require an active target), while [icon] and [sections] are
 * functions of the context so the Data tab can adapt its glyph and rows to the selected item's type.
 *
 * @property PropertyTabId id The tab's stable identity.
 * @property StringResource title The localized tab label (tooltip / accessibility name).
 * @property Function icon The tab's glyph, resolved from the context (adaptive for the Data tab).
 * @property Function isVisible Whether the tab appears for the given context.
 * @property Function sections The tab's sections for the given context, top to bottom.
 */
class PropertyTab(
	val id: PropertyTabId,
	val title: StringResource,
	val icon: (PropertyContext) -> UmamoIcon,
	val isVisible: (PropertyContext) -> Boolean,
	val sections: (PropertyContext) -> List<PropertySection>,
)
