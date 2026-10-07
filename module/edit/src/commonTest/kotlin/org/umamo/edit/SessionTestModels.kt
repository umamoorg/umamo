package org.umamo.edit

import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.Glue
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.Part
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.withDerivedRenderRoot

/**
 * The fixture models the session tests share: a two-part org tree with one drawable and one warp deformer
 * ([model]), the richer delete fixture ([deleteModel]), the one- and three-parameter models ([paramModel],
 * [linkModel]), and the one- and two-mesh models ([meshModel], [twoMeshModel]).  An object rather than a base
 * class, so a test file imports exactly the fixtures it uses and keeps its own fixtures free of clashes.
 */
internal object SessionTestModels {
	/** Part a: holds the drawable in [model]. */
	val partA = Part(PartId("a"), "A", children = emptyList())

	/** Part b: the empty sibling part in [model]. */
	val partB = Part(PartId("b"), "B", children = emptyList())

	/** The one drawable of [model], mesh-less; the mesh fixtures copy it with a mesh. */
	val drawable =
		Drawable(
			id = DrawableId("d"),
			name = "d",
			parentDeformerId = null,
			blendMode = BlendMode.Normal,
			maskedBy = emptyList(),
			mesh = null,
			geometryGrid = null,
		)

	/** The one warp deformer of [model], unbound. */
	val warp =
		Deformer.Warp(
			id = DeformerId("w"),
			name = "Warp",
			parent = null,
			partId = null,
			rows = 2,
			columns = 2,
			isQuadTransform = true,
			geometryGrid = null,
		)

	/**
	 * The base fixture: parts a and b at the top level, drawable d under a, and one warp deformer.
	 *
	 * @return PuppetModel The fixture model.
	 */
	fun model(): PuppetModel =
		PuppetModel(
			parameters = emptyList(),
			// Drawable d lives under part a; the top level holds both parts.
			parts = listOf(partA.copy(children = listOf(OrgChild.Drawable(DrawableId("d")))), partB),
			deformers = listOf(warp),
			drawables = listOf(drawable),
			rootChildren = listOf(OrgChild.Part(PartId("a")), OrgChild.Part(PartId("b"))),
			rootPartId = null,
		).withDerivedRenderRoot()

	/**
	 * A richer model for the delete tests: a part tree R > A > B plus a sibling T; three drawables (d1 under
	 * A bound to w2, d2 under B bound to w, d3 under T masked by d1); a w > w2 deformer chain; one glue
	 * pairing d1 and d2; and a flat render tree of d1, d2, d3 - so deletes can be checked against every kind
	 * of reference (part tree, render order, masks, glue, deformer binding).
	 *
	 * @return PuppetModel The fixture model.
	 */
	fun deleteModel(): PuppetModel {
		// Org tree: R > A > (B, d1); B > d2; T > d3. d1 deformed by w2, d2 by w; d3 masked by d1; glue(d1,d2).
		val partB = Part(PartId("B"), "B", children = listOf(OrgChild.Drawable(DrawableId("d2"))))
		val partA = Part(PartId("A"), "A", children = listOf(OrgChild.Part(PartId("B")), OrgChild.Drawable(DrawableId("d1"))))
		val partR = Part(PartId("R"), "R", children = listOf(OrgChild.Part(PartId("A"))))
		val partT = Part(PartId("T"), "T", children = listOf(OrgChild.Drawable(DrawableId("d3"))))
		val warpW = Deformer.Warp(DeformerId("w"), "W", parent = null, partId = null, rows = 2, columns = 2, isQuadTransform = true, geometryGrid = null)
		val warpW2 =
			Deformer.Warp(DeformerId("w2"), "W2", parent = DeformerId("w"), partId = null, rows = 2, columns = 2, isQuadTransform = true, geometryGrid = null)

		fun mesh(rawId: String, deformer: DeformerId?, masks: List<DrawableId>): Drawable =
			Drawable(
				id = DrawableId(rawId),
				name = rawId,
				parentDeformerId = deformer,
				blendMode = BlendMode.Normal,
				maskedBy = masks,
				mesh = null,
				geometryGrid = null,
			)
		return PuppetModel(
			parameters = emptyList(),
			parts = listOf(partR, partA, partB, partT),
			deformers = listOf(warpW, warpW2),
			drawables =
				listOf(
					mesh("d1", DeformerId("w2"), emptyList()),
					mesh("d2", DeformerId("w"), emptyList()),
					mesh("d3", null, listOf(DrawableId("d1"))),
				),
			rootChildren = listOf(OrgChild.Part(PartId("R")), OrgChild.Part(PartId("T"))),
			rootPartId = PartId("R"),
			glues = listOf(Glue(DrawableId("d1"), DrawableId("d2"), emptyList())),
		).withDerivedRenderRoot()
	}

	/** The parameter id the one-parameter fixtures use. */
	val angleX = ParameterId("ParamAngleX")

	/**
	 * A one-parameter model for the pose / range tests: a single ParamAngleX over the given range.
	 *
	 * @param Float min     The parameter minimum.
	 * @param Float max     The parameter maximum.
	 * @param Float default The parameter default (the initial pose value).
	 * @return PuppetModel The fixture model.
	 */
	fun paramModel(min: Float = -1f, max: Float = 1f, default: Float = 0f): PuppetModel =
		model().copy(parameters = listOf(Parameter(angleX, "Angle X", min, max, default)))

	/** The second parameter id of [linkModel]. */
	val angleY = ParameterId("ParamAngleY")

	/** The third parameter id of [linkModel]. */
	val angleZ = ParameterId("ParamAngleZ")

	/**
	 * A three-parameter model for the link tests: ParamAngleX / Y / Z, all animatable, no links.
	 *
	 * @return PuppetModel The fixture model.
	 */
	fun linkModel(): PuppetModel =
		model().copy(
			parameters =
				listOf(
					Parameter(angleX, "Angle X", -1f, 1f, 0f),
					Parameter(angleY, "Angle Y", -1f, 1f, 0f),
					Parameter(angleZ, "Angle Z", -1f, 1f, 0f),
				),
		)

	/**
	 * A model whose drawable d carries an art mesh (a single triangle), for the mesh-edit tests.
	 *
	 * @return PuppetModel The fixture model with an editable mesh on drawable d.
	 */
	fun meshModel(): PuppetModel {
		val mesh =
			DrawableMesh.withLocalEqualToCanvas(
				positions = floatArrayOf(0f, 0f, 2f, 0f, 0f, 2f),
				uvs = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f),
				indices = intArrayOf(0, 1, 2),
			)
		return model().copy(drawables = listOf(drawable.copy(mesh = mesh)))
	}

	/** A model with two meshed drawables, for the Alt+Q switch and the per-mesh selection memory. */
	fun twoMeshModel(): PuppetModel {
		val meshA = DrawableMesh.withLocalEqualToCanvas(floatArrayOf(0f, 0f, 2f, 0f, 0f, 2f), FloatArray(6), intArrayOf(0, 1, 2))
		val meshB = DrawableMesh.withLocalEqualToCanvas(floatArrayOf(10f, 0f, 12f, 0f, 10f, 2f), FloatArray(6), intArrayOf(0, 1, 2))
		return model().copy(
			parts = emptyList(),
			drawables =
				listOf(
					drawable.copy(id = DrawableId("a"), name = "a", mesh = meshA),
					drawable.copy(id = DrawableId("b"), name = "b", mesh = meshB),
				),
			rootChildren = listOf(OrgChild.Drawable(DrawableId("a")), OrgChild.Drawable(DrawableId("b"))),
		).withDerivedRenderRoot()
	}
}