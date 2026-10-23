"""Graphene Photos release orchestration. Python stdlib only; publishing is opt-in."""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
from urllib.error import HTTPError
from urllib.parse import quote
from urllib.request import HTTPRedirectHandler, Request, build_opener

ROOT = Path(__file__).resolve().parents[1]
REPOSITORY = 'Graphene-Lab/graphene-photos-android-app'
REPOSITORY_ID = '996638361'
PACKAGE = 'com.graphenelab.photosync'
BRANCH = 'master'
WORKFLOW = '.github/workflows/android-play.yml'
AAB = Path('app/build/outputs/bundle/release/app-release.aab')
MAX_CODE = 2_100_000_000


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise ValueError('Unexpected GitHub redirect; credentials were not forwarded.')


urlopen = build_opener(NoRedirect).open


def sha256_file(filename):
    digest = hashlib.sha256()
    with Path(filename).open('rb') as file:
        for chunk in iter(lambda: file.read(1024 * 1024), b''):
            digest.update(chunk)
    return digest.hexdigest()


def version_info(root=ROOT):
    text = (Path(root) / 'app/build.gradle.kts').read_text(encoding='utf-8')
    def literal(pattern):
        values = re.findall(pattern, text, re.MULTILINE)
        if len(values) != 1:
            raise ValueError('Expected one literal applicationId, versionName and versionCode in app/build.gradle.kts.')
        return values[0]
    package = literal(r'^\s*applicationId\s*=\s*"([^"]+)"\s*$')
    name = literal(r'^\s*versionName\s*=\s*"([^"]+)"\s*$')
    code = int(literal(r'^\s*versionCode\s*=\s*(\d+)\s*$'))
    if package != PACKAGE or not re.fullmatch(r'\d+\.\d+\.\d+', name) or not 1 <= code <= MAX_CODE:
        raise ValueError('Invalid Photos package/version in app/build.gradle.kts.')
    return dict(package=package, name=name, code=code)


def read_notes(filename, limit=None):
    filename = Path(filename)
    if not filename.is_file():
        raise ValueError(f'Missing release notes: {filename}')
    text = filename.read_text(encoding='utf-8-sig').strip()
    content = re.sub(r'<!--.*?-->', '', text, flags=re.S).strip()
    if not content or re.search(r'\b(RELEASE_NOTES_TODO|TODO|TBD|PLACEHOLDER)\b', text, flags=re.I):
        raise ValueError(f'Complete and review release notes before publishing: {filename}')
    if limit and len(text) > limit:
        raise ValueError(f'Play notes exceed {limit} Unicode characters: {filename}')
    return text


def play_notes(code, root=ROOT):
    if not isinstance(code, int) or not 1 <= code <= MAX_CODE:
        raise ValueError('Invalid notes versionCode.')
    directory = Path(root) / 'fastlane/metadata/android'
    result = {'en-US': read_notes(directory / f'en-US/changelogs/{code}.txt', 500)}
    for filename in sorted(directory.glob(f'*/changelogs/{code}.txt')):
        locale = filename.parent.parent.name
        if not re.fullmatch(r'[a-z]{2,3}(?:-[A-Za-z0-9]{2,8})*', locale):
            raise ValueError(f'Invalid Play locale: {locale}')
        result[locale] = read_notes(filename, 500)
    return result


def release_info(destination, root=ROOT):
    if destination not in ('github', 'play', 'both'):
        raise ValueError('Destination must be github, play, or both.')
    info = version_info(root)
    if destination != 'play':
        info['github_notes'] = read_notes(Path(root) / f'release-notes/{info["name"]}.md')
    if destination != 'github':
        info['play_notes'] = play_notes(info['code'], root)
    return info


def context(env=os.environ):
    if (env.get('GITHUB_REPOSITORY') != REPOSITORY or env.get('GITHUB_REPOSITORY_ID') != REPOSITORY_ID
            or env.get('GITHUB_REF') != f'refs/heads/{BRANCH}' or env.get('GITHUB_EVENT_NAME') != 'workflow_dispatch'
            or not re.fullmatch('[a-f0-9]{40}', env.get('GITHUB_SHA', ''))):
        raise ValueError(f'Run this workflow manually from {REPOSITORY} on {BRANCH}.')


def positive(value, label, maximum=None):
    if not re.fullmatch('[1-9][0-9]*', str(value)) or (maximum and int(value) > maximum):
        raise ValueError(f'{label} must be a positive integer' + (f' up to {maximum}.' if maximum else '.'))
    return str(value)


def release_plan(env=os.environ):
    context(env)
    action = env.get('RELEASE_ACTION')
    destination = env.get('RELEASE_DESTINATION', 'both')
    if action not in ('verify', 'build', 'release', 'internal', 'promote'):
        raise ValueError('Unknown release action.')
    if action == 'release' and destination not in ('github', 'play', 'both'):
        raise ValueError('Select github, play, or both.')
    if action == 'internal':
        positive(env.get('PLAY_BUILD_RUN_ID', ''), 'build_run_id')
    if action == 'promote' and env.get('PLAY_VERSION_CODE', '').strip():
        positive(env['PLAY_VERSION_CODE'].strip(), 'version_code', MAX_CODE)
    play = action == 'release' and destination != 'github'
    track = env.get('PLAY_TRACK', 'internal') if play else 'internal'
    if track not in ('internal', 'production'):
        raise ValueError('Play track must be internal or production.')
    build = action == 'build' or play
    return dict(prepare_release=action == 'release', build_android=build,
                prepare_signing=build or action == 'verify', authenticate_play=play or action in ('verify', 'internal', 'promote'),
                publish_bundle=play or action == 'internal', play_track=track, download_aab=action == 'internal',
                attach_apk=action == 'release' and destination == 'both')


class Github:
    def __init__(self, env=os.environ):
        self.token = env['GITHUB_TOKEN']
        self.base = f'https://api.github.com/repos/{REPOSITORY}'

    def request(self, method, endpoint, body=None, optional=False, upload=False):
        url = endpoint if upload else self.base + endpoint
        headers = {'Authorization': f'Bearer {self.token}', 'Accept': 'application/vnd.github+json',
                   'X-GitHub-Api-Version': '2022-11-28', 'User-Agent': 'graphene-photos-release'}
        if upload:
            headers['Content-Type'] = 'application/vnd.android.package-archive'
            headers['Content-Length'] = str(os.fstat(body.fileno()).st_size)
            data = body
        else:
            data = json.dumps(body).encode() if body is not None else None
            if data:
                headers['Content-Type'] = 'application/json'
        try:
            with urlopen(Request(url, data=data, method=method, headers=headers), timeout=300 if upload else 30) as response:
                return json.load(response)
        except HTTPError as error:
            if optional and error.code == 404:
                return None
            # SDK/HTTP bodies can contain credentials. Report status only.
            raise ValueError(f'GitHub {method} failed (HTTP {error.code}).') from None


def tag_commit(api, tag):
    ref = api.request('GET', f'/git/ref/tags/{tag}', optional=True)
    if ref is None:
        return None
    obj = ref.get('object', {})
    for _ in range(6):
        if not re.fullmatch('[a-f0-9]{40}', obj.get('sha', '')):
            break
        if obj.get('type') == 'commit':
            return obj['sha']
        if obj.get('type') != 'tag':
            break
        obj = api.request('GET', f'/git/tags/{obj["sha"]}').get('object', {})
    raise ValueError('Cannot resolve version tag to an immutable source commit.')


def prepare_github(api, destination, root=ROOT, env=os.environ):
    context(env)
    info = release_info(destination, root)  # Both files checked before any GitHub mutation.
    tag = f'v{info["name"]}'
    sha = env['GITHUB_SHA']
    existing = tag_commit(api, tag)
    if existing and existing != sha:
        raise ValueError(f'{tag} points to different code. Bump the version; existing tags are never moved.')
    if not existing:
        # Create a lightweight ref directly to the exact workflow commit. Never PATCH a ref.
        api.request('POST', '/git/refs', {'ref': f'refs/tags/{tag}', 'sha': sha})
        if tag_commit(api, tag) != sha:
            raise ValueError('Version tag did not resolve to the workflow source commit.')
    if destination != 'play':
        release = api.request('GET', f'/releases/tags/{tag}', optional=True)
        body = None
        if not release or not (release.get('body') or '').strip():
            generated = api.request('POST', '/releases/generate-notes', {'tag_name': tag, 'target_commitish': sha})
            body = info['github_notes'] + '\n\n' + generated.get('body', '').strip()
        if not release:
            release = api.request('POST', '/releases', dict(tag_name=tag, target_commitish=sha, name=tag,
                                  body=body, draft=False, prerelease=False))
        elif release.get('draft') or body is not None:
            changes = {'draft': False}
            if body is not None:
                changes['body'] = body
            release = api.request('PATCH', f'/releases/{positive(release["id"], "release ID")}', changes)
        if release.get('tag_name') != tag or release.get('draft') is not False:
            raise ValueError('GitHub Release was not published. Android release has stopped.')
        print(f'GitHub Release {tag} published for {sha}; Android steps may now proceed.')
    return info


def write_json(filename, value):
    Path(filename).parent.mkdir(parents=True, exist_ok=True)
    Path(filename).write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


def fingerprint(value):
    result = value.replace(':', '').lower()
    if not re.fullmatch('[a-f0-9]{64}', result):
        raise ValueError('Configure Photos PLAY_APP_SIGNING_SHA256 from Play Console app signing key certificate.')
    return result


def execute(args, **kwargs):
    # Never interpolate shell strings or pass signing passwords on the command line.
    return subprocess.run(args, check=True, **kwargs)


def prepare_ci(env=os.environ, root=ROOT, run=execute):
    keys = ('ANDROID_UPLOAD_KEYSTORE_BASE64', 'ANDROID_KEYSTORE_PASSWORD', 'ANDROID_KEY_ALIAS',
            'ANDROID_KEY_PASSWORD', 'ANDROID_UPLOAD_CERT_SHA256', 'APPLICATION_PROPERTIES', 'RUNNER_TEMP', 'GITHUB_ENV')
    missing = [key for key in keys if not env.get(key, '').strip()]
    if missing:
        raise ValueError('Missing CI configuration: ' + ', '.join(missing))
    expected = fingerprint(env['ANDROID_UPLOAD_CERT_SHA256'])
    config = env['APPLICATION_PROPERTIES']
    props = dict(re.findall(r'^\s*([^#!\s=]+)\s*=\s*(.*?)\s*$', config, re.M))
    if any(not props.get(key) or props[key].lower() in ('null', 'change_me', 'todo') for key in ('BASE_URL_RELEASE', 'STRIPE_PUBLIC_KEY_RELEASE')):
        raise ValueError('APPLICATION_PROPERTIES needs BASE_URL_RELEASE and STRIPE_PUBLIC_KEY_RELEASE.')
    try:
        raw = base64.b64decode(re.sub(r'\s', '', env['ANDROID_UPLOAD_KEYSTORE_BASE64']), validate=True)
    except ValueError:
        raise ValueError('ANDROID_UPLOAD_KEYSTORE_BASE64 must contain valid Base64.') from None
    if not raw:
        raise ValueError('Upload keystore is empty.')
    store = Path(env['RUNNER_TEMP']) / 'photos-upload.keystore'
    store.write_bytes(raw)
    store.chmod(0o600)
    try:
        run(['java', str(Path(root) / 'scripts/VerifyUploadKey.java'), str(store), expected],
            env=dict(env), capture_output=True, text=True, timeout=45)
    except (subprocess.SubprocessError, OSError):
        store.unlink(missing_ok=True)
        raise ValueError('Photos upload-key verification failed. Check keystore, alias, passwords and registered upload SHA-256.') from None
    (Path(root) / 'application.properties').write_text(config, encoding='utf-8')
    with Path(env['GITHUB_ENV']).open('a', encoding='utf-8') as out:
        out.write(f'ANDROID_KEYSTORE_FILE={store}\n')
    print('Photos upload key and application configuration verified; private credentials are not logged.')


def record_build(root=ROOT, env=os.environ):
    context(env)
    info = version_info(root)
    info.update(source_sha=env['GITHUB_SHA'], aab_sha256=sha256_file(Path(root) / AAB))
    write_json(Path(root) / AAB.parent / 'release-build.json', info)
    return info


def resolve_build(api, run_id, env=os.environ):
    context(env)
    positive(run_id, 'build_run_id')
    run = api.request('GET', f'/actions/runs/{run_id}')
    if (str(run.get('repository', {}).get('id')) != REPOSITORY_ID or run.get('path') != WORKFLOW
            or run.get('event') != 'workflow_dispatch' or run.get('head_branch') != BRANCH
            or str(run.get('head_repository', {}).get('id')) != REPOSITORY_ID
            or run.get('status') != 'completed' or run.get('conclusion') != 'success'
            or not re.fullmatch('[a-f0-9]{40}', run.get('head_sha', ''))):
        raise ValueError('Choose a successful manual Photos build from this workflow on master.')
    sha = run['head_sha']
    artifacts = api.request('GET', f'/actions/runs/{run_id}/artifacts?per_page=100')
    matches = [a for a in artifacts.get('artifacts', []) if a['name'] == f'graphene-photos-{sha}' and not a.get('expired')]
    if len(matches) != 1:
        raise ValueError('The run must contain exactly one unexpired Photos AAB artifact.')
    artifact = matches[0]
    positive(artifact['id'], 'artifact ID')
    if not re.fullmatch('sha256:[a-f0-9]{64}', artifact.get('digest', '')):
        raise ValueError('The artifact needs a GitHub SHA-256 digest.')
    source = Path(tempfile.mkdtemp(prefix='photos-build-source-', dir=env['RUNNER_TEMP']))
    def read(filename, optional=False):
        file = api.request('GET', f'/contents/{filename}?ref={sha}', optional=optional)
        if file is None:
            return
        if file.get('type') != 'file' or file.get('encoding') != 'base64':
            raise ValueError('Cannot read the selected build source files.')
        target = source / filename
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(base64.b64decode(file['content']))
    read('app/build.gradle.kts')
    version = version_info(source)
    locales = api.request('GET', f'/contents/fastlane/metadata/android?ref={sha}', optional=True)
    if not isinstance(locales, list):
        raise ValueError('The original build source has no committed Play notes.')
    for locale in locales:
        if locale['type'] != 'dir':
            continue
        language = locale['name']
        if not re.fullmatch(r'[a-z]{2,3}(?:-[A-Za-z0-9]{2,8})*', language):
            raise ValueError('Invalid Play locale in the original source commit.')
        read(f'fastlane/metadata/android/{language}/changelogs/{version["code"]}.txt', optional=language != 'en-US')
    release_info('play', source)  # Must fail before downloading/uploading an unpublishable artifact.
    result = dict(source=str(source), sha=sha, version=version, artifact_id=artifact['id'])
    write_json(Path(env['RUNNER_TEMP']) / 'selected-build.json', result)
    with Path(env['GITHUB_ENV']).open('a', encoding='utf-8') as out:
        out.write(f'PLAY_RELEASE_SOURCE={source}\nPLAY_AAB_ARTIFACT_ID={artifact["id"]}\n')
    print(f'Using original Play notes and AAB from run {run_id}, source {sha}.')
    return result


def verify_build(root=ROOT, env=os.environ):
    selected = json.loads((Path(env['RUNNER_TEMP']) / 'selected-build.json').read_text())
    recorded = json.loads((Path(root) / AAB.parent / 'release-build.json').read_text())
    actual = sha256_file(Path(root) / AAB)
    expected = dict(selected['version'], source_sha=selected['sha'], aab_sha256=actual)
    if recorded != expected:
        raise ValueError('AAB digest, package/version or source provenance does not match the original build.')
    print('Original build AAB digest and package/version/source provenance verified.')


def resolve_apk(api, info, env=os.environ):
    context(env)
    tag = f'v{info["name"]}'
    if tag_commit(api, tag) != env['GITHUB_SHA']:
        raise ValueError('APK release tag points at different source.')
    release = api.request('GET', f'/releases/tags/{tag}')
    if release.get('tag_name') != tag or release.get('draft') is not False:
        raise ValueError('The GitHub Release must already be published.')
    positive(release['id'], 'release ID')
    return dict(name=info['name'], code=info['code'], tag=tag, source_sha=env['GITHUB_SHA'], release_id=release['id'])


def attach_apk(api, env=os.environ, run=execute):
    context(env)
    if env.get('RELEASE_ACTION') != 'release' or env.get('RELEASE_DESTINATION') != 'both':
        raise ValueError('APK attachment runs only as part of Both.')
    recorded = json.loads((Path(env['RUNNER_TEMP']) / 'play-apk-release.json').read_text())
    if resolve_apk(api, recorded, env) != recorded:
        raise ValueError('GitHub Release changed during APK processing.')
    expected = fingerprint(env.get('PLAY_APP_SIGNING_SHA256', ''))
    filename = f'Graphene-Photos-{recorded["name"]}.apk'
    apk = Path(env['RUNNER_TEMP']) / 'play-release-apk' / filename
    tools = Path(env.get('ANDROID_HOME') or env['ANDROID_SDK_ROOT']) / 'build-tools/35.0.0'
    def output(args):
        try:
            return run(args, capture_output=True, text=True, timeout=60).stdout
        except (subprocess.SubprocessError, OSError):
            raise ValueError('Downloaded APK signature/manifest verification failed.') from None
    cert = output(['java', '-jar', str(tools / 'lib/apksigner.jar'), 'verify', '--print-certs', str(apk)])
    signers = re.findall(r'^Signer #\d+ certificate SHA-256 digest:\s*([a-fA-F0-9:]+)\s*$', cert, re.M)
    if len(signers) != 1 or fingerprint(signers[0]) != expected:
        raise ValueError('Actual APK signing certificate does not match Photos Play app signing key.')
    manifest = output([str(tools / ('aapt2.exe' if os.name == 'nt' else 'aapt2')), 'dump', 'badging', str(apk)])
    package_line = re.search(r'^package: (.+)$', manifest, re.M)
    attrs = dict(re.findall(r"(\w+)='([^']*)'", package_line[1] if package_line else ''))
    if (attrs.get('name') != PACKAGE or attrs.get('versionName') != recorded['name']
            or attrs.get('versionCode') != str(recorded['code']) or 'split' in attrs
            or re.search(r'^application-debuggable\b', manifest, re.M)):
        raise ValueError('Actual APK package/version is wrong, split, or debuggable.')
    size = apk.stat().st_size
    if not size:
        raise ValueError('Downloaded APK is empty.')
    digest = 'sha256:' + sha256_file(apk)
    def check(asset):
        if any(asset.get(key) != value for key, value in dict(name=filename, state='uploaded', size=size, digest=digest).items()):
            raise ValueError('Conflicting or incomplete APK asset; existing assets are never overwritten.')
    release_id = recorded['release_id']
    for page in range(1, 11):
        assets = api.request('GET', f'/releases/{release_id}/assets?per_page=100&page={page}')
        existing = [a for a in assets if a['name'] == filename]
        if existing:
            if len(existing) != 1:
                raise ValueError('Duplicate APK release assets.')
            check(existing[0])
            return existing[0]
        if len(assets) < 100:
            break
    else:
        raise ValueError('Too many release assets to verify safely.')
    with apk.open('rb') as file:
        asset = api.request('POST', f'https://uploads.github.com/repos/{REPOSITORY}/releases/{release_id}/assets?name={quote(filename)}', file, upload=True)
    check(asset)
    print(f'Attached verified Play-signed {filename} ({recorded["code"]}).')
    return asset


def dispatch(action, track='internal', version_code=None, build_run_id=None, run=execute):
    args = ['gh', 'workflow', 'run', 'android-play.yml', '--repo', REPOSITORY, '--ref', BRANCH]
    if action in ('github', 'play', 'both'):
        if track not in ('internal', 'production') or (action == 'github' and track != 'internal'):
            raise ValueError('Play track applies to play/both and must be internal or production.')
        fields = dict(action='release', destination=action)
        if action != 'github':
            fields['play_track'] = track
    elif action in ('verify', 'build', 'internal', 'promote'):
        fields = dict(action=action)
        if action == 'internal':
            fields['build_run_id'] = positive(build_run_id or '', 'build_run_id')
        if action == 'promote' and version_code is not None:
            fields['version_code'] = positive(version_code, 'version_code', MAX_CODE)
    else:
        raise ValueError('Unknown workflow command.')
    for key, value in fields.items():
        args += ['-f', f'{key}={value}']
    print(f'Requesting {fields} from GitHub {BRANCH}. Only already-pushed source is used.', flush=True)
    run(args, env=dict(os.environ, GH_PROMPT_DISABLED='1'))


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    for action in ('github', 'play', 'both', 'verify', 'build', 'internal', 'promote'):
        sub = commands.add_parser(f'release:{action}', help=f'Manually dispatch {action} on GitHub master')
        if action in ('play', 'both'):
            sub.add_argument('--play-track', choices=('internal', 'production'), default='internal')
        if action == 'internal':
            sub.add_argument('build_run_id')
        if action == 'promote':
            sub.add_argument('version_code', nargs='?')
    for target in ('apk', 'aab', 'both'):
        commands.add_parser(f'build:local:{target}', help='Build current local files; never publish')
    bump = commands.add_parser('version:bump', help='Run native Gradle versionBump task')
    bump.add_argument('--version-name')
    bump.add_argument('--version-code', type=int)
    notes = commands.add_parser('notes', help='Validate required notes without publishing')
    notes.add_argument('destination', choices=('github', 'play', 'both'))
    for action in ('plan', 'prepare-ci', 'prepare-release', 'record-build', 'resolve-build', 'verify-build', 'resolve-apk', 'attach-apk'):
        commands.add_parser(action, help='Internal workflow step')
    args = parser.parse_args(argv)
    command = args.command
    if command.startswith('release:'):
        dispatch(command.split(':')[1], getattr(args, 'play_track', 'internal'), getattr(args, 'version_code', None), getattr(args, 'build_run_id', None))
    elif command.startswith('build:local:') or command == 'version:bump':
        wrapper = str(ROOT / 'gradlew.bat') if os.name == 'nt' else 'bash'
        prefix = [wrapper] if os.name == 'nt' else [wrapper, str(ROOT / 'gradlew')]
        task = {'apk': 'buildLocalApk', 'aab': 'buildLocalAab', 'both': 'buildLocalBoth'}.get(command.split(':')[-1], 'versionBump')
        options = []
        if getattr(args, 'version_name', None):
            if not re.fullmatch(r'\d+\.\d+\.\d+', args.version_name):
                raise ValueError('versionName must be X.Y.Z.')
            options += [f'-PversionName={args.version_name}']
        if getattr(args, 'version_code', None) is not None:
            options += [f'-PversionCode={positive(args.version_code, "versionCode", MAX_CODE)}']
        execute(prefix + [task] + options, cwd=ROOT)
    elif command == 'notes':
        print(json.dumps(release_info(args.destination), ensure_ascii=False, indent=2))
    elif command == 'plan':
        plan = release_plan()
        with Path(os.environ['GITHUB_OUTPUT']).open('a', encoding='utf-8') as file:
            file.writelines(f'{key}={str(value).lower()}\n' for key, value in plan.items())
    elif command == 'prepare-ci':
        prepare_ci()
    elif command == 'prepare-release':
        info = prepare_github(Github(), os.environ['RELEASE_DESTINATION'])
        write_json(Path(os.environ['RUNNER_TEMP']) / 'release-version.json', info)
    elif command == 'record-build':
        record_build()
    elif command == 'resolve-build':
        resolve_build(Github(), os.environ['PLAY_BUILD_RUN_ID'])
    elif command == 'verify-build':
        verify_build()
    elif command == 'resolve-apk':
        info = json.loads((Path(os.environ['RUNNER_TEMP']) / 'release-version.json').read_text())
        write_json(Path(os.environ['RUNNER_TEMP']) / 'play-apk-release.json', resolve_apk(Github(), info))
    elif command == 'attach-apk':
        attach_apk(Github())


if __name__ == '__main__':
    try:
        main()
    except (ValueError, KeyError, OSError, subprocess.SubprocessError) as error:
        print(f'Release command failed: {error}', file=sys.stderr)
        sys.exit(1)
