package org.umamo.render

import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.PuppetModel

/*
 * Keeping a reshaped warp's children where they rest: the change of space every reshape, resize, and Quad toggle of a
 * warp makes for what lives under it, kept in place by childrenRehomed (ChildRehome.kt).
 */

/**
 * [after] with warp [warpId]'s direct children re-expressed so each rests where it rested in [before].
 *
 * @param PuppetModel before The model before the reshape.
 * @param PuppetModel after The model with the warp reshaped and its children as they were.
 * @param DeformerId warpId The reshaped warp.
 * @return ChildrenRehomed The model with its children kept in place, and those that could not be.
 */
fun warpChildrenKeptInPlace(before: PuppetModel, after: PuppetModel, warpId: DeformerId): ChildrenRehomed =
	childrenRehomed(
		before,
		after,
		RehomedChildren(
			drawables = after.drawables.filter { drawable -> drawable.parentDeformerId == warpId && drawable.mesh != null }.map { drawable -> drawable.id },
			deformers = after.deformers.filter { deformer -> deformer.parent == warpId }.map { deformer -> deformer.id },
		),
	)