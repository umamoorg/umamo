#!/usr/bin/env bash
# Writes SHA256SUMS.txt over every umamo-* download in a directory, signs it with the release key as
# SHA256SUMS.txt.asc, and verifies that signature with gpgv against the committed public key alone - exactly what a
# rigger runs after downloading both files and the key.  The checksums cover the DEB, the jars, and the archives,
# which carry no signature of their own.
#
# Usage: sign-checksums.sh <download directory> <public key file>
#   (GNUPGHOME and UMAMO_SIGNING_KEY_FINGERPRINT from the environment, as linux-signing-key.sh prepares them)

set -euo pipefail

if [ "$#" -ne 2 ]; then
	echo "usage: $0 <download directory> <public key file>" >&2
	exit 2
fi
download_directory="$1"
public_key="$(cd "$(dirname "$2")" && pwd)/$(basename "$2")"

cd "${download_directory}"
rm -f SHA256SUMS.txt SHA256SUMS.txt.asc
# The umamo- prefix keeps the checksum file and its signature from ever hashing themselves.
sha256sum umamo-* > SHA256SUMS.txt
cat SHA256SUMS.txt
gpg --batch --armor --local-user "${UMAMO_SIGNING_KEY_FINGERPRINT}" --output SHA256SUMS.txt.asc --detach-sign SHA256SUMS.txt

verify_home="$(mktemp -d)"
trap 'rm -rf "${verify_home}"' EXIT
gpg --batch --quiet --homedir "${verify_home}" --no-default-keyring --keyring "${verify_home}/published.kbx" --import "${public_key}"
gpgv --keyring "${verify_home}/published.kbx" SHA256SUMS.txt.asc SHA256SUMS.txt
sha256sum --check --quiet SHA256SUMS.txt
echo "SHA256SUMS.txt signed and verified against ${public_key}"