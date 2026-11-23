package com.omokhelper;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;

import java.time.LocalDate;
import java.util.UUID;

/**
 * 등록한 키를 저장하고 확인한다.
 * 키가 없거나 쓸 수 없으면 다음 기능이 잠긴다 : 흑/백 선택(자동만 가능), 추천 5개, 생각 시간 3초.
 *
 * 쓸 수 있는 키 = 서명이 맞고 + 이 기기용이고 + 기간이 지나지 않은 키.
 */
public final class License {

    /** register 결과 */
    public static final int OK = 0, BAD = 1, EXPIRED = 2, WRONG_DEVICE = 3, LEGACY_ENDED = 4;

    private static String cachedText;
    private static LicenseKey cachedKey;
    private static volatile long cachedDevice = -1;

    private License() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("license", Context.MODE_PRIVATE);
    }

    // ---------------------------------------------------------------- 기기 코드

    static long deviceBits(Context c) {
        long d = cachedDevice;
        if (d >= 0) return d;
        String id = null;
        try {
            id = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ANDROID_ID);
        } catch (Exception ignored) {
        }
        if (id == null || id.isEmpty()) { // 아주 드문 경우 : 앱이 만든 무작위 번호를 대신 쓴다
            SharedPreferences s = sp(c);
            id = s.getString("fallbackId", null);
            if (id == null) {
                id = UUID.randomUUID().toString();
                s.edit().putString("fallbackId", id).apply();
            }
        }
        d = LicenseKey.deviceBits(id);
        cachedDevice = d;
        return d;
    }

    /** 이 기기의 코드 (예: K7Q2-9XMA). 키를 받을 때 이 코드를 알려 준다. */
    public static String deviceCode(Context c) {
        return LicenseKey.formatDevice(deviceBits(c));
    }

    // ---------------------------------------------------------------- 오늘 날짜 (뒤로 가지 않는다)

    /**
     * 오늘 날짜(1970-01-01부터 날짜 수). 지금까지 본 가장 늦은 날짜보다 뒤로 가지 않는다.
     * 기기 시계를 과거로 돌려서 만료된 키를 다시 쓰는 것을 막기 위해서다.
     * (앱 데이터를 지우면 이 기록도 지워지므로 완벽하게 막지는 못한다)
     */
    static synchronized long today(Context c) {
        long real = LocalDate.now().toEpochDay();
        SharedPreferences s = sp(c);
        long seen = s.getLong("seen", 0);
        if (real > seen) {
            s.edit().putLong("seen", real).apply();
            return real;
        }
        return seen;
    }

    // ---------------------------------------------------------------- 키

    private static synchronized LicenseKey stored(Context c) {
        String t = sp(c).getString("key", null);
        if (t == null) return null;
        if (!t.equals(cachedText)) {
            cachedText = t;
            cachedKey = LicenseKey.parse(t);
        }
        return cachedKey;
    }

    /** 지금 쓸 수 있는 키가 등록되어 있으면 그 키, 아니면 null */
    public static LicenseKey current(Context c) {
        LicenseKey k = stored(c);
        if (k == null) return null;
        return k.check(deviceBits(c), today(c)) == LicenseKey.Status.OK ? k : null;
    }

    public static boolean isPro(Context c) {
        return current(c) != null;
    }

    /** 등록된 키는 있는데 쓸 수 없을 때 이유 (없으면 null) */
    public static String problem(Context c) {
        LicenseKey k = stored(c);
        if (k == null) return null;
        switch (k.check(deviceBits(c), today(c))) {
            case EXPIRED:
                return "등록한 키의 사용 기간이 끝났어요. 새 키를 받아 등록해 주세요.";
            case WRONG_DEVICE:
                return "이 기기용 키가 아니에요. 이 기기 코드로 새 키를 받아 주세요.";
            case LEGACY_ENDED:
                return "예전 방식 키는 " + LocalDate.ofEpochDay(LicenseKey.LEGACY_END_DAY) + "까지만 쓸 수 있었어요. 새 키를 받아 주세요.";
            default:
                return null;
        }
    }

    /** 남은 날수. 쓸 수 있는 키가 없으면 -1 */
    public static long daysLeft(Context c) {
        LicenseKey k = current(c);
        return k == null ? -1 : k.daysLeft(today(c));
    }

    /** 새 키 등록 (이미 키가 있으면 바꿈 = 갱신) */
    public static int register(Context c, String text) {
        LicenseKey k = LicenseKey.parse(text);
        if (k == null) return BAD;
        switch (k.check(deviceBits(c), today(c))) {
            case WRONG_DEVICE:
                return WRONG_DEVICE;
            case LEGACY_ENDED:
                return LEGACY_ENDED;
            case EXPIRED:
                return EXPIRED;
            default:
                break;
        }
        sp(c).edit().putString("key", LicenseKey.normalize(text)).apply();
        return OK;
    }

    public static void clear(Context c) {
        sp(c).edit().remove("key").apply();
    }

    /** 화면에 보여 줄 키 정보 (예: "#3 · 2026-12-23까지 (30일 남음)") */
    public static String describe(Context c, LicenseKey k) {
        long left = k.daysLeft(today(c));
        String until = LocalDate.ofEpochDay(k.lastDay()) + "까지 (" + (left <= 0 ? "오늘이 마지막 날" : left + "일 남음") + ")";
        return "#" + k.id + " · " + (k.version == 1 ? "예전 방식 키 · " : "") + until;
    }
}
