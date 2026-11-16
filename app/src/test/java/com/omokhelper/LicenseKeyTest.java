package com.omokhelper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.Arrays;
import java.util.Base64;
import org.junit.Test;

/** 테스트용 키 쌍을 새로 만들어 서명/확인 과정을 검사한다. (실제 발급 키는 넣지 않음) */
public class LicenseKeyTest {

    static KeyPair newPair() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        return g.generateKeyPair();
    }

    static String make(PrivateKey key, int id, int expiry) throws Exception {
        byte[] payload = {1, (byte) (id >> 8), (byte) id, (byte) (expiry >> 8), (byte) expiry};
        Signature s = Signature.getInstance("SHA256withECDSA");
        s.initSign(key);
        s.update(LicenseKey.signedBytes(payload));
        byte[] sig = LicenseKey.derToRaw(s.sign());
        byte[] all = Arrays.copyOf(payload, payload.length + sig.length);
        System.arraycopy(sig, 0, all, payload.length, sig.length);
        return LicenseKey.PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(all);
    }

    static String pub(KeyPair kp) {
        return Base64.getEncoder().encodeToString(kp.getPublic().getEncoded());
    }

    @Test
    public void validKeyIsAccepted() throws Exception {
        KeyPair kp = newPair();
        for (int i = 0; i < 30; i++) { // 서명 길이가 달라지는 경우까지 여러 번
            String key = make(kp.getPrivate(), 1000 + i, 0);
            LicenseKey k = LicenseKey.parse(key, pub(kp));
            assertNotNull(key, k);
            assertEquals(1000 + i, k.id);
            assertFalse(k.isExpired(99_999));
        }
    }

    @Test
    public void whitespaceAndLineBreaksAreIgnored() throws Exception {
        KeyPair kp = newPair();
        String key = make(kp.getPrivate(), 3, 0);
        String messy = "  " + key.substring(0, 20) + "\n" + key.substring(20) + " \n";
        assertNotNull(LicenseKey.parse(messy, pub(kp)));
    }

    @Test
    public void tamperedKeyIsRejected() throws Exception {
        KeyPair kp = newPair();
        String key = make(kp.getPrivate(), 5, 0);
        // 키 번호를 바꾸면 서명이 맞지 않는다
        byte[] raw = Base64.getUrlDecoder().decode(key.substring(LicenseKey.PREFIX.length()));
        raw[2] = 6;
        String forged = LicenseKey.PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        assertNull(LicenseKey.parse(forged, pub(kp)));
        // 한 글자만 바꿔도 거부
        char last = key.charAt(key.length() - 1);
        assertNull(LicenseKey.parse(key.substring(0, key.length() - 1) + (last == 'A' ? 'B' : 'A'), pub(kp)));
    }

    @Test
    public void keyFromAnotherSignerIsRejected() throws Exception {
        String key = make(newPair().getPrivate(), 1, 0);
        assertNull(LicenseKey.parse(key, pub(newPair())));
        assertNull(LicenseKey.parse(key)); // 앱에 들어 있는 실제 공개 키로도 거부
    }

    @Test
    public void garbageIsRejected() {
        assertNull(LicenseKey.parse(null));
        assertNull(LicenseKey.parse(""));
        assertNull(LicenseKey.parse("hello"));
        assertNull(LicenseKey.parse("OMOK1-!!!!"));
        assertNull(LicenseKey.parse("OMOK1-AAAA"));
    }

    @Test
    public void expiry() throws Exception {
        KeyPair kp = newPair();
        LicenseKey k = LicenseKey.parse(make(kp.getPrivate(), 9, 20_000), pub(kp));
        assertNotNull(k);
        assertFalse(k.isExpired(20_000));
        assertTrue(k.isExpired(20_001));
    }

    @Test
    public void appPublicKeyIsValid() throws Exception {
        java.security.KeyFactory.getInstance("EC").generatePublic(
                new java.security.spec.X509EncodedKeySpec(Base64.getDecoder().decode(LicenseKey.PUBLIC_KEY)));
    }
}
