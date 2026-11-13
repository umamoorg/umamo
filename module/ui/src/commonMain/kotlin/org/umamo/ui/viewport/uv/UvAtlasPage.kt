package org.umamo.ui.viewport.uv

import org.umamo.render.PuppetTextures
import org.umamo.runtime.model.Drawable

/*
 * The UV editor's surface, in display (texel) space: island picking, the atlas-placement gesture and its
 * operation-strip rows, the Edit and Object overlays, and the UV editor's own cursor, HUD, camera, and snap handler.
 * The frame an edit happens in, the display mapping under it, and the texture-coordinate transform's drive and strip
 * registration need neither a viewport nor the deformation eval, so they are in :edit (UvEditFrame.kt,
 * UvDisplayMapping.kt, UvEditDrive.kt, UvTransformAdjust.kt).  The space that hosts all of it is
 * org.umamo.ui.workspace.spaces.uv.
 */

/**
 * Resolves which atlas page a drawable samples: the mapping is keyed by the source format's ids, so
 * a session-created duplicate resolves through textureSourceId - the same resolution the GPU upload
 * and the thumbnailer use.
 *
 * @param Drawable drawable The drawable whose page to resolve.
 * @param PuppetTextures textures The document's decoded atlas pages.
 * @return Int? The page index into [PuppetTextures.atlases], or null when the drawable is untextured.
 */
internal fun atlasPageIndexFor(drawable: Drawable, textures: PuppetTextures): Int? =
	textures.atlasIndexByDrawableId[(drawable.textureSourceId ?: drawable.id).raw]