package org.umamo.interop.cmo3

import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.format.cmo3.model.gen.CArtMeshSource
import org.umamo.format.cmo3.model.gen.CDeformerSourceSet
import org.umamo.format.cmo3.model.gen.CDrawableSourceSet
import org.umamo.format.cmo3.model.gen.CParameterSource
import org.umamo.format.cmo3.model.gen.CParameterSourceSet
import org.umamo.format.cmo3.model.gen.CPartSource
import org.umamo.format.cmo3.model.gen.CPartSourceSet
import org.umamo.format.cmo3.model.gen.CWarpDeformerSource
import org.umamo.format.cmo3.model.identity.Guid
import org.umamo.format.cmo3.model.identity.Id
import org.umamo.interop.cmo3.import.Cmo3Import
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.ParameterLink
import org.umamo.runtime.model.PartId
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the orderings and identity rules the CMO3 import takes from the order it reads the graph in.
 *
 * Each of these is a rule a wrong reading breaks silently: the import still returns a puppet, with a
 * different and entirely plausible order, an extra part, or a missing drawable.  The corpus tests assert
 * that every drawable is placed and named, not where it lands, so this is the gate that fails when the
 * import's passes are rearranged.  The graphs are hand-built, so it runs without the corpus.
 *
 * @see <a href="https://docs.umamo.org/format/CMO3.md">CMO3.md §3 Model object graph</a>
 */
class Cmo3ImportOrderTest {
	private fun guidOf(uuid: String): Guid = Guid("").apply { this.uuid = uuid }

	private fun idOf(idStr: String): Id = Id("").apply { idstr = idStr }

	/**
	 * An art mesh named [name], or with no id at all.
	 *
	 * @param String? name The id string, or null for a source that carries no id.
	 * @param String uuid The guid's uuid.
	 * @return CArtMeshSource The source.
	 */
	private fun mesh(name: String?, uuid: String = "uuid-$name"): CArtMeshSource =
		CArtMeshSource().apply {
			guid = guidOf(uuid)
			id = name?.let(::idOf)
		}

	/**
	 * A part named [name] whose panel lists [children] in order.
	 *
	 * @param String? name The id string, or null for a source that carries no id.
	 * @param Any children The child sources, parts or art meshes, top of the panel first.
	 * @return CPartSource The source.
	 */
	private fun part(name: String?, vararg children: Any): CPartSource =
		CPartSource().apply {
			guid = guidOf("uuid-part-$name")
			id = name?.let(::idOf)
			_childGuids =
				children.map { child ->
					when (child) {
						is CPartSource -> guidOf((child.guid as Guid).uuid)
						is CArtMeshSource -> guidOf((child.guid as Guid).uuid)
						else -> error("unexpected child $child")
					}
				}
		}

	/**
	 * A parameter named [name], optionally the head of a combined pair.
	 *
	 * @param String? name The id string, or null for a source that carries no id.
	 * @param Boolean combined Whether the source is flagged as a combined pair's first member.
	 * @return CParameterSource The source.
	 */
	private fun parameter(name: String?, combined: Boolean = false): CParameterSource =
		CParameterSource().apply {
			guid = guidOf("uuid-parameter-$name")
			id = name?.let(::idOf)
			this.combined = combined
		}

	/**
	 * A model whose panel reads, top to bottom: PartA holding MeshFirst then MeshSecond, then MeshLast.
	 * Two more meshes sit in no part.  The drawable set lists all five in an order unrelated to the panel,
	 * as the editor's creation-ordered set does.
	 *
	 * @param Function rootPartField Maps the root part source to what `CModelSource.rootPart` holds.
	 * @return CModelSource The model.
	 */
	private fun stackedModel(rootPartField: (CPartSource) -> Any? = { root -> root }): CModelSource {
		val meshFirst = mesh("MeshFirst")
		val meshSecond = mesh("MeshSecond")
		val meshLast = mesh("MeshLast")
		val partA = part("PartA", meshFirst, meshSecond)
		val root = part("__RootPart__", partA, meshLast)

		return CModelSource().apply {
			partSourceSet = CPartSourceSet().apply { _sources = arrayListOf(root, partA) }
			drawableSourceSet =
				CDrawableSourceSet().apply {
					_sources = arrayListOf(mesh("OrphanB"), meshSecond, mesh("OrphanA"), meshLast, meshFirst)
				}
			rootPart = rootPartField(root)
		}
	}

	@Test
	fun theFlatDrawableListRunsBackToFrontFromThePartTree() {
		val puppet = Cmo3Import.fromModelSource(stackedModel())

		// The panel's last entry is the backmost and comes first.  A drawable in no part ranks behind
		// everything, and two of those keep the order the drawable set stores them in.
		assertEquals(
			listOf("OrphanB", "OrphanA", "MeshLast", "MeshSecond", "MeshFirst"),
			puppet.drawables.map { drawable -> drawable.id.raw },
		)
	}

	@Test
	fun aDrawableNoPartPlacesIsAppendedAtTheRoot() {
		val puppet = Cmo3Import.fromModelSource(stackedModel())

		assertEquals(
			listOf(
				OrgChild.Part(PartId("PartA")),
				OrgChild.Drawable(DrawableId("MeshLast")),
				OrgChild.Drawable(DrawableId("OrphanB")),
				OrgChild.Drawable(DrawableId("OrphanA")),
			),
			puppet.rootChildren,
		)
		assertEquals(
			listOf(OrgChild.Drawable(DrawableId("MeshFirst")), OrgChild.Drawable(DrawableId("MeshSecond"))),
			puppet.parts.single().children,
			"a part's children keep the panel's own order",
		)
	}

	@Test
	fun aCombinedHeadTakesTheNextParameterAndTheWalkSkipsIt() {
		val model =
			CModelSource().apply {
				parameterSourceSet =
					CParameterSourceSet().apply {
						_sources =
							arrayListOf(
								parameter("ParamA", combined = true),
								parameter("ParamB"),
								parameter("ParamC", combined = true),
								// Flagged, but already taken as ParamC's partner: it must not open a pair with ParamE.
								parameter("ParamD", combined = true),
								parameter("ParamE"),
								// A head with nothing after it pairs with nothing.
								parameter("ParamF", combined = true),
							)
					}
			}

		assertEquals(
			listOf(
				ParameterLink(ParameterId("ParamA"), ParameterId("ParamB")),
				ParameterLink(ParameterId("ParamC"), ParameterId("ParamD")),
			),
			Cmo3Import.fromModelSource(model).parameterLinks,
		)
	}

	@Test
	fun anIdlessSourceIsKeptExceptForADeformer() {
		val model =
			CModelSource().apply {
				parameterSourceSet = CParameterSourceSet().apply { _sources = arrayListOf(parameter("ParamA"), parameter(null)) }
				partSourceSet = CPartSourceSet().apply { _sources = arrayListOf(part("PartA"), part(null)) }
				drawableSourceSet = CDrawableSourceSet().apply { _sources = arrayListOf(mesh("MeshA"), mesh(null)) }
				deformerSourceSet =
					CDeformerSourceSet().apply {
						_sources =
							arrayListOf(
								CWarpDeformerSource().apply {
									guid = guidOf("uuid-warp-a")
									id = idOf("WarpA")
								},
								CWarpDeformerSource().apply { guid = guidOf("uuid-warp-idless") },
							)
					}
			}

		val puppet = Cmo3Import.fromModelSource(model)

		assertEquals(listOf("ParamA", ""), puppet.parameters.map { parameter -> parameter.id.raw }, "parameters")
		assertEquals(listOf("PartA", ""), puppet.parts.map { part -> part.id.raw }, "parts")
		assertEquals(listOf("MeshA", ""), puppet.drawables.map { drawable -> drawable.id.raw }, "drawables")
		assertEquals(listOf("WarpA"), puppet.deformers.map { deformer -> deformer.id.raw }, "deformers")
	}

	@Test
	fun aRepeatedGuidResolvesToTheLastSourceThatCarriesIt() {
		val meshOld = mesh("MeshOld", uuid = "uuid-shared")
		val meshNew = mesh("MeshNew", uuid = "uuid-shared")
		val partA = part("PartA", meshOld)
		val root = part("__RootPart__", partA)
		val model =
			CModelSource().apply {
				partSourceSet = CPartSourceSet().apply { _sources = arrayListOf(root, partA) }
				drawableSourceSet = CDrawableSourceSet().apply { _sources = arrayListOf(meshOld, meshNew) }
				rootPart = root
			}

		val puppet = Cmo3Import.fromModelSource(model)

		assertEquals(listOf(OrgChild.Drawable(DrawableId("MeshNew"))), puppet.parts.single().children)
		// The earlier source is still a drawable; nothing places it, so it lands at the root.
		assertEquals(
			listOf(OrgChild.Part(PartId("PartA")), OrgChild.Drawable(DrawableId("MeshOld"))),
			puppet.rootChildren,
		)
	}

	@Test
	fun aRootPartStoredAsABareGuidIsStillLeftOutOfTheParts() {
		val puppet = Cmo3Import.fromModelSource(stackedModel { root -> guidOf((root.guid as Guid).uuid) })

		assertEquals(listOf("PartA"), puppet.parts.map { part -> part.id.raw })
		assertEquals(PartId("__RootPart__"), puppet.rootPartId)
		// The panel walk still starts at the root, so the stacking order is the same as with the object.
		assertEquals(
			listOf("OrphanB", "OrphanA", "MeshLast", "MeshSecond", "MeshFirst"),
			puppet.drawables.map { drawable -> drawable.id.raw },
		)
		// A bare guid carries no child list, so the root's own entries arrive through the unplaced net.
		assertEquals(
			listOf(
				OrgChild.Drawable(DrawableId("OrphanB")),
				OrgChild.Drawable(DrawableId("OrphanA")),
				OrgChild.Drawable(DrawableId("MeshLast")),
			),
			puppet.rootChildren,
		)
	}
}