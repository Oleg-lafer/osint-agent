[CmdletBinding()]
param(
    [Parameter(Position = 0, HelpMessage = 'Maximum post_qualification rows to process')]
    [ValidateRange(1, 1000000)]
    [int]$QualificationPostCount = 3000,

    [Parameter(Position = 1, HelpMessage = 'Maximum post_summary rows to process')]
    [ValidateRange(1, 1000000)]
    [int]$SummaryPostCount = 3000
)

$ErrorActionPreference = 'Stop'
$repositoryRoot = $PSScriptRoot
$backendDirectory = Join-Path $repositoryRoot 'post-clustering'
$defaultDatabaseCredentials = Join-Path $repositoryRoot `
    'KEYS_AND_CREDENTIALS\DataBase_Credentials.txt'
$openAiKey = Join-Path $repositoryRoot 'KEYS_AND_CREDENTIALS\OPEN_AI.txt'

foreach ($command in @('java', 'mvn')) {
    if (-not (Get-Command $command -ErrorAction SilentlyContinue)) {
        throw "Required command '$command' was not found in PATH."
    }
}

if (-not $env:DB_CREDENTIALS_FILE) {
    $env:DB_CREDENTIALS_FILE = $defaultDatabaseCredentials
}
if (-not (Test-Path -LiteralPath $env:DB_CREDENTIALS_FILE -PathType Leaf)) {
    throw 'Database credentials file is missing. Set DB_CREDENTIALS_FILE to a valid file.'
}
if (-not (Test-Path -LiteralPath $openAiKey -PathType Leaf)) {
    throw 'OpenAI key file is missing from KEYS_AND_CREDENTIALS.'
}
if ($env:POSTS_CSV) {
    throw 'POSTS_CSV must be unset: the PreProcessing pipeline reads from MySQL.'
}

$configuredJavaHome = [Environment]::GetEnvironmentVariable('JAVA_HOME', 'User')
if ($configuredJavaHome) {
    $env:JAVA_HOME = $configuredJavaHome
    $env:Path = "$(Join-Path $configuredJavaHome 'bin');$env:Path"
}

$javaVersion = (& java --version 2>&1 | Select-Object -First 1).ToString()
if ($javaVersion -notmatch '(?:openjdk|java)(?:\s+version)?\s+"?(\d+)') {
    throw "Could not determine the installed Java version: $javaVersion"
}
if ([int]$matches[1] -lt 17) {
    throw "Java 17 or newer is required. Installed version: $javaVersion"
}

$env:POST_LIMIT = [string]$QualificationPostCount
$env:POST_SUMMARY_LIMIT = [string]$SummaryPostCount
if (-not $env:STORAGE_MODE) {
    $env:STORAGE_MODE = 'database'
}
Write-Host "Starting two separate PreProcessing runs."
Write-Host "Run 1: up to $QualificationPostCount newest post_qualification rows."
Write-Host "Run 2: up to $SummaryPostCount matching post_summary rows."
Write-Host 'This invokes paid OpenAI embedding, extraction, and summarization calls.'
Write-Host "Storage mode: $($env:STORAGE_MODE)"

Push-Location $backendDirectory
try {
    foreach ($source in @('post-qualification', 'post-summary')) {
        Write-Host "Running source: $source"
        $pipelineArguments = "--post-source=$source --embed --extract --summarize"
        & mvn -q compile exec:java "-Dexec.args=$pipelineArguments"
        if ($LASTEXITCODE -ne 0) {
            throw "PreProcessing pipeline for $source failed with exit code $LASTEXITCODE."
        }
    }
} finally {
    Pop-Location
}

Write-Host 'Both separate PreProcessing runs completed successfully.' -ForegroundColor Green
