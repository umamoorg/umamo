# Installs the Umamo MSI per user, upgrades it over an older one, checks what it registered, runs the installed app's
# self-check and launch smoke test, and uninstalls it.
#
# What it proves, in order:
#   * the older MSI installs, then the new one replaces it: one Umamo in the uninstall list, at the new version (the
#     upgrade code in app/desktop/build.gradle.kts, which every later MSI finds the installed one by);
#   * the app sits in %LOCALAPPDATA%\Programs\umamo with its Start-menu entry, and .uma opens with it, the file handed
#     over as an argument;
#   * the installed app's self-check and launch smoke test pass;
#   * uninstalling removes the app, its folder, its uninstall entry, and the .uma registration, and leaves the settings
#     (%APPDATA%\umamo) and the session logs (%LOCALAPPDATA%\umamo) alone.
#
# msiexec and Umamo.exe are both GUI programs, which PowerShell's call operator does not wait for: each is started and
# waited on explicitly.
#
# Usage: install-test-windows.ps1 -Msi <new MSI> -OlderMsi <older MSI> -ExpectedVersion <x.y.z>

param(
	[Parameter(Mandatory = $true)] [string] $Msi,
	[Parameter(Mandatory = $true)] [string] $OlderMsi,
	[Parameter(Mandatory = $true)] [string] $ExpectedVersion
)

$ErrorActionPreference = "Stop"
$scriptDirectory = Split-Path -Parent $MyInvocation.MyCommand.Path
$installDirectory = Join-Path $env:LOCALAPPDATA "Programs\umamo"
$launcher = Join-Path $installDirectory "Umamo.exe"
$startMenuEntry = Join-Path $env:APPDATA "Microsoft\Windows\Start Menu\Programs\Umamo\Umamo.lnk"
$settingsDirectory = Join-Path $env:APPDATA "umamo"
$dataDirectory = Join-Path $env:LOCALAPPDATA "umamo"
$logDirectory = Join-Path $env:RUNNER_TEMP "msiexec-logs"
New-Item -ItemType Directory -Force -Path $logDirectory, $settingsDirectory, $dataDirectory | Out-Null

$failures = 0
# Reports a failed check and carries on, so one run names every problem.
function Report-Failure([string] $Message) {
	Write-Host "::error::$Message"
	$script:failures++
}

# Runs msiexec with the given arguments and waits for it; 3010 means success with a reboot pending.
function Invoke-Msiexec([string[]] $Arguments, [string] $LogName) {
	$log = Join-Path $logDirectory $LogName
	$process = Start-Process -FilePath "msiexec.exe" -ArgumentList ($Arguments + @("/qn", "/l*v", "`"$log`"")) -PassThru
	$null = $process.Handle
	$process.WaitForExit()
	if ($process.ExitCode -ne 0 -and $process.ExitCode -ne 3010) {
		Get-Content -Path $log -Tail 40
		throw "msiexec $($Arguments -join ' ') exited with $($process.ExitCode)"
	}
}

# Every uninstall-list entry named Umamo, per user or per machine.
function Get-UmamoUninstallEntries {
	$roots = @(
		"HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall",
		"HKLM:\Software\Microsoft\Windows\CurrentVersion\Uninstall",
		"HKLM:\Software\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall"
	)
	foreach ($root in $roots) {
		if (Test-Path $root) {
			Get-ChildItem -Path $root | ForEach-Object { Get-ItemProperty -Path $_.PSPath } | Where-Object { $_.DisplayName -eq "Umamo" }
		}
	}
}

# The command Windows runs to open a .uma, or $null when none is registered for this user.
function Get-UmaOpenCommand {
	$extensionKey = "HKCU:\Software\Classes\.uma"
	if (-not (Test-Path $extensionKey)) {
		return $null
	}
	$programId = (Get-ItemProperty -Path $extensionKey)."(default)"
	if (-not $programId) {
		return $null
	}
	$commandKey = "HKCU:\Software\Classes\$programId\shell\open\command"
	if (-not (Test-Path $commandKey)) {
		return $null
	}
	return (Get-ItemProperty -Path $commandKey)."(default)"
}

Set-Content -Path (Join-Path $settingsDirectory "installer-test-sentinel") -Value "a rigger's settings"
Set-Content -Path (Join-Path $dataDirectory "installer-test-sentinel") -Value "a rigger's session logs"

Write-Host "---- installing the older MSI $OlderMsi"
Invoke-Msiexec @("/i", "`"$OlderMsi`"") "older-install.log"
Write-Host "---- installing $Msi"
Invoke-Msiexec @("/i", "`"$Msi`"") "install.log"

$entries = @(Get-UmamoUninstallEntries)
$entries | Format-Table DisplayName, DisplayVersion, InstallLocation, PSPath -AutoSize | Out-String -Width 300 | Write-Host
if ($entries.Count -ne 1) {
	Report-Failure "the uninstall list holds $($entries.Count) Umamo entries after the upgrade, not one"
} elseif ($entries[0].DisplayVersion -ne $ExpectedVersion) {
	Report-Failure "the uninstall list records Umamo $($entries[0].DisplayVersion), expected $ExpectedVersion"
}
if (-not (Test-Path $launcher)) {
	Report-Failure "no $launcher"
}
if (-not (Test-Path $startMenuEntry)) {
	Report-Failure "no Start-menu entry at $startMenuEntry"
}
$openCommand = Get-UmaOpenCommand
Write-Host ".uma opens with: $openCommand"
if (-not $openCommand -or $openCommand -notlike '*Umamo.exe*' -or $openCommand -notlike '*"%1"*') {
	Report-Failure "a .uma does not open with the installed Umamo.exe, handed over as an argument"
}

# Both need the launcher, whose absence is already reported; skipping them keeps the uninstall checks below running.
if (Test-Path $launcher) {
	# A script that succeeds without calling exit leaves $LASTEXITCODE as it was, so each check starts from zero.
	$global:LASTEXITCODE = 0
	& (Join-Path $scriptDirectory "self-check.ps1") -Launcher $launcher
	if ($LASTEXITCODE -ne 0) {
		Report-Failure "the installed app's self-check failed"
	}
	$global:LASTEXITCODE = 0
	& (Join-Path $scriptDirectory "launch-smoke-test.ps1") -Launcher $launcher
	if ($LASTEXITCODE -ne 0) {
		Report-Failure "the installed app's launch smoke test failed"
	}
}

Write-Host "---- uninstalling"
Invoke-Msiexec @("/x", "`"$Msi`"") "uninstall.log"
if (@(Get-UmamoUninstallEntries).Count -ne 0) {
	Report-Failure "Umamo is still in the uninstall list after the uninstall"
}
if (Test-Path $installDirectory) {
	Report-Failure "$installDirectory is still there after the uninstall"
}
if (Get-UmaOpenCommand) {
	Report-Failure ".uma is still registered after the uninstall"
}
if (-not (Test-Path (Join-Path $settingsDirectory "installer-test-sentinel"))) {
	Report-Failure "the uninstall touched the settings folder"
}
if (-not (Test-Path (Join-Path $dataDirectory "installer-test-sentinel"))) {
	Report-Failure "the uninstall touched the session log folder"
}

if ($failures -ne 0) {
	Write-Host "$failures check(s) failed"
	exit 1
}
Write-Host "install test passed: $Msi"