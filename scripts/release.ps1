# Windows release commands: GitHub CLI dispatch and native Gradle version preparation.
# Keep publishing, signing, release notes and artifact checks in the existing workflow.
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$releaseArguments = @($args)
$projectRoot = Split-Path $PSScriptRoot -Parent

function Show-ReleaseHelp {
    @'
Usage: .\release COMMAND [options]

Release workflows (pushed master; requires authenticated GitHub CLI):
  github                                GitHub Release page only
  play [--play-track TRACK]              Play upload; internal by default
  both [--play-track TRACK]              GitHub page, Play upload and verified APK
  verify                                Verify configuration/access without publishing
  internal RUN_ID                       Upload a successful CI build to internal; no rebuild
  promote [VERSION_CODE]                Promote latest/exact internal bundle to production
  TRACK is internal or explicitly selected production.

Release preparation (current local files; requires the Android/JDK toolchain):
  bump [--version-name X.Y.Z] [--version-code CODE]
                                        Bump Gradle values and preserve/create note templates

  help | --help | -h                     Show this command list

No local Ruby or npm is required. For builds, run .\build --help.
'@ | Write-Output
}

function Assert-PositiveNumber {
    param([string]$Value, [string]$Label, [switch]$VersionCode)
    if ($Value -cnotmatch '\A[1-9][0-9]*\z') {
        throw "$Label must be a positive integer."
    }
    if ($VersionCode -and ($Value.Length -gt 10 -or [long]$Value -gt 2100000000)) {
        throw "$Label must be at most 2100000000."
    }
}

try {
    if ($releaseArguments.Count -eq 0) {
        Show-ReleaseHelp
        exit 0
    }
    $command = [string]$releaseArguments[0]
    $remaining = @($releaseArguments | Select-Object -Skip 1)
    $fields = @()
    $gradleArguments = @()

    switch -CaseSensitive ($command) {
        { $_ -cin @('help', '--help', '-h') } {
            if ($remaining.Count -ne 0) { throw 'Help does not accept additional arguments.' }
            Show-ReleaseHelp
            exit 0
        }
        { $_ -cin @('github', 'play', 'both') } {
            $destination = $command
            $track = 'internal'
            if ($remaining.Count -ne 0) {
                if ($destination -ceq 'github' -or $remaining.Count -ne 2 -or
                    $remaining[0] -cne '--play-track' -or $remaining[1] -cnotin @('internal', 'production')) {
                    throw 'Use .\release play or .\release both [--play-track internal|production].'
                }
                $track = [string]$remaining[1]
            }
            $fields = @('action=release', "destination=$destination")
            if ($destination -cne 'github') { $fields += "play_track=$track" }
        }
        'verify' {
            if ($remaining.Count -ne 0) { throw "$command does not accept additional arguments." }
            $fields = @('action=verify')
        }
        'internal' {
            if ($remaining.Count -ne 1) { throw 'Use .\release internal RUN_ID.' }
            Assert-PositiveNumber ([string]$remaining[0]) 'RUN_ID'
            $fields = @('action=internal', "build_run_id=$($remaining[0])")
        }
        'promote' {
            if ($remaining.Count -gt 1) { throw 'Use .\release promote [VERSION_CODE].' }
            $fields = @('action=promote')
            if ($remaining.Count -eq 1) {
                Assert-PositiveNumber ([string]$remaining[0]) 'VERSION_CODE' -VersionCode
                $fields += "version_code=$($remaining[0])"
            }
        }
        'bump' {
            $gradleArguments = @('versionBump')
            $seen = @{}
            for ($index = 0; $index -lt $remaining.Count; $index += 2) {
                $option = [string]$remaining[$index]
                if ($option -cnotin @('--version-name', '--version-code') -or $index + 1 -ge $remaining.Count -or $seen.ContainsKey($option)) {
                    throw 'Use .\release bump [--version-name X.Y.Z] [--version-code CODE], each option once.'
                }
                $value = [string]$remaining[$index + 1]
                $seen[$option] = $true
                if ($option -ceq '--version-name') {
                    if ($value -cnotmatch '\A[0-9]+\.[0-9]+\.[0-9]+\z') { throw 'versionName must be X.Y.Z.' }
                    $gradleArguments += "-PversionName=$value"
                } else {
                    Assert-PositiveNumber $value 'versionCode' -VersionCode
                    $gradleArguments += "-PversionCode=$value"
                }
            }
        }
        default { throw "Unknown release command '$command'. Run .\release --help." }
    }

    $previousPromptSetting = $env:GH_PROMPT_DISABLED
    Push-Location -LiteralPath $projectRoot
    try {
        if ($fields.Count -gt 0) {
            $ghCommand = Get-Command gh -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
            if (-not $ghCommand) { throw 'GitHub CLI (gh) is required. Install it and run gh auth login.' }
            $dispatchArguments = @('workflow', 'run', 'android-play.yml', '--repo', 'Graphene-Lab/graphene-photos-android-app', '--ref', 'master')
            foreach ($field in $fields) { $dispatchArguments += @('-f', $field) }
            Write-Host "Requesting $($fields -join ', ') on GitHub master. Only already-pushed source is used."
            $env:GH_PROMPT_DISABLED = '1'
            & $ghCommand.Source @dispatchArguments
        } else {
            & (Join-Path $projectRoot 'gradlew.bat') @gradleArguments
        }
        $commandExitCode = $LASTEXITCODE
    } finally {
        $env:GH_PROMPT_DISABLED = $previousPromptSetting
        Pop-Location
    }
    exit $commandExitCode
} catch {
    [Console]::Error.WriteLine("Release command failed: $($_.Exception.Message)")
    exit 1
}
