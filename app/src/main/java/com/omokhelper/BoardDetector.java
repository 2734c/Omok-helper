package com.omokhelper;

import java.util.Arrays;

/**
 * 화면 이미지에서 바둑판의 돌을 읽어 낸다.
 *
 * 방법 : 사용자가 맞춰 둔 왼쪽 위 / 오른쪽 아래 교차점 좌표로 모든 교차점의 위치를 계산하고,
 *        각 교차점 주변 작은 원 안의 픽셀 밝기·채도의 "중앙값"을 구한다.
 *        (중앙값을 쓰면 바둑판 선이나 마지막 수 표시 같은 작은 무늬에 덜 흔들린다)
 *        바둑판 자체의 색(칸 한가운데의 색)과 비교해서
 *          - 훨씬 어두우면 흑돌
 *          - 밝고 색이 거의 없으면(무채색) 백돌
 *          - 그 밖에는 빈칸 으로 판정한다.
 */
public final class BoardDetector {

    /** 픽셀을 읽어 오는 방법 (안드로이드 화면 캡처, 테스트용 이미지 등) */
    public interface PixelSource {
        int width();

        int height();

        /** 0xRRGGBB */
        int rgb(int x, int y);
    }

    public static final int UNKNOWN = -1;

    /**
     * @param x0,y0 왼쪽 위 교차점 (이미지 좌표)
     * @param x1,y1 오른쪽 아래 교차점 (이미지 좌표)
     * @param n     줄 수 (15 또는 19)
     * @param skip  이 사각형 [left, top, right, bottom] 안의 교차점은 UNKNOWN (오버레이 창에 가려진 곳). null 가능
     * @return 길이 n*n, 값은 EMPTY / BLACK / WHITE / UNKNOWN
     */
    public static int[] detect(PixelSource src, float x0, float y0, float x1, float y1, int n, int[] skip) {
        return detect(src, x0, y0, x1, y1, n, skip, null);
    }

    /**
     * @param masks 이 앱이 화면에 그린 표시(추천 고리, 순위 배지, 인식 점 등)의 위치.
     *              4개씩 [중심x, 중심y, 안쪽 반지름, 바깥 반지름] (이미지 좌표, 안쪽 반지름 0이면 원).
     *              이 안의 픽셀은 게임 화면이 아니므로 판정에 쓰지 않는다. null 가능.
     */
    public static synchronized int[] detect(PixelSource src, float x0, float y0, float x1, float y1, int n, int[] skip,
                                            float[] masks) {
        float cw = (x1 - x0) / (n - 1);
        float ch = (y1 - y0) / (n - 1);
        float cell = Math.min(Math.abs(cw), Math.abs(ch));

        // 1) 바둑판 색 : 칸 한가운데 지점들의 중앙값
        float[] boardL = new float[(n - 1) * (n - 1)];
        float[] boardS = new float[boardL.length];
        int k = 0;
        for (int j = 0; j < n - 1; j++) {
            for (int i = 0; i < n - 1; i++) {
                float bx = x0 + (i + 0.5f) * cw, by = y0 + (j + 0.5f) * ch;
                if (masked(masks, ALL, masks == null ? 0 : masks.length / 4, bx, by)) continue;
                int c = safeRgb(src, bx, by);
                boardL[k] = lum(c);
                boardS[k] = sat(c);
                k++;
            }
        }
        if (k < 4) return detect(src, x0, y0, x1, y1, n, skip, null); // 표시가 판을 거의 다 덮은 특수한 경우
        float lb = median(boardL, k);
        float sb = median(boardS, k);

        // 2) 교차점마다 판정
        int r = Math.max(1, Math.round(cell * 0.26f));
        int step = Math.max(1, r / 4);
        int maxSamples = (2 * r / step + 2) * (2 * r / step + 2);
        float[] ls = new float[maxSamples];
        float[] ss = new float[maxSamples];

        int[] out = new int[n * n];
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                float cx = x0 + i * cw, cy = y0 + j * ch;
                if (skip != null && cx + r >= skip[0] && cx - r <= skip[2] && cy + r >= skip[1] && cy - r <= skip[3]) {
                    out[j * n + i] = UNKNOWN;
                    continue;
                }
                // 이 교차점 근처에 있는 이 앱의 표시만 골라 둔다 (빠르게 검사하기 위해)
                int near = nearMasks(masks, cx, cy, cell * 0.45f);
                int m = 0;
                for (int dy = -r; dy <= r; dy += step) {
                    for (int dx = -r; dx <= r; dx += step) {
                        if (dx * dx + dy * dy > r * r) continue;
                        if (masked(masks, nearIdx, near, cx + dx, cy + dy)) continue;
                        int c = safeRgb(src, cx + dx, cy + dy);
                        ls[m] = lum(c);
                        ss[m] = sat(c);
                        m++;
                    }
                }
                if (m < 4) { // 가운데가 거의 다 가려져 있으면 판단하지 않음 (이전 값 사용)
                    out[j * n + i] = UNKNOWN;
                    continue;
                }
                int inner = classify(median(ls, m), median(ss, m), lb, sb);
                if (inner != OmokEngine.EMPTY) {
                    // 바깥 고리(반지름 0.34칸)도 같은 색이어야 진짜 돌.
                    // 게임이 금수 자리 등에 그리는 작은 점·× 표시는 바깥 고리까지 닿지 않는다.
                    float ringR = cell * 0.34f;
                    int rc = 0;
                    for (int q = 0; q < RING_POINTS; q++) {
                        double t = 2 * Math.PI * q / RING_POINTS;
                        float px = cx + (float) (ringR * Math.cos(t)), py = cy + (float) (ringR * Math.sin(t));
                        if (masked(masks, nearIdx, near, px, py)) continue;
                        int c = safeRgb(src, px, py);
                        ringL[rc] = lum(c);
                        ringS[rc] = sat(c);
                        rc++;
                    }
                    // 바깥 고리가 이 앱의 표시에 대부분 가려졌으면 가운데 판정만 믿는다
                    if (rc >= 6) {
                        int outer = classify(median(ringL, rc), median(ringS, rc), lb, sb);
                        if (outer != inner) inner = OmokEngine.EMPTY;
                    }
                }
                out[j * n + i] = inner;
            }
        }
        return out;
    }

    private static final int RING_POINTS = 24;
    private static final int[] ALL = null;
    private static int[] nearIdx = new int[64];

    /** (cx,cy)에서 reach 안에 걸치는 표시들의 번호를 nearIdx에 모은다 */
    private static int nearMasks(float[] masks, float cx, float cy, float reach) {
        if (masks == null) return 0;
        int cnt = 0;
        for (int q = 0; q + 3 < masks.length; q += 4) {
            float dx = masks[q] - cx, dy = masks[q + 1] - cy, lim = masks[q + 3] + reach;
            if (dx * dx + dy * dy > lim * lim) continue;
            if (cnt == nearIdx.length) nearIdx = java.util.Arrays.copyOf(nearIdx, cnt * 2);
            nearIdx[cnt++] = q;
        }
        return cnt;
    }

    /** 점 (x,y)가 표시 안에 있는가. idx가 ALL(null)이면 count개의 모든 표시를 검사 */
    private static boolean masked(float[] masks, int[] idx, int count, float x, float y) {
        if (masks == null) return false;
        for (int t = 0; t < count; t++) {
            int q = idx == ALL ? t * 4 : idx[t];
            float dx = x - masks[q], dy = y - masks[q + 1], d2 = dx * dx + dy * dy;
            if (d2 <= masks[q + 3] * masks[q + 3] && d2 >= masks[q + 2] * masks[q + 2]) return true;
        }
        return false;
    }
    private static final float[] ringL = new float[RING_POINTS];
    private static final float[] ringS = new float[RING_POINTS];

    static int classify(float l, float s, float lb, float sb) {
        // 흑돌 : 아주 어둡고 색이 거의 없음 (빨간 점 같은 색 있는 표시는 제외)
        if (l < Math.min(lb * 0.55f, 110f) && s < 0.4f) return OmokEngine.BLACK;
        boolean brighter = l - lb >= 20f;
        boolean grayer = s < sb - 0.15f;
        if (l >= 150f && s < 0.3f && (brighter || grayer)) return OmokEngine.WHITE;
        return OmokEngine.EMPTY;
    }

    private static int safeRgb(PixelSource src, float fx, float fy) {
        int x = Math.round(fx), y = Math.round(fy);
        if (x < 0) x = 0;
        if (y < 0) y = 0;
        if (x >= src.width()) x = src.width() - 1;
        if (y >= src.height()) y = src.height() - 1;
        return src.rgb(x, y);
    }

    static float lum(int c) {
        int r = (c >> 16) & 0xff, g = (c >> 8) & 0xff, b = c & 0xff;
        return 0.299f * r + 0.587f * g + 0.114f * b;
    }

    static float sat(int c) {
        int r = (c >> 16) & 0xff, g = (c >> 8) & 0xff, b = c & 0xff;
        int max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
        return max == 0 ? 0f : (max - min) / (float) max;
    }

    private static float median(float[] a, int len) {
        if (len == 0) return 0f;
        float[] b = Arrays.copyOf(a, len);
        Arrays.sort(b);
        return b[len / 2];
    }
}
