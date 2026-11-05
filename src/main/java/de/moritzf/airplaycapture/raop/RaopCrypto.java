package de.moritzf.airplaycapture.raop;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.RSAPrivateKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;

/**
 * Crypto used by the RAOP (AirPlay 1) audio protocol.
 *
 * <p>The receiver identity is the well-known AirPort Express RAOP key pair. Clients ship the matching
 * public key, so they only accept a receiver that can sign the Apple-Challenge with this private key.
 * The key pair is public; open-source AirPlay 1 receivers such as Shairport Sync ship it too.
 */
public final class RaopCrypto {

    static final BigInteger MODULUS = new BigInteger(
            "E7D744F2A2E2788B6C1F55A08EB70544A8FA7945AA8BE6C62CE5F51CBDD4DC6842FE3D1083DD2EDEC1BFD4252DC02E6F"
                    + "398BDF0E6148EA84855E2E442DA6D62664F674A1F304929ADE4F6893EF2DF6E711A8C77A0D91C9D980822E50D12922AF"
                    + "EA40EA9F0E14C0F76938C5F3882FC0323DD9FE55155F51BB5921C201629FD73352D5E2EFAABF9BA048D7B813A2B6767F"
                    + "6C3CCF1EB4CE673D037B0D2EA30C5FFFEB06F8D08ADDE409571A9C689FEF10728855DD8CFB9A8BEF5C8943EF3B5FAA15"
                    + "DDE698BEDDF3599603EB3E6F61372BB628F6559F599A78BF500687AA7F4976C0562D412956F8989E18A6355BD8159782"
                    + "5E0FC875343EC782117625CDBF98447B", 16);

    private static final BigInteger PRIVATE_EXPONENT = new BigInteger(
            "E5F00C72F577D604B9A4CE4122AA84B01743EC995ACFCC7F4AB27C0B187F90665BE359DF1259818DEEED79D3B1EF845E"
                    + "4DDDDAC9A155373B5E270D8E1315001A2E527D54CDF9000A5768BC98D4446B37BBBD00B29DD8B53062133B2A6E77F4E"
                    + "E32505622904DA720FB1C12C03996DA713A0506098EDBEDECF936D0FA9CBD5929ABB0EDA35799502F9894DCB8FC569A892"
                    + "D17780324A2B6C3166E346709134B854041B867706B58FEF2A0DB922B77628B68E69693C7AF43BF2A73D0B732377A0BA1"
                    + "7B44F051E9BF79849DCB3332571FD8A70933C2D60BDEC479934A3DACA40BB6F2F37C0A9D07106EADC8B369A03F2F41C880"
                    + "098E8ADD46240DAC68CC5354F361", 16);

    static final BigInteger PUBLIC_EXPONENT = BigInteger.valueOf(65537);

    private static final SecureRandom RANDOM = new SecureRandom();

    private static final ThreadLocal<Cipher> AES = ThreadLocal.withInitial(() -> {
        try {
            return Cipher.getInstance("AES/CBC/NoPadding");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    });

    private RaopCrypto() {
    }

    public static PublicKey publicKey() {
        try {
            return KeyFactory.getInstance("RSA")
                    .generatePublic(new RSAPublicKeySpec(MODULUS, PUBLIC_EXPONENT));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static PrivateKey privateKey() {
        try {
            return KeyFactory.getInstance("RSA")
                    .generatePrivate(new RSAPrivateKeySpec(MODULUS, PRIVATE_EXPONENT));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Encrypts the AirPlay session key (RSA OAEP / SHA-1) as sent by the client in the SDP
     * {@code a=rsaaeskey} line.
     */
    public static byte[] decryptSessionKey(byte[] encryptedKey) throws Exception {
        Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPPadding");
        cipher.init(Cipher.DECRYPT_MODE, privateKey());
        return cipher.doFinal(encryptedKey);
    }

    public static byte[] encryptSessionKey(byte[] key) throws Exception {
        Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPPadding");
        cipher.init(Cipher.ENCRYPT_MODE, publicKey());
        return cipher.doFinal(key);
    }

    /**
     * Builds the {@code Apple-Response} header value: RSA/PKCS#1 v1.5 "private encrypt" of
     * challenge + local IP + MAC address, base64 encoded without padding.
     */
    public static String appleResponse(byte[] challenge, byte[] localIpv4, byte[] mac) {
        int length = Math.max(32, challenge.length + localIpv4.length + mac.length);
        byte[] data = new byte[length];
        System.arraycopy(challenge, 0, data, 0, challenge.length);
        System.arraycopy(localIpv4, 0, data, challenge.length, localIpv4.length);
        System.arraycopy(mac, 0, data, challenge.length + localIpv4.length, mac.length);
        byte[] signature = pkcs1Type1Sign(data);
        return Base64.getEncoder().withoutPadding().encodeToString(signature);
    }

    /** PKCS#1 v1.5 padding (type 1, as used by RSA_private_encrypt) followed by the raw RSA operation. */
    static byte[] pkcs1Type1Sign(byte[] data) {
        int k = (MODULUS.bitLength() + 7) / 8;
        if (data.length > k - 11) {
            throw new IllegalArgumentException("challenge too long");
        }
        byte[] block = new byte[k];
        block[0] = 0x00;
        block[1] = 0x01;
        int paddingLength = k - data.length - 3;
        // RSA_private_encrypt with PKCS#1 padding uses type 1 (0xFF) padding. Random bytes are
        // only correct for type 2 (public encryption), and clients reject such a signature.
        java.util.Arrays.fill(block, 2, 2 + paddingLength, (byte) 0xFF);
        block[2 + paddingLength] = 0x00;
        System.arraycopy(data, 0, block, 3 + paddingLength, data.length);
        byte[] signature = new BigInteger(1, block).modPow(PRIVATE_EXPONENT, MODULUS).toByteArray();
        return leftPad(signature, k);
    }

    private static byte[] leftPad(byte[] value, int length) {
        if (value.length == length) {
            return value;
        }
        byte[] out = new byte[length];
        int copy = Math.min(value.length, length);
        System.arraycopy(value, value.length - copy, out, length - copy, copy);
        return out;
    }

    /**
     * RAOP encrypts each RTP payload independently with AES-128-CBC and a fixed IV. Only complete
     * 16-byte blocks are encrypted; a trailing partial block is transmitted in the clear.
     *
     * @return number of decrypted (16-byte aligned) bytes
     */
    public static int decryptPayload(byte[] key, byte[] iv, byte[] buffer, int offset, int length)
            throws Exception {
        int aligned = length & ~0xF;
        if (aligned == 0) {
            return 0;
        }
        Cipher cipher = AES.get();
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        cipher.doFinal(buffer, offset, aligned, buffer, offset);
        return aligned;
    }

    public static byte[] randomAesKey() {
        byte[] key = new byte[16];
        RANDOM.nextBytes(key);
        return key;
    }
}
