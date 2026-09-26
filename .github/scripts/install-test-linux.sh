#!/usr/bin/env bash
# Installs a Umamo .deb or .rpm, upgrades it over an older one, checks what it registered, runs the installed app's
# self-check, and removes it again - on the release runner itself or inside a distribution's container.
#
# What it proves, in order:
#   * the older package installs, then the new one upgrades it in place (the package manager keeps one "umamo");
#   * AFTER the upgrade the menu entry, the .uma type, and the file-manager association are still registered - the
#     check that catches an RPM whose old %preun undoes the new %post (JDK-8301856, packaging/linux/umamo.spec);
#   * the installed app's self-check passes, which on a bare container also proves the package's dependencies pull
#     in every library the runtime and Skia load;
#   * the launch smoke test passes when UMAMO_LAUNCH_SMOKE_TEST is true (a display is up);
#   * removal takes /opt/umamo and the registrations with it, and leaves the settings folder alone.
# A container stands in for a desktop system: the menu and icon-theme directories a desktop environment provides
# are created first, since without them xdg-utils has nowhere to put the entry and the document icon (the package
# installs regardless).
#
# Usage: install-test-linux.sh <package> <expected version> [<older package> [<package-manager flag>]]
#   e.g. install-test-linux.sh /dist/umamo.rpm 0.4.0 /fixtures/umamo.rpm --no-gpgchecks

set -euo pipefail

if [ "$#" -lt 2 ]; then
	echo "usage: $0 <package> <expected version> [<older package> [<package-manager flag>]]" >&2
	exit 2
fi
# absolute_path <file>: the file's absolute path.  apt-get and dnf take an argument for a local package file only
# when it is a path, starting with / or ./; anything else is looked up as a package name in the repositories.
absolute_path() {
	echo "$(cd "$(dirname "$1")" && pwd)/$(basename "$1")"
}

package="$(absolute_path "$1")"
expected_version="$2"
older_package=""
if [ -n "${3:-}" ]; then
	older_package="$(absolute_path "$3")"
fi
manager_flag="${4:-}"
script_directory="$(cd "$(dirname "$0")" && pwd)"

sudo_command=""
if [ "$(id -u)" -ne 0 ]; then
	sudo_command="sudo"
fi

if command -v apt-get > /dev/null; then
	manager="apt"
elif command -v dnf > /dev/null; then
	manager="dnf"
else
	echo "::error::neither apt-get nor dnf is available"
	exit 2
fi

failures=0
# fail <message>: reports a failed check and carries on, so one run names every problem.
fail() {
	echo "::error::$1"
	failures=$((failures + 1))
}

# package_manager <arguments...>: apt-get or dnf, non-interactive, with the caller's extra flag for dnf.
package_manager() {
	if [ "${manager}" = "apt" ]; then
		${sudo_command} env DEBIAN_FRONTEND=noninteractive apt-get "$@"
	else
		# shellcheck disable=SC2086  # manager_flag is one optional word.
		${sudo_command} dnf ${manager_flag} "$@"
	fi
}

# install_package <file>: installs or upgrades from a local file, resolving dependencies from the distribution.
install_package() {
	if [ "${manager}" = "apt" ]; then
		package_manager install -y --no-install-recommends "$1"
	else
		package_manager install -y "$1"
	fi
}

# installed_version: the version the package manager records for umamo.
installed_version() {
	if [ "${manager}" = "apt" ]; then
		dpkg-query -W -f='${Version}' umamo
	else
		rpm -q --qf '%{VERSION}' umamo
	fi
}

# xdg-utils registers in the first writable system data directory: /usr/local/share where the distribution ships
# one (Fedora does), else /usr/share.
data_directories="/usr/local/share /usr/share"

# installed_entry: the menu entry xdg-desktop-menu installed, or nothing.
installed_entry() {
	for data_directory in ${data_directories}; do
		if [ -f "${data_directory}/applications/umamo-umamo.desktop" ]; then
			echo "${data_directory}/applications/umamo-umamo.desktop"
			return
		fi
	done
}

# has_uma_glob: whether a MIME database maps *.uma to a type.
has_uma_glob() {
	for data_directory in ${data_directories}; do
		if grep -q ':\*\.uma$' "${data_directory}/mime/globs2" 2> /dev/null; then
			return 0
		fi
	done
	return 1
}

# check_registered <when>: the menu entry, the default-application cache beside it, and the MIME database's glob.
check_registered() {
	entry="$(installed_entry)"
	if [ -z "${entry}" ]; then
		fail "no menu entry under /usr/local/share or /usr/share $1"
	else
		echo "menu entry: ${entry}"
		grep -q '^application/vnd.umamo.uma+zip=.*umamo-umamo.desktop' "$(dirname "${entry}")/mimeinfo.cache" 2> /dev/null ||
			fail "the file manager's cache does not map .uma to Umamo $1"
	fi
	has_uma_glob || fail "the MIME database has no *.uma glob $1"
}

# A desktop system's tools and menu directories; the runner has them already, a container does not.
if [ "${manager}" = "apt" ]; then
	package_manager update -qq
	package_manager install -y -qq --no-install-recommends desktop-file-utils shared-mime-info
else
	package_manager install -y -q desktop-file-utils shared-mime-info
fi
${sudo_command} mkdir -p /usr/share/desktop-directories /etc/xdg/menus /usr/share/icons/hicolor

settings_directory="${XDG_CONFIG_HOME:-${HOME}/.config}/umamo"
mkdir -p "${settings_directory}"
echo "a rigger's settings" > "${settings_directory}/installer-test-sentinel"

if [ -n "${older_package}" ]; then
	echo "---- installing the older package ${older_package}"
	install_package "${older_package}"
	echo "installed $(installed_version)"
fi

echo "---- installing ${package}"
install_package "${package}"
version="$(installed_version)"
echo "installed ${version}"
[ "${version}" = "${expected_version}" ] || fail "the package manager records umamo ${version}, expected ${expected_version}"
if [ -n "${older_package}" ]; then
	check_registered "after the upgrade"
else
	check_registered "after the install"
fi
if command -v desktop-file-validate > /dev/null && [ -n "$(installed_entry)" ]; then
	desktop-file-validate "$(installed_entry)" || fail "the installed menu entry does not validate"
fi

bash "${script_directory}/self-check.sh" /opt/umamo/bin/umamo || fail "the installed app's self-check failed"

if [ "${UMAMO_LAUNCH_SMOKE_TEST:-false}" = "true" ]; then
	bash "${script_directory}/launch-smoke-test.sh" /opt/umamo/bin/umamo "${XDG_DATA_HOME:-${HOME}/.local/share}/umamo/logs" true ||
		fail "the installed app's launch smoke test failed"
fi

echo "---- removing umamo"
package_manager remove -y umamo
[ ! -e /opt/umamo ] || fail "/opt/umamo is still there after removal"
[ -z "$(installed_entry)" ] || fail "the menu entry is still there after removal: $(installed_entry)"
if has_uma_glob; then
	fail "the MIME database still has the *.uma glob after removal"
fi
[ -f "${settings_directory}/installer-test-sentinel" ] || fail "removing the package touched the settings folder"

if [ "${failures}" -ne 0 ]; then
	echo "${failures} check(s) failed"
	exit 1
fi
echo "install test passed: ${package}"