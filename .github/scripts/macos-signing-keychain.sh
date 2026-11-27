#!/usr/bin/env bash
# Puts the Developer ID Application certificate and its private key into a keychain of their own on the runner, where
# codesign - run by jpackage, by the Compose plugin for the natives inside the jars, and for the DMG - can use them
# without a prompt, and checks the identity belongs to the pinned Apple team: a certificate from any other team in
# the secrets fails here rather than shipping an app Gatekeeper attributes to someone else.
#
# Writes UMAMO_MAC_SIGNING_IDENTITY and UMAMO_MAC_SIGNING_KEYCHAIN to $GITHUB_ENV when it is set (gradle-package.sh
# and the DMG signing step read them), and prints them either way.
#
# Usage: macos-signing-keychain.sh <keychain path> <expected team id>
#
# Written for the MacOS Bash 3.2.

set -euo pipefail

if [ "$#" -ne 2 ]; then
	echo "usage: $0 <keychain path> <expected team id>" >&2
	exit 2
fi
keychain="$1"
team_id="$2"

if [ -z "${MACOS_DEVELOPER_ID_P12:-}" ] || [ -z "${MACOS_DEVELOPER_ID_P12_PASSWORD:-}" ]; then
	echo "::error::the MACOS_DEVELOPER_ID_P12 and MACOS_DEVELOPER_ID_P12_PASSWORD secrets must both be set (RELEASING.md § Signing)"
	exit 1
fi

work_directory="$(mktemp -d)"
# absent until create-keychain has made it, then created, and ready once the identity has passed its checks.
keychain_state="absent"

# remove_leftovers: the decoded certificate always, and the keychain when the setup made one and did not finish.
# A keychain this run did not make is never touched.
remove_leftovers() {
	rm -rf "${work_directory}"
	if [ "${keychain_state}" = "created" ]; then
		security delete-keychain "${keychain}" > /dev/null 2>&1 || true
	fi
}
trap remove_leftovers EXIT
certificate="${work_directory}/developer-id.p12"
intermediate="${work_directory}/DeveloperIDG2CA.cer"
printf '%s' "${MACOS_DEVELOPER_ID_P12}" | base64 --decode > "${certificate}"
curl --fail --silent --show-error --location --output "${intermediate}" https://www.apple.com/certificateauthority/DeveloperIDG2CA.cer

keychain_password="$(openssl rand -hex 24)"
# Masked in case an error message ever quotes a command line that carries it.
if [ -n "${GITHUB_ACTIONS:-}" ]; then
	echo "::add-mask::${keychain_password}"
fi
security create-keychain -p "${keychain_password}" "${keychain}"
keychain_state="created"
# No auto-lock for the length of a job, so a slow notarization cannot leave codesign facing a locked keychain.
security set-keychain-settings -lut 21600 "${keychain}"
security unlock-keychain -p "${keychain_password}" "${keychain}"
security import "${intermediate}" -k "${keychain}"
# -x: the private key cannot be exported from the keychain again.
security import "${certificate}" -k "${keychain}" -f pkcs12 -x -P "${MACOS_DEVELOPER_ID_P12_PASSWORD}" -T /usr/bin/codesign -T /usr/bin/security

security set-key-partition-list -S apple-tool:,apple:,codesign: -s -k "${keychain_password}" "${keychain}" > /dev/null

# Put the keychain first in the user search list, keeping the ones already there.
search_list=("${keychain}")
while IFS= read -r listed; do
	listed="${listed//\"/}"
	listed="${listed#"${listed%%[![:space:]]*}"}"
	if [ -n "${listed}" ]; then
		search_list+=("${listed}")
	fi
done < <(security list-keychains -d user)
security list-keychains -d user -s "${search_list[@]}"

identities="$(security find-identity -v -p codesigning "${keychain}")"
developer_ids="$(printf '%s\n' "${identities}" | sed -n 's/.*"\(Developer ID Application: [^"]*\)".*/\1/p')"
count="$(printf '%s\n' "${developer_ids}" | grep -c . || true)"
if [ "${count}" -ne 1 ]; then
	echo "::error::the certificate secret holds ${count} valid Developer ID Application identities, not one"
	exit 1
fi
identity="${developer_ids}"
case "${identity}" in
	*"(${team_id})") ;;
	*)
		echo "::error::the signing identity '${identity}' is not from the pinned Apple team ${team_id}"
		exit 1
		;;
esac

echo "signing identity: ${identity}"
echo "signing keychain: ${keychain}"
if [ -n "${GITHUB_ENV:-}" ]; then
	{
		echo "UMAMO_MAC_SIGNING_IDENTITY=${identity}"
		echo "UMAMO_MAC_SIGNING_KEYCHAIN=${keychain}"
	} >> "${GITHUB_ENV}"
fi
keychain_state="ready"