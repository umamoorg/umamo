package org.umamo.ui.app

import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import org.umamo.interop.art.BirthMeshMode
import org.umamo.interop.art.mesh.ArtMeshPreset
import org.umamo.settings.Settings
import org.umamo.storage.OkioAppStorage
import org.umamo.ui.settings.IMPORT_BIRTH_MESH_KEY
import org.umamo.ui.settings.MESH_GENERATION_PRESET_KEY
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The mesh an import gives each new drawable, as the preferences say: generated from the art at the mesh
 * generation preset unless the rigger chose the quad, and generated at the standard preset when the
 * settings hold nothing at all.
 */
class ConfiguredBirthMeshTest {
	/**
	 * Settings with no defaults and no user file: every key unset.
	 *
	 * @return Settings The settings.
	 */
	private fun emptySettings(): Settings {
		val fileSystem = FakeFileSystem()
		val configDirectory = "/config".toPath()
		fileSystem.createDirectories(configDirectory)

		return Settings.load(OkioAppStorage(fileSystem, configDirectory, "/data".toPath()), "{}")
	}

	@Test
	fun anUnsetInitialMeshIsGeneratedFromTheArtAtTheStandardPreset() {
		assertEquals(BirthMeshMode.Automatic(ArtMeshPreset.Standard.settings), configuredBirthMesh(emptySettings()))
	}

	@Test
	fun theSettingsPickTheModeAndThePreset() {
		val settings = emptySettings()

		settings.setString(IMPORT_BIRTH_MESH_KEY, "quad")
		assertEquals(BirthMeshMode.Quad, configuredBirthMesh(settings))

		settings.setString(IMPORT_BIRTH_MESH_KEY, "automatic")
		settings.setString(MESH_GENERATION_PRESET_KEY, "fine")
		assertEquals(BirthMeshMode.Automatic(ArtMeshPreset.Fine.settings), configuredBirthMesh(settings))

		settings.setString(IMPORT_BIRTH_MESH_KEY, "something-newer")
		assertEquals(BirthMeshMode.Automatic(ArtMeshPreset.Fine.settings), configuredBirthMesh(settings), "a value this version does not know reads as the default")
	}
}