"""Check the actual Java upload-key preflight using disposable keys, never Photos' real key."""
import hashlib
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
with tempfile.TemporaryDirectory(prefix='upload-key-test-', dir=ROOT / '.release-work' if (ROOT / '.release-work').exists() else None) as tmp:
    directory = Path(tmp)
    store = directory / 'test-only.jks'
    certificate = directory / 'test.der'
    env = dict(os.environ, ANDROID_KEYSTORE_PASSWORD='test-store-password', ANDROID_KEY_PASSWORD='test-key-password', ANDROID_KEY_ALIAS='test-only')
    def run(args, **kwargs):
        return subprocess.run(args, env=env, check=True, capture_output=True, text=True, **kwargs)
    run(['keytool', '-genkeypair', '-storetype', 'JKS', '-keystore', str(store), '-alias', 'test-only',
         '-storepass:env', 'ANDROID_KEYSTORE_PASSWORD', '-keypass:env', 'ANDROID_KEY_PASSWORD',
         '-keyalg', 'RSA', '-keysize', '2048', '-validity', '2', '-dname', 'CN=Release preflight test'])
    run(['keytool', '-exportcert', '-keystore', str(store), '-alias', 'test-only',
         '-storepass:env', 'ANDROID_KEYSTORE_PASSWORD', '-file', str(certificate)])
    digest = hashlib.sha256(certificate.read_bytes()).hexdigest()
    command = ['java', str(ROOT / 'scripts/VerifyUploadKey.java'), str(store), digest]
    run(command)
    for changes in ({'ANDROID_KEYSTORE_PASSWORD': 'wrong'}, {'ANDROID_KEY_PASSWORD': 'wrong'}, {'ANDROID_KEY_ALIAS': 'missing'}):
        result = subprocess.run(command, env=dict(env, **changes), capture_output=True, text=True)
        assert result.returncode != 0 and not result.stdout.strip() and not result.stderr.strip()
    result = subprocess.run(command[:-1] + ['00' * 32], env=env, capture_output=True, text=True)
    assert result.returncode != 0
    public_store = directory / 'public-only.jks'
    run(['keytool', '-importcert', '-noprompt', '-keystore', str(public_store), '-alias', 'test-only',
         '-storepass:env', 'ANDROID_KEYSTORE_PASSWORD', '-file', str(certificate)])
    result = subprocess.run(command[:2] + [str(public_store), digest], env=env, capture_output=True, text=True)
    assert result.returncode != 0
    print('Real Java preflight accepts only an unlocked private key with the expected certificate; wrong credentials, public-only aliases and wrong pins rejected.')
