"""Exercise the real native Gradle preparation tasks against an isolated miniature project."""
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
wrapper = [str(ROOT / 'gradlew.bat')] if os.name == 'nt' else ['bash', str(ROOT / 'gradlew')]
with tempfile.TemporaryDirectory(prefix='gradle-release-', dir=ROOT / '.release-work' if (ROOT / '.release-work').exists() else None) as tmp:
    project = Path(tmp)
    (project / 'settings.gradle.kts').write_text('rootProject.name = "release-task-test"\ninclude(":app")\n')
    (project / 'build.gradle.kts').write_text(f'apply(from = "{(ROOT / "gradle/release-tasks.gradle.kts").as_posix()}")\n')
    (project / 'app').mkdir()
    source = project / 'app/build.gradle.kts'
    source.write_text('// versionName = "99.0.0"\nval versionName = "fixture"\n')
    # Literal version lines can be ordinary Kotlin values in this standalone test DSL.
    source.write_text('var versionName = "fixture"\nvar versionCode = 0\nversionName = "1.1.8"\nversionCode = 20\n'
                      'tasks.register("assembleRelease")\ntasks.register("bundleRelease")\n')
    preserved = project / 'release-notes/1.1.9.md'
    preserved.parent.mkdir()
    preserved.write_text('Already reviewed GitHub notes.')
    play = project / 'fastlane/metadata/android/en-US/changelogs/21.txt'
    play.parent.mkdir(parents=True)
    play.write_text('Already reviewed Play notes.')

    def gradle(*args, success=True):
        result = subprocess.run(wrapper + ['-p', str(project), '--no-daemon', '--console=plain', *args], capture_output=True, text=True)
        if (result.returncode == 0) != success:
            raise AssertionError(result.stdout + result.stderr)
        return result.stdout + result.stderr

    gradle('versionBump')
    assert 'versionName = "1.1.9"' in source.read_text()
    assert 'versionCode = 21' in source.read_text()
    assert preserved.read_text() == 'Already reviewed GitHub notes.'
    assert play.read_text() == 'Already reviewed Play notes.'
    gradle('versionBump', '-PversionName=1.2.0', '-PversionCode=30')
    assert 'versionName = "1.2.0"' in source.read_text()
    assert 'versionCode = 30' in source.read_text()
    assert 'RELEASE_NOTES_TODO' in (project / 'release-notes/1.2.0.md').read_text()
    assert 'RELEASE_NOTES_TODO' in (project / 'fastlane/metadata/android/en-US/changelogs/30.txt').read_text()
    before = source.read_bytes()
    gradle('versionBump', '-PversionName=1.1.9', success=False)
    gradle('versionBump', '-PversionCode=bad', success=False)
    assert source.read_bytes() == before
    for task, outputs in [('buildLocalApk', ['assembleRelease']), ('buildLocalAab', ['bundleRelease']),
                          ('buildLocalBoth', ['assembleRelease', 'bundleRelease'])]:
        output = gradle(task, '--dry-run')
        assert all(f':app:{name} SKIPPED' in output for name in outputs), output
    print('Native Gradle version bump, preserved notes, fresh templates, rejected invalid bumps and all local build shortcuts passed.')
