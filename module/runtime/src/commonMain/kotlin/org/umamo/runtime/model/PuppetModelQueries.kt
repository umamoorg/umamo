package org.umamo.runtime.model

/*
 * Pure per-drawable index queries over a PuppetModel, shared by every platform viewport service
 * (the desktop offscreen renderer today, the Android GLES service when it lands): the pickable
 * geometry sets picking iterates, and the display lookups the overlap picker labels rows with.
 * All derive from the model alone, so services recompute them on each model swap.
 *
 * PuppetModel に対する描画対象ごとの純粋な索引クエリ。各プラットフォームのビューポートサービス
 * が共有する（ピッキング対象の形状と、重なり選択の表示用ルックアップ）。
 */

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
 * Drawable id to the source-format id its atlas region is keyed by: itself, or its texture source for
 * a session-created duplicate (a copy of a copy resolves to the original).
 *
 * @return Map<DrawableId, String> Drawable id to its atlas lookup key.
 */
fun PuppetModel.atlasKeyByDrawable(): Map<DrawableId, String> =
	drawables.associate { drawable -> drawable.id to (drawable.textureSourceId ?: drawable.id).raw }
