package org.umamo.editor.desktop.viewport

import org.lwjgl.glfw.GLFW
import org.lwjgl.opengl.GL
import org.lwjgl.system.MemoryUtil
import org.umamo.render.gl.GlRenderDevice

/**
 * The Windows / Linux offscreen GL context: a hidden GLFW window carrying a GL 3.3 core context, made
 * current on the render thread.
 */
internal class GlfwOffscreenGlContext : OffscreenGlContext {
	override val backendName: String = "GLFW"

	private var window: Long = MemoryUtil.NULL

	/**
	 * Creates the GLFW hidden-window GL 3.3 core context on this thread and makes it current. Returns false
	 * (degrading to a blank viewport) if GLFW init or window creation fails.
	 *
	 * @return Boolean True on success.
	 */
	override fun createAndMakeCurrent(): Boolean {
		if (!GLFW.glfwInit()) {
			return false
		}
		GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE)
		GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3)
		GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 3)
		GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE)
		GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, GLFW.GLFW_TRUE)
		window = GLFW.glfwCreateWindow(1, 1, "umamo-offscreen", MemoryUtil.NULL, MemoryUtil.NULL)
		if (window == MemoryUtil.NULL) {
			return false
		}
		GLFW.glfwMakeContextCurrent(window)
		GL.createCapabilities()
		return true
	}

	override fun describeContext(): String = GlRenderDevice().describeBackend()

	/**
	 * Releases the GLFW window and unbinds the context. Runs on the render thread, where the context is
	 * current, after the engine's glFinish barrier.
	 */
	override fun destroy() {
		if (window != MemoryUtil.NULL) {
			GLFW.glfwMakeContextCurrent(MemoryUtil.NULL)
			GLFW.glfwDestroyWindow(window)
			window = MemoryUtil.NULL
		}
	}
}
