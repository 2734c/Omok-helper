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

    // ------------------------------------------------------------------ 기기 고정 키 (OMOK2)

    static String make2(PrivateKey key, int id, int expiry, long device) throws Exception {
        byte[] payload = new byte[10];
        payload[0] = 2;
        payload[1] = (byte) (id >> 8);
        payload[2] = (byte) id;
        payload[3] = (byte) (expiry >> 8);
        payload[4] = (byte) expiry;
        for (int i = 0; i < 5; i++) payload[5 + i] = (byte) (device >> (8 * (4 - i)));
        Signature s = Signature.getInstance("SHA256withECDSA");
        s.initSign(key);
        s.update(LicenseKey.signedBytes(payload));
        byte[] sig = LicenseKey.derToRaw(s.sign());
        byte[] all = Arrays.copyOf(payload, payload.length + sig.length);
        System.arraycopy(sig, 0, all, payload.length, sig.length);
        return LicenseKey.PREFIX2 + Base64.getUrlEncoder().withoutPadding().encodeToString(all);
    }

    @Test
    public void deviceKeyWorksOnlyOnItsDevice() throws Exception {
        KeyPair kp = newPair();
        long mine = LicenseKey.deviceBits("android-id-of-my-phone");
        long friend = LicenseKey.deviceBits("android-id-of-friends-phone");
        assertTrue(mine != friend);
        for (int i = 0; i < 20; i++) {
            LicenseKey k = LicenseKey.parse(make2(kp.getPrivate(), 100 + i, 20_000, mine), pub(kp));
            assertNotNull(k);
            assertEquals(2, k.version);
            assertEquals(100 + i, k.id);
            assertEquals(LicenseKey.Status.OK, k.check(mine, 19_990));
            assertEquals(LicenseKey.Status.WRONG_DEVICE, k.check(friend, 19_990));
        }
    }

    @Test
    public void deviceKeyExpiresAfterItsLastDay() throws Exception {
        KeyPair kp = newPair();
        long mine = LicenseKey.deviceBits("x");
        LicenseKey k = LicenseKey.parse(make2(kp.getPrivate(), 1, 20_000, mine), pub(kp));
        assertEquals(LicenseKey.Status.OK, k.check(mine, 20_000));          // 마지막 날까지 사용
        assertEquals(LicenseKey.Status.EXPIRED, k.check(mine, 20_001));
        assertEquals(0, k.daysLeft(20_000));
        assertEquals(10, k.daysLeft(19_990));
        // 만료일이 없는 기기 고정 키는 인정하지 않는다
        LicenseKey noExp = LicenseKey.parse(make2(kp.getPrivate(), 1, 0, mine), pub(kp));
        assertEquals(LicenseKey.Status.EXPIRED, noExp.check(mine, 19_000));
    }

    @Test
    public void tamperingWithDeviceOrExpiryBreaksSignature() throws Exception {
        KeyPair kp = newPair();
        long mine = LicenseKey.deviceBits("mine");
        String key = make2(kp.getPrivate(), 5, 20_000, mine);
        byte[] raw = Base64.getUrlDecoder().decode(key.substring(LicenseKey.PREFIX2.length()));
        for (int pos : new int[]{2, 4, 5, 9}) { // 키 번호, 만료일, 기기 코드의 바이트를 하나씩 바꿔 본다
            byte[] bad = raw.clone();
            bad[pos] ^= 1;
            String forged = LicenseKey.PREFIX2 + Base64.getUrlEncoder().withoutPadding().encodeToString(bad);
            assertNull("byte " + pos, LicenseKey.parse(forged, pub(kp)));
        }
        // OMOK1 로 이름만 바꿔도 거부
        assertNull(LicenseKey.parse("OMOK1-" + key.substring(LicenseKey.PREFIX2.length()), pub(kp)));
    }

    @Test
    public void legacyKeysAreOnlyAcceptedUntilTheGraceDay() throws Exception {
        KeyPair kp = newPair();
        LicenseKey k = LicenseKey.parse(make(kp.getPrivate(), 3, 0), pub(kp));
        assertEquals(1, k.version);
        long end = 20_000;
        assertEquals(LicenseKey.Status.OK, k.check(123, end - 5, end));
        assertEquals(LicenseKey.Status.OK, k.check(999, end, end));          // 기기와 상관없이
        assertEquals(LicenseKey.Status.LEGACY_ENDED, k.check(123, end + 1, end));
        // 앱에 정해진 실제 유예 마지막 날은 2026-11-30
        assertEquals(java.time.LocalDate.of(2026, 11, 30).toEpochDay(), LicenseKey.LEGACY_END_DAY);
    }

    @Test
    public void deviceCodeFormatAndParsing() {
        for (String id : new String[]{"a", "0123456789abcdef", "some-android-id"}) {
            long bits = LicenseKey.deviceBits(id);
            String code = LicenseKey.formatDevice(bits);
            assertTrue(code, code.matches("[A-HJ-NP-Z2-9]{4}-[A-HJ-NP-Z2-9]{4}"));
            assertEquals(bits, LicenseKey.parseDevice(code));
            assertEquals(bits, LicenseKey.parseDevice(code.toLowerCase().replace("-", " ")));
        }
        assertEquals(-1, LicenseKey.parseDevice("K7Q2-9XM"));   // 짧음
        assertEquals(-1, LicenseKey.parseDevice("K7Q2-9XM0"));  // 0 은 쓰지 않음
        assertEquals(-1, LicenseKey.parseDevice(null));
        assertTrue(LicenseKey.deviceBits("a") != LicenseKey.deviceBits("b"));
        assertEquals(LicenseKey.deviceBits("a"), LicenseKey.deviceBits("a"));
    }

    @Test
    public void pastedKeyWithQuotesAndLineBreaksIsAccepted() throws Exception {
        KeyPair kp = newPair();
        long mine = LicenseKey.deviceBits("m");
        String key = make2(kp.getPrivate(), 8, 20_000, mine);
        String messy = "```\n" + key.substring(0, 30) + "\n" + key.substring(30) + "\n```";
        assertNotNull(LicenseKey.parse(messy, pub(kp)));
        assertNotNull(LicenseKey.parse("'" + key + "' ", pub(kp)));
    }
}
