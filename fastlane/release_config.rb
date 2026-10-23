require 'json'
require 'open3'
require 'tmpdir'
require 'fileutils'

module PhotosRelease
  ROOT = File.expand_path('..', __dir__)
  PACKAGE_NAME = 'com.graphenelab.photosync'
  AAB_PATH = File.join(ROOT, 'app/build/outputs/bundle/release/app-release.aab')

  # A single parser validates native Gradle versions and notes for all entry points.
  def self.info(root: ENV.fetch('PLAY_RELEASE_SOURCE', ROOT))
    stdout, status = Open3.capture2(
      ENV.fetch('PYTHON', 'python'), '-c',
      'import json,sys; sys.path.insert(0,sys.argv[1]); import release; print(json.dumps(release.release_info("play",sys.argv[2])))',
      File.join(ROOT, 'scripts'), root
    )
    raise ArgumentError, 'Complete the exact versionCode Play notes in the original source commit before publishing.' unless status.success?

    JSON.parse(stdout)
  end

  def self.credentials_file
    path = ENV['GOOGLE_APPLICATION_CREDENTIALS'].to_s
    raise ArgumentError, 'OIDC Google credentials are missing.' unless File.file?(path)

    path
  end

  def self.promotion_version(value, internal_versions:, production_versions:)
    value = internal_versions.map(&:to_i).max if value.to_s.strip.empty?
    unless value.to_s.match?(/\A[1-9]\d*\z/) && value.to_i <= 2_100_000_000
      raise ArgumentError, 'No internal version available, or invalid version_code.'
    end
    code = value.to_i
    raise ArgumentError, "Version #{code} is not on internal testing." unless internal_versions.map(&:to_i).include?(code)
    if production_versions.map(&:to_i).any? { |released| released >= code }
      raise ArgumentError, 'Production already contains this version or a newer one.'
    end

    code
  end
end
