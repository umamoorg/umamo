package org.umamo.ui.properties

import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.StringResource
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
 * プロパティタブとセクションが読み取る不変スナップショット。合成ローカルから毎回再構築する。
 *
 * @property PuppetModel puppet The open document's model (never null - the space guards on it first).
 * @property Selection selection The current object-mode selection.
 * @property SelectionTarget? activeTarget The single active item that drives the per-item tabs, or null
 *   when nothing is selected or several items are (per-item tabs need one focused item).
 */
class PropertyContext(
	val puppet: PuppetModel,
	val selection: Selection,
	val activeTarget: SelectionTarget?,
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
 *
 * プロパティタブの安定した識別子。当面は固定枠だが、レジストリで切り替えるため拡張可能。
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
 * One collapsible section within a tab: a localized [title], the localized labels it renders
 * ([searchTerms], matched by the header search), and the [content] that draws its rows from a
 * [PropertyContext].  A section's [id] keys both its expanded/collapsed state and its search haystack, so
 * it must be stable and unique across the whole catalog.
 *
 * タブ内の折りたたみ可能なセクション。タイトル・検索用ラベル・内容を持つ。
 *
 * @property String id The stable, catalog-unique section key.
 * @property StringResource title The localized section heading.
 * @property List searchTerms The localized row labels this section exposes to the header search.
 * @property Function content The composable that draws the section's rows.
 */
class PropertySection(
	val id: String,
	val title: StringResource,
	val searchTerms: List<StringResource>,
	val content: @Composable (PropertyContext) -> Unit,
)

/**
 * One tab in the left icon strip.  [isVisible] gates whether it appears for the current context (Document
 * is always visible; the per-item tabs require an active target), while [icon] and [sections] are
 * functions of the context so the Data tab can adapt its glyph and rows to the selected item's type.
 *
 * 左アイコンストリップの1タブ。表示可否・アイコン・セクションはコンテキストの関数。
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
