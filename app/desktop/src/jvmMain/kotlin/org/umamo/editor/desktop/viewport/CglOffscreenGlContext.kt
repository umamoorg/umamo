package org.umamo.editor.desktop.viewport

import org.umamo.storage.UmamoLog

/**
 * The MacOS offscreen GL context, via Apple CGL (Core OpenGL) - CURRENTLY A NO-OP STUB.
 */
internal class CglOffscreenGlContext : OffscreenGlContext {
	override val backendName: String = "CGL GL4_Core (stub)"

	/**
	 * Not yet implemented: logs and returns false so the macOS viewport degrades to blank (no crash) until
	 * the CGL context is filled in. The important effect today is that macOS never touches GLFW, so the
	 * GLFW main-thread crash cannot occur.
	 *
	 * @return Boolean Always false (no context is created yet).
	 */
	override fun createAndMakeCurrent(): Boolean {
		UmamoLog.warn(
			"[GL] macOS CGL offscreen context is not implemented yet; viewport will stay blank. " +
				"See CglOffscreenGlContext for the intended CGL GL4_Core implementation.",
		)
		return false
	}

	override fun describeContext(): String = "CGL GL4_Core (stub - not implemented)"

	/** No-op: the stub creates nothing to release. */
	override fun destroy() {
		// Nothing to release until createAndMakeCurrent is implemented.
	}
}
