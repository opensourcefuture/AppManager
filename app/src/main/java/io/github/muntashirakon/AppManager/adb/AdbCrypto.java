/*
 * Copyright (c) 2021 Muntashir Al-Islam
 * Copyright (c) 2016 Anton Tananaev
 * Copyright (c) 2013 Cameron Gutman
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package io.github.muntashirakon.AppManager.adb;

import android.util.Base64;

import androidx.annotation.NonNull;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.interfaces.RSAPublicKey;

import javax.crypto.Cipher;

import io.github.muntashirakon.AppManager.crypto.ks.KeyPair;

/**
 * This class encapsulates the ADB cryptography functions and provides
 * an interface for the storage and retrieval of keys.
 */
class AdbCrypto {

    /**
     * An RSA keypair encapsulated by the AdbCrypto object
     */
    private KeyPair keyPair;

    /**
     * The ADB RSA key length in bits
     */
    public static final int KEY_LENGTH_BITS = 2048;

    /**
     * The ADB RSA key length in bytes
     */
    public static final int KEY_LENGTH_BYTES = KEY_LENGTH_BITS / 8;

    /**
     * The ADB RSA key length in words
     */
    public static final int KEY_LENGTH_WORDS = KEY_LENGTH_BYTES / 4;

    /**
     * The RSA signature padding as an int array
     */
    public static final int[] SIGNATURE_PADDING_AS_INT = new int[] {
        0x00, 0x01, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff,
        0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff,
        0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff,
        0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff,
        0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff,
        0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff,
        0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff,
        0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff,
        0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff,
        0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff,
        0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff,
        0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff,
        0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff,
        0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff,
        0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff,
        0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff,
        0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0x00,
        0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00,
        0x04, 0x14
    };

    /**
     * The RSA signature padding as a byte array
     */
    public static byte[] SIGNATURE_PADDING;

    static {
        SIGNATURE_PADDING = new byte[SIGNATURE_PADDING_AS_INT.length];

        for (int i = 0; i < SIGNATURE_PADDING.length; i++)
            SIGNATURE_PADDING[i] = (byte) SIGNATURE_PADDING_AS_INT[i];
    }

    /**
     * Converts a standard RSAPublicKey object to the special ADB format
     *
     * @param pubkey RSAPublicKey object to convert
     * @return Byte array containing the converted RSAPublicKey object
     */
    @NonNull
    private static byte[] convertRsaPublicKeyToAdbFormat(@NonNull RSAPublicKey pubkey) {
        /*
         * ADB literally just saves the RSAPublicKey struct to a file.
         *
         * typedef struct RSAPublicKey {
         * int len; // Length of n[] in number of uint32_t
         * uint32_t n0inv;  // -1 / n[0] mod 2^32
         * uint32_t n[RSANUMWORDS]; // modulus as little endian array
         * uint32_t rr[RSANUMWORDS]; // R^2 as little endian array
         * int exponent; // 3 or 65537
         * } RSAPublicKey;
         */

        /* ------ This part is a Java-ified version of RSA_to_RSAPublicKey from adb_host_auth.c ------ */
        BigInteger r32, r, rr, rem, n, n0inv;

        r32 = BigInteger.ZERO.setBit(32);
        n = pubkey.getModulus();
        r = BigInteger.ZERO.setBit(KEY_LENGTH_WORDS * 32);
        rr = r.modPow(BigInteger.valueOf(2), n);
        rem = n.remainder(r32);
        n0inv = rem.modInverse(r32);

        int[] myN = new int[KEY_LENGTH_WORDS];
        int[] myRr = new int[KEY_LENGTH_WORDS];
        BigInteger[] res;
        for (int i = 0; i < KEY_LENGTH_WORDS; i++) {
            res = rr.divideAndRemainder(r32);
            rr = res[0];
            rem = res[1];
            myRr[i] = rem.intValue();

            res = n.divideAndRemainder(r32);
            n = res[0];
            rem = res[1];
            myN[i] = rem.intValue();
        }

        /* ------------------------------------------------------------------------------------------- */

        ByteBuffer bbuf = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN);


        bbuf.putInt(KEY_LENGTH_WORDS);
        bbuf.putInt(n0inv.negate().intValue());
        for (int i : myN)
            bbuf.putInt(i);
        for (int i : myRr)
            bbuf.putInt(i);

        bbuf.putInt(pubkey.getPublicExponent().intValue());
        return bbuf.array();
    }

    /**
     * Creates a new AdbCrypto object from a key pair loaded from files.
     *
     * @param keyPair    RSA key pair
     * @return New AdbCrypto object
     */
    @NonNull
    public static AdbCrypto loadAdbKeyPair(KeyPair keyPair) {
        AdbCrypto crypto = new AdbCrypto();

        crypto.keyPair = keyPair;

        return crypto;
    }

    public KeyPair getKeyPair() {
        return keyPair;
    }

    /**
     * Signs the ADB SHA1 payload with the private key of this object.
     *
     * @param payload SHA1 payload to sign
     * @return Signed SHA1 payload
     * @throws GeneralSecurityException If signing fails
     */
    public byte[] signAdbTokenPayload(byte[] payload) throws GeneralSecurityException {
        Cipher c = Cipher.getInstance("RSA/ECB/NoPadding");

        c.init(Cipher.ENCRYPT_MODE, keyPair.getPrivateKey());

        c.update(SIGNATURE_PADDING);

        return c.doFinal(payload);
    }

    /**
     * Gets the RSA public key in ADB format.
     *
     * @return Byte array containing the RSA public key in ADB format.
     */
    public byte[] getAdbPublicKeyPayload() {
        byte[] convertedKey = Base64.encode(convertRsaPublicKeyToAdbFormat((RSAPublicKey) keyPair.getPublicKey()),
                Base64.NO_WRAP);

        /* The key is base64 encoded with a user@host suffix and terminated with a NUL */
        byte[] name = " AppManager\u0000".getBytes(StandardCharsets.UTF_8);
        byte[] payload = new byte[convertedKey.length + name.length];
        System.arraycopy(convertedKey, 0, payload, 0, convertedKey.length);
        System.arraycopy(name, 0, payload, convertedKey.length, name.length);
        return payload;
    }
}