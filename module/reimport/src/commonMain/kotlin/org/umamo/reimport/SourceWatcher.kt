package org.umamo.reimport

/**
 * Watches a source-art file for on-disk changes - the trigger for the refresh workflow (draw in
 * CSP/Photoshop, save, and the rig reconciles).
 *
 * Modelled with a `fun interface` (SAM) listener and an [AutoCloseable] subscription so the contract
 * carries no coroutine/Flow dependency at this layer; an implementation can bridge to whatever the
 * platform offers (desktop `WatchService`, Android `FileObserver`). `fun interface` lets callers
 * pass a lambda where a [ChangeListener] is expected.
 *
 * ソースアートのファイル変更を監視する契約。変更を検知してリインポートを起動する。
 */
interface SourceWatcher {
	fun interface ChangeListener {
		fun onChanged(path: String)
	}

	/** Begins watching [path]; close the returned handle to stop. */
	fun watch(path: String, listener: ChangeListener): AutoCloseable
}
