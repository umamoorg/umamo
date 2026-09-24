# Starts a packaged Umamo on Windows, waits for its session log to say it started, and stops it.
#
# The Windows twin of launch-smoke-test.sh; see that script for why the check reads the session log.  Two
# differences: the launcher is a GUI executable whose stderr cannot be captured reliably, so there is no
# warning check here (the Windows app image bundles JDK 21, which prints none), and the runner's display
# offers no GL 3.3 core context, so the GL line is informational.
#
# Usage: launch-smoke-test.ps1 -Launcher <path to umamo.exe>

param(
	[Parameter(Mandatory = $true)] [string] $Launcher
)

$ErrorActionPreference = "Stop"
$logsDirectory = Join-Path $env:LOCALAPPDATA "umamo\logs"
# A margin under the launch time, so a log created in the same second still counts as this run's.
$started = (Get-Date).AddSeconds(-2)
$app = Start-Process -FilePath $Launcher -PassThru

# The newest session log created since the launch, or $null yet.
function Get-NewestLog {
	Get-ChildItem -Path $logsDirectory -Filter "session-*.log" -ErrorAction SilentlyContinue |
		Where-Object { $_.CreationTime -ge $started } |
		Sort-Object Name |
		Select-Object -Last 1
}

# Waits up to $Seconds for the newest session log to contain $Needle: "found", "timeout", or "exited".
function Wait-ForLine([string] $Needle, [int] $Seconds) {
	for ($elapsed = 0; $elapsed -lt $Seconds; $elapsed++) {
		$log = Get-NewestLog
		if ($log -and (Select-String -Path $log.FullName -SimpleMatch -Quiet -Pattern $Needle)) {
			return "found"
		}
		if ($app.HasExited) {
			return "exited"
		}
		Start-Sleep -Seconds 1
	}
	return "timeout"
}

$failures = 0
$startedStatus = Wait-ForLine "started from the installed launcher" 60
if ($startedStatus -ne "found") {
	Write-Host "::error::the session log never said the installed launcher started the app ($startedStatus)"
	$failures++
} else {
	# The failure line also starts with "[GL]", so the success line is matched by its wording.
	$glStatus = Wait-ForLine "[GL] offscreen via " 60
	if ($glStatus -ne "found") {
		Write-Host "::notice::no offscreen GL context on this runner (informational here)"
	}
	# A few seconds past the first frame, so a failure right after it still lands in the log.
	Start-Sleep -Seconds 5
}

if (-not $app.HasExited) {
	Stop-Process -Id $app.Id -Force
}

$log = Get-NewestLog
Write-Host "---- session log: $($log.FullName)"
if ($log) {
	Get-Content -Path $log.FullName
	if (Select-String -Path $log.FullName -SimpleMatch -Quiet -Pattern "uncaught exception") {
		Write-Host "::error::the session log records an uncaught exception"
		$failures++
	}
}

if ($failures -ne 0) {
	exit 1
}
Write-Host "smoke test passed: $Launcher"