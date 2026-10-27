package tv.openshelf.keeper.core;

import java.math.BigInteger;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.RSAPrivateKey;

/** Key objects shaped like the ones Android's Conscrypt can return, for paths the JVM provider never takes. */
final class TestKeys {
    private TestKeys() {
    }

    /** RSA private key with modulus and private exponent only: deliberately NOT an RSAPrivateCrtKey. */
    static final class PlainRsaPrivateKey implements RSAPrivateKey {
        private final BigInteger n;
        private final BigInteger d;

        PlainRsaPrivateKey(BigInteger n, BigInteger d) {
            this.n = n;
            this.d = d;
        }

        @Override
        public BigInteger getModulus() {
            return n;
        }

        @Override
        public BigInteger getPrivateExponent() {
            return d;
        }

        @Override
        public String getAlgorithm() {
            return "RSA";
        }

        @Override
        public String getFormat() {
            return null;
        }

        @Override
        public byte[] getEncoded() {
            return null;
        }
    }

    /** Private key exposing only its PKCS#8 encoding: neither RSAPrivateKey nor RSAPrivateCrtKey. */
    static final class OpaquePrivateKey implements PrivateKey {
        private final byte[] pkcs8;

        OpaquePrivateKey(byte[] pkcs8) {
            this.pkcs8 = pkcs8.clone();
        }

        @Override
        public String getAlgorithm() {
            return "RSA";
        }

        @Override
        public String getFormat() {
            return "PKCS#8";
        }

        @Override
        public byte[] getEncoded() {
            return pkcs8.clone();
        }
    }

    /** Public key exposing only its X.509 encoding: not an RSAPublicKey. */
    static final class OpaquePublicKey implements PublicKey {
        private final byte[] x509;

        OpaquePublicKey(byte[] x509) {
            this.x509 = x509.clone();
        }

        @Override
        public String getAlgorithm() {
            return "RSA";
        }

        @Override
        public String getFormat() {
            return "X.509";
        }

        @Override
        public byte[] getEncoded() {
            return x509.clone();
        }
    }
}
