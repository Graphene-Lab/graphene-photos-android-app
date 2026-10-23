require 'minitest/autorun'
require 'ostruct'
require 'tmpdir'
require_relative '../../scripts/download-play-apk'

class PlayApkTest < Minitest::Test
  PIN = 'ab' * 32
  VERSION = { 'name' => '1.1.9', 'code' => 21 }.freeze
  class Service
    attr_reader :calls
    def initialize(responses, bytes: 'signed universal APK', fail_download: false)
      @responses = responses
      @bytes = bytes
      @calls = []
      @fail_download = fail_download
    end
    def list_generatedapks(package, code)
      raise 'Unexpected app identity' unless package == PhotosRelease::PACKAGE_NAME && code == 21
      @calls << :list
      value = @responses.shift
      raise value if value.is_a?(Exception)
      raise 'Unexpected API call' if value.nil?
      OpenStruct.new(generated_apks: value)
    end
    def download_generatedapk(package, code, id, download_dest:)
      raise 'Wrong download identity' unless package == PhotosRelease::PACKAGE_NAME && code == 21 && id == 'universal'
      @calls << :download
      download_dest.write(@bytes)
      raise IOError, 'mock interrupted download' if @fail_download
    end
  end
  def group(pin = PIN, universal: true)
    OpenStruct.new(certificate_sha256_hash: pin, generated_universal_apk: universal ? OpenStruct.new(download_id: 'universal') : nil)
  end
  def with_download(service, **options)
    Dir.mktmpdir('photos-apk-', ENV['RUNNER_TEMP']) do |directory|
      yield directory, -> { PhotosApk.download(service: service, version: VERSION, directory: directory, certificate: PIN, attempts: 2, wait: -> {}, **options) }
    end
  end
  def test_waits_for_universal_without_second_build
    service = Service.new([[], [group]])
    with_download(service) do |directory, download|
      path = download.call
      assert_equal File.join(directory, 'Graphene-Photos-1.1.9.apk'), path
      assert_equal 'signed universal APK', File.read(path)
      assert_equal [:list, :list, :download], service.calls
    end
  end
  def test_different_certificate_duplicate_or_no_universal_rejected
    [[[group('cd' * 32)]], [[group, group]], [[group(universal: false)], [group(universal: false)]]].each do |responses|
      service = Service.new(responses)
      with_download(service) do |_directory, download|
        assert_raises(ArgumentError, &download)
        refute_includes service.calls, :download
      end
    end
  end
  def test_transient_error_retried_but_access_denied_is_not
    error_class = Class.new(StandardError) { attr_accessor :status_code }
    error = error_class.new
    error.status_code = 503
    with_download(Service.new([error, [group]])) { |_directory, download| assert File.file?(download.call) }
    error.status_code = 403
    with_download(Service.new([error])) { |_directory, download| assert_raises(error_class, &download) }
  end
  def test_partial_or_empty_download_is_never_kept
    [Service.new([[group]], fail_download: true), Service.new([[group]], bytes: '')].each do |service|
      with_download(service) do |directory, download|
        assert_raises(StandardError, &download)
        assert_empty Dir.children(directory)
      end
    end
  end
end
