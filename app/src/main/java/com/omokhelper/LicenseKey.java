package com.omokhelper;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
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
 * 키 형식 : "OMOK1-" + base64url( 내용 5바이트 + 서명 64바이트 )
 *   내용 = [버전 1][키 번호 2][만료일 2 (1970-01-01부터 날짜 수, 0이면 무기한)]
 */
public final class LicenseKey {

    public static final String PREFIX = "OMOK1-";
    static final int PAYLOAD_LEN = 5;
    static final int SIG_LEN = 64;

    /** 앱에 들어가는 공개 키 (X.509 SubjectPublicKeyInfo, base64) */
    static final String PUBLIC_KEY =
            "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE5Iy+BHwRFjcZwUBdiDDQHX4cuYiwRB9gDKrepXGGL5w2/6iiSbzyviu6K4C4buonY1GSmzbWNpk/N+0Oxxu1JQ==";

    public final int id;
    public final int expiryDay; // 0 = 무기한

    private LicenseKey(int id, int expiryDay) {
        this.id = id;
        this.expiryDay = expiryDay;
    }

    /** 올바른 키면 LicenseKey, 아니면 null */
    public static LicenseKey parse(String text) {
        return parse(text, PUBLIC_KEY);
    }

    static LicenseKey parse(String text, String publicKeyBase64) {
        if (text == null) return null;
        String t = text.replaceAll("\\s+", "");
        if (!t.startsWith(PREFIX)) return null;
        byte[] raw;
        try {
            raw = Base64.getUrlDecoder().decode(t.substring(PREFIX.length()));
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (raw.length != PAYLOAD_LEN + SIG_LEN || raw[0] != 1) return null;
        byte[] payload = Arrays.copyOfRange(raw, 0, PAYLOAD_LEN);
        byte[] sig = Arrays.copyOfRange(raw, PAYLOAD_LEN, raw.length);
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
        return new LicenseKey(id, exp);
    }

    /** 오늘(1970-01-01부터 날짜 수) 기준으로 만료되었는가 */
    public boolean isExpired(long todayEpochDay) {
        return expiryDay != 0 && todayEpochDay > expiryDay;
    }

    /** 서명하는 내용 : 형식 이름 + 키 내용 (다른 용도의 서명과 섞이지 않도록) */
    static byte[] signedBytes(byte[] payload) {
        byte[] head = "OMOK1".getBytes(StandardCharsets.US_ASCII);
        byte[] all = Arrays.copyOf(head, head.length + payload.length);
        System.arraycopy(payload, 0, all, head.length, payload.length);
        return all;
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
