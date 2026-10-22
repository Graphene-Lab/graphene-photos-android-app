# Folders screen tests

Run from PowerShell with the project's Android SDK (API 35) and a compatible JDK configured in `JAVA_HOME`:

```powershell
.\scripts\test-folders.ps1
```

After dependencies have been downloaded, add `-Offline` to disable Gradle dependency downloads. Robolectric's Android runtime must already be cached in the local Maven repository; the first run downloads it separately.

The `--init-script scripts/cloud-space-tests.init.gradle` is reused on purpose: it keeps excluding the three pre-existing test files that do not compile yet, so `compileDebugUnitTestKotlin` can finish. Without it the whole `testDebugUnitTest` task fails before these tests run.

The HTML report is `app/build/reports/tests/testDebugUnitTest/index.html`. Gradle can mark an unchanged successful run `UP-TO-DATE`; append `--rerun-tasks` to the equivalent Gradle command when a fresh execution is needed:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests 'com.graphenelab.photosync.ui.folders.*' --init-script scripts/cloud-space-tests.init.gradle --console=plain
```

## Why these tests exist

Opening **Choose folder** on the sync screen, or **Choose Folders** in Profile, before pressing **Start** used to query MediaStore while the app had no photo permission. An unpermitted MediaStore query does not fail: it returns an empty cursor, so the user saw **No photo folders found**, and the **manage photo permissions** action was hidden inside the non-empty branch. Folder selection must work on its own, so the screen now checks access first and asks for it on the spot.

## What runs

`FoldersViewModelTest` drives the real `FoldersViewModel` with the media store, the sync repository and the permission manager replaced by fakes. The test sets the access level and then calls the same methods the screen calls, exactly like the platform callback does in production.

`FoldersScreenTest` renders the real `FoldersScreen` inside a `ComponentActivity` and clicks the real buttons. A fake permission manager supplies the access level, so no real permission dialog is opened. Neither test pokes at private UI state.

## What they check

- With no access, the permission explanation is shown and the media store is never queried; the automatic request happens only once, while an explicit tap still issues a new one.
- Granting from the prompt lists the folders straight away and the explanation disappears.
- A grant made in the app settings is picked up by the refresh that runs when returning from settings.
- Partial "Select photos" access keeps the limited-access notice, lists only what is really visible, and stays reachable through the manage action even when nothing is visible.
- The plain "No photo folders found" message is reserved for a successful query with full access and no photo folder.
- Folders that were already selected survive a permission change, and toggling a folder saves the new selection.
- The scan-setup start button appears only once folders are visible, and pressing it starts the full scan exactly once.

## Still to verify on a physical device

These tests replace the system permission dialog with the callback the launcher uses, so they cannot prove that the real dialog appears. Before shipping, run on a device or emulator: install fresh (or revoke photo/media access) with photos present, open **Choose folder** from the sync screen and **Choose Folders** from Profile, confirm the system dialog appears without pressing Start, allow it and confirm the folders list appears, then deny it, re-enter, and confirm the explanation and the settings action are reachable, and finally grant from the app settings and confirm the list refreshes. Repeat on Android 13 and on Android 14+ with partial "Select photos" access.
