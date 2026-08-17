[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$repoRoot = $PSScriptRoot
$backendDir = Join-Path $repoRoot 'post-clustering'
$frontendDir = Join-Path $repoRoot 'frontend'
$logDir = Join-Path $repoRoot '.app-logs'
$backendProcess = $null
$frontendProcess = $null

function Require-Command {
    param([Parameter(Mandatory)][string]$Name)

    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        throw "Required command '$Name' was not found in PATH. Install it and try again."
    }
}

function Stop-ProcessTree {
    param([System.Diagnostics.Process]$Process, [string]$Label)

    if ($null -eq $Process -or $Process.HasExited) { return }
    Write-Host "Stopping $Label..."

    # Console applications may reject taskkill's graceful request. That is expected;
    # give them a moment, then terminate the whole child tree if it is still alive.
    $previousErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = 'SilentlyContinue'
    & taskkill.exe /PID $Process.Id /T 2>$null | Out-Null
    if (-not $Process.WaitForExit(5000)) {
        & taskkill.exe /PID $Process.Id /T /F 2>$null | Out-Null
    }
    $ErrorActionPreference = $previousErrorActionPreference
}

function Show-LogTail {
    param([string]$Label, [string]$OutputLog, [string]$ErrorLog)

    Write-Host "$Label log tail:" -ForegroundColor Yellow
    foreach ($path in @($OutputLog, $ErrorLog)) {
        if (Test-Path $path) {
            Get-Content -Path $path -Tail 20 | Write-Host
        }
    }
}

try {
    Write-Host "Detected operating system: Windows"
    foreach ($command in @('java', 'mvn', 'node', 'npm')) {
        Require-Command $command
    }

    New-Item -ItemType Directory -Path $logDir -Force | Out-Null
    $backendOut = Join-Path $logDir 'backend.log'
    $backendErr = Join-Path $logDir 'backend-error.log'
    $frontendOut = Join-Path $logDir 'frontend.log'
    $frontendErr = Join-Path $logDir 'frontend-error.log'
    Remove-Item -Path $backendOut, $backendErr, $frontendOut, $frontendErr -Force -ErrorAction SilentlyContinue

    if (-not (Test-Path (Join-Path $frontendDir 'node_modules'))) {
        Write-Host 'Installing frontend dependencies (node_modules is missing)...'
        Push-Location $frontendDir
        try {
            & npm install
            if ($LASTEXITCODE -ne 0) { throw "npm install failed with exit code $LASTEXITCODE." }
        } finally {
            Pop-Location
        }
    } else {
        Write-Host 'Frontend dependencies are already installed.'
    }

    Write-Host 'Starting backend; output is being written to .app-logs/backend*.log...'
    $backendProcess = Start-Process -FilePath 'mvn.cmd' `
        -ArgumentList '-q', 'compile', 'exec:java', '-Dexec.mainClass=com.leadspotnic.web.Server' `
        -WorkingDirectory $backendDir -NoNewWindow -PassThru `
        -RedirectStandardOutput $backendOut -RedirectStandardError $backendErr

    $deadline = (Get-Date).AddSeconds(120)
    $backendReady = $false
    while ((Get-Date) -lt $deadline) {
        if ($backendProcess.HasExited) {
            $backendProcess.WaitForExit()
            Show-LogTail 'Backend' $backendOut $backendErr
            throw "Backend exited before becoming ready (exit code $($backendProcess.ExitCode))."
        }
        try {
            $response = Invoke-WebRequest -Uri 'http://localhost:7070/status' -UseBasicParsing -TimeoutSec 2
            if ($response.StatusCode -ge 200 -and $response.StatusCode -lt 300) {
                $backendReady = $true
                break
            }
        } catch {
            Start-Sleep -Milliseconds 500
        }
    }
    if (-not $backendReady) {
        Show-LogTail 'Backend' $backendOut $backendErr
        throw 'Backend did not become ready at http://localhost:7070/status within 120 seconds.'
    }

    Write-Host 'Backend: http://localhost:7070' -ForegroundColor Green
    Write-Host 'Starting frontend; output is being written to .app-logs/frontend*.log...'
    $frontendAddress = 'http://localhost:5173'
    $frontendProcess = Start-Process -FilePath 'npm.cmd' `
        -ArgumentList 'run', 'dev', '--', '--port', '5173', '--strictPort' `
        -WorkingDirectory $frontendDir -NoNewWindow -PassThru `
        -RedirectStandardOutput $frontendOut -RedirectStandardError $frontendErr

    $frontendDeadline = (Get-Date).AddSeconds(30)
    $frontendReady = $false
    while ((Get-Date) -lt $frontendDeadline) {
        if ($frontendProcess.HasExited) {
            $frontendProcess.WaitForExit()
            Show-LogTail 'Frontend' $frontendOut $frontendErr
            throw "Frontend exited during startup (exit code $($frontendProcess.ExitCode))."
        }
        try {
            $frontendResponse = Invoke-WebRequest -Uri $frontendAddress -UseBasicParsing -TimeoutSec 2
            if ($frontendResponse.StatusCode -ge 200 -and $frontendResponse.StatusCode -lt 300) {
                $frontendReady = $true
                break
            }
        } catch {
            Start-Sleep -Milliseconds 250
        }
    }

    if (-not $frontendReady) {
        Show-LogTail 'Frontend' $frontendOut $frontendErr
        throw "Frontend did not become ready at $frontendAddress within 30 seconds."
    }

    Write-Host "Frontend: $frontendAddress" -ForegroundColor Green
    Write-Host 'Press Ctrl+C to stop both processes.'

    while ($true) {
        if ($backendProcess.HasExited) {
            $backendProcess.WaitForExit()
            Show-LogTail 'Backend' $backendOut $backendErr
            throw "Backend stopped unexpectedly (exit code $($backendProcess.ExitCode))."
        }
        if ($frontendProcess.HasExited) {
            $frontendProcess.WaitForExit()
            Show-LogTail 'Frontend' $frontendOut $frontendErr
            throw "Frontend stopped unexpectedly (exit code $($frontendProcess.ExitCode))."
        }
        Start-Sleep -Seconds 1
    }
} catch [System.Management.Automation.PipelineStoppedException] {
    Write-Host 'Shutdown requested.'
} catch {
    Write-Error $_
} finally {
    Stop-ProcessTree $frontendProcess 'frontend'
    Stop-ProcessTree $backendProcess 'backend'
    Write-Host 'Application stopped.'
}
