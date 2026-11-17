package org.umamo.interop.cmo3

import org.umamo.format.cmo3.model.custom.GEditableMesh2
import org.umamo.format.cmo3.model.gen.CArtMeshSource
import org.umamo.format.cmo3.model.gen.CEditableMeshExtension
import org.umamo.format.cmo3.model.gen.CTextureInputExtension
import org.umamo.format.cmo3.model.gen.CTextureInput_TextureAtlasRegion
import org.umamo.format.cmo3.model.identity.Guid
import org.umamo.format.cmo3.model.identity.Id

/*
 * Reads of the loosely typed CMO3 graph that both directions share.  The graph holds its collections
 * and identity tokens as `Any?`, so the import, the export's lowerings, and the texture-frame rules all
 * unwrap them through the same few functions.
 */

/**
 * Flattens a CMO3 collection field (a `CArrayList`/`ArrayList`, or a `CHashMap`) to a plain list.
 *
 * @param Any? collection The raw `_sources`/`_childGuids`/etc. field, held as `Any?`.
 * @return List<Any?> The contained elements, or empty when the field is null/unrecognized.
 */
internal fun elementsOf(collection: Any?): List<Any?> =
	when (collection) {
		is Map<*, *> -> collection.values.toList()
		is Iterable<*> -> collection.toList()
		is Array<*> -> collection.toList()
		else -> emptyList()
	}

/**
 * Extracts the uuid string from a CMO3 `Guid` object, or null if absent/empty.
 *
 * @param Any? value A field expected to hold a `Guid`.
 * @return String? The uuid, or null.
 */
internal fun uuidOf(value: Any?): String? = (value as? Guid)?.uuid?.takeIf { it.isNotEmpty() }

/**
 * Extracts the id string from a CMO3 `Id` object, or null if absent/empty.
 *
 * @param Any? value A field expected to hold an `Id`.
 * @return String? The idstr (e.g. `ParamAngleX`, `ArtMesh82`), or null.
 */
internal fun idStrOf(value: Any?): String? = (value as? Id)?.idstr?.takeIf { it.isNotEmpty() }

/**
 * The display name of a CMO3 object: its own name, or a fallback when it has none.  The editor writes the
 * name of an object the rigger never named as an empty string as readily as it leaves the name out, so
 * an empty or blank name counts as none.
 *
 * @param String? name The object's own name field (`localName`, or `name` on a parameter, a group, or an image).
 * @param String? fallback What stands in for a missing name: the object's idstr (e.g. `ArtMesh82`), or its
 *   guid where it carries no id.
 * @return String The object's own name, else the fallback, else an empty string.
 */
internal fun displayNameOf(name: String?, fallback: String?): String = name?.takeIf { ownName -> ownName.isNotBlank() } ?: fallback.orEmpty()

/**
 * The editable mesh holding a drawable's stable per-vertex UIDs (`pointUid[i]` is vertex `i`'s UID,
 * since `editableMesh.point[i] == CArtMeshSource.positions[i]`).
 *
 * @param CArtMeshSource source The art-mesh source.
 * @return GEditableMesh2? The editable mesh, or null if the extension is absent.
 */
internal fun editableMeshOf(source: CArtMeshSource): GEditableMesh2? =
	elementsOf(source._extensions).filterIsInstance<CEditableMeshExtension>().firstOrNull()?.editableMesh as? GEditableMesh2

/**
 * Whether a drawable carries a `CTextureInput_TextureAtlasRegion` in its texture-input extension - i.e.
 * it was packed into a texture atlas at some point.  Packing rewrites a mesh's `uvs` into the frame of the
 * image it samples, and they stay there even when the model is toggled back to combined-layer display, so
 * this is one part of [Cmo3TextureFrames.storedFrameOf]'s call on which frame a drawable's coordinates are in.
 *
 * CMO3: CArtMeshSource _extensions -> CTextureInputExtension._textureInputs holds a
 * CTextureInput_ModelImage and, once packed, a CTextureInput_TextureAtlasRegion.  See docs/format/CMO3.md §4.
 *
 * @param CArtMeshSource source The art-mesh source.
 * @return Boolean True when the drawable has an atlas-region texture input.
 */
internal fun hasAtlasRegion(source: CArtMeshSource): Boolean {
	val extension = elementsOf(source._extensions).filterIsInstance<CTextureInputExtension>().firstOrNull() ?: return false

	return elementsOf(extension._textureInputs).any { it is CTextureInput_TextureAtlasRegion }
}