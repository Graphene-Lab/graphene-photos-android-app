# Exercise Java's upload-key preflight using disposable keys, never Photos' real key.
require_relative '../../scripts/release'

root = PhotosRelease::ROOT
scratch = File.directory?(File.join(root, '.release-work')) ? File.join(root, '.release-work') : nil
Dir.mktmpdir('upload-key-test-', scratch) do |directory|
  store = File.join(directory, 'test-only.jks')
  certificate = File.join(directory, 'test.der')
  env = { 'ANDROID_KEYSTORE_PASSWORD' => 'test-store-password', 'ANDROID_KEY_PASSWORD' => 'test-key-password', 'ANDROID_KEY_ALIAS' => 'test-only' }
  run = ->(args) { PhotosRelease.execute(args, env: env, capture: true) }
  run.call(['keytool', '-genkeypair', '-storetype', 'JKS', '-keystore', store, '-alias', 'test-only',
            '-storepass:env', 'ANDROID_KEYSTORE_PASSWORD', '-keypass:env', 'ANDROID_KEY_PASSWORD',
            '-keyalg', 'RSA', '-keysize', '2048', '-validity', '2', '-dname', 'CN=Release preflight test'])
  run.call(['keytool', '-exportcert', '-keystore', store, '-alias', 'test-only',
            '-storepass:env', 'ANDROID_KEYSTORE_PASSWORD', '-file', certificate])
  digest = PhotosRelease.sha256_file(certificate)
  command = ['java', File.join(root, 'scripts/VerifyUploadKey.java'), store, digest]
  run.call(command)
  reject = lambda do |args, changes = {}|
    stdout, stderr, status = Open3.capture3(env.merge(changes), *args)
    raise 'Invalid upload key was accepted or private diagnostics were logged.' if status.success? || !stdout.strip.empty? || !stderr.strip.empty?
  end
  [{ 'ANDROID_KEYSTORE_PASSWORD' => 'wrong' }, { 'ANDROID_KEY_PASSWORD' => 'wrong' }, { 'ANDROID_KEY_ALIAS' => 'missing' }].each do |changes|
    reject.call(command, changes)
  end
  reject.call(command[0...-1] + ['00' * 32])
  public_store = File.join(directory, 'public-only.jks')
  run.call(['keytool', '-importcert', '-noprompt', '-keystore', public_store, '-alias', 'test-only',
            '-storepass:env', 'ANDROID_KEYSTORE_PASSWORD', '-file', certificate])
  reject.call(command.take(2) + [public_store, digest])
  # CI restoration invokes this same Ruby subprocess helper with credentials kept out of arguments/logs.
  PhotosRelease.execute([RbConfig.ruby, '-e', 'sleep 30'], capture: true, timeout: 0.1)
  raise 'Subprocess timeout was not enforced.'
rescue PhotosRelease::CommandError => error
  raise unless error.message.start_with?('Command timed out:')

  puts 'Real Java preflight accepts only the expected unlocked private key; wrong credentials/public aliases/pins rejected. Ruby subprocess timeout passed.'
end
