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
        float cw = (x1 - x0) / (n - 1);
        float ch = (y1 - y0) / (n - 1);
        float cell = Math.min(Math.abs(cw), Math.abs(ch));

        // 1) 바둑판 색 : 칸 한가운데 지점들의 중앙값
        float[] boardL = new float[(n - 1) * (n - 1)];
        float[] boardS = new float[boardL.length];
        int k = 0;
        for (int j = 0; j < n - 1; j++) {
            for (int i = 0; i < n - 1; i++) {
                int c = safeRgb(src, x0 + (i + 0.5f) * cw, y0 + (j + 0.5f) * ch);
                boardL[k] = lum(c);
                boardS[k] = sat(c);
                k++;
            }
        }
        float lb = median(boardL, boardL.length);
        float sb = median(boardS, boardS.length);

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
                int m = 0;
                for (int dy = -r; dy <= r; dy += step) {
                    for (int dx = -r; dx <= r; dx += step) {
                        if (dx * dx + dy * dy > r * r) continue;
                        int c = safeRgb(src, cx + dx, cy + dy);
                        ls[m] = lum(c);
                        ss[m] = sat(c);
                        m++;
                    }
                }
                out[j * n + i] = classify(median(ls, m), median(ss, m), lb, sb);
            }
        }
        return out;
    }

    static int classify(float l, float s, float lb, float sb) {
        if (l < Math.min(lb * 0.55f, 110f)) return OmokEngine.BLACK;
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
