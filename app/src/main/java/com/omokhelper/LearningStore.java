package com.omokhelper;

import android.content.Context;
import android.content.SharedPreferences;

/** 패배 학습 내용(배운 수), 통계, 최근 기보를 기기에 저장한다. */
public final class LearningStore {

    private static final int MAX_RECORDS = 10;

    private LearningStore() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("learning", Context.MODE_PRIVATE);
    }

    /** 저장된 배운 수를 불러와 앱 전체에서 쓰도록 한다 */
    public static synchronized LearnedBook load(Context c) {
        LearnedBook b = LearnedBook.parse(sp(c).getString("book", ""));
        LearnedBook.active = isEnabled(c) ? b : null;
        return b;
    }

    public static synchronized void saveBook(Context c, LearnedBook b) {
        sp(c).edit().putString("book", b.serialize()).apply();
    }

    public static boolean isEnabled(Context c) {
        return sp(c).getBoolean("enabled", true);
    }

    public static void setEnabled(Context c, boolean on) {
        sp(c).edit().putBoolean("enabled", on).apply();
        load(c);
    }

    /** 끝난 판 하나를 기록 (lost = 내가 진 판, lesson = 배운 내용 또는 null) */
    public static synchronized void addGame(Context c, String record, boolean lost, String lesson) {
        SharedPreferences s = sp(c);
        String recent = s.getString("records", "");
        String line = record + (lesson != null ? "   [배운 수: " + lesson + "]" : "");
        String all = recent.isEmpty() ? line : line + "\n" + recent;
        String[] parts = all.split("\n");
        StringBuilder trimmed = new StringBuilder();
        for (int i = 0; i < Math.min(MAX_RECORDS, parts.length); i++) {
            if (i > 0) trimmed.append('\n');
            trimmed.append(parts[i]);
        }
        s.edit()
                .putString("records", trimmed.toString())
                .putInt("games", s.getInt("games", 0) + 1)
                .putInt("losses", s.getInt("losses", 0) + (lost ? 1 : 0))
                .putInt("lessons", s.getInt("lessons", 0) + (lesson != null ? 1 : 0))
                .apply();
    }

    public static String recentRecords(Context c) {
        return sp(c).getString("records", "");
    }

    public static String stats(Context c) {
        SharedPreferences s = sp(c);
        LearnedBook b = LearnedBook.parse(s.getString("book", ""));
        return "기록한 판 " + s.getInt("games", 0) + " · 복기한 패배 " + s.getInt("losses", 0)
                + " · 배운 자리 " + b.size();
    }

    public static synchronized void clear(Context c) {
        boolean enabled = isEnabled(c);
        sp(c).edit().clear().putBoolean("enabled", enabled).apply();
        load(c);
    }
}
