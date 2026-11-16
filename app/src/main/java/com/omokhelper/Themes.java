package com.omokhelper;

/** 패널에서 고를 수 있는 색 테마 */
public final class Themes {
    private Themes() {
    }

    public static final String[] MARKER_THEME_NAMES = {"기본", "네온", "파스텔", "고대비", "흑백"};
    /** {1순위, 2순위, 3순위 이하, 필승 수} */
    public static final int[][] MARKER_THEMES = {
            {0xFFFF3D00, 0xFFFFAB00, 0xFF00B0FF, 0xFFFF1744},
            {0xFF00E676, 0xFF00E5FF, 0xFFD500F9, 0xFFFFEA00},
            {0xFFFF8A80, 0xFFFFD180, 0xFF80D8FF, 0xFFFF80AB},
            {0xFFFF00FF, 0xFFFFFF00, 0xFF00FFFF, 0xFFFF0000},
            {0xFFFFFFFF, 0xFFBDBDBD, 0xFF757575, 0xFFFFFFFF},
    };
    public static final String[] PANEL_THEME_NAMES = {"다크", "라이트", "네이비", "우드", "반투명"};
    /** {배경, 글자, 제목} */
    public static final int[][] PANEL_THEMES = {
            {0xE6202833, 0xFFFFFFFF, 0xFFFFD54F},
            {0xF2FFFFFF, 0xFF212121, 0xFF6D4C41},
            {0xE60D1B3D, 0xFFE3F2FD, 0xFF64FFDA},
            {0xEE5D4037, 0xFFFFF8E1, 0xFFFFCC80},
            {0x80000000, 0xFFFFFFFF, 0xFFFFD54F},
    };
}
