package tv.openshelf.keeper.core;

import java.io.File;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAKeyGenParameterSpec;
import java.security.spec.RSAPrivateKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.function.Consumer;

/**
 * RSA-2048 key in the two shapes adbd needs: Android's public-key blob and SHA-1 PKCS#1 v1.5 token signatures.
 * Holds only n, d and e as numbers: Android's Conscrypt returns private keys that are not RSAPrivateCrtKey,
 * so nothing here may depend on CRT parameters or on a provider's concrete key classes.
 */
public final class AdbKey {
    static final int KEY_BITS = 2048;
    static final int KEY_BYTES = KEY_BITS / 8;
    static final int TOKEN_SIZE = 20;

    // DER DigestInfo prefix for SHA-1. adbd checks RSA_verify(NID_sha1, token, ...), i.e. the token is used as the digest.
    static final byte[] SHA1_DIGEST_INFO = {
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14};

    private final BigInteger n;
    private final BigInteger d;
    private final BigInteger e;

    private AdbKey(BigInteger n, BigInteger d, BigInteger e) throws InvalidKeySpecException {
        if (n.bitLength() != KEY_BITS) {
            throw new InvalidKeySpecException("expected RSA-" + KEY_BITS + ", got " + n.bitLength() + " bits");
        }
        this.n = n;
        this.d = d;
        this.e = e;
        byte[] probe = new byte[TOKEN_SIZE];
        if (!new BigInteger(1, sign(probe)).modPow(e, n).equals(new BigInteger(1, encode(probe)))) {
            throw new InvalidKeySpecException("private and public key do not belong together");
        }
    }

    /** Accepts any RSA private/public key objects; provider classes without the java.security.interfaces types are read via KeyFactory specs. */
    public static AdbKey fromKeys(PrivateKey privateKey, PublicKey publicKey) throws GeneralSecurityException {
        KeyFactory kf = KeyFactory.getInstance("RSA");
        BigInteger pn;
        BigInteger pd;
        if (privateKey instanceof RSAPrivateKey) {
            pn = ((RSAPrivateKey) privateKey).getModulus();
            pd = ((RSAPrivateKey) privateKey).getPrivateExponent();
        } else {
            RSAPrivateKeySpec spec = kf.getKeySpec(privateKey, RSAPrivateKeySpec.class);
            pn = spec.getModulus();
            pd = spec.getPrivateExponent();
        }
        BigInteger un;
        BigInteger ue;
        if (publicKey instanceof RSAPublicKey) {
            un = ((RSAPublicKey) publicKey).getModulus();
            ue = ((RSAPublicKey) publicKey).getPublicExponent();
        } else {
            RSAPublicKeySpec spec = kf.getKeySpec(publicKey, RSAPublicKeySpec.class);
            un = spec.getModulus();
            ue = spec.getPublicExponent();
        }
        if (!pn.equals(un)) {
            throw new InvalidKeySpecException("private and public key have different moduli");
        }
        return new AdbKey(pn, pd, ue);
    }

    public static AdbKey fromEncoded(byte[] pkcs8, byte[] x509) throws GeneralSecurityException {
        KeyFactory kf = KeyFactory.getInstance("RSA");
        return fromKeys(kf.generatePrivate(new PKCS8EncodedKeySpec(pkcs8)),
                kf.generatePublic(new X509EncodedKeySpec(x509)));
    }

    /**
     * Loads the pair (PKCS#8 private, X.509 public) or creates one. An incomplete or unparseable pair is replaced:
     * such a key can never have been approved on the TV. Read errors are thrown, so a transient I/O problem
     * never silently replaces an approved key.
     */
    public static AdbKey loadOrCreate(File privateFile, File publicFile, Consumer<String> log)
            throws IOException, GeneralSecurityException {
        if (privateFile.exists() && publicFile.exists()) {
            byte[] priv = Files.readAllBytes(privateFile.toPath());
            byte[] pub = Files.readAllBytes(publicFile.toPath());
            try {
                return fromEncoded(priv, pub);
            } catch (GeneralSecurityException | IllegalArgumentException ex) {
                log.accept("stored ADB key unusable (" + ex + "), creating a new one");
            }
        } else if (privateFile.exists() || publicFile.exists()) {
            log.accept("stored ADB key incomplete, creating a new one");
        }
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(new RSAKeyGenParameterSpec(KEY_BITS, RSAKeyGenParameterSpec.F4));
        KeyPair pair = gen.generateKeyPair();
        AdbKey key = fromKeys(pair.getPrivate(), pair.getPublic());
        writePrivate(publicFile, pair.getPublic().getEncoded());
        writePrivate(privateFile, pair.getPrivate().getEncoded());
        log.accept("new ADB key created, approval on the TV needed");
        return key;
    }

    /** struct RSAPublicKey from adb's android_pubkey.c: len, n0inv, n, rr, exponent; all little-endian. */
    public byte[] androidPublicKey() {
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        BigInteger n0inv = r32.subtract(n.mod(r32).modInverse(r32));
        BigInteger rr = BigInteger.ONE.shiftLeft(2 * KEY_BITS).mod(n);
        ByteBuffer b = ByteBuffer.allocate(4 + 4 + KEY_BYTES + KEY_BYTES + 4).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(KEY_BYTES / 4);
        b.putInt(n0inv.intValue());
        b.put(littleEndian(n));
        b.put(littleEndian(rr));
        b.putInt(e.intValue());
        return b.array();
    }

    /** AUTH RSAPUBLICKEY payload: same text as an adbkey.pub file, NUL-terminated. */
    public byte[] publicKeyPayload(String name) {
        String text = Base64.getEncoder().encodeToString(androidPublicKey()) + " " + name + "\0";
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    /** RSA_sign(NID_sha1) over the token: PKCS#1 v1.5 type-1 padding of DigestInfo(SHA-1) || token. */
    public byte[] sign(byte[] token) {
        return bigEndian(new BigInteger(1, encode(token)).modPow(d, n));
    }

    private static byte[] encode(byte[] token) {
        if (token.length != TOKEN_SIZE) {
            throw new IllegalArgumentException("token must be " + TOKEN_SIZE + " bytes, got " + token.length);
        }
        byte[] em = new byte[KEY_BYTES];
        int tLen = SHA1_DIGEST_INFO.length + token.length;
        em[1] = 0x01;
        Arrays.fill(em, 2, KEY_BYTES - tLen - 1, (byte) 0xff);
        System.arraycopy(SHA1_DIGEST_INFO, 0, em, KEY_BYTES - tLen, SHA1_DIGEST_INFO.length);
        System.arraycopy(token, 0, em, KEY_BYTES - token.length, token.length);
        return em;
    }

    /** Writes via a temp file restricted to the owner (0600) before it is renamed into place. */
    private static void writePrivate(File file, byte[] data) throws IOException {
        File tmp = new File(file.getPath() + ".tmp");
        Files.write(tmp.toPath(), new byte[0]);
        boolean modeOk = tmp.setReadable(false, false) && tmp.setReadable(true, true)
                && tmp.setWritable(false, false) && tmp.setWritable(true, true) && tmp.setExecutable(false, false);
        if (!modeOk) {
            throw new IOException("cannot restrict permissions of " + tmp);
        }
        Files.write(tmp.toPath(), data);
        if (!tmp.renameTo(file)) {
            throw new IOException("cannot store " + file);
        }
    }

    static byte[] bigEndian(BigInteger v) {
        byte[] raw = v.toByteArray();
        byte[] out = new byte[KEY_BYTES];
        int copy = Math.min(raw.length, KEY_BYTES);
        System.arraycopy(raw, raw.length - copy, out, KEY_BYTES - copy, copy);
        return out;
    }

    static byte[] littleEndian(BigInteger v) {
        byte[] be = bigEndian(v);
        byte[] le = new byte[be.length];
        for (int i = 0; i < be.length; i++) {
            le[i] = be[be.length - 1 - i];
        }
        return le;
    }
}
