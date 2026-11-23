package com.omokhelper;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Base64;

/**
 * 키(라이선스) 확인.
 *
 * 원리 : 전자서명 (ECDSA P-256)
 *  - 키를 만드는 쪽은 "비밀 키"로 키 내용에 서명한다. (비밀 키는 키 생성기를 가진 사람만 갖고 있음)
 *  - 앱에는 "공개 키"만 들어 있어서, 서명이 맞는지 확인만 할 수 있고 새 키를 만들 수는 없다.
 *  - 그래서 앱을 분석해도 키를 위조할 수 없다.
 *
 * 키 종류
 *  - OMOK2- (현재) : [버전 2][키 번호 2][만료일 2][기기 코드 5] + 서명 64
 *                    그 기기에서만 쓸 수 있고, 만료일(1970-01-01부터 날짜 수)이 반드시 있다.
 *  - OMOK1- (예전) : [버전 1][키 번호 2][만료일 2] + 서명 64
 *                    기기 제한이 없고 만료일이 없다. LEGACY_END_DAY 까지만 유예로 인정한다.
 */
public final class LicenseKey {

    public static final String PREFIX = "OMOK1-";   // 예전 방식 (기기 제한 없음)
    public static final String PREFIX2 = "OMOK2-";  // 기기 고정 + 만료일
    static final int SIG_LEN = 64;
    static final int PAYLOAD_V1 = 5, PAYLOAD_V2 = 10;

    /** 예전 방식 키를 인정하는 마지막 날 (이 날 다음부터 쓸 수 없음) */
    static final long LEGACY_END_DAY = LocalDate.of(2026, 11, 30).toEpochDay();

    /** 앱에 들어가는 공개 키 (X.509 SubjectPublicKeyInfo, base64) */
    static final String PUBLIC_KEY =
            "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE5Iy+BHwRFjcZwUBdiDDQHX4cuYiwRB9gDKrepXGGL5w2/6iiSbzyviu6K4C4buonY1GSmzbWNpk/N+0Oxxu1JQ==";

    public enum Status { OK, EXPIRED, WRONG_DEVICE, LEGACY_ENDED }

    public final int version;
    public final int id;
    public final int expiryDay; // 0 = 없음
    public final long device;   // 40비트 기기 코드, 예전 키는 -1

    private LicenseKey(int version, int id, int expiryDay, long device) {
        this.version = version;
        this.id = id;
        this.expiryDay = expiryDay;
        this.device = device;
    }

    /** 키 글자에서 키에 쓰이지 않는 글자(공백, 줄바꿈, 따옴표, 백틱 등)를 모두 뺀다 */
    public static String normalize(String text) {
        return text == null ? "" : text.replaceAll("[^A-Za-z0-9_-]", "");
    }

    /** 올바른 키면 LicenseKey, 아니면 null (기기나 기간은 check로 따로 확인) */
    public static LicenseKey parse(String text) {
        return parse(text, PUBLIC_KEY);
    }

    static LicenseKey parse(String text, String publicKeyBase64) {
        if (text == null) return null;
        String t = normalize(text);
        int version;
        String body;
        if (t.startsWith(PREFIX)) {
            version = 1;
            body = t.substring(PREFIX.length());
        } else if (t.startsWith(PREFIX2)) {
            version = 2;
            body = t.substring(PREFIX2.length());
        } else {
            return null;
        }
        byte[] raw;
        try {
            raw = Base64.getUrlDecoder().decode(body);
        } catch (IllegalArgumentException e) {
            return null;
        }
        int plen = version == 1 ? PAYLOAD_V1 : PAYLOAD_V2;
        if (raw.length != plen + SIG_LEN || raw[0] != version) return null;
        byte[] payload = Arrays.copyOfRange(raw, 0, plen);
        byte[] sig = Arrays.copyOfRange(raw, plen, raw.length);
        try {
            PublicKey pub = KeyFactory.getInstance("EC")
                    .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(publicKeyBase64)));
            Signature v = Signature.getInstance("SHA256withECDSA");
            v.initVerify(pub);
            v.update(signedBytes(payload));
            if (!v.verify(rawToDer(sig))) return null;
        } catch (Exception e) {
            return null;
        }
        int id = ((payload[1] & 0xff) << 8) | (payload[2] & 0xff);
        int exp = ((payload[3] & 0xff) << 8) | (payload[4] & 0xff);
        long device = -1;
        if (version == 2) {
            device = 0;
            for (int i = 5; i < 10; i++) device = (device << 8) | (payload[i] & 0xff);
        }
        return new LicenseKey(version, id, exp, device);
    }

    /** 오늘(1970-01-01부터 날짜 수) 기준으로 만료되었는가 */
    public boolean isExpired(long todayEpochDay) {
        return expiryDay != 0 && todayEpochDay > expiryDay;
    }

    /** 이 기기(deviceBits)에서 오늘 쓸 수 있는 키인가 */
    public Status check(long deviceBits, long today) {
        return check(deviceBits, today, LEGACY_END_DAY);
    }

    Status check(long deviceBits, long today, long legacyEndDay) {
        if (version == 1) {
            if (today > legacyEndDay) return Status.LEGACY_ENDED;
            return isExpired(today) ? Status.EXPIRED : Status.OK;
        }
        if (device != deviceBits) return Status.WRONG_DEVICE;
        if (expiryDay == 0 || today > expiryDay) return Status.EXPIRED; // 기기 고정 키는 만료일이 반드시 있다
        return Status.OK;
    }

    /** 남은 날수 (오늘 포함하지 않음, 마지막 날이면 0) */
    public long daysLeft(long today) {
        return (version == 1 ? LEGACY_END_DAY : expiryDay) - today;
    }

    /** 키가 마지막으로 유효한 날 */
    public long lastDay() {
        return version == 1 ? LEGACY_END_DAY : expiryDay;
    }

    /** 서명하는 내용 : 형식 이름 + 키 내용 (다른 용도의 서명과 섞이지 않도록) */
    static byte[] signedBytes(byte[] payload) {
        byte[] head = ("OMOK" + payload[0]).getBytes(StandardCharsets.US_ASCII);
        byte[] all = Arrays.copyOf(head, head.length + payload.length);
        System.arraycopy(payload, 0, all, head.length, payload.length);
        return all;
    }

    // ---------------------------------------------------------------- 기기 코드
    // 기기 고유 번호(ANDROID_ID)를 해시해서 40비트만 쓴다. 사람이 읽고 보낼 수 있게 XXXX-XXXX 로 보여 준다.
    // (헷갈리는 글자 I, O, 0, 1 은 쓰지 않는다)

    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    public static long deviceBits(String androidId) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256")
                    .digest(("OMOK-DEVICE|" + androidId).getBytes(StandardCharsets.UTF_8));
            long v = 0;
            for (int i = 0; i < 5; i++) v = (v << 8) | (h[i] & 0xff);
            return v;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String formatDevice(long bits) {
        StringBuilder sb = new StringBuilder();
        for (int i = 7; i >= 0; i--) {
            sb.append(ALPHABET.charAt((int) ((bits >> (i * 5)) & 31)));
            if (i == 4) sb.append('-');
        }
        return sb.toString();
    }

    /** "K7Q2-9XMA" → 40비트 값. 형식이 틀리면 -1 */
    public static long parseDevice(String code) {
        if (code == null) return -1;
        String c = code.replaceAll("[\\s-]", "").toUpperCase();
        if (c.length() != 8) return -1;
        long v = 0;
        for (int i = 0; i < 8; i++) {
            int k = ALPHABET.indexOf(c.charAt(i));
            if (k < 0) return -1;
            v = (v << 5) | k;
        }
        return v;
    }

    // ---------------------------------------------------------------- 서명 형식 변환
    // 자바의 ECDSA 서명은 DER 형식(70~72바이트)이다. 키를 짧게 하려고 r, s 두 숫자만 32바이트씩 저장한다.

    static byte[] rawToDer(byte[] raw) {
        byte[] r = derInt(Arrays.copyOfRange(raw, 0, 32));
        byte[] s = derInt(Arrays.copyOfRange(raw, 32, 64));
        byte[] out = new byte[2 + r.length + s.length];
        out[0] = 0x30;
        out[1] = (byte) (r.length + s.length);
        System.arraycopy(r, 0, out, 2, r.length);
        System.arraycopy(s, 0, out, 2 + r.length, s.length);
        return out;
    }

    private static byte[] derInt(byte[] v) {
        byte[] b = new BigInteger(1, v).toByteArray(); // 앞자리 0 정리 + 부호 바이트
        byte[] out = new byte[2 + b.length];
        out[0] = 0x02;
        out[1] = (byte) b.length;
        System.arraycopy(b, 0, out, 2, b.length);
        return out;
    }

    static byte[] derToRaw(byte[] der) {
        int i = 2;
        if ((der[1] & 0x80) != 0) i += der[1] & 0x7f;
        byte[] out = new byte[64];
        for (int k = 0; k < 2; k++) {
            if (der[i] != 0x02) throw new IllegalArgumentException("bad DER");
            int len = der[i + 1];
            byte[] n = new BigInteger(1, Arrays.copyOfRange(der, i + 2, i + 2 + len)).toByteArray();
            int start = n.length > 32 ? n.length - 32 : 0;
            int count = n.length - start;
            System.arraycopy(n, start, out, k * 32 + 32 - count, count);
            i += 2 + len;
        }
        return out;
    }
}
