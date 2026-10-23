require_relative '../scripts/release'

module PhotosRelease
  # A single parser validates native Gradle versions and notes for all entry points.
  def self.info(root: ENV.fetch('PLAY_RELEASE_SOURCE', ROOT))
    release_info('play', root)
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
