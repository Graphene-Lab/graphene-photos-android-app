# Local login screen tests

Run from PowerShell with the project's Android SDK (API 35) and a compatible JDK configured in `JAVA_HOME`:

```powershell
.\scripts\test-login.ps1
```

Include repository, initialization-controller, and ViewModel tests:

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
