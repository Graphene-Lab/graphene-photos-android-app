param(
    [switch]$Offline
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$gradleArguments = @(
    ':app:testDebugUnitTest',
    '--tests', 'com.graphenelab.photosync.ui.folders.FoldersScreenTest',
    '--tests', 'com.graphenelab.photosync.ui.folders.FoldersViewModelTest',
    '--init-script', (Join-Path $PSScriptRoot 'cloud-space-tests.init.gradle'),
    '--console=plain'
)
if ($Offline) {
    $gradleArguments += '--offline'
}

Push-Location $projectRoot
try {
    & (Join-Path $projectRoot 'gradlew.bat') @gradleArguments
    if ($LASTEXITCODE -ne 0) {
        throw "Folders tests failed (Gradle exit code $LASTEXITCODE)."
    }
} finally {
    Pop-Location
}
