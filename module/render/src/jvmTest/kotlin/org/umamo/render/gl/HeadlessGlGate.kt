package org.umamo.render.gl

import org.junit.Assume

// Set by CI (-Dumamo.requireGl=true) to make a missing GL context a hard failure instead of a skip.
private const val REQUIRE_GL_PROPERTY = "umamo.requireGl"

/**
 * Gates a GL test on having actually acquired a context, skipping it when there is none.
 *
 * Two behaviors, and the difference is the whole point.  On a developer machine with no display the
 * test SKIPS - via a JUnit assumption, so the run reports it as skipped rather than passing green
 * having asserted nothing.  Under `-Dumamo.requireGl=true` (CI passes it) a missing context is
 * instead a hard FAILURE.
 *
 * Why both: these tests are the only thing pinning the GL renderer's behavior, and they assert on
 * read-back pixels, so a context-less run covers exactly nothing.  A plain skip is right locally
 * (not every machine has a display) but wrong in CI, where a silent skip is indistinguishable from
 * a pass and would let the entire GL suite quietly stop covering anything - which is precisely the
 * state this gate was written to end.  CI asserts the context exists; developers get the skip.
 *
 * GL テストのコンテキスト取得を判定する。ローカルでは skip、CI（-Dumamo.requireGl）では失敗させる。
 *
 * @param String tag    The test's log tag, e.g. "[world-axis-lines]".
 * @param Long   window The GLFW window handle from the test's own headless-context helper; 0 = none.
 */
internal fun assumeGlContext(tag: String, window: Long) {
	if (window != 0L) {
		return
	}
	val message = "$tag no GL context (display-less env)"
	if (System.getProperty(REQUIRE_GL_PROPERTY).toBoolean()) {
		// CI opted in: a missing context means the GL suite would cover nothing, so fail loudly.
		throw AssertionError("$message, and -$REQUIRE_GL_PROPERTY=true requires one")
	}
	Assume.assumeTrue(message, false)
}
