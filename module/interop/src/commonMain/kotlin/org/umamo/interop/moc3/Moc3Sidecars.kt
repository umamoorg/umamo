package org.umamo.interop.moc3

import org.umamo.format.moc3.Moc3
import org.umamo.format.moc3.json.Cdi3Json
import org.umamo.format.moc3.json.DisplayDrawable
import org.umamo.format.moc3.json.DisplayParameter
import org.umamo.format.moc3.json.DisplayParameterGroup
import org.umamo.format.moc3.json.DisplayPart
import org.umamo.format.moc3.json.FileReferences
import org.umamo.format.moc3.json.Model3Json
import org.umamo.format.moc3.moc.MocVersion
import org.umamo.interop.ExportReport
import org.umamo.interop.mocVersion
import org.umamo.runtime.model.ParameterNode
import org.umamo.runtime.model.PuppetModel

/**
 * Builds the whole `.moc3` FILE FAMILY - the moc, its `model3.json` manifest, a `cdi3.json` of
 * display names, the atlas pages, and whatever sidecars the document was imported with.
 *
 * A baked model is a family, not a file: the runtime loads the manifest, and the manifest is what
 * names the atlas pages a moc's texture indices point into.  Writing the moc alone produces something
 * no runtime can open, so the export's unit is this bundle.
 *
 * Everything here is pure - the caller supplies the atlas bytes and the retained sidecar texts, and
 * receives named byte arrays to write wherever it likes.  That keeps the family layout testable
 * without a filesystem, and keeps the IO (and its per-platform limits) in the app layer.
 *
 * @see <a href="https://docs.umamo.org/format/MOC3.md">MOC3.md § Export</a>
 */
object Moc3Sidecars {
	/** The cdi3 schema version the editor writes. */
	private const val CDI3_VERSION: Int = 3

	/** The model3 schema version the editor writes. */
	private const val MODEL3_VERSION: Int = 3

	/**
	 * One file of the exported family.
	 *
	 * @property String    name  The file name, relative to the moc's own directory.
	 * @property ByteArray bytes The contents.
	 */
	class BundleFile(val name: String, val bytes: ByteArray)

	/**
	 * A retained sidecar carried through untouched.
	 *
	 * Pass-through, not synthesis: physics, poses, expressions, and user data are authored elsewhere
	 * and reference parameters and parts by id, which the export preserves - so re-emitting the
	 * original text is both lossless and the only honest option, since Umamo does not model them.
	 *
	 * @property SidecarKind kind     Which sidecar this is, for the manifest wiring below.
	 * @property String      fileName The name to write it under.
	 * @property String      text     The original file's text.
	 */
	class PassThroughSidecar(val kind: SidecarKind, val fileName: String, val text: String)

	/** The sidecar families a MOC3 export carries through from the imported document. */
	enum class SidecarKind {
		Physics,
		Pose,
		UserData,
		Expression,
		Motion,
	}

	/**
	 * One atlas page's bytes and the name to write them under.
	 *
	 * @property String    fileName The page's file name, manifest-relative.
	 * @property ByteArray bytes    The PNG bytes, verbatim from the source when there is one.
	 */
	class AtlasPage(val fileName: String, val bytes: ByteArray)

	/**
	 * The complete export: every file to write, plus the lowering's advisory report.
	 *
	 * @property List         files  The family, moc first.
	 * @property ExportReport report The notices from the moc lowering.
	 */
	class Bundle(val files: List<BundleFile>, val report: ExportReport)

	/**
	 * Builds the family for [puppet].
	 *
	 * @param PuppetModel puppet   The rig to export.
	 * @param String      basename The family's base name (no extension).
	 * @param MocVersion  version  The moc version to bake; the document's runtime target by default.
	 * @param List        pages    The atlas pages, in the drawables' texture-page index order.
	 * @param List        sidecars The retained sidecars to carry through.
	 * @param Model3Json? source   The imported manifest, whose non-file sections carry through.
	 * @param CanvasToParentSpace? canvasToParentSpace The unkeyed-drawable space inverse, or null.
	 * @return Bundle The files to write and the report.
	 */
	fun bundle(
		puppet: PuppetModel,
		basename: String,
		version: MocVersion = puppet.runtimeTarget.mocVersion(),
		pages: List<AtlasPage>,
		sidecars: List<PassThroughSidecar> = emptyList(),
		source: Model3Json? = null,
		canvasToParentSpace: CanvasToParentSpace? = null,
	): Bundle {
		val (mocBytes, report) = Moc3Export.write(puppet, version, canvasToParentSpace)
		val mocFileName = "$basename.moc3"
		val displayInfoName = "$basename.cdi3.json"
		val files = ArrayList<BundleFile>(pages.size + sidecars.size + 3)
		files.add(BundleFile(mocFileName, mocBytes))
		files.add(BundleFile(displayInfoName, Moc3.writeCdi3(displayInfo(puppet)).encodeToByteArray()))
		for (page in pages) {
			files.add(BundleFile(page.fileName, page.bytes))
		}
		for (sidecar in sidecars) {
			files.add(BundleFile(sidecar.fileName, sidecar.text.encodeToByteArray()))
		}
		val manifest =
			Model3Json(
				version = MODEL3_VERSION,
				fileReferences =
					FileReferences(
						moc = mocFileName,
						textures = pages.map { page -> page.fileName },
						pose = sidecars.firstOrNull { it.kind == SidecarKind.Pose }?.fileName,
						physics = sidecars.firstOrNull { it.kind == SidecarKind.Physics }?.fileName,
						userData = sidecars.firstOrNull { it.kind == SidecarKind.UserData }?.fileName,
						displayInfo = displayInfoName,
						// Expression and motion entries carry their own fade times and group names, so the
						// source's own entries are re-emitted rather than rebuilt from the file list - the
						// files themselves ride along in `sidecars` under those same relative names.
						expressions = source?.fileReferences?.expressions,
						motions = source?.fileReferences?.motions,
					),
				// Auto-wiring groups (EyeBlink / LipSync) and hit areas are USER DATA that lives only in
				// the manifest.  Nothing in PuppetModel holds them, so dropping them here would quietly
				// delete a rigger's eye-blink wiring on the first MOC3 round trip.
				groups = source?.groups,
				hitAreas = source?.hitAreas,
			)
		files.add(BundleFile("$basename.model3.json", Moc3.writeModel3(manifest).encodeToByteArray()))
		return Bundle(files, report)
	}

	/**
	 * The `cdi3.json` for [puppet]: parameter, group, part, and art-mesh display names.
	 *
	 * Written unconditionally, even when every name equals its id.  A cdi3 is what carries the ONE
	 * thing the moc cannot - what the rigger called each object - and deciding it is "not worth
	 * writing" because this model happens to use default names would make the family's shape depend
	 * on the data in it.
	 *
	 * @param PuppetModel puppet The rig.
	 * @return Cdi3Json The display info.
	 */
	fun displayInfo(puppet: PuppetModel): Cdi3Json {
		val groupIdByParameter = HashMap<String, String>()
		val groups = ArrayList<DisplayParameterGroup>()

		/**
		 * Walks the parameter tree, recording each group and the group every parameter belongs to.
		 *
		 * @param List   nodes         The nodes at this level.
		 * @param String parentGroupId The enclosing group's id, empty at the root.
		 */
		fun walk(nodes: List<ParameterNode>, parentGroupId: String) {
			for (node in nodes) {
				when (node) {
					is ParameterNode.Param -> groupIdByParameter[node.id.raw] = parentGroupId
					is ParameterNode.Group -> {
						groups.add(DisplayParameterGroup(id = node.id.raw, groupId = parentGroupId, name = node.name))
						walk(node.children, node.id.raw)
					}
				}
			}
		}
		walk(puppet.parameterTree, "")

		return Cdi3Json(
			version = CDI3_VERSION,
			parameters =
				puppet.parameters.map { parameter ->
					DisplayParameter(
						id = parameter.id.raw,
						groupId = groupIdByParameter[parameter.id.raw] ?: "",
						name = parameter.name,
					)
				},
			parameterGroups = groups,
			parts = puppet.parts.map { part -> DisplayPart(id = part.id.raw, name = part.name) },
			// The pair order is the same one the import reads back: horizontal first, then vertical.
			combinedParameters =
				puppet.parameterLinks
					.map { link -> listOf(link.horizontal.raw, link.vertical.raw) }
					.takeIf { links -> links.isNotEmpty() },
			drawables =
				puppet.drawables
					.map { drawable -> DisplayDrawable(id = drawable.id.raw, name = drawable.name) }
					.takeIf { meshes -> meshes.isNotEmpty() },
		)
	}
}
