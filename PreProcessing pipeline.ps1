[CmdletBinding()]
param(
    [Parameter(Mandatory, Position = 0, HelpMessage = 'Number of newest database posts to process')]
    [ValidateRange(1, 1000000)]
    [int]$PostCount
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

$env:POST_LIMIT = [string]$PostCount
Write-Host "Starting the PreProcessing pipeline for the $PostCount newest database posts."
Write-Host 'This invokes paid OpenAI embedding, extraction, and summarization calls.'

Push-Location $backendDirectory
try {
    & mvn -q compile exec:java '-Dexec.args=--embed --extract --summarize'
    if ($LASTEXITCODE -ne 0) {
        throw "PreProcessing pipeline failed with exit code $LASTEXITCODE."
    }
} finally {
    Pop-Location
}

Write-Host 'PreProcessing pipeline completed successfully.' -ForegroundColor Green
