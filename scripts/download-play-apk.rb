require 'json'
require 'fileutils'
require_relative '../fastlane/release_config'

module PhotosApk
  def self.fingerprint(value)
    result = value.to_s.delete(':').downcase
    raise ArgumentError, 'Invalid Photos Play signing SHA-256 fingerprint.' unless result.match?(/\A[0-9a-f]{64}\z/)

    result
  end

  def self.download(service:, version:, directory:, certificate:, attempts: 41, wait: -> { sleep 15 })
    code = version.fetch('code')
    name = version.fetch('name')
    unless code.is_a?(Integer) && code.between?(1, 2_100_000_000) && name.match?(/\A\d+\.\d+\.\d+\z/)
      raise ArgumentError, 'Invalid validated Photos version.'
    end
    expected = fingerprint(certificate)
    selected = nil
    attempts.times do |attempt|
      begin
        groups = service.list_generatedapks(PhotosRelease::PACKAGE_NAME, code).generated_apks || []
        matches = groups.select { |group| fingerprint(group.certificate_sha256_hash) == expected }
        raise ArgumentError, 'Duplicate Play signing certificates.' if matches.length > 1
        raise ArgumentError, 'Play returned a different app signing certificate.' if groups.any? && matches.empty?

        selected = matches.first&.generated_universal_apk&.download_id
        break unless selected.to_s.empty?
      rescue StandardError => error
        status = error.respond_to?(:status_code) ? error.status_code.to_i : nil
        raise unless [404, 429, 500, 502, 503, 504].include?(status)
      end
      wait.call if attempt < attempts - 1
    end
    raise ArgumentError, 'Play did not provide a matching universal APK within ten minutes.' if selected.to_s.empty?

    FileUtils.mkdir_p(directory)
    path = File.join(directory, "Graphene-Photos-#{name}.apk")
    partial = "#{path}.part"
    begin
      File.open(partial, 'wb') do |file|
        service.download_generatedapk(PhotosRelease::PACKAGE_NAME, code, selected, download_dest: file)
      end
      raise ArgumentError, 'Play returned an empty APK.' unless File.size?(partial)

      File.rename(partial, path)
    ensure
      File.delete(partial) if File.exist?(partial)
    end
    path
  end
end

if $PROGRAM_NAME == __FILE__
  begin
    require 'google/apis/androidpublisher_v3'
    require 'googleauth'
    service = Google::Apis::AndroidpublisherV3::AndroidPublisherService.new
    service.authorization = Google::Auth.get_application_default(['https://www.googleapis.com/auth/androidpublisher'])
    service.client_options.open_timeout_sec = 30
    service.client_options.read_timeout_sec = 120
    PhotosApk.download(
      service: service,
      version: JSON.parse(File.read(File.join(ENV.fetch('RUNNER_TEMP'), 'play-apk-release.json'))),
      directory: File.join(ENV.fetch('RUNNER_TEMP'), 'play-release-apk'),
      certificate: ENV.fetch('PLAY_APP_SIGNING_SHA256')
    )
  rescue StandardError => error
    warn(error.is_a?(ArgumentError) ? error.message : "Play APK download failed (#{error.class.name.split('::').last}); check API access/processing.")
    exit 1
  end
end
