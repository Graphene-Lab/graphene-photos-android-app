# Read-only verification of the shared provider and Photos-specific service account.
require_relative 'release'

module PhotosIdentity
  PROJECT = 'graphene-play-publishing'
  NUMBER = '117253714562'
  PROVIDER = "projects/#{NUMBER}/locations/global/workloadIdentityPools/github-actions/providers/github"
  ACCOUNT = "publish-photos@#{PROJECT}.iam.gserviceaccount.com"
  MEMBER = "principalSet://iam.googleapis.com/projects/#{NUMBER}/locations/global/workloadIdentityPools/github-actions/attribute.repository_id/996638361"

  def self.validate_provider(provider)
    mappings = provider.fetch('attributeMapping', {})
    expected = { 'google.subject' => 'assertion.sub', 'attribute.repository_id' => 'assertion.repository_id',
                 'attribute.repository_owner_id' => 'assertion.repository_owner_id' }
    unless provider['name'] == PROVIDER && provider['state'] == 'ACTIVE' && !provider['disabled'] &&
           provider.dig('oidc', 'issuerUri').to_s.delete_suffix('/') == 'https://token.actions.githubusercontent.com' &&
           expected.all? { |key, value| mappings[key] == value } &&
           provider.fetch('attributeCondition', '').strip == "assertion.repository_owner_id == '264203613'"
      raise ArgumentError, 'Provider differs from the expected active Graphene-Lab GitHub OIDC configuration. Review it before reuse; do not change Explorer access.'
    end
  end

  def self.validate_policy(policy)
    bindings = policy.fetch('bindings', []).select { |binding| binding['role'] == 'roles/iam.workloadIdentityUser' }
    unless bindings.length == 1 && bindings.first['members'] == [MEMBER]
      raise ArgumentError, 'Photos service account must grant workloadIdentityUser only to Photos repository ID 996638361.'
    end
  end

  def self.configured(env = ENV)
    unless env['GCP_WORKLOAD_IDENTITY_PROVIDER'] == PROVIDER && env['GCP_SERVICE_ACCOUNT'] == ACCOUNT
      raise ArgumentError, 'Configure the verified shared provider and separate publish-photos service account. Explorer credentials cannot be reused.'
    end
    if env['RELEASE_ACTION'] == 'verify' || (env['RELEASE_DESTINATION'] == 'both' && env['RELEASE_ACTION'] == 'release')
      PhotosRelease.fingerprint(env.fetch('PLAY_APP_SIGNING_SHA256', ''))
    end
  end

  def self.verify(run: PhotosRelease.method(:execute))
    get = ->(*args) { JSON.parse(run.call(['gcloud', *args, '--format=json'], capture: true)) }
    project = get.call('projects', 'describe', PROJECT)
    unless project['projectNumber'].to_s == NUMBER && project['projectId'] == PROJECT
      raise ArgumentError, 'Unexpected Google Cloud project identity.'
    end
    provider = get.call('iam', 'workload-identity-pools', 'providers', 'describe', 'github', "--project=#{PROJECT}",
                        '--location=global', '--workload-identity-pool=github-actions')
    validate_provider(provider)
    puts 'Shared GitHub OIDC provider verified; no changes made.'
    account = get.call('iam', 'service-accounts', 'describe', ACCOUNT, "--project=#{PROJECT}")
    unless account['email'] == ACCOUNT && !account['disabled']
      raise ArgumentError, 'Create/enable the separate Photos service account before reuse.'
    end
    validate_policy(get.call('iam', 'service-accounts', 'get-iam-policy', ACCOUNT, "--project=#{PROJECT}"))
    enabled = get.call('services', 'list', '--enabled', "--project=#{PROJECT}").map { |service| service.fetch('config').fetch('name') }
    required = %w[androidpublisher.googleapis.com iam.googleapis.com iamcredentials.googleapis.com sts.googleapis.com]
    missing = required - enabled
    raise ArgumentError, "Missing required Google APIs: #{missing.sort.join(', ')}" unless missing.empty?

    puts 'Photos service account, repository-only federation binding and enabled APIs verified. Play permissions still need Console/read-access verification.'
  end

  def self.main(argv = ARGV)
    configured_only = false
    parser = OptionParser.new do |opts|
      opts.banner = 'Usage: ruby scripts/verify-google-identity.rb [--configured]'
      opts.on('--configured', 'Check workflow variables only; does not claim a remote IAM audit') { configured_only = true }
      opts.on('-h', '--help') { puts opts; return }
    end
    remaining = parser.parse(argv)
    raise ArgumentError, 'Unexpected identity command arguments.' unless remaining.empty?

    configured_only ? configured : verify
  end
end

if $PROGRAM_NAME == __FILE__
  begin
    PhotosIdentity.main
  rescue ArgumentError, OptionParser::ParseError, KeyError, SystemCallError, IOError, PhotosRelease::CommandError => error
    warn "Identity verification failed: #{error.message}"
    exit 1
  end
end
