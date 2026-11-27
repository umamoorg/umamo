#!/usr/bin/env bash
# Submits a file to Apple's notary service with the App Store Connect API key, waits for the verdict, prints the
# notary's log either way, and staples the ticket to the path given.  Anything but Accepted fails, with the log's
# list of issues - an unsigned native inside a jar, a missing secure timestamp - printed above the failure.
#
# Stapling puts the ticket in the file itself, so Gatekeeper accepts it on a first launch with no network: the app
# is stapled before the DMG is built around it, so an app dragged out of the DMG carries its own ticket, and the DMG
# is stapled after its own submission.
#
# The key comes from APPLE_NOTARY_KEY (the text of the AuthKey_<id>.p8 App Store Connect issued),
# APPLE_NOTARY_KEY_ID, and APPLE_NOTARY_ISSUER_ID.
#
# Usage: macos-notarize.sh <file to submit: .zip or .dmg> <path to staple: the .app or the .dmg>
#
# Written for the bash 3.2 macOS ships.

set -euo pipefail

if [ "$#" -ne 2 ]; then
	echo "usage: $0 <file to submit> <path to staple>" >&2
	exit 2
fi
submission="$1"
staple_target="$2"

if [ -z "${APPLE_NOTARY_KEY:-}" ] || [ -z "${APPLE_NOTARY_KEY_ID:-}" ] || [ -z "${APPLE_NOTARY_ISSUER_ID:-}" ]; then
	echo "::error::the APPLE_NOTARY_KEY, APPLE_NOTARY_KEY_ID, and APPLE_NOTARY_ISSUER_ID secrets must all be set (RELEASING.md § Signing)"
	exit 1
fi

work_directory="$(mktemp -d)"
trap 'rm -rf "${work_directory}"' EXIT
key_file="${work_directory}/AuthKey_${APPLE_NOTARY_KEY_ID}.p8"
(umask 077 && printf '%s\n' "${APPLE_NOTARY_KEY}" > "${key_file}")
credentials=(--key "${key_file}" --key-id "${APPLE_NOTARY_KEY_ID}" --issuer "${APPLE_NOTARY_ISSUER_ID}")

echo "---- submitting ${submission}"
result="${work_directory}/result.json"
xcrun notarytool submit "${submission}" "${credentials[@]}" --wait --timeout 45m --output-format json > "${result}"
cat "${result}"
echo
submission_id="$(plutil -extract id raw -o - "${result}")"
status="$(plutil -extract status raw -o - "${result}")"

echo "---- notary log for ${submission_id}"
xcrun notarytool log "${submission_id}" "${credentials[@]}" || true
echo

if [ "${status}" != "Accepted" ]; then
	echo "::error::notarization of $(basename "${submission}") ended ${status}; the log above lists the issues"
	exit 1
fi

echo "---- stapling ${staple_target}"
xcrun stapler staple "${staple_target}"
xcrun stapler validate "${staple_target}"