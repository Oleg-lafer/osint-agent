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

function Require-AvailablePort {
    param([Parameter(Mandatory)][int]$Port, [Parameter(Mandatory)][string]$Label)

    $listener = [System.Net.Sockets.TcpListener]::new(
        [System.Net.IPAddress]::Loopback, $Port)
    try {
        $listener.Start()
    } catch {
        throw "$Label port $Port is already in use. Stop the existing process and try again."
    } finally {
        $listener.Stop()
    }
}

function Stop-StaleAppOnPort {
    param(
        [Parameter(Mandatory)][int]$Port,
        [Parameter(Mandatory)][string]$Label,
        [Parameter(Mandatory)][string]$CommandMarker
    )

    $connections = @(Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue)
    if ($connections.Count -eq 0) { return }

    foreach ($connection in $connections) {
        $processId = $connection.OwningProcess
        $currentId = $processId
        $commandLines = @()
        for ($depth = 0; $depth -lt 6 -and $currentId -gt 0; $depth++) {
            $processInfo = Get-CimInstance Win32_Process -Filter "ProcessId=$currentId" `
                -ErrorAction SilentlyContinue
            if ($null -eq $processInfo) { break }
            $commandLines += [string]$processInfo.CommandLine
            $currentId = $processInfo.ParentProcessId
        }

        $processDescription = $commandLines -join "`n"
        if ($processDescription -notlike "*$repoRoot*" -or
                $processDescription -notlike "*$CommandMarker*") {
            throw "$Label port $Port is already used by an unrelated process (PID $processId)."
        }

        Write-Host "Stopping stale $Label process on port $Port..."
        $previousErrorActionPreference = $ErrorActionPreference
        $ErrorActionPreference = 'SilentlyContinue'
        & taskkill.exe /PID $processId /T /F 2>$null | Out-Null
        $ErrorActionPreference = $previousErrorActionPreference
    }

    $deadline = (Get-Date).AddSeconds(5)
    do {
        Start-Sleep -Milliseconds 200
        $remaining = @(Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue)
    } while ($remaining.Count -gt 0 -and (Get-Date) -lt $deadline)

    if ($remaining.Count -gt 0) {
        throw "Could not stop the stale $Label process on port $Port."
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

    if (-not $env:DB_CREDENTIALS_FILE) {
        $env:DB_CREDENTIALS_FILE = Join-Path $repoRoot 'KEYS_AND_CREDENTIALS\DataBase_Credentials.txt'
    }
    if (-not (Test-Path -LiteralPath $env:DB_CREDENTIALS_FILE -PathType Leaf)) {
        throw 'Database credentials file is missing. Set DB_CREDENTIALS_FILE to a valid file.'
    }
    if ($env:POSTS_CSV) {
        throw 'POSTS_CSV must be unset: this launcher enforces database-only mode.'
    }
    $env:DATABASE_ONLY = 'true'
    Write-Host 'Database-only mode enabled; all local data fallbacks are disabled.'
    Stop-StaleAppOnPort 7070 'backend' 'com.leadspotnic.web.Server'
    Stop-StaleAppOnPort 5173 'frontend' 'node_modules\vite'
    Require-AvailablePort 7070 'Backend'
    Require-AvailablePort 5173 'Frontend'

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

    Write-Host 'Backend: http://localhost:7070 (MySQL only)' -ForegroundColor Green
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
