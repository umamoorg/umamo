#!/usr/bin/env bash
# Starts a packaged Umamo, waits for its session log to say it started, and stops it.
#
# The release workflow runs this against every app image it archives, after unpacking the archive, so it
# proves what nothing else in CI does: that the thing a rigger downloads starts at all - the jlinked runtime,
# the launcher's JVM options, the natives, and the archive's own execute bits and symlinks.  The session log
# (docs/plan/distribution.md Phase 0) already records everything the check needs, so the app needs no test
# mode of its own.
#
# It fails when the log never says the installed launcher started it, when the app dies first, when the log
# records an uncaught exception, when the JVM printed a warning on stderr (the launcher options exist to keep
# a JDK 24 or later runtime quiet), or when LWJGL reported an error there.  The GL line is required only
# where the caller says a GL 3.3 core context is available.
#
# Usage: launch-smoke-test.sh <launcher> <logs directory> <require GL: true|false>
#
# Written for the bash 3.2 macOS ships as well as Linux's: no associative arrays, no empty-array expansion.

set -euo pipefail

if [ "$#" -ne 3 ]; then
	echo "usage: $0 <launcher> <logs directory> <require GL: true|false>" >&2
	exit 2
fi
launcher="$1"
logs_directory="$2"
require_gl="$3"

work_directory="$(mktemp -d)"
marker="${work_directory}/marker"
stderr_file="${work_directory}/stderr.txt"
stdout_file="${work_directory}/stdout.txt"
touch "${marker}"
# Session log names carry the start second; a pause keeps a log from this run strictly newer than the marker.
sleep 1

"${launcher}" > "${stdout_file}" 2> "${stderr_file}" &
app_pid=$!

# The newest session log written since the marker, or nothing yet.  Names sort in start order.
newest_log() {
	find "${logs_directory}" -maxdepth 1 -type f -name 'session-*.log' -newer "${marker}" 2> /dev/null | sort | tail -n 1
}

# Waits up to $2 seconds for the newest session log to contain the fixed string $1.
# Returns 0 when found, 1 on timeout, and 2 when the app exited first.
wait_for() {
	needle="$1"
	seconds="$2"
	elapsed=0
	while [ "${elapsed}" -lt "${seconds}" ]; do
		log="$(newest_log)"
		if [ -n "${log}" ] && grep -qF -- "${needle}" "${log}"; then
			return 0
		fi
		if ! kill -0 "${app_pid}" 2> /dev/null; then
			return 2
		fi
		sleep 1
		elapsed=$((elapsed + 1))
	done
	return 1
}

# Stops the app: TERM, then KILL if it has not gone within ten seconds.
stop_app() {
	if kill -0 "${app_pid}" 2> /dev/null; then
		kill -TERM "${app_pid}" 2> /dev/null || true
		waited=0
		while kill -0 "${app_pid}" 2> /dev/null && [ "${waited}" -lt 10 ]; do
			sleep 1
			waited=$((waited + 1))
		done
		kill -KILL "${app_pid}" 2> /dev/null || true
	fi
	wait "${app_pid}" 2> /dev/null || true
}

# Prints what the run produced, for the job log.
report() {
	log="$(newest_log)"
	echo "---- session log: ${log:-none}"
	if [ -n "${log}" ]; then
		cat "${log}"
	fi
	echo "---- stderr"
	cat "${stderr_file}"
}

failures=0

started_status=0
wait_for "started from the installed launcher" 60 || started_status=$?
if [ "${started_status}" -ne 0 ]; then
	if [ "${started_status}" -eq 2 ]; then
		echo "::error::the app exited before its session log said the installed launcher started it"
	else
		echo "::error::no session log under ${logs_directory} said the installed launcher started it within 60 s"
	fi
	stop_app
	report
	exit 1
fi

# The failure line also starts with "[GL]", so the success line is matched by its wording.
gl_status=0
wait_for "[GL] offscreen via " 60 || gl_status=$?
if [ "${gl_status}" -ne 0 ]; then
	if [ "${require_gl}" = "true" ]; then
		echo "::error::the session log never reported an offscreen GL context"
		failures=$((failures + 1))
	else
		echo "::notice::no offscreen GL context on this runner (informational here)"
	fi
fi

# A few seconds past the first frame, so a failure right after it still lands in the log.
sleep 5
stop_app
report

log="$(newest_log)"
if grep -qF "uncaught exception" "${log}"; then
	echo "::error::the session log records an uncaught exception"
	failures=$((failures + 1))
fi
if grep -n '^WARNING:' "${stderr_file}"; then
	echo "::error::the JVM printed warnings at startup; the launcher options in app/desktop/build.gradle.kts should keep it quiet"
	failures=$((failures + 1))
fi
if grep -nF '[LWJGL] [ERROR]' "${stderr_file}"; then
	echo "::error::LWJGL reported an error at startup"
	failures=$((failures + 1))
fi

if [ "${failures}" -ne 0 ]; then
	exit 1
fi
echo "smoke test passed: ${launcher}"