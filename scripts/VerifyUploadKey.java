import java.io.File;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.MessageDigest;
import java.util.HexFormat;

// Passwords stay in the environment. Verify Photos' registered upload certificate too.
class VerifyUploadKey {
    public static void main(String[] args) {
        try {
            KeyStore store = KeyStore.getInstance(new File(args[0]), System.getenv("ANDROID_KEYSTORE_PASSWORD").toCharArray());
            String alias = System.getenv("ANDROID_KEY_ALIAS");
            if (!store.isKeyEntry(alias) || !(store.getKey(alias, System.getenv("ANDROID_KEY_PASSWORD").toCharArray()) instanceof PrivateKey)) {
                System.exit(2);
            }
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(store.getCertificate(alias).getEncoded()));
            if (!digest.equals(args[1])) System.exit(3);
        } catch (Exception error) {
            // Never print exceptions, paths, key aliases, or credential values.
            System.exit(1);
        }
    }
}
