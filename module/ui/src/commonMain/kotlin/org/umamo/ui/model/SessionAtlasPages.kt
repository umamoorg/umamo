package org.umamo.ui.model

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import org.umamo.edit.EditorSession
import org.umamo.render.PuppetTextures
import org.umamo.render.SourceArtRasters
import org.umamo.render.deriveAtlasTextures
import org.umamo.runtime.model.PuppetAtlas
import org.umamo.runtime.model.PuppetModel
import org.umamo.storage.UmamoLog
import org.umamo.ui.viewport.AtlasPageBinding

/**
 * The session's effective atlas pages: which pixels the model's current atlas value denotes.
 *
 * Pages are DERIVED state, not history state - a snapshot must never hold pixels - so this follows
 * the session's model and resolves each atlas value to a page set: the document's imported baseline
 * short-circuits to its own decoded pages (byte-exact originals, nothing composed on open), and a
 * generated value (a repack's) composes through deriveAtlasTextures.  Undo and redo therefore swap
 * pages with no help from anyone: restoring the model IS the trigger.
 *
 * Resolution is memoized by ATLAS IDENTITY, which the model's copy-on-write discipline makes exact:
 * every non-atlas edit passes `atlas` through by reference, and undo restores the very instance the
 * snapshot holds.  The cache is the baseline plus ONE derived entry - a page set is hundreds of
 * megabytes at full size, so holding generations would be ruinous; the single slot gives redo its
 * free round trip, and an older generation re-derives off-thread while the current pages keep
 * rendering.  The repack pre-warms the slot with the pages it already composed, so the commit that
 * publishes its model cache-hits instead of composing twice.
 *
 * UI-thread confined (the binding is Compose state); only the derivation itself runs on the default
 * dispatcher.
 *
 * @property EditorSession    session    The session whose model drives the resolution.
 * @property PuppetAtlas      baselineAtlas    The imported document's atlas value.
 * @property PuppetTextures   baselineTextures The imported document's decoded pages.
 * @property SourceArtRasters artRasters The source-art pixels a generated value composes from.
 */
class SessionAtlasPages(
	private val session: EditorSession,
	baselineAtlas: PuppetAtlas,
	baselineTextures: PuppetTextures,
	private val artRasters: SourceArtRasters,
) {
	private val baseline = AtlasPageBinding(baselineAtlas, baselineTextures)

	// The one derived generation kept beyond baseline; replaced wholesale on the next derivation.
	private var derived: AtlasPageBinding? = null

	private val mutableBinding = mutableStateOf(baseline)

	/** The pages the current model's atlas denotes, paired with that atlas value. */
	val binding: State<AtlasPageBinding> get() = mutableBinding

	/**
	 * Seeds the derived slot with pages the caller already composed for [atlas], so the model commit
	 * that follows resolves by cache hit instead of composing the same pages again.
	 *
	 * @param PuppetAtlas    atlas    The atlas value the pages belong to.
	 * @param PuppetTextures textures The composed pages.
	 */
	fun prewarm(atlas: PuppetAtlas, textures: PuppetTextures) {
		derived = AtlasPageBinding(atlas, textures)
	}

	/**
	 * Follows the session's model until cancelled, publishing the binding for every atlas change.
	 * Launched once by the host beside the other session collectors.
	 */
	suspend fun follow() {
		session.model
			.distinctUntilChanged { previous, next -> previous.atlas === next.atlas }
			.collectLatest { model -> publishFor(model) }
	}

	private suspend fun publishFor(model: PuppetModel) {
		val atlas = model.atlas
		if (mutableBinding.value.atlas === atlas) {
			return
		}
		if (atlas === baseline.atlas || atlas == baseline.atlas) {
			mutableBinding.value = baseline
			return
		}
		derived?.let { cached ->
			if (cached.atlas === atlas) {
				mutableBinding.value = cached
				return
			}
		}
		val textures =
			withContext(Dispatchers.Default) {
				deriveAtlasTextures(model, artRasters, baseline.textures.premultipliedAlpha)
			}
		val next =
			if (textures != null) {
				AtlasPageBinding(atlas, textures)
			} else {
				// Unreachable for an atlas the repack authored (its placements derive by construction).
				// Publishing the previous pixels under the NEW atlas keeps the engine's model/pages pairing
				// from holding forever: wrong pixels beat a frozen viewport, and the log names the fault.
				UmamoLog.error("atlas pages: the current atlas is not derivable; keeping the previous pages")
				AtlasPageBinding(atlas, mutableBinding.value.textures)
			}
		derived = next
		mutableBinding.value = next
	}
}