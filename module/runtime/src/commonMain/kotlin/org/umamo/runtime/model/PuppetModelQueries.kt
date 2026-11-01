package org.umamo.runtime.model

/*
 * Pure per-drawable index queries over a PuppetModel.  The picking-oriented queries are shared by
 * every platform viewport service (the desktop offscreen renderer today, the Android GLES service
 * when it lands): the pickable geometry sets picking iterates, and the display lookups the overlap
 * picker labels rows with.  The atlas queries resolve a drawable's or tile's source-art binding, the
 * same resolution the renderer and UV editor build on.  All derive from the model alone, so callers
 * recompute them on each model swap - except when the swap moved mesh positions alone, which
 * differsOnlyInMeshPositions tells them, so the position-free lookups can be kept.
 */

/**
 * The mesh of drawable [id], or null when the model has no such drawable or it carries no mesh - the one
 * lookup every per-drawable mesh edit starts from.
 *
 * @param DrawableId id The drawable.
 * @return DrawableMesh? Its mesh, or null.
 */
fun PuppetModel.meshOf(id: DrawableId): DrawableMesh? = drawables.firstOrNull { drawable -> drawable.id == id }?.mesh

/**
 * The per-drawable triangle indices picking iterates: the shown, meshed drawables only, so
 * unshown / mesh-less drawables are never hit.
 *
 * @return Map<DrawableId, IntArray> Drawable id to its triangle indices.
 */
fun PuppetModel.pickableIndicesByDrawable(): Map<DrawableId, IntArray> =
	visibleDrawableIds().let { shownIds ->
		drawables
			.filter { drawable -> drawable.id in shownIds }
			.mapNotNull { drawable ->
				drawable.mesh?.takeIf { it.indices.isNotEmpty() }?.let { drawable.id to it.indices }
			}
			.toMap()
	}

/**
 * The per-drawable full-atlas UVs for the same pickable set as [pickableIndicesByDrawable], so
 * picking can interpolate a hit point's UV and sample the atlas alpha.
 *
 * @return Map<DrawableId, FloatArray> Drawable id to its mesh UVs.
 */
fun PuppetModel.pickableUvsByDrawable(): Map<DrawableId, FloatArray> =
	visibleDrawableIds().let { shownIds ->
		drawables
			.filter { drawable -> drawable.id in shownIds }
			.mapNotNull { drawable ->
				drawable.mesh?.takeIf { it.indices.isNotEmpty() }?.let { drawable.id to it.uvs }
			}
			.toMap()
	}

/**
 * Drawable id to owning part name, for the overlap-picker row labels.
 *
 * @return Map<DrawableId, String> Drawable id to its owning part's name.
 */
fun PuppetModel.partNameByDrawable(): Map<DrawableId, String> =
	parts.associate { part -> part.id to part.name }.let { partNameById ->
		val ownerByDrawable = partByDrawable()
		drawables
			.mapNotNull { drawable ->
				ownerByDrawable[drawable.id]?.let { partId -> partNameById[partId]?.let { name -> drawable.id to name } }
			}
			.toMap()
	}

/**
 * Drawable id to its own display name, for the overlap-picker row labels.  Names are returned as the
 * model holds them - a blank one is a display decision the caller makes, not a lookup failure.
 *
 * @return Map<DrawableId, String> Drawable id to its name.
 */
fun PuppetModel.drawableNameByDrawable(): Map<DrawableId, String> =
	drawables.associate { drawable -> drawable.id to drawable.name }

/**
 * Drawable id to the source-format id its atlas region is keyed by: itself, or its texture source for
 * a session-created duplicate (a copy of a copy resolves to the original).
 *
 * @return Map<DrawableId, String> Drawable id to its atlas lookup key.
 */
fun PuppetModel.atlasKeyByDrawable(): Map<DrawableId, String> =
	drawables.associate { drawable -> drawable.id to (drawable.textureSourceId ?: drawable.id).raw }

/**
 * How one drawable's stored texture coordinates map back into its source art's own pixel frame: the
 * single resolution every source-art consumer starts from (the renderer sampling per-layer textures, the
 * UV editor drawing a mapping over the artwork).
 *
 * @param Drawable drawable The drawable to resolve.
 * @return DrawableLayerBinding? The binding, or null when the document retains no art for it.
 */
fun PuppetModel.atlasBindingFor(drawable: Drawable): DrawableLayerBinding? =
	drawable.atlasTileId?.let { tileId -> atlasBindingForTile(tileId) }

/**
 * Every drawable sampling each tile, in document order.
 *
 * The inverse of [Drawable.atlasTileId], and the answer to "how many drawables share this art" -
 * duplicated art is common, and a surface offering a tile needs to say what picking it will select.
 *
 * @return Map<AtlasTileId, List<DrawableId>> Tile to its drawables; a tile nothing samples is absent.
 */
fun PuppetModel.drawableIdsByAtlasTile(): Map<AtlasTileId, List<DrawableId>> {
	val byTile = LinkedHashMap<AtlasTileId, MutableList<DrawableId>>()

	for (drawable in drawables) {
		val tileId = drawable.atlasTileId ?: continue
		byTile.getOrPut(tileId) { ArrayList() }.add(drawable.id)
	}

	return byTile
}

/**
 * How a tile's stored texture coordinates map back into its own pixel frame.
 *
 * The placement is a property of the TILE, so every drawable over one piece of art shares this binding -
 * which is what lets the editor describe a tile's frame without first deciding which of its users to
 * ask, and keeps that frame stable as the selection narrows.
 *
 * @param AtlasTileId tileId The tile to resolve.
 * @return DrawableLayerBinding? The binding, or null when the tile is unknown or its page is missing.
 */
fun PuppetModel.atlasBindingForTile(tileId: AtlasTileId): DrawableLayerBinding? = atlas.bindingForTile(tileId)

/**
 * How a tile's stored texture coordinates map back into its own pixel frame, resolved against THIS
 * atlas value's pages.
 *
 * The atlas-level form of [PuppetModel.atlasBindingForTile], for callers holding an atlas that is not
 * the model's own - a repack reads a tile's OLD mapping against the current page inventory and its NEW
 * one against the replacement inventory, and the page a placement names supplies the normalization,
 * so the two reads must not share pages.
 *
 * @param AtlasTileId tileId The tile to resolve.
 * @return DrawableLayerBinding? The binding, or null when the tile is unknown or its page is missing.
 */
fun PuppetAtlas.bindingForTile(tileId: AtlasTileId): DrawableLayerBinding? {
	val tile = tileById[tileId] ?: return null
	val placement = tile.placement?.takeIf { storedUvsAddressPages }
	val page = placement?.let { packed -> pages.getOrNull(packed.pageIndex) }

	if (placement != null && page == null) {
		// A placement naming a page the document does not have is broken wiring, not a tile that is
		// sampled directly - resolving it to the identity would silently draw the wrong frame.
		return null
	}

	return DrawableLayerBinding(tile.id.raw, placement, page?.width ?: 0, page?.height ?: 0)
}

/**
 * The whole stored-uv to art-uv mapping of a tile under this atlas value, as one 2x3 affine - the
 * [bindingForTile] resolution folded with the tile's own size through [layerUvAffineOf], so every
 * consumer that needs the mapping rather than the binding (a repack's re-derivation, a mesh-reach
 * measurement) reads it from one place.
 *
 * @param AtlasTileId tileId The tile to resolve.
 * @return FloatArray? The affine, or null when the binding or the mapping cannot be formed.
 */
fun PuppetAtlas.storedToArtAffineForTile(tileId: AtlasTileId): FloatArray? {
	val tile = tileById[tileId] ?: return null
	val binding = bindingForTile(tileId) ?: return null

	return layerUvAffineOf(binding, tile.width, tile.height)
}

/**
 * Part id to every drawable in its subtree, walking nested parts.  The inverse direction of
 * [partByDrawable], and the resolution behind a part used as a clip mask ([PartComposite.maskedByParts]):
 * masking by a part means masking by all of its descendant drawables, recomputed from the tree so the mask
 * follows the part as its children change.  A part with no drawables under it maps to an empty list.
 *
 * @return Map<PartId, List<DrawableId>> Part id to its descendant drawables, in tree order.
 */
fun PuppetModel.drawablesByPartSubtree(): Map<PartId, List<DrawableId>> {
	val partById = parts.associateBy { part -> part.id }
	val collected = HashMap<PartId, List<DrawableId>>(parts.size)

	fun collect(partId: PartId, guard: MutableSet<PartId>): List<DrawableId> {
		collected[partId]?.let { return it }

		// A malformed tree could cycle; the guard makes this terminate rather than blow the stack.
		if (!guard.add(partId)) {
			return emptyList()
		}

		val part = partById[partId]
		val gathered =
			if (part == null) {
				emptyList()
			} else {
				val into = ArrayList<DrawableId>()

				for (child in part.children) {
					when (child) {
						is OrgChild.Drawable -> into.add(child.id)
						is OrgChild.Part -> into.addAll(collect(child.id, guard))
					}
				}

				into
			}
		guard.remove(partId)
		collected[partId] = gathered

		return gathered
	}

	for (part in parts) {
		collect(part.id, mutableSetOf())
	}

	return collected
}

/**
 * Part id to the part that owns it, for the parts nested under another part.  A top-level part (one sitting
 * in [PuppetModel.rootChildren]) has no entry.  Part membership lives only in the org tree - there is no
 * back-pointer on [Part] - so this is the part-side counterpart of [partByDrawable], recomputed from the
 * child lists.
 *
 * @return Map<PartId, PartId> Nested part id to its owning part.
 */
fun PuppetModel.parentPartByPart(): Map<PartId, PartId> {
	val parentOf = HashMap<PartId, PartId>(parts.size)

	for (part in parts) {
		for (child in part.children) {
			if (child is OrgChild.Part) {
				parentOf[child.id] = part.id
			}
		}
	}

	return parentOf
}

/**
 * Every part in [id]'s org-tree subtree, including [id] itself: the set a part may NOT be re-homed under,
 * since that would orphan a cycle.  The org-move guard and the Properties picker's candidate filter both
 * derive from this one definition.
 *
 * @param PartId id The part whose subtree to collect.
 * @return Set<PartId> The part and every part beneath it.
 */
fun PuppetModel.partSelfAndDescendants(id: PartId): Set<PartId> {
	val partById = parts.associateBy { part -> part.id }
	val collected = LinkedHashSet<PartId>()

	fun walk(current: PartId) {
		// add() guards a malformed (already cyclic) tree from recursing forever.
		if (!collected.add(current)) {
			return
		}

		partById[current]?.children?.forEach { child ->
			if (child is OrgChild.Part) {
				walk(child.id)
			}
		}
	}
	walk(id)

	return collected
}

/**
 * Every deformer in [id]'s nesting subtree, including [id] itself.  The set a deformer may NOT be re-nested
 * under - doing so would make the hierarchy a cycle - so both the move guard and the Properties picker's
 * candidate filter derive from this one definition rather than each rolling their own walk.
 *
 * @param DeformerId id The deformer whose subtree to collect.
 * @return Set<DeformerId> The deformer and everything nested beneath it.
 */
fun PuppetModel.deformerSelfAndDescendants(id: DeformerId): Set<DeformerId> {
	val childrenByParent = deformers.groupBy { deformer -> deformer.parent }
	val collected = LinkedHashSet<DeformerId>()

	fun walk(current: DeformerId) {
		// add() guards a malformed (already cyclic) hierarchy from recursing forever.
		if (!collected.add(current)) {
			return
		}

		childrenByParent[current]?.forEach { child -> walk(child.id) }
	}
	walk(id)

	return collected
}

/**
 * A composite's clip masks as ONE flat drawable list: its direct [PartComposite.maskedBy] plus every
 * drawable beneath each [PartComposite.maskedByParts] entry, de-duplicated (a drawable masked both
 * directly and through an ancestor part counts once).
 *
 * This is the single definition of "what actually clips this layer".  The render tree resolves composites
 * through it, and an exporter to a format that cannot express part masks MUST use it too, so the two can
 * never disagree about the clipping the user sees.
 *
 * Takes the part-subtree index rather than building it, since both callers resolve many composites in one
 * pass and [drawablesByPartSubtree] walks the whole part tree; compute it once for the batch.
 *
 * @param PartComposite composite The authored composite.
 * @param Map subtrees Each part's descendant drawables, from [drawablesByPartSubtree], built once per batch.
 * @return List<DrawableId> The effective clip masks, in order.
 */
fun flattenedMasks(composite: PartComposite, subtrees: Map<PartId, List<DrawableId>>): List<DrawableId> {
	if (composite.maskedByParts.isEmpty()) {
		return composite.maskedBy
	}

	val expanded = composite.maskedByParts.flatMap { maskPartId -> subtrees[maskPartId].orEmpty() }

	return (composite.maskedBy + expanded).distinct()
}

/**
 * A copy of this model with every part composite's part-masks expanded into plain drawable masks and
 * [PartComposite.maskedByParts] emptied - the conversion an export to a drawable-only mask format (CMO3,
 * MOC3) MUST apply, so the clipping survives exactly as the viewport shows it and only the authoring
 * grouping is lost.  UMA keeps the grouping and needs no flattening.
 *
 * @return PuppetModel The model with part-masks flattened, or [this] when none are used.
 */
fun PuppetModel.withPartMasksFlattened(): PuppetModel {
	if (parts.none { part -> part.composite.maskedByParts.isNotEmpty() }) {
		return this
	}

	// One index for the whole batch (see flattenedMasks).
	val subtrees = drawablesByPartSubtree()
	val flattened =
		parts.map { part ->
			val composite = part.composite

			if (composite.maskedByParts.isEmpty()) {
				part
			} else {
				part.copy(
					composite = composite.copy(maskedBy = flattenedMasks(composite, subtrees), maskedByParts = emptyList()),
				)
			}
		}

	return copy(parts = flattened).withDerivedRenderRoot()
}

/**
 * The drawable's 5.3 per-art-mesh multiply color for display in the Properties panel.
 *
 * A plain static read: the tint is its own channel track, so no keyform cell is consulted for it.
 *
 * @return ColorRgb The drawable's static multiply color.
 */
fun Drawable.displayMultiplyColor(): ColorRgb = multiplyColor

/**
 * The drawable's 5.3 per-art-mesh screen color for display in the Properties panel; see
 * [displayMultiplyColor].
 *
 * @return ColorRgb The drawable's static screen color.
 */
fun Drawable.displayScreenColor(): ColorRgb = screenColor

/**
 * Whether this model differs from [previous] in nothing but some drawables' mesh positions - the canvas mesh
 * and the keyform-space base - the shape every preview push of a Grab has.  Conservative: any other change,
 * a UV, topology, or vertex-count edit included, counts as structural.
 *
 * The renderer reads it to keep its pose across a push (the pose's inputs hold no positions), and the
 * desktop viewport service to keep the picker lookups that read no positions.
 *
 * @param PuppetModel previous The model the consumer currently holds.
 * @return Boolean True when only mesh positions differ, or nothing does.
 */
fun PuppetModel.differsOnlyInMeshPositions(previous: PuppetModel): Boolean {
	if (this === previous) {
		return true
	}

	// A data-class equals short-circuits on shared references, so a positions-only push (new meshes, every
	// other field shared) costs one walk over the drawables.
	if (drawables.size != previous.drawables.size || copy(drawables = previous.drawables) != previous) {
		return false
	}

	for (drawableIndex in drawables.indices) {
		val nextDrawable = drawables[drawableIndex]
		val previousDrawable = previous.drawables[drawableIndex]

		if (nextDrawable === previousDrawable) {
			continue
		}

		val nextMesh = nextDrawable.mesh ?: return false
		val previousMesh = previousDrawable.mesh ?: return false

		if (nextMesh.uvs !== previousMesh.uvs ||
			nextMesh.indices !== previousMesh.indices ||
			nextMesh.positions.size != previousMesh.positions.size ||
			nextMesh.localPositions.size != previousMesh.localPositions.size
		) {
			return false
		}

		if (nextDrawable.copy(mesh = previousMesh) != previousDrawable) {
			return false
		}
	}

	return true
}