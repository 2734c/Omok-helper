package com.omokhelper;

import android.content.Context;
import android.content.SharedPreferences;

/** 앱 설정 (판 크기, 룰, 내 돌 색, 추천 개수, 바둑판 위치) */
public final class Prefs {
    public int boardSize = 15;
    public boolean renju = false;
    public int myColor = 0;          // 0 = 차례인 쪽 자동, 1 = 흑, 2 = 백
    public int topN = 3;
    public boolean calibrated = false;
    public float x0, y0, x1, y1;     // 왼쪽 위 / 오른쪽 아래 교차점의 화면 좌표

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("omok", Context.MODE_PRIVATE);
    }

    public static Prefs load(Context c) {
        SharedPreferences s = sp(c);
        Prefs p = new Prefs();
        p.boardSize = s.getInt("boardSize", 15);
        p.renju = s.getBoolean("renju", false);
        p.myColor = s.getInt("myColor", 0);
        p.topN = s.getInt("topN", 3);
        p.calibrated = s.getBoolean("calibrated", false);
        p.x0 = s.getFloat("x0", 0);
        p.y0 = s.getFloat("y0", 0);
        p.x1 = s.getFloat("x1", 0);
        p.y1 = s.getFloat("y1", 0);
        return p;
    }

    public void save(Context c) {
        sp(c).edit()
                .putInt("boardSize", boardSize)
                .putBoolean("renju", renju)
                .putInt("myColor", myColor)
                .putInt("topN", topN)
                .putBoolean("calibrated", calibrated)
                .putFloat("x0", x0)
                .putFloat("y0", y0)
                .putFloat("x1", x1)
                .putFloat("y1", y1)
                .apply();
    }

    /** 설정이 바뀌었는지 비교하기 위한 문자열 */
    public String key() {
        return boardSize + "/" + renju + "/" + myColor + "/" + topN + "/" + calibrated + "/" + x0 + "," + y0 + "," + x1 + "," + y1;
    }
}
