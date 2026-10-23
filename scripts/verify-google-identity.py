"""Read-only verification of the shared provider and Photos-specific service account."""
import argparse
import json
import os
import subprocess
import sys

PROJECT = 'graphene-play-publishing'
NUMBER = '117253714562'
PROVIDER = f'projects/{NUMBER}/locations/global/workloadIdentityPools/github-actions/providers/github'
ACCOUNT = f'publish-photos@{PROJECT}.iam.gserviceaccount.com'
MEMBER = f'principalSet://iam.googleapis.com/projects/{NUMBER}/locations/global/workloadIdentityPools/github-actions/attribute.repository_id/996638361'


def validate_provider(provider):
    mappings = provider.get('attributeMapping', {})
    expected = {'google.subject': 'assertion.sub', 'attribute.repository_id': 'assertion.repository_id',
                'attribute.repository_owner_id': 'assertion.repository_owner_id'}
    if (provider.get('name') != PROVIDER or provider.get('state') != 'ACTIVE' or provider.get('disabled', False)
            or provider.get('oidc', {}).get('issuerUri', '').rstrip('/') != 'https://token.actions.githubusercontent.com'
            or any(mappings.get(key) != value for key, value in expected.items())
            or provider.get('attributeCondition', '').strip() != "assertion.repository_owner_id == '264203613'"):
        raise ValueError('Provider differs from the expected active Graphene-Lab GitHub OIDC configuration. Review it before reuse; do not change Explorer access.')


def validate_policy(policy):
    bindings = [b for b in policy.get('bindings', []) if b['role'] == 'roles/iam.workloadIdentityUser']
    if len(bindings) != 1 or bindings[0].get('members') != [MEMBER]:
        raise ValueError('Photos service account must grant workloadIdentityUser only to Photos repository ID 996638361.')


def configured(env=os.environ):
    if env.get('GCP_WORKLOAD_IDENTITY_PROVIDER') != PROVIDER or env.get('GCP_SERVICE_ACCOUNT') != ACCOUNT:
        raise ValueError('Configure the verified shared provider and separate publish-photos service account. Explorer credentials cannot be reused.')
    if env.get('RELEASE_ACTION') == 'verify' or (env.get('RELEASE_DESTINATION') == 'both' and env.get('RELEASE_ACTION') == 'release'):
        from release import fingerprint
        fingerprint(env.get('PLAY_APP_SIGNING_SHA256', ''))


def verify(run=subprocess.run):
    def get(*args):
        result = run(['gcloud', *args, '--format=json'], check=True, capture_output=True, text=True)
        return json.loads(result.stdout)
    project = get('projects', 'describe', PROJECT)
    if str(project.get('projectNumber')) != NUMBER or project.get('projectId') != PROJECT:
        raise ValueError('Unexpected Google Cloud project identity.')
    provider = get('iam', 'workload-identity-pools', 'providers', 'describe', 'github', '--project=' + PROJECT,
                   '--location=global', '--workload-identity-pool=github-actions')
    validate_provider(provider)
    print('Shared GitHub OIDC provider verified; no changes made.')
    account = get('iam', 'service-accounts', 'describe', ACCOUNT, '--project=' + PROJECT)
    if account.get('email') != ACCOUNT or account.get('disabled', False):
        raise ValueError('Create/enable the separate Photos service account before reuse.')
    policy = get('iam', 'service-accounts', 'get-iam-policy', ACCOUNT, '--project=' + PROJECT)
    validate_policy(policy)
    enabled = get('services', 'list', '--enabled', '--project=' + PROJECT)
    services = {service['config']['name'] for service in enabled}
    required = {'androidpublisher.googleapis.com', 'iam.googleapis.com', 'iamcredentials.googleapis.com', 'sts.googleapis.com'}
    if not required <= services:
        raise ValueError('Missing required Google APIs: ' + ', '.join(sorted(required - services)))
    print('Photos service account, repository-only federation binding and enabled APIs verified. Play permissions still need Console/read-access verification.')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--configured', action='store_true', help='Check workflow variables only; does not claim a remote IAM audit')
    args = parser.parse_args()
    try:
        configured() if args.configured else verify()
    except (ValueError, OSError, subprocess.SubprocessError) as error:
        print(f'Identity verification failed: {error}', file=sys.stderr)
        sys.exit(1)
