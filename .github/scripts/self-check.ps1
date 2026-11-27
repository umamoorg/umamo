# Runs a packaged Umamo's headless self-check (--self-check) on Windows and judges it.
#
# The Windows twin of self-check.sh; see that script for what the self-check covers.  The launcher is a GUI
# executable, so PowerShell's call operator would return before it finishes and its output cannot be read: the
# process is started and waited on explicitly, and the verdict comes from its exit code and its report file.
#
# Usage: self-check.ps1 -Launcher <path to Umamo.exe>

param(
	[Parameter(Mandatory = $true)] [string] $Launcher
)

$ErrorActionPreference = "Stop"
$report = Join-Path ([System.IO.Path]::GetTempPath()) "umamo-self-check-$([System.Guid]::NewGuid()).txt"

$process = Start-Process -FilePath $Launcher -ArgumentList @("--self-check", "`"$report`"") -PassThru
# Reading the handle now keeps the exit code: without it a process that ends quickly can report none.
$null = $process.Handle
if (-not $process.WaitForExit(120000)) {
	Stop-Process -Id $process.Id -Force
	Write-Host "::error::the self-check did not finish in two minutes"
	exit 1
}

Write-Host "---- self-check report ($Launcher)"
if (Test-Path $report) {
	Get-Content -Path $report
} else {
	Write-Host "(no report written)"
}

$failures = 0
if ($process.ExitCode -ne 0) {
	Write-Host "::error::the self-check exited with $($process.ExitCode)"
	$failures++
}
if ((Test-Path $report) -and (Select-String -Path $report -SimpleMatch -Quiet -Pattern ": FAILED ")) {
	Write-Host "::error::a self-check failed"
	$failures++
}

if ($failures -ne 0) {
	exit 1
}
Write-Host "self-check passed: $Launcher"
