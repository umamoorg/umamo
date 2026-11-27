package org.umamo.ui.settings

/**
 * The mesh an artwork import gives each new drawable, as the import.birthMesh setting stores it: the
 * quad over the layer's art, or a mesh generated from the art at the mesh generation preset.
 *
 * @property String key The value the setting stores.
 */
internal enum class BirthMeshSetting(val key: String) {
	/** A four-vertex quad over the layer's opaque bounds. */
	Quad("quad"),

	/** The art mesher's mesh at the meshGeneration.preset preset. */
	Automatic("automatic"),
	;

	companion object {
		/** What an import gives when the setting holds nothing it knows. */
		val Default: BirthMeshSetting = Automatic

		/**
		 * The choice a stored key names, or [Default] for an unknown or missing key.
		 *
		 * @param String? key The stored key.
		 * @return BirthMeshSetting The choice.
		 */
		fun fromKey(key: String?): BirthMeshSetting = entries.firstOrNull { setting -> setting.key == key } ?: Default
	}
}