import base64
import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'scripts'))
import release as r

SPEC = importlib.util.spec_from_file_location('identity', r.ROOT / 'scripts/verify-google-identity.py')
identity = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(identity)
SHA = 'a' * 40
PIN = 'ab' * 32


def environment(root):
    return dict(GITHUB_REPOSITORY=r.REPOSITORY, GITHUB_REPOSITORY_ID=r.REPOSITORY_ID,
                GITHUB_REF='refs/heads/master', GITHUB_EVENT_NAME='workflow_dispatch', GITHUB_SHA=SHA,
                RELEASE_ACTION='release', RELEASE_DESTINATION='both', PLAY_TRACK='internal',
                RUNNER_TEMP=str(root), GITHUB_ENV=str(root / 'env'), ANDROID_HOME=str(root / 'sdk'),
                PLAY_APP_SIGNING_SHA256=PIN)


class Api:
    """Ordered, closed mock: any unexpected API call fails, including real publishing."""
    def __init__(self, responses):
        self.responses = list(responses)
        self.calls = []

    def request(self, method, endpoint, body=None, **kwargs):
        self.calls.append((method, endpoint, body))
        if not self.responses:
            raise AssertionError(f'Unexpected API operation: {method} {endpoint}')
        expected_method, expected_path, response = self.responses.pop(0)
        assert (method, endpoint) == (expected_method, expected_path), (method, endpoint, expected_path)
        return copy.deepcopy(response)


class ReleaseTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(dir=r.ROOT / '.release-work' if (r.ROOT / '.release-work').exists() else None)
        self.root = Path(self.temp.name)
        self.env = environment(self.root)
        self.gradle = self.root / 'app/build.gradle.kts'
        self.gradle.parent.mkdir()
        self.gradle.write_text(f'applicationId = "{r.PACKAGE}"\nversionName = "1.1.9"\nversionCode = 21\n')
        self.network = patch.object(r, 'urlopen', side_effect=AssertionError('Real network forbidden in release tests'))
        self.network.start()
        self.addCleanup(self.network.stop)
        self.addCleanup(self.temp.cleanup)

    def notes(self, github='## Detailed Markdown\n\nFixed sync.', play='Improved photo sync.'):
        for file, text in [(self.root / 'release-notes/1.1.9.md', github),
                           (self.root / 'fastlane/metadata/android/en-US/changelogs/21.txt', play)]:
            file.parent.mkdir(parents=True, exist_ok=True)
            file.write_text(text, encoding='utf-8')

    def test_native_version_and_identity(self):
        self.assertEqual(r.version_info(self.root), dict(package=r.PACKAGE, name='1.1.9', code=21))
        for text in ('applicationId = "another.app"\nversionName = "1.1.9"\nversionCode = 21',
                     'applicationId = "' + r.PACKAGE + '"\nversionName = "1.1.9"\nversionCode = 0'):
            self.gradle.write_text(text)
            with self.assertRaises(ValueError):
                r.version_info(self.root)

    def test_destination_notes_are_separate(self):
        self.notes()
        both = r.release_info('both', self.root)
        self.assertIn('Markdown', both['github_notes'])
        self.assertEqual(both['play_notes'], {'en-US': 'Improved photo sync.'})
        (self.root / 'release-notes/1.1.9.md').unlink()
        self.assertIn('play_notes', r.release_info('play', self.root))
        with self.assertRaises(ValueError):
            r.release_info('both', self.root)
        self.notes()
        (self.root / 'fastlane/metadata/android/en-US/changelogs/21.txt').unlink()
        self.assertIn('github_notes', r.release_info('github', self.root))
        with self.assertRaises(ValueError):
            r.release_info('play', self.root)

    def test_missing_blank_comment_only_or_unfinished_notes_stop_before_api(self):
        for text in ('', '  \n', '<!-- notes -->', '# Changes\nRELEASE_NOTES_TODO', 'TODO: write summary', 'TBD', 'PLACEHOLDER'):
            with self.subTest(text=text):
                self.notes(play=text)
                api = Api([])
                with self.assertRaises(ValueError):
                    r.prepare_github(api, 'both', self.root, self.env)
                self.assertEqual(api.calls, [])

    def test_unicode_limit_and_additional_exact_locale(self):
        self.notes(play='😀' * 500)
        self.assertEqual(len(r.play_notes(21, self.root)['en-US']), 500)
        self.notes(play='😀' * 501)
        with self.assertRaises(ValueError):
            r.play_notes(21, self.root)
        self.notes()
        locale = self.root / 'fastlane/metadata/android/fr-FR/changelogs/21.txt'
        locale.parent.mkdir(parents=True)
        locale.write_text('Synchronisation améliorée.', encoding='utf-8')
        self.assertEqual(r.play_notes(21, self.root)['fr-FR'], 'Synchronisation améliorée.')
        locale.write_text('x' * 501)
        with self.assertRaises(ValueError):
            r.play_notes(21, self.root)

    def test_historical_and_default_notes_do_not_substitute(self):
        self.notes()
        directory = self.root / 'fastlane/metadata/android/en-US/changelogs'
        (directory / '21.txt').unlink()
        (directory / 'default.txt').write_text('Fallback must never publish.')
        (directory / '20.txt').write_text('Old release.')
        with self.assertRaises(ValueError):
            r.play_notes(21, self.root)

    def test_plan_all_release_choices_and_default_track(self):
        for dest in ('github', 'play', 'both'):
            for track in ('internal', 'production'):
                plan = r.release_plan(dict(self.env, RELEASE_DESTINATION=dest, PLAY_TRACK=track))
                self.assertEqual(plan['build_android'], dest != 'github')
                self.assertEqual(plan['authenticate_play'], dest != 'github')
                self.assertEqual(plan['attach_apk'], dest == 'both')
                self.assertEqual(plan['play_track'], track if dest != 'github' else 'internal')
        for action in ('verify', 'build', 'internal', 'promote'):
            plan = r.release_plan(dict(self.env, RELEASE_ACTION=action, PLAY_BUILD_RUN_ID='123'))
            self.assertFalse(plan['prepare_release'])
            self.assertFalse(plan['attach_apk'])
            self.assertEqual(plan['build_android'], action == 'build')
        plan = r.release_plan({k: v for k, v in self.env.items() if k != 'PLAY_TRACK'})
        self.assertEqual(plan['play_track'], 'internal')

    def test_context_and_invalid_inputs_are_rejected(self):
        for changes in ({'GITHUB_EVENT_NAME': 'push'}, {'GITHUB_REF': 'refs/tags/v1.1.9'},
                        {'GITHUB_REPOSITORY_ID': '1016678903'}, {'GITHUB_REPOSITORY': 'fork/photos'},
                        {'PLAY_TRACK': 'beta'}, {'RELEASE_ACTION': 'internal', 'PLAY_BUILD_RUN_ID': ''},
                        {'RELEASE_ACTION': 'promote', 'PLAY_VERSION_CODE': '2100000001'}):
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                r.release_plan(dict(self.env, **changes))

    def test_github_first_creates_exact_tag_then_markdown_and_generated_changelog(self):
        self.notes()
        ref = {'object': {'type': 'commit', 'sha': SHA}}
        api = Api([('GET', '/git/ref/tags/v1.1.9', None), ('POST', '/git/refs', {}),
                   ('GET', '/git/ref/tags/v1.1.9', ref), ('GET', '/releases/tags/v1.1.9', None),
                   ('POST', '/releases/generate-notes', {'body': '## What changed\n* Generated PR'}),
                   ('POST', '/releases', {'tag_name': 'v1.1.9', 'draft': False})])
        r.prepare_github(api, 'both', self.root, self.env)
        self.assertEqual(api.calls[1][2], {'ref': 'refs/tags/v1.1.9', 'sha': SHA})
        self.assertEqual(api.calls[-1][2]['body'], '## Detailed Markdown\n\nFixed sync.\n\n## What changed\n* Generated PR')
        self.assertFalse(api.responses)

    def test_existing_lightweight_or_annotated_tag_and_nonempty_release_preserved(self):
        self.notes()
        for annotated in (False, True):
            responses = [('GET', '/git/ref/tags/v1.1.9', {'object': {'type': 'tag' if annotated else 'commit', 'sha': SHA}})]
            if annotated:
                responses += [('GET', f'/git/tags/{SHA}', {'object': {'type': 'commit', 'sha': SHA}})]
            responses += [('GET', '/releases/tags/v1.1.9', {'id': 7, 'tag_name': 'v1.1.9', 'draft': False, 'body': 'Existing description'})]
            api = Api(responses)
            r.prepare_github(api, 'github', self.root, self.env)
            self.assertTrue(all(call[0] == 'GET' for call in api.calls))

    def test_existing_draft_preserves_notes_and_is_published(self):
        self.notes()
        api = Api([('GET', '/git/ref/tags/v1.1.9', {'object': {'type': 'commit', 'sha': SHA}}),
                   ('GET', '/releases/tags/v1.1.9', {'id': 7, 'tag_name': 'v1.1.9', 'draft': True, 'body': 'Keep this'}),
                   ('PATCH', '/releases/7', {'tag_name': 'v1.1.9', 'draft': False})])
        r.prepare_github(api, 'github', self.root, self.env)
        self.assertEqual(api.calls[-1][2], {'draft': False})

    def test_blank_existing_release_receives_markdown_and_generated_notes(self):
        self.notes()
        api = Api([('GET', '/git/ref/tags/v1.1.9', {'object': {'type': 'commit', 'sha': SHA}}),
                   ('GET', '/releases/tags/v1.1.9', {'id': 7, 'tag_name': 'v1.1.9', 'draft': False, 'body': '  '}),
                   ('POST', '/releases/generate-notes', {'body': 'Generated changelog'}),
                   ('PATCH', '/releases/7', {'tag_name': 'v1.1.9', 'draft': False})])
        r.prepare_github(api, 'github', self.root, self.env)
        self.assertEqual(api.calls[-1][2]['body'], '## Detailed Markdown\n\nFixed sync.\n\nGenerated changelog')

    def test_unfinished_github_notes_stop_both_before_tagging(self):
        self.notes(github='# Changes\nRELEASE_NOTES_TODO: Write notes')
        api = Api([])
        with self.assertRaises(ValueError):
            r.prepare_github(api, 'both', self.root, self.env)
        self.assertEqual(api.calls, [])

    def test_tag_conflicts_never_mutate(self):
        self.notes()
        api = Api([('GET', '/git/ref/tags/v1.1.9', {'object': {'type': 'commit', 'sha': 'b' * 40}})])
        with self.assertRaisesRegex(ValueError, 'never moved'):
            r.prepare_github(api, 'both', self.root, self.env)
        self.assertEqual(len(api.calls), 1)

    def test_play_only_creates_tag_without_github_page(self):
        self.notes()
        api = Api([('GET', '/git/ref/tags/v1.1.9', {'object': {'type': 'commit', 'sha': SHA}})])
        r.prepare_github(api, 'play', self.root, self.env)
        self.assertEqual(len(api.calls), 1)

    def test_build_records_digest_and_immutable_source(self):
        aab = self.root / r.AAB
        aab.parent.mkdir(parents=True)
        aab.write_bytes(b'signed test bundle')
        info = r.record_build(self.root, self.env)
        selected = dict(version=r.version_info(self.root), sha=SHA)
        r.write_json(self.root / 'selected-build.json', selected)
        r.verify_build(self.root, self.env)
        self.assertEqual(info['source_sha'], SHA)
        aab.write_bytes(b'tampered')
        with self.assertRaises(ValueError):
            r.verify_build(self.root, self.env)

    def build_api(self, notes='Original build notes', changes=None):
        run = dict(repository={'id': int(r.REPOSITORY_ID)}, head_repository={'id': int(r.REPOSITORY_ID)},
                   event='workflow_dispatch', head_branch='master', status='completed', conclusion='success', path=r.WORKFLOW, head_sha=SHA)
        run.update(changes or {})
        def file(text):
            return dict(type='file', encoding='base64', content=base64.b64encode(text.encode()).decode())
        return Api([('GET', '/actions/runs/123', run),
                    ('GET', '/actions/runs/123/artifacts?per_page=100', {'artifacts': [dict(id=5, name=f'graphene-photos-{SHA}', expired=False, digest='sha256:' + 'c' * 64)]}),
                    ('GET', f'/contents/app/build.gradle.kts?ref={SHA}', file(self.gradle.read_text())),
                    ('GET', f'/contents/fastlane/metadata/android?ref={SHA}', [dict(type='dir', name='en-US')]),
                    ('GET', f'/contents/fastlane/metadata/android/en-US/changelogs/21.txt?ref={SHA}', file(notes))])

    def test_reuse_reads_notes_from_original_commit(self):
        self.notes(play='Newer checkout notes must not be used')
        api = self.build_api()
        selected = r.resolve_build(api, '123', self.env)
        self.assertEqual(r.play_notes(21, selected['source']), {'en-US': 'Original build notes'})
        self.assertIn('PLAY_AAB_ARTIFACT_ID=5', (self.root / 'env').read_text())
        self.assertFalse(api.responses)

    def test_reuse_rejects_unsuccessful_wrong_branch_fork_or_workflow(self):
        for change in ({'conclusion': 'failure'}, {'status': 'in_progress'}, {'head_branch': 'feature'},
                       {'event': 'pull_request'}, {'head_repository': {'id': 1}}, {'path': '.github/workflows/other.yml'}):
            with self.subTest(change=change), self.assertRaises(ValueError):
                r.resolve_build(self.build_api(changes=change), '123', self.env)

    def test_reuse_rejects_original_unfinished_notes(self):
        self.notes(play='Valid notes today cannot repair the original build')
        with self.assertRaises(ValueError):
            r.resolve_build(self.build_api(notes='RELEASE_NOTES_TODO'), '123', self.env)

    def test_reuse_rejects_expired_duplicate_or_undigested_artifacts(self):
        for kind in ('expired', 'duplicate', 'digest'):
            api = self.build_api()
            response = api.responses[1][2]
            if kind == 'expired':
                response['artifacts'][0]['expired'] = True
            elif kind == 'duplicate':
                response['artifacts'] *= 2
            else:
                del response['artifacts'][0]['digest']
            with self.subTest(kind=kind), self.assertRaises(ValueError):
                r.resolve_build(api, '123', self.env)

    def test_dispatch_all_choices_is_mocked_and_never_builds_locally(self):
        for action in ('github', 'play', 'both', 'verify', 'build', 'internal', 'promote'):
            run = Mock()
            r.dispatch(action, build_run_id='123', run=run)
            args = run.call_args.args[0]
            self.assertEqual(args[:4], ['gh', 'workflow', 'run', 'android-play.yml'])
            self.assertIn(r.REPOSITORY, args)
            if action in ('play', 'both'):
                self.assertIn('play_track=internal', args)
        run = Mock()
        r.dispatch('both', track='production', run=run)
        self.assertIn('play_track=production', run.call_args.args[0])
        run = Mock()
        r.dispatch('promote', version_code='21', run=run)
        self.assertIn('version_code=21', run.call_args.args[0])

    def apk_setup(self):
        info = dict(name='1.1.9', code=21, tag='v1.1.9', source_sha=SHA, release_id=7)
        r.write_json(self.root / 'play-apk-release.json', info)
        apk = self.root / 'play-release-apk/Graphene-Photos-1.1.9.apk'
        apk.parent.mkdir()
        apk.write_bytes(b'actual test APK bytes')
        asset = dict(name=apk.name, state='uploaded', size=apk.stat().st_size, digest='sha256:' + hashlib.sha256(apk.read_bytes()).hexdigest())
        responses = [('GET', '/git/ref/tags/v1.1.9', {'object': {'type': 'commit', 'sha': SHA}}),
                     ('GET', '/releases/tags/v1.1.9', {'id': 7, 'tag_name': 'v1.1.9', 'draft': False})]
        def run(args, **kwargs):
            stdout = ('Signer #1 certificate SHA-256 digest: ' + PIN if 'verify' in args else
                      f"package: name='{r.PACKAGE}' versionCode='21' versionName='1.1.9'\n")
            return subprocess.CompletedProcess(args, 0, stdout)
        return responses, asset, run

    def test_actual_apk_verified_then_uploaded_and_identical_retry_reused(self):
        for retry in (False, True):
            if (self.root / 'play-release-apk').exists():
                import shutil
                shutil.rmtree(self.root / 'play-release-apk')
            responses, asset, run = self.apk_setup()
            responses += [('GET', '/releases/7/assets?per_page=100&page=1', [asset] if retry else [])]
            if not retry:
                responses += [('POST', f'https://uploads.github.com/repos/{r.REPOSITORY}/releases/7/assets?name={asset["name"]}', asset)]
            api = Api(responses)
            self.assertEqual(r.attach_apk(api, self.env, run), asset)
            self.assertFalse(api.responses)

    def test_actual_apk_wrong_certificate_identity_version_split_debug_or_signature_is_rejected(self):
        responses, _, valid_run = self.apk_setup()
        for incorrect in ('certificate', 'package', 'version', 'versionName', 'split', 'debug', 'signature'):
            def run(args, **kwargs):
                result = valid_run(args)
                if incorrect == 'signature':
                    raise subprocess.CalledProcessError(1, args)
                if 'verify' in args and incorrect == 'certificate':
                    result.stdout = result.stdout.replace(PIN, 'cd' * 32)
                if 'verify' not in args:
                    if incorrect == 'package':
                        result.stdout = result.stdout.replace(r.PACKAGE, 'another.package')
                    if incorrect == 'version':
                        result.stdout = result.stdout.replace("versionCode='21'", "versionCode='20'")
                    if incorrect == 'versionName':
                        result.stdout = result.stdout.replace("versionName='1.1.9'", "versionName='1.1.8'")
                    if incorrect == 'split':
                        result.stdout = result.stdout.rstrip() + " split='config.arm64'\n"
                    if incorrect == 'debug':
                        result.stdout += 'application-debuggable\n'
                return result
            api = Api(responses)
            with self.subTest(incorrect=incorrect), self.assertRaises(ValueError):
                r.attach_apk(api, self.env, run)
            self.assertEqual(len(api.calls), 2)

    def test_conflicting_apk_asset_never_overwritten(self):
        responses, asset, run = self.apk_setup()
        responses += [('GET', '/releases/7/assets?per_page=100&page=1', [dict(asset, digest='sha256:' + 'c' * 64)])]
        api = Api(responses)
        with self.assertRaisesRegex(ValueError, 'never overwritten'):
            r.attach_apk(api, self.env, run)
        self.assertTrue(all(call[0] == 'GET' for call in api.calls))

    def test_ci_preflight_checks_key_before_writing_configuration(self):
        env = dict(self.env, ANDROID_UPLOAD_KEYSTORE_BASE64=base64.b64encode(b'test store').decode(),
                   ANDROID_KEYSTORE_PASSWORD='test', ANDROID_KEY_ALIAS='test', ANDROID_KEY_PASSWORD='test',
                   ANDROID_UPLOAD_CERT_SHA256=PIN, APPLICATION_PROPERTIES='BASE_URL_RELEASE=https://example.test\nSTRIPE_PUBLIC_KEY_RELEASE=pk_test_value\n')
        runner = Mock()
        r.prepare_ci(env, self.root, runner)
        args = runner.call_args.args[0]
        self.assertEqual(args[-1], PIN)
        self.assertNotIn('test', args)
        self.assertIn('ANDROID_KEYSTORE_FILE=', (self.root / 'env').read_text())
        (self.root / 'application.properties').unlink()
        runner.side_effect = subprocess.CalledProcessError(1, ['java'])
        with self.assertRaises(ValueError):
            r.prepare_ci(env, self.root, runner)
        self.assertFalse((self.root / 'application.properties').exists())
        self.assertFalse((self.root / 'photos-upload.keystore').exists())

    def test_provider_and_repo_restricted_account_validation(self):
        provider = dict(name=identity.PROVIDER, state='ACTIVE', oidc={'issuerUri': 'https://token.actions.githubusercontent.com'},
                        attributeMapping={'google.subject': 'assertion.sub', 'attribute.repository_id': 'assertion.repository_id',
                                          'attribute.repository_owner_id': 'assertion.repository_owner_id'},
                        attributeCondition="assertion.repository_owner_id == '264203613'")
        identity.validate_provider(provider)
        for change in ({'disabled': True}, {'attributeCondition': 'true'}, {'attributeMapping': {}}, {'state': 'DELETED'}):
            with self.assertRaises(ValueError):
                identity.validate_provider(dict(provider, **change))
        identity.validate_policy({'bindings': [{'role': 'roles/iam.workloadIdentityUser', 'members': [identity.MEMBER]}]})
        for members in ([identity.MEMBER, 'allUsers'], [identity.MEMBER.replace('996638361', '1016678903')], []):
            with self.assertRaises(ValueError):
                identity.validate_policy({'bindings': [{'role': 'roles/iam.workloadIdentityUser', 'members': members}]})

    def test_identity_variables_cannot_select_explorer_and_both_requires_photos_pin(self):
        env = dict(self.env, GCP_WORKLOAD_IDENTITY_PROVIDER=identity.PROVIDER, GCP_SERVICE_ACCOUNT=identity.ACCOUNT)
        identity.configured(env)
        for changes in ({'GCP_SERVICE_ACCOUNT': 'publish-explorer@graphene-play-publishing.iam.gserviceaccount.com'},
                        {'GCP_WORKLOAD_IDENTITY_PROVIDER': 'different/provider'}, {'PLAY_APP_SIGNING_SHA256': ''}):
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                identity.configured(dict(env, **changes))

    def test_lint_baseline_contains_only_the_ten_existing_login_translation_findings(self):
        import xml.etree.ElementTree as xml
        issues = xml.parse(r.ROOT / 'app/lint-baseline.xml').getroot().findall('issue')
        self.assertEqual(len(issues), 10)
        for issue in issues:
            self.assertEqual(issue.get('id'), 'MissingTranslation')
            self.assertEqual(issue.find('location').get('file'), 'src/main/res/values/strings.xml')
            self.assertRegex(issue.get('message'), r'^"login_')

    def test_workflow_dispatch_only_and_github_page_before_android_build(self):
        text = (r.ROOT / r.WORKFLOW).read_text()
        self.assertIn('workflow_dispatch:', text)
        triggers = text.split('\npermissions:', 1)[0]
        self.assertNotRegex(triggers, r'(?m)^  (push|pull_request|release|schedule):')
        self.assertLess(text.index('scripts/release.py prepare-release'), text.index('fastlane android build_release'))
        self.assertEqual(text.count('fastlane android build_release'), 1)
        self.assertNotIn('setup-node', text)
        self.assertNotIn('npm ', text)


if __name__ == '__main__':
    unittest.main()
