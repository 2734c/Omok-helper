package com.omokhelper;

import android.content.Context;
import android.content.SharedPreferences;

import java.time.LocalDate;

/**
 * 등록한 키를 저장하고 확인한다.
 * 키가 없으면 다음 기능이 잠긴다 : 흑/백 선택(자동만 가능), 추천 5개, 생각 시간 3초.
 */
public final class License {

    private static String cachedText;
    private static LicenseKey cachedKey;

    private License() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("license", Context.MODE_PRIVATE);
    }

    /** 사용 가능한 키가 등록되어 있으면 그 키, 아니면 null */
    public static synchronized LicenseKey current(Context c) {
        String t = sp(c).getString("key", null);
        if (t == null) return null;
        if (!t.equals(cachedText)) {
            cachedText = t;
            cachedKey = LicenseKey.parse(t);
        }
        if (cachedKey == null || cachedKey.isExpired(LocalDate.now().toEpochDay())) return null;
        return cachedKey;
    }

    public static boolean isPro(Context c) {
        return current(c) != null;
    }

    /** 결과 : 0 = 등록 성공, 1 = 잘못된 키, 2 = 기간이 지난 키 */
    public static int register(Context c, String text) {
        LicenseKey k = LicenseKey.parse(text);
        if (k == null) return 1;
        if (k.isExpired(LocalDate.now().toEpochDay())) return 2;
        sp(c).edit().putString("key", text.replaceAll("\\s+", "")).apply();
        return 0;
    }

    public static void clear(Context c) {
        sp(c).edit().remove("key").apply();
    }

    /** 화면에 보여 줄 키 정보 (예: "#3 · 무기한") */
    public static String describe(LicenseKey k) {
        return "#" + k.id + " · " + (k.expiryDay == 0 ? "무기한" : LocalDate.ofEpochDay(k.expiryDay) + "까지");
    }
}
