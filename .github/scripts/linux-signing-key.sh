#!/usr/bin/env bash
# Imports the release signing key from the LINUX_SIGNING_KEY secret into a GnuPG home of its own, and refuses a key
# that is not the one the project publishes: the secret's primary fingerprint, the committed public key's, and the
# fingerprint the workflow pins must be one and the same, so a replaced secret or a stale public key fails here
# instead of shipping packages nobody can verify.  The key has no passphrase; every gpg call runs --batch, so a key
# that gains one fails loudly rather than waiting for a prompt that never comes.
#
# Usage: linux-signing-key.sh <gnupg home> <public key file> <expected primary fingerprint>
#   (the armored secret key in LINUX_SIGNING_KEY; the caller exports GNUPGHOME=<gnupg home> for the signing steps)

set -euo pipefail

if [ "$#" -ne 3 ]; then
	echo "usage: $0 <gnupg home> <public key file> <expected primary fingerprint>" >&2
	exit 2
fi
gnupg_home="$1"
public_key="$2"
expected_fingerprint="$3"

if [ -z "${LINUX_SIGNING_KEY:-}" ]; then
	echo "::error::the LINUX_SIGNING_KEY secret is empty (RELEASING.md § Signing)"
	exit 1
fi

# first_fingerprint: the first fpr record of gpg's colon listing on stdin - the primary key's.  awk reads to the end,
# so gpg never writes into a closed pipe.
first_fingerprint() {
	awk -F: '$1 == "fpr" && !found { print $10; found = 1 }'
}

mkdir -p "${gnupg_home}"
chmod 700 "${gnupg_home}"
export GNUPGHOME="${gnupg_home}"

published_fingerprint="$(gpg --batch --with-colons --import-options show-only --import "${public_key}" 2> /dev/null | first_fingerprint)"
printf '%s\n' "${LINUX_SIGNING_KEY}" | gpg --batch --quiet --import
secret_fingerprint="$(gpg --batch --with-colons --list-secret-keys | first_fingerprint)"

echo "pinned:    ${expected_fingerprint}"
echo "published: ${published_fingerprint}"
echo "secret:    ${secret_fingerprint}"
if [ "${published_fingerprint}" != "${expected_fingerprint}" ]; then
	echo "::error file=${public_key}::the committed public key is not the pinned signing key ${expected_fingerprint}"
	exit 1
fi
if [ "${secret_fingerprint}" != "${expected_fingerprint}" ]; then
	echo "::error::the LINUX_SIGNING_KEY secret holds key ${secret_fingerprint}, not the pinned signing key ${expected_fingerprint}"
	exit 1
fi

# A signature made now, so a secret without a usable signing (sub)key, or one that wants a passphrase, fails here.
echo "signing key check" | gpg --batch --quiet --local-user "${expected_fingerprint}" --detach-sign > /dev/null
echo "signing key ready in ${gnupg_home}"