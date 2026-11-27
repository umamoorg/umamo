#!/usr/bin/env bash
# Opens the Umamo DMG and the Umamo.app zip the way a rigger does - a browser download, so both arrive quarantined -
# and checks what Gatekeeper decides and what the copied app does.
#
# What it proves, in order:
#   * the DMG is signed by the pinned team, notarized, and stapled, and Gatekeeper accepts it as a download;
#   * it opens with no prompt (a license agreement would stop it) and holds Umamo.app and the Applications link;
#   * the app dragged out of it, and the app unpacked from the zip, each verify strictly, carry their own stapled
#     ticket, and are accepted by Gatekeeper as notarized Developer ID code;
#   * the copied app's self-check passes, and it starts.
# The self-check and the launch run on a copy without the quarantine mark: executed from a script, a first launch
# of a quarantined app is the one step a runner cannot answer for a rigger, and Gatekeeper's verdict on it is
# already the assessment above.  A DMG has no install or uninstall of its own: an upgrade is dragging the new app
# over the old one and a removal is deleting it, so there is nothing further to test here.
#
# Usage: install-test-macos.sh <dmg> <zip> <expected team id>
#
# Written for the bash 3.2 macOS ships.

set -euo pipefail

if [ "$#" -ne 3 ]; then
	echo "usage: $0 <dmg> <zip> <expected team id>" >&2
	exit 2
fi
script_directory="$(cd "$(dirname "$0")" && pwd)"
work_directory="$(mktemp -d)"
dmg="${work_directory}/$(basename "$1")"
zip="${work_directory}/$(basename "$2")"
team_id="$3"
cp "$1" "${dmg}"
cp "$2" "${zip}"
mount_point="${work_directory}/mount"
applications="${work_directory}/Applications"
unzipped="${work_directory}/unzipped"
mkdir -p "${mount_point}" "${applications}" "${unzipped}"

failures=0
# fail <message>: reports a failed check and carries on, so one run names every problem.
fail() {
	echo "::error::$1"
	failures=$((failures + 1))
}

# quarantine <file>: marks a file as a browser marks a download.
quarantine() {
	xattr -w com.apple.quarantine "0081;$(printf '%x' "$(date +%s)");Safari;" "$1"
}

# expect_notarized <label> <spctl arguments...>: Gatekeeper's assessment must accept the code as notarized.
expect_notarized() {
	label="$1"
	shift
	verdict="$(spctl "$@" 2>&1 || true)"
	echo "${verdict}"
	case "${verdict}" in
		*"accepted"*"source=Notarized Developer ID"*) ;;
		*) fail "Gatekeeper does not accept ${label} as notarized Developer ID code" ;;
	esac
}

# check_app <label> <app>: the checks a Gatekeeper-bound app must pass.
check_app() {
	label="$1"
	app="$2"
	echo "---- ${label}: ${app}"
	codesign --verify --deep --strict --verbose=2 "${app}" || fail "${label} does not verify"
	signature="$(codesign --display --verbose=2 "${app}" 2>&1)"
	echo "${signature}" | grep -E "^(Authority|TeamIdentifier|Runtime Version|Timestamp)=" || true
	case "${signature}" in
		*"Authority=Developer ID Application: "*"TeamIdentifier=${team_id}"*) ;;
		*) fail "${label} is not signed by a Developer ID of team ${team_id}" ;;
	esac
	xcrun stapler validate "${app}" || fail "${label} has no stapled ticket"
	expect_notarized "${label}" --assess --type execute -vv "${app}"
	if command -v syspolicy_check > /dev/null; then
		syspolicy_check distribution "${app}" || fail "syspolicy_check finds ${label} not ready for distribution"
	fi
}

echo "---- the DMG as downloaded"
quarantine "${dmg}"
codesign --verify --verbose=2 "${dmg}" || fail "the DMG's signature does not verify"
xcrun stapler validate "${dmg}" || fail "the DMG has no stapled ticket"
expect_notarized "the DMG" --assess --type open --context context:primary-signature -vv "${dmg}"

# Nothing answers here, so a DMG that asks a question - a license agreement, which the build leaves out because the
# license ships inside the app - fails to mount.
if ! hdiutil attach -nobrowse -readonly -mountpoint "${mount_point}" "${dmg}" < /dev/null; then
	echo "::error::the DMG did not mount without an answer: does it carry a license agreement again?"
	exit 1
fi
ls -la "${mount_point}"
[ -d "${mount_point}/Umamo.app" ] || fail "the DMG holds no Umamo.app"
[ -L "${mount_point}/Applications" ] || fail "the DMG has no Applications link to drag onto"
if [ -d "${mount_point}/Umamo.app" ]; then
	ditto "${mount_point}/Umamo.app" "${applications}/Umamo.app"
fi
hdiutil detach "${mount_point}"

if [ -d "${applications}/Umamo.app" ]; then
	check_app "the app from the DMG" "${applications}/Umamo.app"
fi

echo "---- the zip as downloaded"
quarantine "${zip}"
ditto -x -k "${zip}" "${unzipped}"
if [ -d "${unzipped}/Umamo.app" ]; then
	check_app "the app from the zip" "${unzipped}/Umamo.app"
else
	fail "the zip holds no Umamo.app at its root"
fi

if [ -d "${applications}/Umamo.app" ]; then
	launch_copy="${work_directory}/launch/Umamo.app"
	mkdir -p "$(dirname "${launch_copy}")"
	# --noqtn copies without the quarantine mark.  Stripping it afterwards cannot work: the runtime's legal files are
	# read-only, and macOS refuses to remove an attribute from a file the caller may not write.
	ditto --noqtn "${applications}/Umamo.app" "${launch_copy}"
	bash "${script_directory}/self-check.sh" "${launch_copy}/Contents/MacOS/Umamo" || fail "the copied app's self-check failed"
	bash "${script_directory}/launch-smoke-test.sh" "${launch_copy}/Contents/MacOS/Umamo" "${HOME}/Library/Application Support/umamo/logs" false ||
		fail "the copied app's launch smoke test failed"
fi

if [ "${failures}" -ne 0 ]; then
	echo "${failures} check(s) failed"
	exit 1
fi
echo "install test passed: $(basename "${dmg}") and $(basename "${zip}")"