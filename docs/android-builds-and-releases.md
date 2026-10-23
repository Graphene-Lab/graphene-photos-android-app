# Android builds and releases

Photos is a native Android Gradle project. It uses the checked-in Gradle 8.11.1 wrapper, AGP 8.10.1, Kotlin 2.0.21, Java 21 on CI (Java/Kotlin bytecode target 11), Android platform/build tools 35, and the `release` variant. Modules are `app` and `communicationLib`; there are no product flavors. The existing applicationId is **com.graphenelab.photosync**, including its OAuth redirect scheme. Debug adds `.debug` to the applicationId.

Gradle owns building, testing and version bumps. Ruby 3.3 and Fastlane own release automation: `scripts/release.rb` provides shared version/note validation, GitHub/artifact helpers and optional Ruby command wrappers; Fastlane handles Play upload and promotion. The workflow and release checks use this same Ruby runtime. Windows has two launchers: `.\release` for release preparation/publishing and `.\build` for builds. Both use existing GitHub CLI/Gradle commands and need no local Ruby or npm. Existing Google identity, permissions, signing keys and GitHub secrets need no changes.

Publishing uses **Actions → Android Google Play → Run workflow** on `master` in `Graphene-Lab/graphene-photos-android-app` (repository ID `996638361`). The workflow has only `workflow_dispatch`. Commits, version bumps and tag pushes do not trigger publishing. The default action is **build** (save an AAB without publishing); Play uploads default to **internal**. Ordinary local builds never dispatch Actions.

## Local builds

Use JDK 21, the Android SDK with API/build tools 35, and the project's existing signing/configuration files. Set `JAVA_HOME` to that JDK; the Gradle Java toolchain compatibility target remains 11. These commands use the current local files, including uncommitted edits:

| Native PowerShell command | Windows shortcut | Result |
| --- | --- | --- |
| `.\gradlew.bat buildLocalApk` | `.\build apk` | `app/build/outputs/apk/release/app-release.apk` |
| `.\gradlew.bat buildLocalAab` | `.\build aab` | `app/build/outputs/bundle/release/app-release.aab` |
| `.\gradlew.bat buildLocalBoth` | `.\build both` | Both files above |

On Windows, `build.cmd` calls `scripts/build.ps1` and `release.cmd` calls `scripts/release.ps1`, using the built-in Windows PowerShell 5.1. Run `.\build --help` or `.\release --help` for each command list; both also accept `help`, `-h`, or no arguments. The launchers run local tasks from their own repository directory, including when the path contains spaces. They reject unknown commands/options and return the GitHub CLI/Gradle exit code to the terminal. Local builds belong to `build`; publishing/promotion and version preparation belong to `release`.

On Linux/macOS, use `bash ./gradlew` instead of `.\gradlew.bat`. The existing `ruby scripts/release.rb <command>` interface remains available on every supported Ruby platform; put Ruby 3.3 on `PATH` for those optional wrappers. They use standard libraries and need no installed Fastlane gems. No Expo, React Native, npm, JavaScript installation or Firebase configuration is introduced.

Use Photos' existing **registered upload keystore**, not a newly generated key. Existing `keystore.properties` takes priority over environment variables:

| Local property | Environment alternative |
| --- | --- |
| `storeFile` | `ANDROID_KEYSTORE_FILE` |
| `storePassword` | `ANDROID_KEYSTORE_PASSWORD` |
| `keyAlias` | `ANDROID_KEY_ALIAS` |
| `keyPassword` | `ANDROID_KEY_PASSWORD` |

`storeFile` is absolute or relative to the project root. `keystore.properties.example` describes the format. Keep real keystores/password files ignored. `application.properties` supplies `BASE_URL_RELEASE`, `STRIPE_PUBLIC_KEY_RELEASE`, and the existing debug values (`BASE_URL_DEBUG`, `STRIPE_PUBLIC_KEY_DEBUG`). The new local shortcuts and CI's `-PrequireReleaseConfiguration=true` reject missing release signing or blank release application values. Existing ordinary Gradle tasks keep their signing behavior. Local APKs use the upload key, which can differ from the Play app signing key; use the Play-generated APK for distribution alongside Play installations.

Local and build-only commands compile without completed release notes. The shared `.\build` shortcuts above call the existing tracked `buildLocalApk`, `buildLocalAab` and `buildLocalBoth` Gradle tasks directly; they add no second Android build implementation. Set `JAVA_HOME` for the local toolchain, and run `.\gradlew.bat clean` separately when a clean build is needed.

## Prepare a release

The preparation order is **bump version → write/review both note files → commit/push → explicitly start the selected workflow**. Notes are written by the developer or coding agent during preparation. CI reads committed notes and never invents release text.

```powershell
# Defaults to patch versionName + 1 and versionCode + 1.
.\release bump
# Equivalent native Gradle task:
.\gradlew.bat versionBump

# Explicit larger version/code when needed:
.\release bump --version-name 1.2.0 --version-code 30
.\gradlew.bat versionBump -PversionName=1.2.0 -PversionCode=30
```

The native `versionBump` task updates literal `versionName`/`versionCode` values in `app/build.gradle.kts`. Both must increase; code must remain at most 2100000000. It creates separate UTF-8 templates, preserving any existing files for the new version. From the inspected 1.1.8 / 20 source, a default bump creates:

| Destination | File | Expected text |
| --- | --- | --- |
| GitHub | `release-notes/1.1.9.md` | Detailed Markdown, useful changes/fixes/links |
| Play | `fastlane/metadata/android/en-US/changelogs/21.txt` | Short plain-text user summary, maximum 500 Unicode characters |

Replace `RELEASE_NOTES_TODO` with reviewed text. Blank, comment-only or unfinished templates (`TODO`, `TBD`, `PLACEHOLDER`) are rejected. GitHub and Play text can differ. Add optional translations as `fastlane/metadata/android/<locale>/changelogs/<versionCode>.txt`, for example `fr-FR/changelogs/21.txt`. Every supplied exact-code locale is validated, including the 500 Unicode-character limit; an emoji counts as one Unicode code point. Historical files and `default.txt` are never substitutes.

Optionally validate locally without publishing using Ruby; CI always validates the committed notes before creating a tag or publishing:

```powershell
ruby scripts/release.rb notes github
ruby scripts/release.rb notes play
ruby scripts/release.rb notes both
```

Then review changes and commit/push the intended app changes, version values and completed notes to `master`. Stage only intended files. No Git operations happen in the bump or notes commands. This implementation does not bump, commit or push your current version for you.

A new upload needs an unused versionCode. The upload lane rejects codes at or below current internal/production codes; Play also enforces global uniqueness, including codes previously uploaded to other tracks, drafts or removed releases. If Play accepted a bundle, do not upload it again. Promotion reuses an existing uploaded bundle and its code.

## All workflow choices and local dispatch commands

Install/authenticate the GitHub CLI (`gh auth login`) before using dispatch wrappers. Windows release dispatch commands and `.\build ci` invoke `gh workflow run android-play.yml --repo Graphene-Lab/graphene-photos-android-app --ref master` with the selected inputs, without starting local Ruby or Gradle. They use GitHub's pushed `master` commit, not local/unpushed files, and do not build, tag, commit or publish locally. Run `.\release --help` and `.\build --help` for the command lists. The optional Ruby interface retains its original prefixes: `ruby scripts/release.rb release:promote 21` corresponds to `.\release promote 21`, `release:build` corresponds to `.\build ci`, `version:bump` corresponds to `.\release bump`, and `build:local:apk|aab|both` corresponds to `.\build apk|aab|both`.

| Choice | Command | Actions inputs |
| --- | --- | --- |
| GitHub Release page only, no Android build | `.\release github` | `action=release`, `destination=github` |
| Build/upload to internal only | `.\release play` | `action=release`, `destination=play`, `play_track=internal` |
| GitHub page first, internal upload, signed APK attachment | `.\release both` | `action=release`, `destination=both`, `play_track=internal` |
| Direct production only | `.\release play --play-track production` | `action=release`, `destination=play`, `play_track=production` |
| GitHub page first, direct production, signed APK attachment | `.\release both --play-track production` | `action=release`, `destination=both`, `play_track=production` |
| Promote latest internal without rebuilding | `.\release promote` | `action=promote`, blank `version_code` |
| Promote exact internal code | `.\release promote 21` | `action=promote`, `version_code=21` |
| Verify configuration and read access, no publishing | `.\release verify` | `action=verify` |
| Check/build only, save signed AAB artifact | `.\build ci` | `action=build` |
| Upload an existing successful CI build to internal | `.\release internal 123456789` | `action=internal`, `build_run_id=123456789` |

`play_track` applies only to release with `destination=play/both`. Saved-build reuse always targets internal. `build_run_id` applies only to internal reuse; `version_code` applies only to promotion. The numeric examples above are illustrative, not known valid Play codes/run IDs. All runs serialize in one Photos concurrency group.

Direct production runs the same checks and single AAB build as internal. It skips Play testing tracks and requests a completed production release. Google review, production eligibility, policy requirements and managed publishing still govern availability. A successful workflow does not guarantee immediate public availability. Review-related API errors remain visible; the lane does not silently retry with deferred-review publishing.

## Release sequence, tags and retries

The workflow first runs the mocked release-tooling checks and isolated native Gradle task checks. A new Play build then runs the existing focused app unit tests, `communicationLib` tests, release lint, and a single `:app:bundleRelease` invocation. No second APK build runs in CI.

Before creating a version tag or publishing anything, **GitHub** requires the versionName Markdown notes, **Play** requires the exact versionCode English Play notes and supplied translations, and **Both** requires all of these. For Play destinations the workflow also checks Photos' Google identity variables and, for Both, a valid Play app signing pin.

For a release, `v<versionName>` points directly at the exact workflow `github.sha`. An existing lightweight or annotated tag is reusable only when it resolves to the same commit; tags are never moved. To release different source after a tag exists, bump the version again.

For Both, the order is:

1. Validate version and all required notes.
2. Create/reuse the exact-commit tag and publish the GitHub page with the prepared Markdown followed by GitHub's generated changelog.
3. Restore/verify Photos' existing upload key and app configuration; run native checks and build one signed AAB.
4. Save the signed AAB and provenance manifest as Actions artifacts, then authenticate through short-lived OIDC credentials.
5. Upload that AAB and its exact versionCode Play notes to the selected track.
6. Poll Google's generated APK API for the Photos app signing certificate's **universal** APK (up to ten minutes), download it and verify the actual file.
7. Verify `apksigner` succeeds and reports exactly Photos' configured Play signing SHA-256. Use `aapt2` to check package, versionName and versionCode against the same tagged release, and reject split/debug APKs. Only then attach `Graphene-Photos-<versionName>.apk` to the GitHub Release.

An existing nonempty GitHub description is preserved on retry. A matching draft is published with its nonempty description preserved; a blank description receives the prepared Markdown plus generated changelog. Identical already-attached APK assets are reused using size and SHA-256 digest; conflicting or incomplete assets are never overwritten. GitHub-only makes no Play requests and does not compile Android. Play-only creates the version tag but no GitHub Release page.

If GitHub publication fails, Both stops before building/uploading Android. A later failure leaves the GitHub page published. A retry is valid only from the same tagged source and only if Play has not already accepted that versionCode. If upload succeeded and APK processing/attachment later failed, do not rerun the full release: inspect the failure and accepted Play state first. This workflow intentionally has no separate attachment/recovery mode or additional publishing workflows.

Promotion queries current Play tracks rather than local version values/tags, selects the highest internal code by default or the exact supplied code, rejects an empty internal track, missing selected version, and a production version at or above the selected code. Fastlane promotes the selected original Play release, retaining its stored release notes; no local note files, new bundle, new tag or GitHub page are involved.

## Build-only and existing-build upload

`release:build` restores signing/app configuration, runs native checks and saves a signed AAB. It creates no tag, GitHub page, Play edit or upload and needs no Google authentication. `release:verify` validates the upload key and app configuration plus OIDC/Play track read access; it creates no tag, compiles no app and never commits a Play release. Fastlane reads tracks through temporary Play edits, which it aborts without committing. Read access does not prove write/release permissions; those are exercised only by a later authorized publishing run.

Outputs for every successful CI build:

- Artifact name: `graphene-photos-<full source commit SHA>`, retained for **14 days** under the run's **Artifacts** section.
- `app-release.aab`: signed with the registered Photos upload key.
- `release-build.json`: Photos package/versionName/versionCode, source SHA and AAB SHA-256 digest.
- Native test reports remain in `app/build/reports/tests/testDebugUnitTest/`; lint report in `app/build/reports/lint-results-release.html` during the job. They are not GitHub Release assets.

Commit completed Play notes **before** starting a build intended for later publishing. Build-only may succeed without them, but that build cannot later be uploaded through artifact reuse if the original commit lacks completed notes. Editing today's notes cannot repair its original source.

`.\release internal <run-id>` accepts only a completed successful manual run of this workflow, from this repository's master branch and original head repository. It selects exactly one unexpired named artifact. It reads `app/build.gradle.kts` and every supplied exact-code locale from the original source commit through GitHub, validates them, downloads with GitHub artifact digest verification set to `error`, and checks the AAB digest/provenance manifest. It skips Java/Android setup, signing secrets and compilation. It uploads using the original build's package/version/notes. It creates no version tag or GitHub page.

## Authentication setup (perform one step at a time)

Local inspection verified the Photos repository ID/default branch and native application identity. During guided setup, Cloud Shell output confirmed the existing ACTIVE provider and expected mappings/owner condition, creation of `publish-photos`, its Photos-only workload identity binding, and all four required enabled APIs. The supplied upload SHA-256 was checked against Photos' existing local keystore: its private key unlocks and the certificate matches. The Play app signing pin below is supplied from Console in the requested order; actual APK verification happens during Both. GitHub has the four required repository variables and five required repository secrets under the correct tabs; their names and the public variable values were checked without exposing private values. The manually authorized `verify` run checks secret contents and live Play access after the implementation is committed and pushed. Publishing requires a separately selected release action.

### 1. Inspect the existing provider before reusing it

Use [Google Cloud Workload Identity Federation](https://console.cloud.google.com/iam-admin/workload-identity-pools?project=graphene-play-publishing), or run this read-only command in Cloud Shell:

```sh
gcloud iam workload-identity-pools providers describe github --project=graphene-play-publishing --location=global --workload-identity-pool=github-actions --format=json
```

Verify project number `117253714562`, provider `projects/117253714562/locations/global/workloadIdentityPools/github-actions/providers/github`, ACTIVE/enabled state, issuer `https://token.actions.githubusercontent.com`, mappings `google.subject=assertion.sub`, `attribute.repository_id=assertion.repository_id`, `attribute.repository_owner_id=assertion.repository_owner_id`, and owner condition `assertion.repository_owner_id == '264203613'`. Do not overwrite the shared provider or alter Explorer's binding. If its configuration differs, review the difference before reuse.

### 2. Create a separate Photos service account and repository-only binding

After step 1 is reviewed, the intended new account is `publish-photos@graphene-play-publishing.iam.gserviceaccount.com`. No service-account private key is needed. The account needs no broad Google Cloud project role merely to use Google Play; permissions are granted within Play Console. Inspect an existing same-named account before reusing it.

For an authorized administrator, the setup commands are:

```sh
gcloud iam service-accounts create publish-photos --project=graphene-play-publishing --display-name='Graphene Photos Play publishing'
gcloud iam service-accounts add-iam-policy-binding publish-photos@graphene-play-publishing.iam.gserviceaccount.com --project=graphene-play-publishing --role=roles/iam.workloadIdentityUser --member='principalSet://iam.googleapis.com/projects/117253714562/locations/global/workloadIdentityPools/github-actions/attribute.repository_id/996638361'
```

Only Photos repository ID `996638361` should be a federation member of this account. Never reuse Explorer's service account, package, repository ID or key. Confirm the Android Publisher, IAM, IAM Credentials and Security Token Service APIs are enabled in `graphene-play-publishing`. If not, an administrator can enable them after review:

```sh
gcloud services enable androidpublisher.googleapis.com iam.googleapis.com iamcredentials.googleapis.com sts.googleapis.com --project=graphene-play-publishing
```

With gcloud installed/authenticated locally, run `ruby scripts/verify-google-identity.rb`. It performs only describe/list/get-policy requests and validates the project, shared provider, Photos account, exact repository-only binding and enabled APIs. `--configured` is the workflow's variable check and does **not** claim a live IAM policy audit.

### 3. Grant Photos-only Google Play permissions

In **Play Console → Users and permissions**, invite the Photos service-account email. Under **App permissions**, add **Graphene Photos** (`com.graphenelab.photosync`) only. Grant **View app information (read-only)**, **Release apps to testing tracks**, and **Release to production, exclude devices and use Play app signing** for direct production and promotion. Do not grant account-wide administration, financial access or access to other apps. Existing app setup/Play App Signing must be complete. [Google's permission descriptions](https://support.google.com/googleplay/android-developer/answer/9844686).

### 4. Obtain Photos' two public certificate fingerprints

Open Photos' **App signing** page in Play Console. Current navigation is **Protected with Play → Play Store distribution → Go to Play app signing**; some Console layouts use **App integrity → App signing**. Copy the **SHA-256 certificate fingerprint** separately from:

- **App signing key certificate** → `PLAY_APP_SIGNING_SHA256` (verifies the downloaded APK).
- **Upload key certificate** → `ANDROID_UPLOAD_CERT_SHA256` (verifies the existing local/CI upload keystore).

These may differ, which is normal with Play App Signing. Do not reset/replace either key or use Explorer's certificate. If Play has multiple signing certificates after a key upgrade, review which certificate signs the generated universal APK before selecting the pin; the workflow fails on a mismatch instead of falling back. [Google's signing-key explanation](https://support.google.com/googleplay/android-developer/answer/9842756).

### 5. Enter GitHub variables and secrets individually

In this Photos repository's **Settings → Secrets and variables → Actions**, add:

| Repository variable | Value |
| --- | --- |
| `GCP_WORKLOAD_IDENTITY_PROVIDER` | `projects/117253714562/locations/global/workloadIdentityPools/github-actions/providers/github` |
| `GCP_SERVICE_ACCOUNT` | `publish-photos@graphene-play-publishing.iam.gserviceaccount.com` |
| `PLAY_APP_SIGNING_SHA256` | `DE:7F:A7:35:2C:64:F9:A8:32:E9:72:E7:61:55:1F:7A:46:EC:F6:F7:5E:AA:10:E0:87:BD:D0:38:DD:D2:D0:62` |
| `ANDROID_UPLOAD_CERT_SHA256` | `CE:B7:8C:E5:F9:77:4E:DD:F7:31:86:43:E4:02:85:28:84:BB:87:56:0E:8E:77:A7:7B:68:3D:71:3E:CD:65:E0` |

| Repository secret | Value |
| --- | --- |
| `ANDROID_UPLOAD_KEYSTORE_BASE64` | Base64 of the existing registered Photos upload keystore |
| `ANDROID_KEYSTORE_PASSWORD` | Existing keystore store password |
| `ANDROID_KEY_ALIAS` | Existing private-key alias |
| `ANDROID_KEY_PASSWORD` | Existing private-key password |
| `APPLICATION_PROPERTIES` | Complete Photos `application.properties`, including release and debug values |

For the existing upload key, prepare base64 **locally**, avoiding chat/logs:

```powershell
# Replace the path with Photos' existing storeFile value; do not generate a new key.
[Convert]::ToBase64String([IO.File]::ReadAllBytes('C:\path\to\existing-photos-upload.jks')) | Set-Clipboard
# Paste into the GitHub secret, then clear the clipboard after saving.
```

The Java preflight verifies the keystore opens, the alias is a private key, its key password unlocks it, and the upload certificate matches the pinned registered fingerprint. Passwords stay in environment variables, not CLI arguments. CI restores private files into the runner/temp workspace and removes the keystore/application file on completion. Generated OIDC credential files are ignored and not included in artifacts. [Fastlane supports Workload Identity credentials](https://docs.fastlane.tools/actions/upload_to_play_store/).

### 6. First authorized run

After the implementation is reviewed, committed and pushed with your approval, explicitly run `.\release verify` first. This does not publish. Once its read checks pass, prepare completed notes and a new unused versionCode, then explicitly run `.\release both` for internal testing. Test updates/installations through Play before a separate production promotion. Do not start any of these runs until you authorize that step.

## Runtime, checks and internal helper commands

GitHub-hosted Ubuntu 24.04 jobs start on fresh VMs. `ruby/setup-ruby` supplies Ruby 3.3 and Bundler before running any release helper, restores the cache keyed by `Gemfile.lock`, and installs locked gems on a cache miss. Fastlane calls the shared Ruby version/note parser directly, without launching another language runtime. `gradle/actions/setup-gradle` caches Gradle dependencies. Java 21 and Android SDK 35 match Photos' native toolchain; no Explorer NDK/toolchain settings are copied.

Focused checks (publishing and CLI dispatch are mocked):

```powershell
# Windows shortcut integration: disposable repository and mocked gh/Gradle, no Ruby needed.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tests/release/check_windows_launcher.ps1
# Requires Ruby with minitest and rexml (both included in the locked bundle).
ruby tests/release/run.rb
ruby tests/release/check_gradle_tasks.rb
ruby tests/release/check_upload_key.rb
# With Ruby dependencies installed:
bundle install
bundle exec fastlane lanes
# If actionlint is installed:
actionlint .github/workflows/android-play.yml
```

The Windows launcher check runs both launchers' real `.cmd`/PowerShell code in a disposable path containing spaces. Its `PATH` contains only mock GitHub CLI/Gradle shims and Windows tools, so it cannot publish or build Android. It covers separate release/build commands, all dispatch choices, internal defaults, explicit production, latest/exact promotion, local tasks, invalid arguments, missing `gh`, and exit-code propagation. This Windows-only check is run locally; the existing Ubuntu workflow continues to run its Ruby/Fastlane and native Gradle checks.

The isolated Gradle check exercises the actual task script against a temporary miniature project, including template creation/preservation and invalid bumps; it never changes Photos' real version. Ruby/Fastlane tests fail closed on unrecognized network/actions, cover destination-specific notes and Unicode limits, current versus original build notes, immutable tags, release-description retries, AAB provenance, production selection/promotion, Play APK polling and actual-file verification rejection paths.

Native checks used before every new CI Play build:

```powershell
.\gradlew.bat :app:testDebugUnitTest :communicationLib:test :app:lintRelease --init-script scripts/cloud-space-tests.init.gradle --no-daemon --max-workers=2 -PrequireReleaseConfiguration=true
```

`scripts/cloud-space-tests.init.gradle` is the existing focused test convention. It excludes only the three pre-existing test files already documented in [login-screen-tests.md](login-screen-tests.md); no test sources are deleted. Normal Gradle test tasks retain those sources. CI still executes all other available unit tests, and release lint remains required. `app/lint-baseline.xml` records only the ten pre-existing `MissingTranslation` errors for the login strings in `src/main/res/values/strings.xml`; it does not baseline warnings or new findings. The baseline is technical debt to remove when these translations are supplied.

The internal `release.rb` commands are called by the single workflow: `plan` writes step-selection outputs; `prepare-ci` verifies/restores signing and app settings; `prepare-release` validates notes and prepares the tag/page; `record-build` writes the manifest; `resolve-build` selects provenance and original source notes; `verify-build` checks the downloaded AAB; `resolve-apk` records the immutable release context; `attach-apk` verifies/attaches the actual APK. These helpers are workflow plumbing, not separate publishing/recovery modes. Fastlane lanes are `verify_access`, `build_release`, `publish track:internal|production`, and `promote version_code:<optional-code>`; prefer the workflow wrappers so checks and source/tag guards run in order.
