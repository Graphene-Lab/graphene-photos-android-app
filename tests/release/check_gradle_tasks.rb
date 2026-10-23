# Exercise the actual native preparation tasks in a disposable miniature Gradle project.
require_relative '../../scripts/release'

root = PhotosRelease::ROOT
wrapper = PhotosRelease.windows? ? [File.join(root, 'gradlew.bat')] : ['bash', File.join(root, 'gradlew')]
scratch = File.directory?(File.join(root, '.release-work')) ? File.join(root, '.release-work') : nil
Dir.mktmpdir('gradle-release-', scratch) do |project|
  File.write(File.join(project, 'settings.gradle.kts'), "rootProject.name = \"release-task-test\"\ninclude(\":app\")\n")
  script = File.join(root, 'gradle/release-tasks.gradle.kts').tr('\\', '/')
  File.write(File.join(project, 'build.gradle.kts'), "apply(from = \"#{script}\")\n")
  FileUtils.mkdir_p(File.join(project, 'app'))
  source = File.join(project, 'app/build.gradle.kts')
  File.write(source, <<~KOTLIN)
    var versionName = "fixture"
    var versionCode = 0
    versionName = "1.1.8"
    versionCode = 20
    tasks.register("assembleRelease")
    tasks.register("bundleRelease")
  KOTLIN
  github = File.join(project, 'release-notes/1.1.9.md')
  play = File.join(project, 'fastlane/metadata/android/en-US/changelogs/21.txt')
  [github, play].each { |path| FileUtils.mkdir_p(File.dirname(path)) }
  File.write(github, 'Already reviewed GitHub notes.')
  File.write(play, 'Already reviewed Play notes.')

  gradle = lambda do |*args, success: true|
    output, status = Open3.capture2e(*(wrapper + ['-p', project, '--no-daemon', '--console=plain', *args]))
    raise output unless status.success? == success

    output
  end
  check = ->(condition) { raise 'Native Gradle preparation check failed.' unless condition }
  gradle.call('versionBump')
  check.call(File.read(source).include?('versionName = "1.1.9"'))
  check.call(File.read(source).include?('versionCode = 21'))
  check.call(File.read(github) == 'Already reviewed GitHub notes.')
  check.call(File.read(play) == 'Already reviewed Play notes.')
  gradle.call('versionBump', '-PversionName=1.2.0', '-PversionCode=30')
  check.call(File.read(source).include?('versionName = "1.2.0"'))
  check.call(File.read(source).include?('versionCode = 30'))
  check.call(File.read(File.join(project, 'release-notes/1.2.0.md')).include?('RELEASE_NOTES_TODO'))
  check.call(File.read(File.join(project, 'fastlane/metadata/android/en-US/changelogs/30.txt')).include?('RELEASE_NOTES_TODO'))
  before = File.binread(source)
  gradle.call('versionBump', '-PversionName=1.1.9', success: false)
  gradle.call('versionBump', '-PversionCode=bad', success: false)
  check.call(File.binread(source) == before)
  { 'buildLocalApk' => ['assembleRelease'], 'buildLocalAab' => ['bundleRelease'],
    'buildLocalBoth' => %w[assembleRelease bundleRelease] }.each do |task, outputs|
    output = gradle.call(task, '--dry-run')
    check.call(outputs.all? { |name| output.include?(":app:#{name} SKIPPED") })
  end
  puts 'Native Gradle bump, preserved notes, fresh templates, invalid bumps and all local build shortcuts passed.'
end
