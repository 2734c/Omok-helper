package com.omokhelper;

/**
 * 오버레이 표시의 크기·위치 (그리기와 인식 제외 영역 계산이 같은 값을 쓰도록 한곳에 모음).
 * 모든 길이는 "칸" 단위 (교차점 사이 거리 = 1).
 */
public final class OverlayShapes {

    private OverlayShapes() {
    }

    public static final float RING_R = 0.42f;        // 추천 고리 반지름
    public static final float RING_W1 = 0.12f;       // 1순위 고리 두께
    public static final float RING_W = 0.08f;        // 그 밖의 고리 두께
    public static final float BADGE_OFF = 0.38f;     // 순위 배지 : 교차점에서 오른쪽 위로 떨어진 거리
    public static final float BADGE_R = 0.2f;        // 순위 배지 반지름
    public static final float DOT_OFF = 0.38f;       // 인식 점 : 왼쪽 위 (돌), 오른쪽 아래 (금수 ×)
    public static final float DOT_R = 0.07f;
    public static final float CROSS_H = 0.08f;       // 금수 × 의 반 너비
    public static final float CROSS_W = 0.035f;      // 금수 × 선 두께

    static float pad(float cell) {
        return Math.max(2f, cell * 0.04f);
    }

    public static float ringWidth(float cell, float scale, int rank) {
        return Math.max(3f, cell * scale * (rank == 1 ? RING_W1 : RING_W));
    }

    /** 추천 표시 하나(고리 + 배지)의 제외 영역 2개를 out[k..]에 쓰고 다음 위치를 돌려준다 */
    public static int addMarker(float[] out, int k, float x, float y, float cell, float scale, int rank) {
        float r = cell * scale * RING_R, w = ringWidth(cell, scale, rank), pad = pad(cell);
        out[k++] = x;
        out[k++] = y;
        out[k++] = Math.max(0, r - w / 2 - pad);
        out[k++] = r + w / 2 + pad;
        out[k++] = x + cell * scale * BADGE_OFF;
        out[k++] = y - cell * scale * BADGE_OFF;
        out[k++] = 0;
        out[k++] = cell * scale * BADGE_R + pad;
        return k;
    }

    /** 인식 결과 점(kind 1·2 = 돌, 3·4 = 금수 ×)의 제외 영역 1개 */
    public static int addDot(float[] out, int k, float x, float y, float cell, int kind) {
        float pad = pad(cell);
        if (kind == 1 || kind == 2) {
            out[k++] = x - cell * DOT_OFF;
            out[k++] = y - cell * DOT_OFF;
            out[k++] = 0;
            out[k++] = cell * DOT_R + pad;
        } else {
            out[k++] = x + cell * DOT_OFF;
            out[k++] = y + cell * DOT_OFF;
            out[k++] = 0;
            out[k++] = cell * CROSS_H * 1.42f + Math.max(2f, cell * CROSS_W) + pad;
        }
        return k;
    }
}
