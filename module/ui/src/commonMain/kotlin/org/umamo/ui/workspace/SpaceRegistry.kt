package org.umamo.ui.workspace

import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import org.jetbrains.compose.resources.StringResource
import org.umamo.ui.theme.UmamoIcon

/**
 * The per-area context handed to a space's content factory and its header slot. Carries the hosting
 * area's stable id (which the 2D viewport needs to key its GL surface) and the per-space state bag -
 * the header slot and the body render as sibling subtrees sharing only this scope, so [spaceState] is
 * the one channel a space's header controls and its body can share state through (a CompositionLocal
 * provided inside the body never reaches the header).
 *
 * 空間のコンテンツとヘッダスロットに渡されるエリアコンテキスト。安定したエリア id と空間ごとの状態置き場を持つ。
 *
 * @property String areaId The id of the leaf hosting this space.
 */
class AreaScope(
	val areaId: String,
) {
	/** Per-space parked state, keyed by a space-chosen string. */
	private val spaceStates = mutableMapOf<String, Any>()

	/**
	 * Returns the state object parked under [stateKey], creating it via [factory] on first use. The
	 * instance lives as long as this scope (the leaf's lifetime - remember(area.id) in AreaLeaf), so
	 * it survives switching the space away and back. Keys must be namespaced by their space
	 * ("outliner.view"); a cross-space key collision is a programming error, hence the unchecked cast.
	 *
	 * @param String stateKey The space-unique key naming the parked state.
	 * @param Function factory Creates the state on first request.
	 * @return StateT The one instance for this area.
	 */
	@Suppress("UNCHECKED_CAST")
	fun <StateT : Any> spaceState(stateKey: String, factory: () -> StateT): StateT = spaceStates.getOrPut(stateKey, factory) as StateT
}

/**
 * Describes one editor space: its [kind], the localized [title] shown in the dropdown menu, the [icon]
 * shown in the area header and beside the title in the dropdown, and the [content] factory that renders
 * it given its [AreaScope]. Keeping content a `@Composable (AreaScope) -> Unit` lambda lets the app
 * override specific kinds (notably the GL viewport) without `:ui` depending on any platform code.
 *
 * [headerContent] is the optional space-specific header strip, rendered by the (otherwise
 * space-agnostic) area header between the editor-type dropdown and its flexible gap - the 2D viewport
 * mounts its mode dropdown and select-mode buttons here, and the UV editor is the intended second
 * consumer.  Declared before [content] so the existing trailing-lambda construction sites stay valid.
 *
 * 1 つのエディタ空間の記述。種別・ローカライズ済みタイトル・アイコン・コンテンツ生成関数を持つ。
 * headerContent は空間固有のヘッダ内容（任意）。
 *
 * @property SpaceKind kind The space type this describes.
 * @property StringResource title The localized dropdown label.
 * @property UmamoIcon icon The glyph shown in the area header and the dropdown rows.
 * @property Function? headerContent Optional space-specific header controls, or null for none.
 * @property Function content The composable that renders the space body.
 */
class SpaceDescriptor(
	val kind: SpaceKind,
	val title: StringResource,
	val icon: UmamoIcon,
	val headerContent: (@Composable RowScope.(AreaScope) -> Unit)? = null,
	val content: @Composable (AreaScope) -> Unit,
)

/**
 * The resolver from [SpaceKind] to its [SpaceDescriptor] - the agnostic-area machinery. An area asks
 * the registry for its current kind's descriptor to render content, and lists [all] descriptors to
 * populate the editor-type dropdown. The app augments the base registry with [withOverrides] (e.g. to
 * swap the placeholder viewport for the real GL one).
 *
 * SpaceKind から SpaceDescriptor への解決器。エリアはこれで内容を描画し、ドロップダウンを構成する。
 */
class SpaceRegistry(private val descriptorsByKind: Map<SpaceKind, SpaceDescriptor>) {
	/**
	 * The descriptor for [kind]. Throws if a kind is unregistered - every kind must have a descriptor
	 * (the base registry covers all of them), so a miss is a programming error, not a runtime branch.
	 *
	 * @param SpaceKind kind The kind to resolve.
	 * @return SpaceDescriptor The descriptor for that kind.
	 */
	fun descriptor(kind: SpaceKind): SpaceDescriptor =
		descriptorsByKind[kind] ?: error("no SpaceDescriptor registered for $kind")

	/** Every descriptor, in SpaceKind declaration order - the source for the editor-type dropdown. */
	val all: List<SpaceDescriptor> get() = SpaceKind.entries.mapNotNull { kind -> descriptorsByKind[kind] }

	/**
	 * Returns a new registry with [extra] descriptors layered over this one (same-kind entries win),
	 * so the app can replace specific spaces without rebuilding the whole map.
	 *
	 * @param Map extra The overriding descriptors, keyed by kind.
	 * @return SpaceRegistry The merged registry.
	 */
	fun withOverrides(extra: Map<SpaceKind, SpaceDescriptor>): SpaceRegistry = SpaceRegistry(descriptorsByKind + extra)
}

/**
 * The active [SpaceRegistry] for the composition. `static` - the registry is stable for the shell's
 * lifetime; areas read `LocalSpaceRegistry.current` to render and to build their dropdown.
 *
 * コンポジションで有効な SpaceRegistry。
 */
val LocalSpaceRegistry =
	staticCompositionLocalOf<SpaceRegistry> {
		error("LocalSpaceRegistry not provided — wrap the shell in a SpaceRegistry provider")
	}
