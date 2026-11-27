#!/usr/bin/env bash
# Signs RPMs with the release key and verifies each signature against the committed public key alone, in a throwaway
# rpm database, the way a Fedora machine that ran `rpm --import` on that key would.  Fedora 45 refuses a local
# package it cannot verify, so an RPM that leaves here unsigned, or signed by any other key, fails the build instead.
#
# Usage: sign-rpm.sh <public key file> <rpm...>
#   (GNUPGHOME and UMAMO_SIGNING_KEY_FINGERPRINT from the environment, as linux-signing-key.sh prepares them)

set -euo pipefail

if [ "$#" -lt 2 ]; then
	echo "usage: $0 <public key file> <rpm...>" >&2
	exit 2
fi
public_key="$1"
shift

for rpm_file in "$@"; do
	echo "---- signing ${rpm_file}"
	# Debian's rpm names gpg2 as its signer, which Ubuntu no longer ships: the gpg on the PATH is named instead.
	rpmsign --addsign \
		--define "__gpg $(command -v gpg)" \
		--define "_gpg_name ${UMAMO_SIGNING_KEY_FINGERPRINT}" \
		--define "_gpg_path ${GNUPGHOME}" \
		--define "_gpg_sign_cmd_extra_args --batch" \
		"${rpm_file}"
done

rpm_database="$(mktemp -d)"
trap 'rm -rf "${rpm_database}"' EXIT
rpmkeys --dbpath "${rpm_database}" --import "${public_key}"
failures=0
for rpm_file in "$@"; do
	verdict="$(rpmkeys --dbpath "${rpm_database}" --checksig "${rpm_file}")"
	echo "${verdict}"
	case "${verdict}" in
		*": digests signatures OK")
			rpmkeys --dbpath "${rpm_database}" --checksig --verbose "${rpm_file}" | grep -i "signature"
			;;
		*)
			echo "::error::${rpm_file} does not verify against ${public_key}"
			failures=$((failures + 1))
			;;
	esac
done
[ "${failures}" -eq 0 ]