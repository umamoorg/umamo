package org.umamo.editor.desktop.viewport

/**
 * An off-screen GL 3.3+ core context owned by a single render thread. The offscreen viewport renderer
 * ([OffscreenRenderEngine]) renders only to framebuffer objects, so a context needs no visible window and no
 * presentable drawable - just a current GL context on the thread that issues the draw and read-back calls.
 * 
 * Threading contract: [createAndMakeCurrent], [describeContext], and [destroy] all run on the one render
 * thread, in that order across the context's life; the context is never migrated to another thread.
 */
internal interface OffscreenGlContext {
	/**
	 * A short backend label for the startup log line (for example "GLFW" or "CGL GL4_Core"), so the log
	 * records which path the running OS actually took.
	 */
	val backendName: String

	/**
	 * Creates the context and makes it current on the calling (render) thread. On failure returns false so
	 * the caller degrades to a blank viewport rather than crashing - the same graceful fallback the engine
	 * already applies when GL is unavailable.
	 *
	 * @return Boolean True on success (a context is now current), false to degrade to a blank viewport.
	 */
	fun createAndMakeCurrent(): Boolean

	/**
	 * The renderer / version / vendor / GLSL of the current context, for the startup diagnostic. Valid only
	 * after a successful [createAndMakeCurrent].
	 *
	 * @return String The context description.
	 */
	fun describeContext(): String

	/**
	 * Releases the context on the render thread. Must NOT call glFinish - the engine issues one glFinish
	 * barrier before disposing its GL resources, and this runs last, after that barrier.
	 */
	fun destroy()
}

/**
 * Selects the offscreen GL context implementation for the running OS: CGL on macOS, GLFW everywhere else.
 *
 * @return OffscreenGlContext The context backend for this OS.
 */
internal fun createOffscreenGlContext(): OffscreenGlContext {
	val osName = System.getProperty("os.name").orEmpty().lowercase()
	return if (osName.contains("mac") || osName.contains("darwin")) {
		CglOffscreenGlContext()
	} else {
		GlfwOffscreenGlContext()
	}
}
