# Graphene Photos release commands and shared Fastlane helpers. Publishing is opt-in.
require 'base64'
require 'digest'
require 'fileutils'
require 'json'
require 'net/http'
require 'open3'
require 'optparse'
require 'rbconfig'
require 'tmpdir'
require 'uri'

module PhotosRelease
  ROOT = File.expand_path('..', __dir__)
  REPOSITORY = 'Graphene-Lab/graphene-photos-android-app'
  REPOSITORY_ID = '996638361'
  PACKAGE_NAME = 'com.graphenelab.photosync'
  BRANCH = 'master'
  WORKFLOW = '.github/workflows/android-play.yml'
  AAB = 'app/build/outputs/bundle/release/app-release.aab'
  AAB_PATH = File.join(ROOT, AAB)
  MAX_CODE = 2_100_000_000

  class CommandError < StandardError; end

  def self.windows?
    RbConfig::CONFIG.fetch('host_os').match?(/mswin|mingw/)
  end

  def self.read_text(path)
    text = File.read(path, encoding: 'bom|utf-8').gsub(/\r\n?/, "\n")
    raise ArgumentError, "Invalid UTF-8 text: #{path}" unless text.valid_encoding?

    text
  end

  def self.sha256_file(path)
    Digest::SHA256.file(path).hexdigest
  end

  def self.version_info(root = ROOT)
    text = read_text(File.join(root, 'app/build.gradle.kts'))
    literal = lambda do |pattern|
      values = text.scan(pattern).flatten
      unless values.length == 1
        raise ArgumentError, 'Expected one literal applicationId, versionName and versionCode in app/build.gradle.kts.'
      end
      values.first
    end
    package = literal.call(/^\s*applicationId\s*=\s*"([^"]+)"\s*$/)
    name = literal.call(/^\s*versionName\s*=\s*"([^"]+)"\s*$/)
    code = literal.call(/^\s*versionCode\s*=\s*(\d+)\s*$/).to_i
    unless package == PACKAGE_NAME && name.match?(/\A\d+\.\d+\.\d+\z/) && code.between?(1, MAX_CODE)
      raise ArgumentError, 'Invalid Photos package/version in app/build.gradle.kts.'
    end

    { 'package' => package, 'name' => name, 'code' => code }
  end

  def self.read_notes(path, limit = nil)
    raise ArgumentError, "Missing release notes: #{path}" unless File.file?(path)

    text = read_text(path).gsub(/\A[[:space:]]+|[[:space:]]+\z/, '')
    content = text.gsub(/<!--.*?-->/m, '').strip
    if content.empty? || text.match?(/\b(RELEASE_NOTES_TODO|TODO|TBD|PLACEHOLDER)\b/i)
      raise ArgumentError, "Complete and review release notes before publishing: #{path}"
    end
    if limit && text.length > limit
      raise ArgumentError, "Play notes exceed #{limit} Unicode characters: #{path}"
    end

    text
  end

  def self.play_notes(code, root = ROOT)
    raise ArgumentError, 'Invalid notes versionCode.' unless code.is_a?(Integer) && code.between?(1, MAX_CODE)

    directory = File.join(root, 'fastlane/metadata/android')
    result = { 'en-US' => read_notes(File.join(directory, "en-US/changelogs/#{code}.txt"), 500) }
    Dir.glob(File.join(directory, "*/changelogs/#{code}.txt")).sort.each do |path|
      locale = File.basename(File.dirname(File.dirname(path)))
      raise ArgumentError, "Invalid Play locale: #{locale}" unless locale.match?(/\A[a-z]{2,3}(?:-[A-Za-z0-9]{2,8})*\z/)

      result[locale] = read_notes(path, 500)
    end
    result
  end

  def self.release_info(destination, root = ROOT)
    raise ArgumentError, 'Destination must be github, play, or both.' unless %w[github play both].include?(destination)

    info = version_info(root)
    info['github_notes'] = read_notes(File.join(root, "release-notes/#{info.fetch('name')}.md")) unless destination == 'play'
    info['play_notes'] = play_notes(info.fetch('code'), root) unless destination == 'github'
    info
  end

  def self.context(env = ENV)
    unless env['GITHUB_REPOSITORY'] == REPOSITORY && env['GITHUB_REPOSITORY_ID'] == REPOSITORY_ID &&
           env['GITHUB_REF'] == "refs/heads/#{BRANCH}" && env['GITHUB_EVENT_NAME'] == 'workflow_dispatch' &&
           env.fetch('GITHUB_SHA', '').match?(/\A[a-f0-9]{40}\z/)
      raise ArgumentError, "Run this workflow manually from #{REPOSITORY} on #{BRANCH}."
    end
  end

  def self.positive(value, label, maximum = nil)
    unless value.to_s.match?(/\A[1-9][0-9]*\z/) && (!maximum || value.to_i <= maximum)
      raise ArgumentError, "#{label} must be a positive integer#{maximum ? " up to #{maximum}." : '.'}"
    end
    value.to_s
  end

  def self.release_plan(env = ENV)
    context(env)
    action = env['RELEASE_ACTION']
    destination = env.fetch('RELEASE_DESTINATION', 'both')
    raise ArgumentError, 'Unknown release action.' unless %w[verify build release internal promote].include?(action)
    if action == 'release' && !%w[github play both].include?(destination)
      raise ArgumentError, 'Select github, play, or both.'
    end
    positive(env.fetch('PLAY_BUILD_RUN_ID', ''), 'build_run_id') if action == 'internal'
    if action == 'promote' && !env.fetch('PLAY_VERSION_CODE', '').strip.empty?
      positive(env.fetch('PLAY_VERSION_CODE').strip, 'version_code', MAX_CODE)
    end
    play = action == 'release' && destination != 'github'
    track = play ? env.fetch('PLAY_TRACK', 'internal') : 'internal'
    raise ArgumentError, 'Play track must be internal or production.' unless %w[internal production].include?(track)

    build = action == 'build' || play
    { 'prepare_release' => action == 'release', 'build_android' => build,
      'prepare_signing' => build || action == 'verify', 'authenticate_play' => play || %w[verify internal promote].include?(action),
      'publish_bundle' => play || action == 'internal', 'play_track' => track, 'download_aab' => action == 'internal',
      'attach_apk' => action == 'release' && destination == 'both' }
  end

  class Github
    def initialize(env = ENV, transport: nil)
      @token = env.fetch('GITHUB_TOKEN')
      @base = "https://api.github.com/repos/#{REPOSITORY}"
      @transport = transport || method(:send_request)
    end

    def request(method, endpoint, body = nil, optional: false, upload: false)
      uri = URI(upload ? endpoint : @base + endpoint)
      if upload && (uri.scheme != 'https' || uri.host != 'uploads.github.com' || uri.port != 443 ||
                    !uri.path.start_with?("/repos/#{REPOSITORY}/releases/"))
        raise ArgumentError, 'Unexpected GitHub upload URL; credentials were not sent.'
      end
      request = Net::HTTPGenericRequest.new(method, !body.nil?, true, uri.request_uri)
      request['Authorization'] = "Bearer #{@token}"
      request['Accept'] = 'application/vnd.github+json'
      request['X-GitHub-Api-Version'] = '2022-11-28'
      request['User-Agent'] = 'graphene-photos-release'
      if upload
        request['Content-Type'] = 'application/vnd.android.package-archive'
        request.content_length = body.stat.size
        request.body_stream = body
      elsif !body.nil?
        request['Content-Type'] = 'application/json'
        request.body = JSON.generate(body)
      end
      response = @transport.call(uri, request, upload ? 300 : 30)
      status = response.code.to_i
      return nil if optional && status == 404
      # Net::HTTP never follows redirects here. Never forward credentials or report API response bodies.
      raise ArgumentError, 'Unexpected GitHub redirect; credentials were not forwarded.' if status.between?(300, 399)
      raise ArgumentError, "GitHub #{method} failed (HTTP #{status})." unless status.between?(200, 299)

      JSON.parse(response.body)
    rescue JSON::ParserError
      raise ArgumentError, 'GitHub returned an invalid JSON response.'
    end

    private

    def send_request(uri, request, timeout)
      Net::HTTP.start(uri.host, uri.port, use_ssl: true, open_timeout: 30, read_timeout: timeout, write_timeout: timeout) do |http|
        http.request(request)
      end
    end
  end

  def self.tag_commit(api, tag)
    ref = api.request('GET', "/git/ref/tags/#{tag}", optional: true)
    return nil unless ref

    object = ref.fetch('object', {})
    6.times do
      break unless object.fetch('sha', '').match?(/\A[a-f0-9]{40}\z/)
      return object['sha'] if object['type'] == 'commit'
      break unless object['type'] == 'tag'

      object = api.request('GET', "/git/tags/#{object.fetch('sha')}").fetch('object', {})
    end
    raise ArgumentError, 'Cannot resolve version tag to an immutable source commit.'
  end

  def self.prepare_github(api, destination, root: ROOT, env: ENV)
    context(env)
    info = release_info(destination, root) # Validate both destinations before any GitHub mutation.
    tag = "v#{info.fetch('name')}"
    sha = env.fetch('GITHUB_SHA')
    existing = tag_commit(api, tag)
    if existing && existing != sha
      raise ArgumentError, "#{tag} points to different code. Bump the version; existing tags are never moved."
    end
    unless existing
      api.request('POST', '/git/refs', { 'ref' => "refs/tags/#{tag}", 'sha' => sha })
      raise ArgumentError, 'Version tag did not resolve to the workflow source commit.' unless tag_commit(api, tag) == sha
    end
    unless destination == 'play'
      release = api.request('GET', "/releases/tags/#{tag}", optional: true)
      body = nil
      if !release || release['body'].to_s.strip.empty?
        generated = api.request('POST', '/releases/generate-notes', { 'tag_name' => tag, 'target_commitish' => sha })
        body = info.fetch('github_notes') + "\n\n" + generated.fetch('body', '').strip
      end
      if !release
        release = api.request('POST', '/releases', { 'tag_name' => tag, 'target_commitish' => sha, 'name' => tag,
                                                  'body' => body, 'draft' => false, 'prerelease' => false })
      elsif release['draft'] || body
        changes = { 'draft' => false }
        changes['body'] = body if body
        release = api.request('PATCH', "/releases/#{positive(release.fetch('id'), 'release ID')}", changes)
      end
      unless release['tag_name'] == tag && release['draft'] == false
        raise ArgumentError, 'GitHub Release was not published. Android release has stopped.'
      end
      puts "GitHub Release #{tag} published for #{sha}; Android steps may now proceed."
    end
    info
  end

  def self.write_json(path, value)
    FileUtils.mkdir_p(File.dirname(path))
    File.write(path, JSON.pretty_generate(value) + "\n", encoding: 'UTF-8')
  end

  def self.fingerprint(value)
    result = value.to_s.delete(':').downcase
    unless result.match?(/\A[a-f0-9]{64}\z/)
      raise ArgumentError, 'Configure Photos PLAY_APP_SIGNING_SHA256 from Play Console app signing key certificate.'
    end
    result
  end

  def self.execute(args, env: {}, capture: false, timeout: nil, chdir: nil)
    # Argument arrays only; signing passwords stay in the environment. Captured preflight output is never logged.
    options = chdir ? { chdir: chdir } : {}
    Open3.popen3(env, *args, **options) do |stdin, stdout, stderr, waiter|
      stdin.close
      readers = [Thread.new { capture ? stdout.read : IO.copy_stream(stdout, $stdout) },
                 Thread.new { capture ? stderr.read : IO.copy_stream(stderr, $stderr) }]
      unless waiter.join(timeout)
        Process.kill('KILL', waiter.pid)
        waiter.join
        readers.each(&:value)
        raise CommandError, "Command timed out: #{File.basename(args.first)}."
      end
      output = readers.map(&:value)
      unless waiter.value.success?
        raise CommandError, "Command failed: #{File.basename(args.first)} (exit #{waiter.value.exitstatus})."
      end
      capture ? output.first : nil
    end
  end

  def self.prepare_ci(env = ENV, root: ROOT, run: method(:execute))
    keys = %w[ANDROID_UPLOAD_KEYSTORE_BASE64 ANDROID_KEYSTORE_PASSWORD ANDROID_KEY_ALIAS ANDROID_KEY_PASSWORD
              ANDROID_UPLOAD_CERT_SHA256 APPLICATION_PROPERTIES RUNNER_TEMP GITHUB_ENV]
    missing = keys.select { |key| env.fetch(key, '').strip.empty? }
    raise ArgumentError, "Missing CI configuration: #{missing.join(', ')}" unless missing.empty?

    expected = fingerprint(env.fetch('ANDROID_UPLOAD_CERT_SHA256'))
    config = env.fetch('APPLICATION_PROPERTIES')
    props = config.scan(/^\s*([^#!\s=]+)\s*=\s*(.*?)\s*$/).to_h
    if %w[BASE_URL_RELEASE STRIPE_PUBLIC_KEY_RELEASE].any? { |key| props[key].to_s.empty? || %w[null change_me todo].include?(props[key].downcase) }
      raise ArgumentError, 'APPLICATION_PROPERTIES needs BASE_URL_RELEASE and STRIPE_PUBLIC_KEY_RELEASE.'
    end
    begin
      raw = Base64.strict_decode64(env.fetch('ANDROID_UPLOAD_KEYSTORE_BASE64').gsub(/\s/, ''))
    rescue ArgumentError
      raise ArgumentError, 'ANDROID_UPLOAD_KEYSTORE_BASE64 must contain valid Base64.'
    end
    raise ArgumentError, 'Upload keystore is empty.' if raw.empty?

    store = File.join(env.fetch('RUNNER_TEMP'), 'photos-upload.keystore')
    File.open(store, 'wb', 0o600) { |file| file.write(raw) }
    File.chmod(0o600, store)
    begin
      run.call(['java', File.join(root, 'scripts/VerifyUploadKey.java'), store, expected], env: env.to_h, capture: true, timeout: 45)
    rescue CommandError, SystemCallError
      FileUtils.rm_f(store)
      raise ArgumentError, 'Photos upload-key verification failed. Check keystore, alias, passwords and registered upload SHA-256.'
    end
    File.write(File.join(root, 'application.properties'), config, encoding: 'UTF-8')
    File.open(env.fetch('GITHUB_ENV'), 'a:UTF-8') { |file| file.puts "ANDROID_KEYSTORE_FILE=#{store}" }
    puts 'Photos upload key and application configuration verified; private credentials are not logged.'
  end

  def self.record_build(root: ROOT, env: ENV)
    context(env)
    info = version_info(root).merge('source_sha' => env.fetch('GITHUB_SHA'), 'aab_sha256' => sha256_file(File.join(root, AAB)))
    write_json(File.join(root, File.dirname(AAB), 'release-build.json'), info)
    info
  end

  def self.resolve_build(api, run_id, env = ENV)
    context(env)
    positive(run_id, 'build_run_id')
    run = api.request('GET', "/actions/runs/#{run_id}")
    unless run.dig('repository', 'id').to_s == REPOSITORY_ID && run['path'] == WORKFLOW &&
           run['event'] == 'workflow_dispatch' && run['head_branch'] == BRANCH &&
           run.dig('head_repository', 'id').to_s == REPOSITORY_ID && run['status'] == 'completed' &&
           run['conclusion'] == 'success' && run.fetch('head_sha', '').match?(/\A[a-f0-9]{40}\z/)
      raise ArgumentError, 'Choose a successful manual Photos build from this workflow on master.'
    end
    sha = run.fetch('head_sha')
    artifacts = api.request('GET', "/actions/runs/#{run_id}/artifacts?per_page=100")
    matches = artifacts.fetch('artifacts', []).select { |item| item['name'] == "graphene-photos-#{sha}" && !item['expired'] }
    raise ArgumentError, 'The run must contain exactly one unexpired Photos AAB artifact.' unless matches.length == 1

    artifact = matches.first
    positive(artifact.fetch('id'), 'artifact ID')
    unless artifact.fetch('digest', '').match?(/\Asha256:[a-f0-9]{64}\z/)
      raise ArgumentError, 'The artifact needs a GitHub SHA-256 digest.'
    end
    source = Dir.mktmpdir('photos-build-source-', env.fetch('RUNNER_TEMP'))
    read = lambda do |path, optional = false|
      file = api.request('GET', "/contents/#{path}?ref=#{sha}", optional: optional)
      next unless file
      unless file['type'] == 'file' && file['encoding'] == 'base64'
        raise ArgumentError, 'Cannot read the selected build source files.'
      end
      target = File.join(source, path)
      FileUtils.mkdir_p(File.dirname(target))
      File.binwrite(target, Base64.strict_decode64(file.fetch('content').gsub(/\s/, '')))
    end
    read.call('app/build.gradle.kts')
    version = version_info(source)
    locales = api.request('GET', "/contents/fastlane/metadata/android?ref=#{sha}", optional: true)
    raise ArgumentError, 'The original build source has no committed Play notes.' unless locales.is_a?(Array)

    locales.each do |locale|
      next unless locale['type'] == 'dir'

      language = locale.fetch('name')
      unless language.match?(/\A[a-z]{2,3}(?:-[A-Za-z0-9]{2,8})*\z/)
        raise ArgumentError, 'Invalid Play locale in the original source commit.'
      end
      read.call("fastlane/metadata/android/#{language}/changelogs/#{version.fetch('code')}.txt", language != 'en-US')
    end
    release_info('play', source) # Fail before downloading/uploading an unpublishable artifact.
    result = { 'source' => source, 'sha' => sha, 'version' => version, 'artifact_id' => artifact.fetch('id') }
    write_json(File.join(env.fetch('RUNNER_TEMP'), 'selected-build.json'), result)
    File.open(env.fetch('GITHUB_ENV'), 'a:UTF-8') do |file|
      file.puts "PLAY_RELEASE_SOURCE=#{source}", "PLAY_AAB_ARTIFACT_ID=#{artifact.fetch('id')}"
    end
    puts "Using original Play notes and AAB from run #{run_id}, source #{sha}."
    result
  end

  def self.verify_build(root: ROOT, env: ENV)
    selected = JSON.parse(read_text(File.join(env.fetch('RUNNER_TEMP'), 'selected-build.json')))
    recorded = JSON.parse(read_text(File.join(root, File.dirname(AAB), 'release-build.json')))
    expected = selected.fetch('version').merge('source_sha' => selected.fetch('sha'), 'aab_sha256' => sha256_file(File.join(root, AAB)))
    unless recorded == expected
      raise ArgumentError, 'AAB digest, package/version or source provenance does not match the original build.'
    end
    puts 'Original build AAB digest and package/version/source provenance verified.'
  end

  def self.resolve_apk(api, info, env = ENV)
    context(env)
    tag = "v#{info.fetch('name')}"
    raise ArgumentError, 'APK release tag points at different source.' unless tag_commit(api, tag) == env.fetch('GITHUB_SHA')

    release = api.request('GET', "/releases/tags/#{tag}")
    unless release['tag_name'] == tag && release['draft'] == false
      raise ArgumentError, 'The GitHub Release must already be published.'
    end
    positive(release.fetch('id'), 'release ID')
    { 'name' => info.fetch('name'), 'code' => info.fetch('code'), 'tag' => tag,
      'source_sha' => env.fetch('GITHUB_SHA'), 'release_id' => release.fetch('id') }
  end

  def self.attach_apk(api, env = ENV, run: method(:execute))
    context(env)
    unless env['RELEASE_ACTION'] == 'release' && env['RELEASE_DESTINATION'] == 'both'
      raise ArgumentError, 'APK attachment runs only as part of Both.'
    end
    recorded = JSON.parse(read_text(File.join(env.fetch('RUNNER_TEMP'), 'play-apk-release.json')))
    raise ArgumentError, 'GitHub Release changed during APK processing.' unless resolve_apk(api, recorded, env) == recorded

    expected = fingerprint(env.fetch('PLAY_APP_SIGNING_SHA256', ''))
    filename = "Graphene-Photos-#{recorded.fetch('name')}.apk"
    apk = File.join(env.fetch('RUNNER_TEMP'), 'play-release-apk', filename)
    tools = File.join(env['ANDROID_HOME'] || env.fetch('ANDROID_SDK_ROOT'), 'build-tools/35.0.0')
    output = lambda do |args|
      run.call(args, capture: true, timeout: 60)
    rescue CommandError, SystemCallError
      raise ArgumentError, 'Downloaded APK signature/manifest verification failed.'
    end
    cert = output.call(['java', '-jar', File.join(tools, 'lib/apksigner.jar'), 'verify', '--print-certs', apk])
    signers = cert.scan(/^Signer #\d+ certificate SHA-256 digest:\s*([a-fA-F0-9:]+)\s*$/).flatten
    unless signers.length == 1 && fingerprint(signers.first) == expected
      raise ArgumentError, 'Actual APK signing certificate does not match Photos Play app signing key.'
    end
    manifest = output.call([File.join(tools, windows? ? 'aapt2.exe' : 'aapt2'), 'dump', 'badging', apk])
    package_line = manifest[/^package: (.+)$/, 1].to_s
    attrs = package_line.scan(/(\w+)='([^']*)'/).to_h
    unless attrs['name'] == PACKAGE_NAME && attrs['versionName'] == recorded['name'] &&
           attrs['versionCode'] == recorded['code'].to_s && !attrs.key?('split') && !manifest.match?(/^application-debuggable\b/)
      raise ArgumentError, 'Actual APK package/version is wrong, split, or debuggable.'
    end
    size = File.size(apk)
    raise ArgumentError, 'Downloaded APK is empty.' if size.zero?

    digest = "sha256:#{sha256_file(apk)}"
    check = lambda do |asset|
      unless { 'name' => filename, 'state' => 'uploaded', 'size' => size, 'digest' => digest }.all? { |key, value| asset[key] == value }
        raise ArgumentError, 'Conflicting or incomplete APK asset; existing assets are never overwritten.'
      end
    end
    release_id = recorded.fetch('release_id')
    1.upto(10) do |page|
      assets = api.request('GET', "/releases/#{release_id}/assets?per_page=100&page=#{page}")
      existing = assets.select { |asset| asset['name'] == filename }
      unless existing.empty?
        raise ArgumentError, 'Duplicate APK release assets.' unless existing.length == 1

        check.call(existing.first)
        return existing.first
      end
      break if assets.length < 100
      raise ArgumentError, 'Too many release assets to verify safely.' if page == 10
    end
    asset = File.open(apk, 'rb') do |file|
      api.request('POST', "https://uploads.github.com/repos/#{REPOSITORY}/releases/#{release_id}/assets?name=#{URI.encode_www_form_component(filename)}", file, upload: true)
    end
    check.call(asset)
    puts "Attached verified Play-signed #{filename} (#{recorded.fetch('code')})."
    asset
  end

  def self.dispatch(action, track: 'internal', version_code: nil, build_run_id: nil, run: method(:execute))
    args = ['gh', 'workflow', 'run', 'android-play.yml', '--repo', REPOSITORY, '--ref', BRANCH]
    if %w[github play both].include?(action)
      unless %w[internal production].include?(track) && (action != 'github' || track == 'internal')
        raise ArgumentError, 'Play track applies to play/both and must be internal or production.'
      end
      fields = { 'action' => 'release', 'destination' => action }
      fields['play_track'] = track unless action == 'github'
    elsif %w[verify build internal promote].include?(action)
      fields = { 'action' => action }
      fields['build_run_id'] = positive(build_run_id, 'build_run_id') if action == 'internal'
      fields['version_code'] = positive(version_code, 'version_code', MAX_CODE) if action == 'promote' && version_code
    else
      raise ArgumentError, 'Unknown workflow command.'
    end
    fields.each { |key, value| args.concat(['-f', "#{key}=#{value}"]) }
    puts "Requesting #{fields} from GitHub #{BRANCH}. Only already-pushed source is used."
    run.call(args, env: { 'GH_PROMPT_DISABLED' => '1' })
  end

  def self.main(argv = ARGV)
    argv = argv.dup
    options = { track: 'internal' }
    parser = OptionParser.new do |opts|
      opts.banner = <<~HELP
        Usage: ruby scripts/release.rb COMMAND [options]
        Commands:
          release:github | release:play | release:both [--play-track internal|production]
          release:verify | release:build | release:internal RUN_ID | release:promote [VERSION_CODE]
          build:local:apk | build:local:aab | build:local:both
          version:bump [--version-name X.Y.Z] [--version-code CODE]
          notes github|play|both
        Internal workflow steps: plan, prepare-ci, prepare-release, record-build,
          resolve-build, verify-build, resolve-apk, attach-apk
      HELP
      opts.on('--play-track TRACK', %w[internal production]) { |value| options[:track] = value; options[:track_selected] = true }
      opts.on('--version-name NAME') { |value| options[:name] = value }
      opts.on('--version-code CODE') { |value| options[:code] = value }
      opts.on('-h', '--help') { puts opts; return }
    end
    parser.parse!(argv)
    command = argv.shift
    if options[:track_selected] && !%w[release:play release:both].include?(command)
      raise ArgumentError, '--play-track applies only to release:play or release:both.'
    end
    if (options[:name] || options[:code]) && command != 'version:bump'
      raise ArgumentError, '--version-name and --version-code apply only to version:bump.'
    end
    positional = %w[release:internal release:promote notes].include?(command) ? argv.shift : nil
    raise ArgumentError, 'Unexpected command arguments.' unless argv.empty?

    case command
    when /\Arelease:(github|play|both|verify|build|internal|promote)\z/
      dispatch(Regexp.last_match(1), track: options[:track], version_code: command == 'release:promote' ? positional : nil,
               build_run_id: command == 'release:internal' ? positional : nil)
    when 'build:local:apk', 'build:local:aab', 'build:local:both', 'version:bump'
      prefix = windows? ? [File.join(ROOT, 'gradlew.bat')] : ['bash', File.join(ROOT, 'gradlew')]
      task = { 'apk' => 'buildLocalApk', 'aab' => 'buildLocalAab', 'both' => 'buildLocalBoth' }.fetch(command.split(':').last, 'versionBump')
      flags = []
      if options[:name]
        raise ArgumentError, 'versionName must be X.Y.Z.' unless options[:name].match?(/\A\d+\.\d+\.\d+\z/)

        flags << "-PversionName=#{options[:name]}"
      end
      flags << "-PversionCode=#{positive(options[:code], 'versionCode', MAX_CODE)}" if options[:code]
      execute(prefix + [task] + flags, chdir: ROOT)
    when 'notes'
      puts JSON.pretty_generate(release_info(positional))
    when 'plan'
      File.open(ENV.fetch('GITHUB_OUTPUT'), 'a:UTF-8') do |file|
        release_plan.each { |key, value| file.puts "#{key}=#{value}" }
      end
    when 'prepare-ci' then prepare_ci
    when 'prepare-release'
      write_json(File.join(ENV.fetch('RUNNER_TEMP'), 'release-version.json'), prepare_github(Github.new, ENV.fetch('RELEASE_DESTINATION')))
    when 'record-build' then record_build
    when 'resolve-build' then resolve_build(Github.new, ENV.fetch('PLAY_BUILD_RUN_ID'))
    when 'verify-build' then verify_build
    when 'resolve-apk'
      info = JSON.parse(read_text(File.join(ENV.fetch('RUNNER_TEMP'), 'release-version.json')))
      write_json(File.join(ENV.fetch('RUNNER_TEMP'), 'play-apk-release.json'), resolve_apk(Github.new, info))
    when 'attach-apk' then attach_apk(Github.new)
    else raise ArgumentError, "Choose a release command; use --help. Unknown command: #{command}"
    end
  end
end

if $PROGRAM_NAME == __FILE__
  begin
    PhotosRelease.main
  rescue ArgumentError, OptionParser::ParseError, KeyError, SystemCallError, IOError, PhotosRelease::CommandError, Timeout::Error, SocketError, OpenSSL::SSL::SSLError => error
    warn "Release command failed: #{error.message}"
    exit 1
  end
end
