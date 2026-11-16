package com.omokhelper;

import android.content.Context;
import android.content.SharedPreferences;

/** 앱 설정 (판 크기, 룰, 내 돌 색, 추천 개수, 바둑판 위치) */
public final class Prefs {
    public int boardSize = 15;
    public int rules = 0;            // 규칙 (OmokEngine.B_OVERLINE 등을 합친 값, 0 = 자유룰)
    public int myColor = 0;          // 0 = 차례인 쪽 자동, 1 = 흑, 2 = 백
    public int topN = 3;
    public int thinkLevel = 1;       // 0 = 빠르게(0.5초), 1 = 보통(1.5초), 2 = 깊게(3초)
    public int markerTheme = 0;      // 추천 표시 색 (Themes.MARKER_THEMES)
    public int panelTheme = 0;       // 패널 색 (Themes.PANEL_THEMES)
    public int displaySize = 1;      // 0 = 작게, 1 = 보통, 2 = 크게
    public boolean calibrated = false;
    public float x0, y0, x1, y1;     // 왼쪽 위 / 오른쪽 아래 교차점의 화면 좌표

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("omok", Context.MODE_PRIVATE);
    }

    public static Prefs load(Context c) {
        SharedPreferences s = sp(c);
        Prefs p = new Prefs();
        p.boardSize = s.getInt("boardSize", 15);
        // 예전 버전의 "렌주룰 켜기" 설정도 이어서 쓴다
        p.rules = s.contains("rules") ? s.getInt("rules", 0)
                : (s.getBoolean("renju", false) ? OmokEngine.RULE_RENJU : OmokEngine.RULE_FREE);
        p.myColor = s.getInt("myColor", 0);
        p.topN = s.getInt("topN", 3);
        p.thinkLevel = s.getInt("thinkLevel", 1);
        p.markerTheme = s.getInt("markerTheme", 0);
        p.panelTheme = s.getInt("panelTheme", 0);
        p.displaySize = s.getInt("displaySize", 1);
        p.calibrated = s.getBoolean("calibrated", false);
        p.x0 = s.getFloat("x0", 0);
        p.y0 = s.getFloat("y0", 0);
        p.x1 = s.getFloat("x1", 0);
        p.y1 = s.getFloat("y1", 0);
        // 키가 없으면 잠긴 기능은 기본값으로 (흑/백 선택, 추천 5개, 생각 시간 3초)
        if (!License.isPro(c)) {
            p.myColor = 0;
            if (p.topN > 3) p.topN = 3;
            if (p.thinkLevel > 1) p.thinkLevel = 1;
        }
        return p;
    }

    public void save(Context c) {
        sp(c).edit()
                .putInt("boardSize", boardSize)
                .putInt("rules", rules)
                .putInt("myColor", myColor)
                .putInt("topN", topN)
                .putInt("thinkLevel", thinkLevel)
                .putInt("markerTheme", markerTheme)
                .putInt("panelTheme", panelTheme)
                .putInt("displaySize", displaySize)
                .putBoolean("calibrated", calibrated)
                .putFloat("x0", x0)
                .putFloat("y0", y0)
                .putFloat("x1", x1)
                .putFloat("y1", y1)
                .apply();
    }

    /** 첫 화면의 설정 항목만 저장한다 (패널에서 저장한 바둑판 위치 등을 덮어쓰지 않도록) */
    public void saveSettings(Context c) {
        sp(c).edit()
                .putInt("boardSize", boardSize)
                .putInt("rules", rules)
                .putInt("myColor", myColor)
                .putInt("topN", topN)
                .putInt("thinkLevel", thinkLevel)
                .apply();
    }

    /** 표시 크기 배율 */
    public float sizeScale() {
        return displaySize == 0 ? 0.85f : (displaySize == 2 ? 1.25f : 1f);
    }

    // ---------------------------------------------------------------- 판 프리셋 (슬롯 1~3)
    // 판 크기 + 바둑판 위치(두 모서리 좌표)를 저장해 두고 한 번에 불러온다.

    public static final int PRESET_SLOTS = 3;

    /** 현재 판 크기와 위치를 slot(1~3)에 저장. 판 맞추기를 안 했으면 false */
    public boolean savePreset(Context c, int slot) {
        if (!calibrated) return false;
        sp(c).edit().putString("preset" + slot, boardSize + "," + x0 + "," + y0 + "," + x1 + "," + y1).apply();
        return true;
    }

    /** slot(1~3)을 불러와 현재 설정으로 저장. 비어 있으면 false */
    public boolean loadPreset(Context c, int slot) {
        String v = sp(c).getString("preset" + slot, null);
        if (v == null) return false;
        try {
            String[] a = v.split(",");
            boardSize = Integer.parseInt(a[0]);
            x0 = Float.parseFloat(a[1]);
            y0 = Float.parseFloat(a[2]);
            x1 = Float.parseFloat(a[3]);
            y1 = Float.parseFloat(a[4]);
            calibrated = true;
        } catch (RuntimeException e) {
            return false;
        }
        save(c);
        return true;
    }

    /** 버튼에 표시할 프리셋 요약 (예: "15줄"), 비어 있으면 null */
    public static String presetLabel(Context c, int slot) {
        String v = sp(c).getString("preset" + slot, null);
        if (v == null) return null;
        int comma = v.indexOf(',');
        return comma > 0 ? v.substring(0, comma) + "줄" : null;
    }

    /** AI가 생각하는 시간 (밀리초) */
    public long thinkMs() {
        return thinkLevel == 0 ? 500 : (thinkLevel == 2 ? 3000 : 1500);
    }

    /** 설정이 바뀌었는지 비교하기 위한 문자열 */
    public String key() {
        return boardSize + "/" + rules + "/" + myColor + "/" + topN + "/" + thinkLevel + "/" + calibrated + "/" + x0 + "," + y0 + "," + x1 + "," + y1;
    }
}
