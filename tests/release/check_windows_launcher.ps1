# Exercise the real .cmd -> Windows PowerShell launcher with fail-closed gh/Gradle shims.
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$scratchRoot = Join-Path $projectRoot '.release-work'
[IO.Directory]::CreateDirectory($scratchRoot) | Out-Null
$fixture = Join-Path $scratchRoot ('release launcher test ' + [Guid]::NewGuid().ToString('N'))
$originalPath = $env:PATH
$originalLog = $env:PHOTOS_LAUNCHER_TEST_LOG
$originalExit = $env:PHOTOS_LAUNCHER_TEST_EXIT
$originalPrompt = $env:GH_PROMPT_DISABLED
$testCount = 0

function Assert-Launcher {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw $Message }
}

try {
    [IO.Directory]::CreateDirectory((Join-Path $fixture 'scripts')) | Out-Null
    $mockBin = Join-Path $fixture 'mock commands'
    [IO.Directory]::CreateDirectory($mockBin) | Out-Null
    foreach ($launcherName in @('release', 'build')) {
        Copy-Item -LiteralPath (Join-Path $projectRoot "$launcherName.cmd") -Destination $fixture
        Copy-Item -LiteralPath (Join-Path $projectRoot "scripts/$launcherName.ps1") -Destination (Join-Path $fixture "scripts/$launcherName.ps1")
    }
    $capture = @'
$call = [ordered]@{ tool = $args[0]; arguments = @($args | Select-Object -Skip 1); directory = (Get-Location).Path; promptDisabled = $env:GH_PROMPT_DISABLED }
$call | ConvertTo-Json -Depth 4 -Compress | Set-Content -LiteralPath $env:PHOTOS_LAUNCHER_TEST_LOG -Encoding UTF8
exit [int]$env:PHOTOS_LAUNCHER_TEST_EXIT
'@
    $capturePath = Join-Path $mockBin 'capture.ps1'
    [IO.File]::WriteAllText($capturePath, $capture)
    $shim = @'
@echo off
"%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe" -NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -File "{0}" {1} %*
exit /b %ERRORLEVEL%
'@
    [IO.File]::WriteAllText((Join-Path $mockBin 'gh.cmd'), ($shim -f $capturePath, 'gh').Replace("`n", "`r`n"))
    [IO.File]::WriteAllText((Join-Path $fixture 'gradlew.bat'), ($shim -f $capturePath, 'gradle').Replace("`n", "`r`n"))
    # Exclude installed gh/Ruby/JDK: nothing can reach an actual workflow or Android build.
    $env:PATH = "$mockBin;$env:SystemRoot\System32;$env:SystemRoot"
    $env:PHOTOS_LAUNCHER_TEST_LOG = Join-Path $fixture 'invocation.json'
    $env:PHOTOS_LAUNCHER_TEST_EXIT = '0'
    $env:GH_PROMPT_DISABLED = 'original-setting'
    function Invoke-LauncherCheck {
        param([string[]]$CommandArguments, [string]$Tool, [string[]]$ExpectedArguments, [int]$ExitCode = 0,
              [ValidateSet('release', 'build')][string]$Launcher = 'release')
        $launcherPath = Join-Path $fixture "$Launcher.cmd"
        if (Test-Path -LiteralPath $env:PHOTOS_LAUNCHER_TEST_LOG) { Remove-Item -LiteralPath $env:PHOTOS_LAUNCHER_TEST_LOG }
        # Windows PowerShell treats redirected native stderr as an ErrorRecord; expected failures must be captured.
        $ErrorActionPreference = 'Continue'
        $output = & $launcherPath @CommandArguments 2>&1
        $actualExit = $LASTEXITCODE
        $ErrorActionPreference = 'Stop'
        Assert-Launcher ($actualExit -eq $ExitCode) "Wrong exit code for $($CommandArguments -join ' '): $actualExit. $output"
        if ($Tool) {
            Assert-Launcher (Test-Path -LiteralPath $env:PHOTOS_LAUNCHER_TEST_LOG) 'Expected only a mocked external command.'
            $call = Get-Content -LiteralPath $env:PHOTOS_LAUNCHER_TEST_LOG -Raw | ConvertFrom-Json
            Assert-Launcher ($call.tool -ceq $Tool) 'Incorrect tool selected.'
            Assert-Launcher (($call.arguments -join '|') -ceq ($ExpectedArguments -join '|')) "Incorrect arguments: $($call.arguments -join ' ')"
            Assert-Launcher ($call.directory -eq $fixture) 'Launcher did not use the repository directory containing spaces.'
            if ($Tool -eq 'gh') { Assert-Launcher ($call.promptDisabled -eq '1') 'CLI prompts were not disabled.' }
        } else {
            Assert-Launcher (-not (Test-Path -LiteralPath $env:PHOTOS_LAUNCHER_TEST_LOG)) 'Invalid/help input invoked an external command.'
        }
        Assert-Launcher ($env:GH_PROMPT_DISABLED -eq 'original-setting') 'Caller environment was changed.'
        $script:testCount++
    }

    $dispatch = @('workflow', 'run', 'android-play.yml', '--repo', 'Graphene-Lab/graphene-photos-android-app', '--ref', 'master')
    foreach ($destination in @('github', 'play', 'both')) {
        $expected = $dispatch + @('-f', 'action=release', '-f', "destination=$destination")
        if ($destination -ne 'github') { $expected += @('-f', 'play_track=internal') }
        Invoke-LauncherCheck @($destination) 'gh' $expected
    }
    foreach ($destination in @('play', 'both')) {
        Invoke-LauncherCheck @($destination, '--play-track', 'production') 'gh' ($dispatch + @('-f', 'action=release', '-f', "destination=$destination", '-f', 'play_track=production'))
    }
    Invoke-LauncherCheck @('promote') 'gh' ($dispatch + @('-f', 'action=promote'))
    Invoke-LauncherCheck @('promote', '21') 'gh' ($dispatch + @('-f', 'action=promote', '-f', 'version_code=21'))
    Invoke-LauncherCheck @('internal', '39974314347') 'gh' ($dispatch + @('-f', 'action=internal', '-f', 'build_run_id=39974314347'))
    Invoke-LauncherCheck @('verify') 'gh' ($dispatch + @('-f', 'action=verify'))
    Invoke-LauncherCheck @('ci') 'gh' ($dispatch + @('-f', 'action=build')) -Launcher build
    foreach ($entry in @{ apk = 'buildLocalApk'; aab = 'buildLocalAab'; both = 'buildLocalBoth' }.GetEnumerator()) {
        Invoke-LauncherCheck @($entry.Key) 'gradle' @($entry.Value) -Launcher build
    }
    Invoke-LauncherCheck @('bump') 'gradle' @('versionBump')
    Invoke-LauncherCheck @('bump', '--version-name', '1.2.0', '--version-code', '30') 'gradle' @('versionBump', '-PversionName=1.2.0', '-PversionCode=30')
    Invoke-LauncherCheck @() '' @()
    Invoke-LauncherCheck @('--help') '' @()
    Invoke-LauncherCheck @() '' @() -Launcher build
    Invoke-LauncherCheck @('--help') '' @() -Launcher build

    $invalid = @(
        @('promote', '0'), @('promote', '2100000001'), @('promote', '21', '22'),
        @('promote', '21;whoami'), @('internal'), @('internal', 'abc'),
        @('verify', '--play-track', 'production'), @('github', '--play-track', 'production'),
        @('both', '--play-track', 'beta'), @('both', '--play-track'),
        @('bump', '--version-code', 'bad'), @('bump', '--version-name', '1.2'),
        @('bump', '--version-code', '30', '--version-code', '31'), @('bump', '--version-name'),
        @('apk'), @('build'), @('release:promote', '21'), @('unknown')
    )
    foreach ($invalidArguments in $invalid) { Invoke-LauncherCheck $invalidArguments '' @() -ExitCode 1 }
    foreach ($invalidArguments in @(@('apk', 'promote'), @('both', '--play-track', 'production'), @('promote', '21'), @('bump'))) {
        Invoke-LauncherCheck $invalidArguments '' @() -ExitCode 1 -Launcher build
    }
    $env:PHOTOS_LAUNCHER_TEST_EXIT = '23'
    Invoke-LauncherCheck @('verify') 'gh' ($dispatch + @('-f', 'action=verify')) -ExitCode 23
    Invoke-LauncherCheck @('both') 'gradle' @('buildLocalBoth') -ExitCode 23 -Launcher build
    Invoke-LauncherCheck @('ci') 'gh' ($dispatch + @('-f', 'action=build')) -ExitCode 23 -Launcher build
    $env:PATH = "$env:SystemRoot\System32;$env:SystemRoot"
    Invoke-LauncherCheck @('promote', '21') '' @() -ExitCode 1
    Invoke-LauncherCheck @('ci') '' @() -ExitCode 1 -Launcher build
    Write-Output "Windows launchers passed $testCount checks: separate release/build commands, mocked dispatch/Gradle, internal default, explicit production, exact/latest promotion, validation, paths with spaces and exit codes."
} finally {
    $env:PATH = $originalPath
    $env:PHOTOS_LAUNCHER_TEST_LOG = $originalLog
    $env:PHOTOS_LAUNCHER_TEST_EXIT = $originalExit
    $env:GH_PROMPT_DISABLED = $originalPrompt
    # Delete only this test's verified disposable directory inside .release-work.
    if (Test-Path -LiteralPath $fixture) {
        $resolvedFixture = [IO.Path]::GetFullPath($fixture)
        $allowedRoot = [IO.Path]::GetFullPath($scratchRoot).TrimEnd('\') + '\'
        if (-not $resolvedFixture.StartsWith($allowedRoot, [StringComparison]::OrdinalIgnoreCase)) { throw 'Unexpected launcher test directory.' }
        Remove-Item -LiteralPath $resolvedFixture -Recurse -Force
    }
}
