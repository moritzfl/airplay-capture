package de.moritzf.airplaycapture.raop;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class RaopCryptoTest {

    @Test
    void appleResponseIsAValidPkcs1Type1Signature() {
        byte[] challenge = new byte[16];
        Arrays.fill(challenge, (byte) 0x0A);
        byte[] ip = { (byte) 192, (byte) 168, (byte) 178, (byte) 65 };
        byte[] mac = { 0x3E, (byte) 0xEB, 0x26, 0x24, 0x2E, (byte) 0xEC };

        byte[] response = Base64.getDecoder().decode(RaopCrypto.appleResponse(challenge, ip, mac));
        assertEquals(256, response.length);

        byte[] decoded = publicOperation(response);
        assertEquals(0x00, decoded[0]);
        assertEquals(0x01, decoded[1]);
        int separator = 2;
        while (decoded[separator] == (byte) 0xFF) {
            separator++;
        }
        assertEquals(0x00, decoded[separator]);
        assertArrayEquals(challenge, Arrays.copyOfRange(decoded, separator + 1, separator + 17));
        assertArrayEquals(ip, Arrays.copyOfRange(decoded, separator + 17, separator + 21));
        assertArrayEquals(mac, Arrays.copyOfRange(decoded, separator + 21, separator + 27));
    }

    private static byte[] publicOperation(byte[] input) {
        byte[] value = new BigInteger(1, input)
            .modPow(RaopCrypto.PUBLIC_EXPONENT, RaopCrypto.MODULUS)
            .toByteArray();
        if (value.length == 256) {
            return value;
        }
        byte[] padded = new byte[256];
        System.arraycopy(
            value,
            Math.max(0, value.length - 256),
            padded,
            Math.max(0, 256 - value.length),
            Math.min(value.length, 256)
        );
        return padded;
    }
}
