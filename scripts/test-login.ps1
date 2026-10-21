param(
    [switch]$IncludeUnitTests,
    [switch]$Offline
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$gradleArguments = @(
    ':app:testDebugUnitTest',
    '--tests', 'com.graphenelab.photosync.ui.login.LoginScreenTest',
    '--init-script', (Join-Path $PSScriptRoot 'cloud-space-tests.init.gradle'),
    '--console=plain'
)
if ($IncludeUnitTests) {
    $gradleArguments += @(
        '--tests', 'com.graphenelab.photosync.ui.login.LoginViewModelTest',
        '--tests', 'com.graphenelab.photosync.manager.CloudSpaceInitializationControllerTest',
        '--tests', 'com.graphenelab.photosync.data.repository.CloudSpaceRepositoryTest'
    )
}
if ($Offline) {
    $gradleArguments += '--offline'
}

Push-Location $projectRoot
try {
    & (Join-Path $projectRoot 'gradlew.bat') @gradleArguments
    if ($LASTEXITCODE -ne 0) {
        throw "Login tests failed (Gradle exit code $LASTEXITCODE)."
    }
} finally {
    Pop-Location
}
