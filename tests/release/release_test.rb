require 'minitest/autorun'
require 'rexml/document'
require_relative '../../scripts/release'
require_relative '../../scripts/verify-google-identity'
require_relative '../../fastlane/release_config'

# Closed mocks: accidental real HTTP or CLI publishing is forbidden throughout these unit tests.
Net::HTTP.singleton_class.prepend(Module.new do
  def start(*)
    raise 'Real network forbidden in release tests'
  end
end)
Open3.singleton_class.prepend(Module.new do
  def popen3(*)
    raise 'Real subprocess forbidden in release unit tests'
  end
end)

class ReleaseApi
  attr_reader :responses, :calls

  def initialize(responses)
    @responses = Marshal.load(Marshal.dump(responses))
    @calls = []
  end

  def request(method, endpoint, body = nil, **)
    @calls << [method, endpoint, body]
    raise "Unexpected API operation: #{method} #{endpoint}" if @responses.empty?

    expected_method, expected_path, response = @responses.shift
    raise "Unexpected API operation: #{method} #{endpoint}, expected #{expected_path}" unless [method, endpoint] == [expected_method, expected_path]

    Marshal.load(Marshal.dump(response))
  end
end

class ReleaseTest < Minitest::Test
  R = PhotosRelease
  ID = PhotosIdentity
  SHA = 'a' * 40
  PIN = 'ab' * 32

  def setup
    @root = Dir.mktmpdir('photos-release-test-', File.directory?(File.join(R::ROOT, '.release-work')) ? File.join(R::ROOT, '.release-work') : nil)
    @env = { 'GITHUB_REPOSITORY' => R::REPOSITORY, 'GITHUB_REPOSITORY_ID' => R::REPOSITORY_ID,
             'GITHUB_REF' => 'refs/heads/master', 'GITHUB_EVENT_NAME' => 'workflow_dispatch', 'GITHUB_SHA' => SHA,
             'RELEASE_ACTION' => 'release', 'RELEASE_DESTINATION' => 'both', 'PLAY_TRACK' => 'internal',
             'RUNNER_TEMP' => @root, 'GITHUB_ENV' => File.join(@root, 'env'), 'ANDROID_HOME' => File.join(@root, 'sdk'),
             'PLAY_APP_SIGNING_SHA256' => PIN }
    @gradle = File.join(@root, 'app/build.gradle.kts')
    write(@gradle, "applicationId = \"#{R::PACKAGE_NAME}\"\nversionName = \"1.1.9\"\nversionCode = 21\n")
  end

  def teardown
    FileUtils.remove_entry(@root)
  end

  def write(path, text)
    FileUtils.mkdir_p(File.dirname(path))
    File.write(path, text, encoding: 'UTF-8')
  end

  def notes(github: "## Detailed Markdown\n\nFixed sync.", play: 'Improved photo sync.')
    write(File.join(@root, 'release-notes/1.1.9.md'), github)
    write(File.join(@root, 'fastlane/metadata/android/en-US/changelogs/21.txt'), play)
  end

  def prepare(api, destination = 'both')
    R.prepare_github(api, destination, root: @root, env: @env)
  end

  def ref
    { 'object' => { 'type' => 'commit', 'sha' => SHA } }
  end

  def published(**changes)
    { 'id' => 7, 'tag_name' => 'v1.1.9', 'draft' => false }.merge(changes.transform_keys(&:to_s))
  end

  def test_native_version_and_identity
    assert_equal({ 'package' => R::PACKAGE_NAME, 'name' => '1.1.9', 'code' => 21 }, R.version_info(@root))
    original = File.read(@gradle)
    [original.sub(R::PACKAGE_NAME, 'another.app'), original.sub('versionCode = 21', 'versionCode = 0'),
     original + "versionCode = 22\n"].each do |text|
      write(@gradle, text)
      assert_raises(ArgumentError) { R.version_info(@root) }
    end
  end

  def test_destination_notes_are_separate
    notes
    both = R.release_info('both', @root)
    assert_includes both.fetch('github_notes'), 'Markdown'
    assert_equal({ 'en-US' => 'Improved photo sync.' }, both.fetch('play_notes'))
    File.delete(File.join(@root, 'release-notes/1.1.9.md'))
    assert R.release_info('play', @root).key?('play_notes')
    assert_raises(ArgumentError) { R.release_info('both', @root) }
    notes
    File.delete(File.join(@root, 'fastlane/metadata/android/en-US/changelogs/21.txt'))
    assert R.release_info('github', @root).key?('github_notes')
    assert_raises(ArgumentError) { R.release_info('play', @root) }
  end

  def test_missing_blank_comment_only_or_unfinished_notes_stop_before_api
    ['', "  \n", "\u00a0", '<!-- notes -->', "# Changes\nRELEASE_NOTES_TODO", 'TODO: write summary', 'TBD', 'PLACEHOLDER'].each do |text|
      notes(play: text)
      api = ReleaseApi.new([])
      assert_raises(ArgumentError) { prepare(api) }
      assert_empty api.calls
    end
    File.delete(File.join(@root, 'fastlane/metadata/android/en-US/changelogs/21.txt'))
    api = ReleaseApi.new([])
    assert_raises(ArgumentError) { prepare(api) }
    assert_empty api.calls
  end

  def test_unicode_limit_and_additional_exact_locale
    notes(play: '😀' * 500)
    assert_equal 500, R.play_notes(21, @root).fetch('en-US').length
    notes(play: '😀' * 501)
    assert_raises(ArgumentError) { R.play_notes(21, @root) }
    notes(play: "\uFEFFImproved photo sync.\r\n")
    assert_equal 'Improved photo sync.', R.play_notes(21, @root).fetch('en-US')
    locale = File.join(@root, 'fastlane/metadata/android/fr-FR/changelogs/21.txt')
    write(locale, 'Synchronisation améliorée.')
    assert_equal 'Synchronisation améliorée.', R.play_notes(21, @root).fetch('fr-FR')
    write(locale, 'x' * 501)
    assert_raises(ArgumentError) { R.play_notes(21, @root) }
  end

  def test_historical_and_default_notes_do_not_substitute
    notes
    directory = File.join(@root, 'fastlane/metadata/android/en-US/changelogs')
    File.delete(File.join(directory, '21.txt'))
    write(File.join(directory, 'default.txt'), 'Fallback must never publish.')
    write(File.join(directory, '20.txt'), 'Old release.')
    assert_raises(ArgumentError) { R.play_notes(21, @root) }
  end

  def test_plan_all_release_choices_and_default_track
    %w[github play both].product(%w[internal production]).each do |destination, track|
      plan = R.release_plan(@env.merge('RELEASE_DESTINATION' => destination, 'PLAY_TRACK' => track))
      assert_equal destination != 'github', plan['build_android']
      assert_equal destination != 'github', plan['authenticate_play']
      assert_equal destination == 'both', plan['attach_apk']
      assert_equal destination == 'github' ? 'internal' : track, plan['play_track']
    end
    %w[verify build internal promote].each do |action|
      plan = R.release_plan(@env.merge('RELEASE_ACTION' => action, 'PLAY_BUILD_RUN_ID' => '123'))
      refute plan['prepare_release']
      refute plan['attach_apk']
      assert_equal action == 'build', plan['build_android']
    end
    assert_equal 'internal', R.release_plan(@env.reject { |key, _| key == 'PLAY_TRACK' })['play_track']
  end

  def test_context_and_invalid_inputs_are_rejected
    [{ 'GITHUB_EVENT_NAME' => 'push' }, { 'GITHUB_REF' => 'refs/tags/v1.1.9' },
     { 'GITHUB_REPOSITORY_ID' => '1016678903' }, { 'GITHUB_REPOSITORY' => 'fork/photos' },
     { 'PLAY_TRACK' => 'beta' }, { 'RELEASE_ACTION' => 'internal', 'PLAY_BUILD_RUN_ID' => '' },
     { 'RELEASE_ACTION' => 'promote', 'PLAY_VERSION_CODE' => '2100000001' }].each do |changes|
      assert_raises(ArgumentError) { R.release_plan(@env.merge(changes)) }
    end
  end

  def test_github_first_creates_exact_tag_then_markdown_and_generated_changelog
    notes
    api = ReleaseApi.new([['GET', '/git/ref/tags/v1.1.9', nil], ['POST', '/git/refs', {}],
                          ['GET', '/git/ref/tags/v1.1.9', ref], ['GET', '/releases/tags/v1.1.9', nil],
                          ['POST', '/releases/generate-notes', { 'body' => "## What changed\n* Generated PR" }],
                          ['POST', '/releases', published]])
    prepare(api)
    assert_equal({ 'ref' => 'refs/tags/v1.1.9', 'sha' => SHA }, api.calls[1][2])
    assert_equal "## Detailed Markdown\n\nFixed sync.\n\n## What changed\n* Generated PR", api.calls.last[2]['body']
    assert_empty api.responses
  end

  def test_existing_lightweight_or_annotated_tag_and_nonempty_release_preserved
    notes
    [false, true].each do |annotated|
      responses = [['GET', '/git/ref/tags/v1.1.9', { 'object' => { 'type' => annotated ? 'tag' : 'commit', 'sha' => SHA } }]]
      responses << ['GET', "/git/tags/#{SHA}", ref] if annotated
      responses << ['GET', '/releases/tags/v1.1.9', published(body: 'Existing description')]
      api = ReleaseApi.new(responses)
      prepare(api, 'github')
      assert api.calls.all? { |call| call.first == 'GET' }
    end
  end

  def test_existing_draft_preserves_notes_and_is_published
    notes
    api = ReleaseApi.new([['GET', '/git/ref/tags/v1.1.9', ref],
                          ['GET', '/releases/tags/v1.1.9', published(draft: true, body: 'Keep this')],
                          ['PATCH', '/releases/7', published]])
    prepare(api, 'github')
    assert_equal({ 'draft' => false }, api.calls.last[2])
  end

  def test_blank_existing_release_receives_markdown_and_generated_notes
    notes
    api = ReleaseApi.new([['GET', '/git/ref/tags/v1.1.9', ref], ['GET', '/releases/tags/v1.1.9', published(body: '  ')],
                          ['POST', '/releases/generate-notes', { 'body' => 'Generated changelog' }], ['PATCH', '/releases/7', published]])
    prepare(api, 'github')
    assert_equal "## Detailed Markdown\n\nFixed sync.\n\nGenerated changelog", api.calls.last[2]['body']
  end

  def test_unfinished_github_notes_stop_both_before_tagging
    notes(github: "# Changes\nRELEASE_NOTES_TODO: Write notes")
    api = ReleaseApi.new([])
    assert_raises(ArgumentError) { prepare(api) }
    assert_empty api.calls
  end

  def test_tag_conflicts_never_mutate
    notes
    api = ReleaseApi.new([['GET', '/git/ref/tags/v1.1.9', { 'object' => { 'type' => 'commit', 'sha' => 'b' * 40 } }]])
    error = assert_raises(ArgumentError) { prepare(api) }
    assert_includes error.message, 'never moved'
    assert_equal 1, api.calls.length
  end

  def test_play_only_creates_tag_without_github_page
    notes
    api = ReleaseApi.new([['GET', '/git/ref/tags/v1.1.9', ref]])
    prepare(api, 'play')
    assert_equal 1, api.calls.length
  end

  def test_build_records_digest_and_immutable_source_without_notes
    aab = File.join(@root, R::AAB)
    write(aab, 'signed test bundle')
    info = R.record_build(root: @root, env: @env)
    R.write_json(File.join(@root, 'selected-build.json'), { 'version' => R.version_info(@root), 'sha' => SHA })
    R.verify_build(root: @root, env: @env)
    assert_equal SHA, info['source_sha']
    write(aab, 'tampered')
    assert_raises(ArgumentError) { R.verify_build(root: @root, env: @env) }
  end

  def build_api(notes: 'Original build notes', changes: {})
    run = { 'repository' => { 'id' => R::REPOSITORY_ID.to_i }, 'head_repository' => { 'id' => R::REPOSITORY_ID.to_i },
            'event' => 'workflow_dispatch', 'head_branch' => 'master', 'status' => 'completed', 'conclusion' => 'success',
            'path' => R::WORKFLOW, 'head_sha' => SHA }.merge(changes)
    file = ->(text) { { 'type' => 'file', 'encoding' => 'base64', 'content' => Base64.encode64(text) } }
    ReleaseApi.new([['GET', '/actions/runs/123', run],
                    ['GET', '/actions/runs/123/artifacts?per_page=100', { 'artifacts' => [{ 'id' => 5, 'name' => "graphene-photos-#{SHA}", 'expired' => false, 'digest' => 'sha256:' + 'c' * 64 }] }],
                    ['GET', "/contents/app/build.gradle.kts?ref=#{SHA}", file.call(File.read(@gradle))],
                    ['GET', "/contents/fastlane/metadata/android?ref=#{SHA}", [{ 'type' => 'dir', 'name' => 'en-US' }]],
                    ['GET', "/contents/fastlane/metadata/android/en-US/changelogs/21.txt?ref=#{SHA}", file.call(notes)]])
  end

  def test_reuse_and_fastlane_read_notes_from_original_commit
    notes(play: 'Newer checkout notes must not be used')
    api = build_api
    selected = R.resolve_build(api, '123', @env)
    assert_equal({ 'en-US' => 'Original build notes' }, R.play_notes(21, selected.fetch('source')))
    assert_equal({ 'en-US' => 'Original build notes' }, R.info(root: selected.fetch('source')).fetch('play_notes'))
    assert_includes File.read(File.join(@root, 'env')), 'PLAY_AAB_ARTIFACT_ID=5'
    assert_empty api.responses
  end

  def test_reuse_rejects_unsuccessful_wrong_branch_fork_or_workflow
    [{ 'conclusion' => 'failure' }, { 'status' => 'in_progress' }, { 'head_branch' => 'feature' },
     { 'event' => 'pull_request' }, { 'head_repository' => { 'id' => 1 } }, { 'path' => '.github/workflows/other.yml' }].each do |changes|
      assert_raises(ArgumentError) { R.resolve_build(build_api(changes: changes), '123', @env) }
    end
  end

  def test_reuse_rejects_original_unfinished_notes
    notes(play: 'Valid notes today cannot repair the original build')
    assert_raises(ArgumentError) { R.resolve_build(build_api(notes: 'RELEASE_NOTES_TODO'), '123', @env) }
  end

  def test_reuse_rejects_expired_duplicate_or_undigested_artifacts
    %w[expired duplicate digest].each do |kind|
      api = build_api
      artifacts = api.responses[1][2].fetch('artifacts')
      case kind
      when 'expired' then artifacts.first['expired'] = true
      when 'duplicate' then artifacts << artifacts.first.dup
      when 'digest' then artifacts.first.delete('digest')
      end
      assert_raises(ArgumentError) { R.resolve_build(api, '123', @env) }
    end
  end

  def test_dispatch_all_choices_is_mocked_and_never_builds_locally
    %w[github play both verify build internal promote].each do |action|
      calls = []
      R.dispatch(action, build_run_id: '123', run: ->(args, **) { calls << args })
      assert_equal ['gh', 'workflow', 'run', 'android-play.yml'], calls.first.take(4)
      assert_includes calls.first, R::REPOSITORY
      assert_includes calls.first, 'play_track=internal' if %w[play both].include?(action)
    end
    calls = []
    runner = ->(args, **) { calls << args }
    R.dispatch('both', track: 'production', run: runner)
    assert_includes calls.last, 'play_track=production'
    R.dispatch('promote', version_code: '21', run: runner)
    assert_includes calls.last, 'version_code=21'
  end

  def apk_setup
    info = { 'name' => '1.1.9', 'code' => 21, 'tag' => 'v1.1.9', 'source_sha' => SHA, 'release_id' => 7 }
    R.write_json(File.join(@root, 'play-apk-release.json'), info)
    apk = File.join(@root, 'play-release-apk/Graphene-Photos-1.1.9.apk')
    write(apk, 'actual test APK bytes')
    asset = { 'name' => File.basename(apk), 'state' => 'uploaded', 'size' => File.size(apk), 'digest' => "sha256:#{R.sha256_file(apk)}" }
    responses = [['GET', '/git/ref/tags/v1.1.9', ref], ['GET', '/releases/tags/v1.1.9', published]]
    runner = lambda do |args, **|
      args.include?('verify') ? "Signer #1 certificate SHA-256 digest: #{PIN}\n" :
        "package: name='#{R::PACKAGE_NAME}' versionCode='21' versionName='1.1.9'\n"
    end
    [responses, asset, runner]
  end

  def test_actual_apk_verified_then_uploaded_and_identical_retry_reused
    [false, true].each do |retrying|
      responses, asset, runner = apk_setup
      responses << ['GET', '/releases/7/assets?per_page=100&page=1', retrying ? [asset] : []]
      responses << ['POST', "https://uploads.github.com/repos/#{R::REPOSITORY}/releases/7/assets?name=#{asset['name']}", asset] unless retrying
      api = ReleaseApi.new(responses)
      assert_equal asset, R.attach_apk(api, @env, run: runner)
      assert_empty api.responses
    end
  end

  def test_actual_apk_wrong_certificate_identity_version_split_debug_or_signature_is_rejected
    responses, _, valid = apk_setup
    %w[certificate multiple-signers package version versionName split debug signature].each do |incorrect|
      runner = lambda do |args, **|
        raise R::CommandError, 'Mock signature failure' if incorrect == 'signature'

        result = valid.call(args)
        if args.include?('verify')
          result = result.sub(PIN, 'cd' * 32) if incorrect == 'certificate'
          result += "Signer #2 certificate SHA-256 digest: #{PIN}\n" if incorrect == 'multiple-signers'
        else
          result = result.sub(R::PACKAGE_NAME, 'another.package') if incorrect == 'package'
          result = result.sub("versionCode='21'", "versionCode='20'") if incorrect == 'version'
          result = result.sub("versionName='1.1.9'", "versionName='1.1.8'") if incorrect == 'versionName'
          result = result.strip + " split='config.arm64'\n" if incorrect == 'split'
          result += "application-debuggable\n" if incorrect == 'debug'
        end
        result
      end
      api = ReleaseApi.new(responses)
      assert_raises(ArgumentError) { R.attach_apk(api, @env, run: runner) }
      assert_equal 2, api.calls.length
    end
  end

  def test_conflicting_apk_asset_never_overwritten
    responses, asset, runner = apk_setup
    responses << ['GET', '/releases/7/assets?per_page=100&page=1', [asset.merge('digest' => 'sha256:' + 'c' * 64)]]
    api = ReleaseApi.new(responses)
    error = assert_raises(ArgumentError) { R.attach_apk(api, @env, run: runner) }
    assert_includes error.message, 'never overwritten'
    assert api.calls.all? { |call| call.first == 'GET' }
  end

  def test_ci_preflight_checks_key_before_writing_configuration
    env = @env.merge('ANDROID_UPLOAD_KEYSTORE_BASE64' => Base64.strict_encode64('test store'), 'ANDROID_KEYSTORE_PASSWORD' => 'test',
                     'ANDROID_KEY_ALIAS' => 'test', 'ANDROID_KEY_PASSWORD' => 'test', 'ANDROID_UPLOAD_CERT_SHA256' => PIN,
                     'APPLICATION_PROPERTIES' => "BASE_URL_RELEASE=https://example.test\nSTRIPE_PUBLIC_KEY_RELEASE=pk_test_value\n")
    calls = []
    R.prepare_ci(env, root: @root, run: ->(args, **options) { calls << [args, options] })
    assert_equal PIN, calls.first[0].last
    refute_includes calls.first[0], 'test'
    assert calls.first[1][:capture]
    assert_equal 'test', calls.first[1][:env]['ANDROID_KEY_PASSWORD']
    assert_includes File.read(File.join(@root, 'env')), 'ANDROID_KEYSTORE_FILE='
    File.delete(File.join(@root, 'application.properties'))
    assert_raises(ArgumentError) do
      R.prepare_ci(env, root: @root, run: ->(*) { raise R::CommandError, 'Mock key failure' })
    end
    refute File.exist?(File.join(@root, 'application.properties'))
    refute File.exist?(File.join(@root, 'photos-upload.keystore'))
  end

  def provider
    { 'name' => ID::PROVIDER, 'state' => 'ACTIVE', 'oidc' => { 'issuerUri' => 'https://token.actions.githubusercontent.com' },
      'attributeMapping' => { 'google.subject' => 'assertion.sub', 'attribute.repository_id' => 'assertion.repository_id',
                              'attribute.repository_owner_id' => 'assertion.repository_owner_id' },
      'attributeCondition' => "assertion.repository_owner_id == '264203613'" }
  end

  def test_provider_and_repo_restricted_account_validation
    ID.validate_provider(provider)
    [{ 'disabled' => true }, { 'attributeCondition' => 'true' }, { 'attributeMapping' => {} }, { 'state' => 'DELETED' }].each do |changes|
      assert_raises(ArgumentError) { ID.validate_provider(provider.merge(changes)) }
    end
    policy = ->(members) { { 'bindings' => [{ 'role' => 'roles/iam.workloadIdentityUser', 'members' => members }] } }
    ID.validate_policy(policy.call([ID::MEMBER]))
    [[ID::MEMBER, 'allUsers'], [ID::MEMBER.sub('996638361', '1016678903')], []].each do |members|
      assert_raises(ArgumentError) { ID.validate_policy(policy.call(members)) }
    end
  end

  def test_identity_variables_cannot_select_explorer_and_both_requires_photos_pin
    env = @env.merge('GCP_WORKLOAD_IDENTITY_PROVIDER' => ID::PROVIDER, 'GCP_SERVICE_ACCOUNT' => ID::ACCOUNT)
    ID.configured(env)
    [{ 'GCP_SERVICE_ACCOUNT' => 'publish-explorer@graphene-play-publishing.iam.gserviceaccount.com' },
     { 'GCP_WORKLOAD_IDENTITY_PROVIDER' => 'different/provider' }, { 'PLAY_APP_SIGNING_SHA256' => '' }].each do |changes|
      assert_raises(ArgumentError) { ID.configured(env.merge(changes)) }
    end
  end

  def test_identity_remote_audit_uses_only_read_operations
    commands = []
    responses = [{ 'projectNumber' => ID::NUMBER, 'projectId' => ID::PROJECT }, provider, { 'email' => ID::ACCOUNT },
                 { 'bindings' => [{ 'role' => 'roles/iam.workloadIdentityUser', 'members' => [ID::MEMBER] }] },
                 %w[androidpublisher iam iamcredentials sts].map { |service| { 'config' => { 'name' => "#{service}.googleapis.com" } } }]
    ID.verify(run: lambda do |args, **|
      commands << args
      raise 'Unexpected gcloud operation' if responses.empty?

      JSON.generate(responses.shift)
    end)
    assert_empty responses
    assert_equal 5, commands.length
    assert commands.all? { |args| args.first == 'gcloud' && (args & %w[describe list get-iam-policy]).length == 1 }
  end

  def test_github_http_sends_json_and_streams_apk_without_redirecting_or_leaking_error_bodies
    calls = []
    response = Struct.new(:code, :body).new('200', '{"ok":true}')
    api = R::Github.new({ 'GITHUB_TOKEN' => 'test-token' }, transport: lambda do |uri, request, timeout|
      calls << [uri, request, timeout]
      response
    end)
    assert_equal({ 'ok' => true }, api.request('POST', '/git/refs', { 'sha' => SHA }))
    assert_equal "https://api.github.com/repos/#{R::REPOSITORY}/git/refs", calls.last[0].to_s
    assert_equal({ 'sha' => SHA }, JSON.parse(calls.last[1].body))
    assert_equal 'Bearer test-token', calls.last[1]['Authorization']
    path = File.join(@root, 'upload.apk')
    write(path, 'APK bytes')
    File.open(path, 'rb') do |file|
      api.request('POST', "https://uploads.github.com/repos/#{R::REPOSITORY}/releases/7/assets?name=test.apk", file, upload: true)
      assert_same file, calls.last[1].body_stream
      assert_equal File.size(path), calls.last[1].content_length
      assert_equal 300, calls.last[2]
      assert_raises(ArgumentError) { api.request('POST', 'https://example.test/upload', file, upload: true) }
    end
    response.code = '302'
    error = assert_raises(ArgumentError) { api.request('GET', '/releases') }
    assert_includes error.message, 'credentials were not forwarded'
    response.code, response.body = '403', 'secret-server-response'
    error = assert_raises(ArgumentError) { api.request('GET', '/releases') }
    refute_includes error.message, response.body
    response.code = '404'
    assert_nil api.request('GET', '/releases/tags/v1.1.9', optional: true)
  end

  def test_cli_keeps_native_tasks_and_rejects_irrelevant_production_flags
    calls = []
    R.stub(:execute, ->(args, **options) { calls << [args, options] }) do
      { 'apk' => 'buildLocalApk', 'aab' => 'buildLocalAab', 'both' => 'buildLocalBoth' }.each do |target, task|
        R.main(["build:local:#{target}"])
        assert_equal task, calls.last[0].last
        assert_equal R::ROOT, calls.last[1][:chdir]
      end
      R.main(%w[version:bump --version-name 1.2.0 --version-code 30])
      assert_equal %w[versionBump -PversionName=1.2.0 -PversionCode=30], calls.last[0].last(3)
      R.main(%w[release:both --play-track production])
      assert_includes calls.last[0], 'play_track=production'
      %w[release:verify release:build release:internal release:promote release:github].each do |command|
        assert_raises(ArgumentError) { R.main([command, '--play-track', 'production']) }
      end
      assert_raises(ArgumentError) { R.main(%w[release:internal]) }
      assert_raises(ArgumentError) { R.main(%w[version:bump --version-code bad]) }
      assert_raises(OptionParser::InvalidOption) { R.main(%w[release:both --unknown-option]) }
    end
  end

  def test_lint_baseline_contains_only_the_ten_existing_login_translation_findings
    document = REXML::Document.new(File.read(File.join(R::ROOT, 'app/lint-baseline.xml')))
    issues = document.get_elements('issues/issue')
    assert_equal 10, issues.length
    issues.each do |issue|
      assert_equal 'MissingTranslation', issue.attributes['id']
      assert_equal 'src/main/res/values/strings.xml', issue.elements['location'].attributes['file']
      assert_match(/^"login_/, issue.attributes['message'])
    end
  end

  def test_workflow_dispatch_only_and_github_page_before_android_build
    text = File.read(File.join(R::ROOT, R::WORKFLOW))
    assert_includes text, 'workflow_dispatch:'
    refute_match(/^  (push|pull_request|release|schedule):/, text.split("\npermissions:", 2).first)
    assert_operator text.index('scripts/release.rb prepare-release'), :<, text.index('fastlane android build_release')
    assert_equal 1, text.scan('fastlane android build_release').length
    %w[setup-python release.py verify-google-identity.py setup-node].each { |removed| refute_includes text, removed }
    refute_includes text, 'npm '
  end
end
