package org.umamo.render.gl

import org.junit.Assume
import org.lwjgl.glfw.GLFW
import org.lwjgl.glfw.GLFWErrorCallback
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL13
import org.lwjgl.opengl.GL14
import org.lwjgl.opengl.GL15
import org.lwjgl.opengl.GL20
import org.lwjgl.opengl.GL21
import org.lwjgl.opengl.GL30
import org.lwjgl.opengl.GL31
import org.lwjgl.system.MemoryUtil

// Set by CI (-Dumamo.requireGl=true) to make a missing GL context a hard failure instead of a skip.
private const val REQUIRE_GL_PROPERTY = "umamo.requireGl"

// Tries per acquisition.  Context creation under CI's Xvfb + Mesa software GL fails intermittently, and
// one shared context means a single unlucky attempt would strand every GL test in the module.
private const val ACQUIRE_ATTEMPT_LIMIT = 3

// The hidden window is 1x1, so its default framebuffer - and a freshly created context's viewport - is
// 1x1.  Restoring that is what makes a reused context indistinguishable from a new one.
private const val HIDDEN_WINDOW_SIZE = 1

// The process-wide context: created on first use, then kept current for the JVM's lifetime.
private var sharedGlWindow: Long = MemoryUtil.NULL
private var sharedGlAttempted = false
private var acquireFailureReason: String? = null
private var lastGlfwErrorText: String? = null

/**
 * Prepares the shared headless GL context for a test, skipping the test when there is none.
 *
 * One context serves the whole module, created once and never terminated - the same lifetime the desktop
 * app gives its own offscreen context.  That is deliberate: context creation under CI's Xvfb + Mesa
 * software GL fails often enough per attempt that a context per test would put three dozen independent
 * chances to fail in a single run, and most runs would lose at least one.  Acquiring once holds the
 * exposure to one attempt, retried up to [ACQUIRE_ATTEMPT_LIMIT] times.
 *
 * Reuse costs the one thing a fresh context supplies for free - a clean GL state vector - so each caller
 * gets [resetSharedGlState] before its body runs, leaving bindings, capabilities, and the viewport exactly
 * as a new context would have them.
 *
 * Two behaviors on a missing context, and the difference is the whole point.  On a developer machine with
 * no display the test SKIPS - via a JUnit assumption, so the run reports it as skipped rather than passing
 * green having asserted nothing.  Under `-Dumamo.requireGl=true` (CI passes it) it is instead a hard
 * FAILURE.
 *
 * Why both: these tests are the only thing pinning the GL renderer's behavior, and they assert on
 * read-back pixels, so a context-less run covers exactly nothing.  A plain skip is right locally (not
 * every machine has a display) but wrong in CI, where a silent skip is indistinguishable from a pass and
 * would let the entire GL suite quietly stop covering anything.  CI asserts the context exists;
 * developers get the skip.
 *
 * @param String tag The test's log tag, e.g. "[world-axis-lines]".
 */
internal fun requireHeadlessGl(tag: String) {
	val window = acquireSharedGlWindow()
	if (window == MemoryUtil.NULL) {
		val message = "$tag no GL context (${acquireFailureReason ?: "cause not reported"})"
		if (System.getProperty(REQUIRE_GL_PROPERTY).toBoolean()) {
			// CI opted in: a missing context means the GL suite would cover nothing, so fail loudly.
			throw AssertionError("$message, and -D$REQUIRE_GL_PROPERTY=true requires one")
		}
		Assume.assumeTrue(message, false)
		return
	}
	resetSharedGlState()
}

/**
 * Returns the process-wide GL context, creating it on the first call.
 *
 * The outcome is cached either way: once an acquisition has failed, later callers reuse the recorded
 * reason instead of paying for another round of doomed attempts.
 *
 * @return Long The GLFW window handle carrying the context, or MemoryUtil.NULL if none could be had.
 */
private fun acquireSharedGlWindow(): Long {
	if (sharedGlAttempted) {
		return sharedGlWindow
	}
	sharedGlAttempted = true
	installGlfwErrorReporting()
	for (attempt in 1..ACQUIRE_ATTEMPT_LIMIT) {
		lastGlfwErrorText = null
		val window = createHeadlessGlWindow()
		if (window != MemoryUtil.NULL) {
			sharedGlWindow = window
			GLFW.glfwMakeContextCurrent(window)
			GL.createCapabilities()
			return sharedGlWindow
		}
		System.err.println("[headless-gl] attempt $attempt/$ACQUIRE_ATTEMPT_LIMIT failed: $acquireFailureReason")
	}
	return MemoryUtil.NULL
}

/**
 * Creates a hidden 1x1 GL 3.3 core window, recording which step failed and what GLFW said about it.
 *
 * The two failure modes are reported separately on purpose: glfwInit failing means the platform layer
 * never came up, while glfwCreateWindow failing means it did and the context itself was refused.  Those
 * point at different things, and a bare null handle names neither.
 *
 * @return Long The GLFW window handle, or MemoryUtil.NULL on failure.
 */
private fun createHeadlessGlWindow(): Long {
	if (!GLFW.glfwInit()) {
		acquireFailureReason = describeAcquireFailure("glfwInit() returned false")
		return MemoryUtil.NULL
	}
	GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE)
	GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3)
	GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 3)
	GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE)
	GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, GLFW.GLFW_TRUE)
	val window = GLFW.glfwCreateWindow(HIDDEN_WINDOW_SIZE, HIDDEN_WINDOW_SIZE, "umamo-headless", MemoryUtil.NULL, MemoryUtil.NULL)
	if (window == MemoryUtil.NULL) {
		acquireFailureReason = describeAcquireFailure("glfwCreateWindow() returned NULL")
		// glfwInit succeeded, so the platform layer is up and owes a matching terminate before a retry.
		GLFW.glfwTerminate()
		return MemoryUtil.NULL
	}
	return window
}

/**
 * Routes GLFW's diagnostics to stderr and keeps the latest one for the acquisition-failure message.
 *
 * Without a callback GLFW discards its error code and description entirely, which leaves a failed
 * acquisition looking like an unexplained null handle.
 */
private fun installGlfwErrorReporting() {
	GLFWErrorCallback
		.create { errorCode, descriptionPointer ->
			val text = "GLFW error 0x${errorCode.toString(16)}: ${GLFWErrorCallback.getDescription(descriptionPointer)}"
			lastGlfwErrorText = text
			System.err.println("[headless-gl] $text")
		}.set()
}

/**
 * Joins the failing GLFW call to whatever the error callback reported for it.
 *
 * @param String step The GLFW call that failed, named as it appears in the source.
 * @return String A one-line reason suitable for a skip message or an assertion failure.
 */
private fun describeAcquireFailure(step: String): String = "$step; ${lastGlfwErrorText ?: "no GLFW error reported"}"

/**
 * Restores the GL state vector to its post-creation defaults, so one test cannot leak state into the next.
 *
 * Only the state this renderer actually touches is covered - bindings, the capabilities it toggles, the
 * blend and cull configuration, the pixel-transfer alignments, and the viewport.  GL objects themselves
 * are deliberately left alive: each test builds its own [GlRenderDevice] and the handles die with the JVM.
 */
private fun resetSharedGlState() {
	GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0)
	GL30.glBindVertexArray(0)
	GL20.glUseProgram(0)
	GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0)
	GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, 0)
	GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0)
	GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER, 0)
	GL30.glBindBufferBase(GL30.GL_TRANSFORM_FEEDBACK_BUFFER, 0, 0)
	GL13.glActiveTexture(GL13.GL_TEXTURE0)
	GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0)
	GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, 0)

	GL11.glDisable(GL11.GL_SCISSOR_TEST)
	GL11.glDisable(GL11.GL_BLEND)
	GL11.glDisable(GL11.GL_CULL_FACE)
	GL11.glDisable(GL11.GL_DEPTH_TEST)
	GL11.glDisable(GL11.GL_STENCIL_TEST)
	GL11.glDisable(GL30.GL_RASTERIZER_DISCARD)

	GL14.glBlendFuncSeparate(GL11.GL_ONE, GL11.GL_ZERO, GL11.GL_ONE, GL11.GL_ZERO)
	GL11.glFrontFace(GL11.GL_CCW)
	GL11.glCullFace(GL11.GL_BACK)
	GL11.glColorMask(true, true, true, true)
	GL11.glDepthMask(true)
	GL11.glClearColor(0f, 0f, 0f, 0f)
	GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 4)
	GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4)
	GL11.glViewport(0, 0, HIDDEN_WINDOW_SIZE, HIDDEN_WINDOW_SIZE)
}
