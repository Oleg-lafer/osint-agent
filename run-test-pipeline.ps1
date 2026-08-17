$ErrorActionPreference = 'Stop'

$repositoryRoot = $PSScriptRoot
$backendDirectory = Join-Path $repositoryRoot 'post-clustering'
$databaseCredentials = Join-Path $repositoryRoot 'KEYS_AND_CREDENTIALS\DataBase_Credentials.txt'
$openAiKey = Join-Path $repositoryRoot 'KEYS_AND_CREDENTIALS\OPEN_AI.txt'

if (-not (Test-Path -LiteralPath $databaseCredentials)) {
    throw "Database credentials file is missing: $databaseCredentials"
}
if (-not (Test-Path -LiteralPath $openAiKey)) {
    throw "OpenAI key file is missing: $openAiKey"
}

$configuredJavaHome = [Environment]::GetEnvironmentVariable('JAVA_HOME', 'User')
if ($configuredJavaHome) {
    $env:JAVA_HOME = $configuredJavaHome
    $env:Path = "$(Join-Path $configuredJavaHome 'bin');$env:Path"
}

$javaVersion = (& java --version 2>&1 | Select-Object -First 1).ToString()
# Some OpenJDK distributions prefix version output with ANSI control sequences.
# Do not anchor the match to the first character, and accept both modern
# "openjdk 17..." and traditional 'java version "17..."' formats.
if ($javaVersion -notmatch '(?:openjdk|java)(?:\s+version)?\s+"?(\d+)') {
    throw "Could not determine the installed Java version: $javaVersion"
}
if ([int]$matches[1] -lt 17) {
    throw "Java 17 or newer is required. Installed version: $javaVersion"
}

$env:DB_CREDENTIALS_FILE = $databaseCredentials
$env:DB_NAME = 'leadspot_main'
$env:WATCH_LIST_ID = '1406'
$env:POST_LOOKBACK_DAYS = '14'
$env:POST_LIMIT = '5'

Write-Host 'Starting full test pipeline with the 5 newest matching posts.'
Write-Host 'This run invokes paid OpenAI embedding, extraction, and summarization calls.'

Push-Location $backendDirectory
try {
    & mvn -q compile exec:java '-Dexec.args=--embed --extract --summarize'
    if ($LASTEXITCODE -ne 0) {
        throw "Pipeline failed with exit code $LASTEXITCODE"
    }
} finally {
    Pop-Location
}

Write-Host 'Test pipeline completed successfully.'
