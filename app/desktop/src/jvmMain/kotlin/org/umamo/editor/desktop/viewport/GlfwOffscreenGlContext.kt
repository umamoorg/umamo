package org.umamo.editor.desktop.viewport

import org.lwjgl.glfw.GLFW
import org.lwjgl.opengl.GL
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import org.umamo.render.gl.GlRenderDevice

/**
 * The Windows / Linux offscreen GL context: a hidden GLFW window carrying a GL 3.3 core context, made
 * current on the render thread.
 */
internal class GlfwOffscreenGlContext : OffscreenGlContext {
	override val backendName: String = "GLFW"

	private var window: Long = MemoryUtil.NULL

	/** Why the last attempt failed, or null. */
	private var failure: String? = null

	/**
	 * Creates the GLFW hidden-window GL 3.3 core context on this thread and makes it current. Returns false
	 * (degrading to a blank viewport) if GLFW init or window creation fails, or the natives will not load, and
	 * keeps GLFW's own description of the failure for [failureReason].
	 *
	 * @return Boolean True on success.
	 */
	override fun createAndMakeCurrent(): Boolean {
		failure = null
		try {
			if (!GLFW.glfwInit()) {
				failure = "glfwInit failed: ${lastGlfwError()}"
				return false
			}
			GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE)
			GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3)
			GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 3)
			GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE)
			GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, GLFW.GLFW_TRUE)
			window = GLFW.glfwCreateWindow(1, 1, "umamo-offscreen", MemoryUtil.NULL, MemoryUtil.NULL)
			if (window == MemoryUtil.NULL) {
				failure = "glfwCreateWindow for a GL 3.3 core context failed: ${lastGlfwError()}"
				return false
			}
			GLFW.glfwMakeContextCurrent(window)
			GL.createCapabilities()
			return true
		} catch (loadFailure: LinkageError) {
			// A native library that will not load (a missing or rejected dylib, dll, or so) costs the viewport, not
			// the editor.
			failure = "the GLFW or OpenGL natives did not load: $loadFailure"
			return false
		} catch (glFailure: IllegalStateException) {
			failure = "OpenGL capabilities could not be created: ${glFailure.message}"
			return false
		}
	}

	override fun failureReason(): String? = failure

	/**
	 * GLFW's most recent error, as its code and description.
	 *
	 * @return String The error, or a note that GLFW reported none.
	 */
	private fun lastGlfwError(): String =
		MemoryStack.stackPush().use { stack ->
			val description = stack.mallocPointer(1)
			val code = GLFW.glfwGetError(description)
			if (code == GLFW.GLFW_NO_ERROR) {
				"GLFW reported no error"
			} else {
				"GLFW error 0x${code.toString(16)}: ${MemoryUtil.memUTF8Safe(description.get(0)) ?: "no description"}"
			}
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