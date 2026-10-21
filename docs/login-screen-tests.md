# Local login screen tests

Run from PowerShell with the project's Android SDK (API 35) and a compatible JDK configured in `JAVA_HOME`:

```powershell
.\scripts\test-login.ps1
```

Include repository, initialization-controller, ViewModel, and secure-storage recovery tests:

```powershell
.\scripts\test-login.ps1 -IncludeUnitTests
```

After dependencies have been downloaded, add `-Offline` to disable Gradle dependency downloads. Robolectric's Android runtime must also already be cached in the local Maven repository; the first run downloads it separately.

The HTML report is `app/build/reports/tests/testDebugUnitTest/index.html`. Gradle can mark an unchanged successful run `UP-TO-DATE`; append `--rerun-tasks` to the equivalent Gradle command when a fresh execution is needed:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests 'com.graphenelab.photosync.ui.login.LoginScreenTest' --init-script scripts/cloud-space-tests.init.gradle --console=plain
```

## What runs

`LoginScreenTest` renders the real `LoginRoute` and `LoginScreen`, using the real `LoginViewModel` and `CloudSpaceInitializationController`. It clicks existing buttons and delivers OAuth results through the activity-result registry used by the screen. It does not set private UI state or duplicate the controller's decision logic.

The suite checks:

- Sign-in, QR navigation, and the privacy dialog; canceled or failed IAM sign-in.
- Distinct token-exchange, status-check, credentials, and initialization-request loading messages.
- Pending/in-progress polling and scheduled recovery without a premature poll or manual setup request.
- Exactly one initialization POST for rapid retry taps; lost responses and timeouts resume with a status GET.
- Status/credentials failures, support-required state, and session expiry with token clearing.
- Recovery when credentials become unavailable after READY, and exactly-once credential handoff.
- Zero-knowledge cancellation with a late response, and cancellation when the route's ViewModel is cleared.

OAuth, secure token storage, and backend repositories are mocked. Repository/OAuth mocks are strict, so unstubbed operations fail the test. An OkHttp constructor mock rejects accidental real HTTP calls, and the suite verifies this guard. A plain test `Application` replaces `MyApp`, so production Hilt/native-service startup does not run. The API 35 Android runtime is supplied by Robolectric; no emulator, device, APK installation, IAM account, or live backend is needed.

These tests verify rendered text, progress indicators, existing handlers, request counts, and navigation callbacks. They do not verify screenshot appearance, the real IAM browser, camera scanning, passphrase setup, or an actual connection to cloud storage. Those still need device/integration testing.

## Existing test compilation failures

The focused commands explicitly load `scripts/cloud-space-tests.init.gradle`. This temporarily excludes three older test source files that already fail compilation: `FullScanServiceTest.kt`, `PhotoSyncWorkerTest.kt`, and `FullScanProcessManagerTest.kt`. All other test sources remain available. The normal Gradle test task retains these files; no global exclusions or existing test deletions were introduced. Remove the focused init script once those unrelated tests have been repaired.

Compose/Robolectric tests use JUnit 4 via the Vintage engine. Existing JUnit 5 tests continue to run through Jupiter in the same Gradle task.

## Secure-storage regression tests

`SecureStorageRecoveryTest` exercises the real `TokenStorage`, `OauthTokenRepository`, and `StartupViewModel` against a token-store failure matching the device report (`AEADBadTagException` while opening encrypted preferences). Android preference files and deletion/cache behavior are real under Robolectric. Only the AndroidX encryption factory and master-key builder are replaced, because a local JVM does not reproduce the phone's hardware-backed Keystore.

The tests cover startup reaching login, saving tokens after recovery, corrupt access/refresh values, malformed keysets, concurrent readers, failed deletion, and bounded retry when encryption stays unavailable. They check that recovery preserves the session and cloud-encryption-key stores and never deletes their shared Keystore key. Persistent session encryption failures must not write a plaintext fallback. Resource checks verify exclusions for all encrypted stores and the legacy session fallback in both backup XML formats, including cloud backup and device transfer.

Token recovery deletes only `secret_shared_prefs.xml`, then retries encrypted storage once. Unavailable token reads mean signed out; unavailable token writes fail visibly. Session recovery can reset only its own unreadable preferences and also retries once. The cloud-encryption-key store is never automatically reset by either recovery path. This does not recover a cloud encryption key that was already lost or made unreadable before the fix.

To run just these regression tests:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests 'com.graphenelab.photosync.data.local.secure.SecureStorageRecoveryTest' --init-script scripts/cloud-space-tests.init.gradle --console=plain
```

Physical-device validation of uninstall/reinstall and restore behavior remains necessary. Backup exclusions prevent future backups/transfers of these files; the local recovery path handles an unreadable token store that is already present.
