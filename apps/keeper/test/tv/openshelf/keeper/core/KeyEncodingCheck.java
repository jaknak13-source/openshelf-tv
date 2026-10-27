package tv.openshelf.keeper.core;

import java.io.File;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Arrays;
import java.util.Base64;

/**
 * Checks AdbKey against the host adb key pair: our public-key encoding must equal adbkey.pub byte for byte,
 * and our token signature must verify against the key decoded from adbkey.pub.
 * Writes only public material (public key PEM, token, signature) to the output dir for an OpenSSL cross-check.
 * Usage: KeyEncodingCheck <adbkey> <adbkey.pub> <out-dir>
 */
public final class KeyEncodingCheck {
    public static void main(String[] args) throws Exception {
        String pem = new String(Files.readAllBytes(new File(args[0]).toPath()), StandardCharsets.US_ASCII);
        String body = pem.replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "") // gitleaks:allow (strips PEM armour, no key here)
                .replaceAll("\\s", "");
        KeyFactory kf = KeyFactory.getInstance("RSA");
        // The JVM returns a CRT key; only used here to get e. AdbKey itself receives a plain RSAPrivateKey,
        // the shape Android's Conscrypt returned on the TV.
        RSAPrivateCrtKey crt = (RSAPrivateCrtKey) kf.generatePrivate(
                new PKCS8EncodedKeySpec(Base64.getMimeDecoder().decode(body)));
        PrivateKey plain = new TestKeys.PlainRsaPrivateKey(crt.getModulus(), crt.getPrivateExponent());
        check(!(plain instanceof RSAPrivateCrtKey), "host private key handed to AdbKey as non-CRT RSAPrivateKey");
        AdbKey key = AdbKey.fromKeys(plain,
                kf.generatePublic(new RSAPublicKeySpec(crt.getModulus(), crt.getPublicExponent())));

        String pubFile = new String(Files.readAllBytes(new File(args[1]).toPath()), StandardCharsets.US_ASCII).trim();
        String expectedB64 = pubFile.split(" ", 2)[0];
        String ours = new String(key.publicKeyPayload("check@host"), StandardCharsets.US_ASCII);
        String oursB64 = ours.split(" ", 2)[0];
        check(ours.endsWith(" check@host\0"), "payload ends with ' name\\0'");
        check(oursB64.equals(expectedB64), "base64 public key equals adbkey.pub (" + oursB64.length() + " chars)");
        check(Arrays.equals(Base64.getDecoder().decode(oursB64), Base64.getDecoder().decode(expectedB64)),
                "decoded blobs identical (" + Base64.getDecoder().decode(expectedB64).length + " bytes)");

        // Public key decoded from adbkey.pub, independent of the private key object.
        byte[] blob = Base64.getDecoder().decode(expectedB64);
        ByteBuffer b = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN);
        int words = b.getInt();
        b.getInt();
        byte[] nLe = new byte[words * 4];
        b.get(nLe);
        b.position(b.position() + words * 4);
        int e = b.getInt();
        BigInteger n = new BigInteger(1, reverse(nLe));
        RSAPublicKey pub = (RSAPublicKey) KeyFactory.getInstance("RSA")
                .generatePublic(new RSAPublicKeySpec(n, BigInteger.valueOf(e)));

        byte[] token = new byte[AdbKey.TOKEN_SIZE];
        new SecureRandom().nextBytes(token);
        byte[] sig = key.sign(token);
        check(sig.length == 256, "signature is 256 bytes");

        Signature v = Signature.getInstance("NONEwithRSA");
        v.initVerify(pub);
        v.update(concat(AdbKey.SHA1_DIGEST_INFO, token));
        check(v.verify(sig), "NONEwithRSA verify of DigestInfo(SHA-1)||token with adbkey.pub key");

        byte[] other = token.clone();
        other[0] ^= 1;
        v.initVerify(pub);
        v.update(concat(AdbKey.SHA1_DIGEST_INFO, other));
        check(!v.verify(sig), "signature does not verify for a different token");

        File out = new File(args[2]);
        out.mkdirs();
        Files.write(new File(out, "token.bin").toPath(), token);
        Files.write(new File(out, "sig.bin").toPath(), sig);
        String pubPem = "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(pub.getEncoded())
                + "\n-----END PUBLIC KEY-----\n";
        Files.write(new File(out, "pub.pem").toPath(), pubPem.getBytes(StandardCharsets.US_ASCII));
        System.out.println("KeyEncodingCheck: all checks passed");
    }

    static void check(boolean ok, String what) {
        System.out.println((ok ? "PASS " : "FAIL ") + what);
        if (!ok) {
            System.exit(1);
        }
    }

    static byte[] reverse(byte[] a) {
        byte[] r = new byte[a.length];
        for (int i = 0; i < a.length; i++) {
            r[i] = a[a.length - 1 - i];
        }
        return r;
    }

    static byte[] concat(byte[] a, byte[] b) {
        byte[] r = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }
}
