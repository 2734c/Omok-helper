package com.omokhelper;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class BoardDetectorTest {

    /** 가짜 게임 화면을 그린다: 나무색 판 + 검은 선 + 돌 + 마지막 수 빨간 점 */
    static final class FakeScreen implements BoardDetector.PixelSource {
        final int w, h;
        final int[] px;

        FakeScreen(int w, int h, int bg) {
            this.w = w;
            this.h = h;
            px = new int[w * h];
            java.util.Arrays.fill(px, bg);
        }

        void rect(int x0, int y0, int x1, int y1, int c) {
            for (int y = Math.max(0, y0); y <= Math.min(h - 1, y1); y++)
                for (int x = Math.max(0, x0); x <= Math.min(w - 1, x1); x++) px[y * w + x] = c;
        }

        void circle(float cx, float cy, float r, int c) {
            for (int y = (int) (cy - r) - 1; y <= cy + r + 1; y++)
                for (int x = (int) (cx - r) - 1; x <= cx + r + 1; x++)
                    if (x >= 0 && y >= 0 && x < w && y < h && (x - cx) * (x - cx) + (y - cy) * (y - cy) <= r * r)
                        px[y * w + x] = c;
        }

        @Override public int width() { return w; }
        @Override public int height() { return h; }
        @Override public int rgb(int x, int y) { return px[y * w + x]; }
    }

    private void runCase(int boardColor, int n, float cell) {
        int ox = 40, oy = 300;
        FakeScreen s = new FakeScreen(1080, 2000, 0x223344);
        s.rect(ox - 30, oy - 30, (int) (ox + cell * (n - 1) + 30), (int) (oy + cell * (n - 1) + 30), boardColor);
        for (int i = 0; i < n; i++) {
            int p = Math.round(i * cell);
            s.rect(ox + p - 1, oy, ox + p + 1, (int) (oy + cell * (n - 1)), 0x3a2a10);
            s.rect(ox, oy + p - 1, (int) (ox + cell * (n - 1)), oy + p + 1, 0x3a2a10);
        }

        int[] expected = new int[n * n];
        int[][] stones = {{7, 7, 1}, {8, 7, 2}, {8, 8, 1}, {6, 6, 2}, {0, 0, 1}, {n - 1, n - 1, 2}, {3, 10, 1}};
        for (int[] st : stones) {
            float cx = ox + st[0] * cell, cy = oy + st[1] * cell;
            if (st[2] == 1) {
                s.circle(cx, cy, cell * 0.46f, 0x151515);
            } else {
                s.circle(cx, cy, cell * 0.46f, 0x808080);
                s.circle(cx, cy, cell * 0.43f, 0xf2f2f2);
            }
            expected[st[1] * n + st[0]] = st[2];
        }
        // 마지막 수 표시 (작은 빨간 점)
        s.circle(ox + 3 * cell, oy + 10 * cell, cell * 0.1f, 0xff2020);

        int[] got = BoardDetector.detect(s, ox, oy, ox + cell * (n - 1), oy + cell * (n - 1), n, null);
        assertArrayEquals(expected, got);
    }

    @Test
    public void woodBoard15() {
        runCase(0xdcb35c, 15, 68f);
    }

    @Test
    public void lightBoard15() {
        runCase(0xe8d9b0, 15, 70f);
    }

    @Test
    public void greenBoard19() {
        runCase(0x7fa86a, 19, 54f);
    }

    /** 오버레이의 추천 고리 + 순위 배지가 캡처에 찍혀도 빈칸이 돌로 오인되지 않아야 한다 */
    @Test
    public void overlayMarkersAreNotReadAsStones() {
        int n = 15;
        float cell = 66f;
        int ox = 40, oy = 300;
        for (float scale : new float[]{0.85f, 1f, 1.25f}) {
            for (int[] theme : Themes.MARKER_THEMES) {
                FakeScreen s = new FakeScreen(1080, 2000, 0x223344);
                s.rect(ox - 30, oy - 30, (int) (ox + cell * (n - 1) + 30), (int) (oy + cell * (n - 1) + 30), 0xdcb35c);
                for (int i = 0; i < n; i++) {
                    int p = Math.round(i * cell);
                    s.rect(ox + p - 1, oy, ox + p + 1, (int) (oy + cell * (n - 1)), 0x3a2a10);
                    s.rect(ox, oy + p - 1, (int) (ox + cell * (n - 1)), oy + p + 1, 0x3a2a10);
                }
                int[][] spots = {{7, 7}, {8, 8}, {3, 4}};
                for (int k = 0; k < spots.length; k++) {
                    float cx = ox + spots[k][0] * cell, cy = oy + spots[k][1] * cell;
                    int color = theme[k] & 0xffffff;
                    float stroke = cell * scale * (k == 0 ? 0.12f : 0.08f);
                    float ringR = cell * scale * 0.42f;
                    ring(s, cx, cy, ringR - stroke / 2, ringR + stroke / 2, color);
                    s.circle(cx + cell * scale * 0.38f, cy - cell * scale * 0.38f, cell * scale * 0.2f, color);
                }
                int[] got = BoardDetector.detect(s, ox, oy, ox + cell * (n - 1), oy + cell * (n - 1), n, null);
                assertArrayEquals("scale " + scale, new int[n * n], got);
            }
        }
    }

    /** 게임이 금수 자리 등에 그리는 표시(빨간 점, ×, 반투명 돌)는 돌이 아니다 */
    @Test
    public void gameMarksAreNotStones() {
        int n = 15, ox = 40, oy = 300;
        float cell = 66f;
        int bg = 0xdcb35c;
        FakeScreen s = new FakeScreen(1080, 2000, 0x223344);
        s.rect(ox - 30, oy - 30, (int) (ox + cell * 14 + 30), (int) (oy + cell * 14 + 30), bg);
        for (int i = 0; i < n; i++) {
            int p = Math.round(i * cell);
            s.rect(ox + p - 1, oy, ox + p + 1, (int) (oy + cell * 14), 0x3a2a10);
            s.rect(ox, oy + p - 1, (int) (ox + cell * 14), oy + p + 1, 0x3a2a10);
        }
        s.circle(ox + 3 * cell, oy + 3 * cell, cell * 0.30f, 0xff2020);                 // 빨간 점
        xMark(s, ox + 5 * cell, oy + 5 * cell, cell * 0.30f, cell * 0.12f, 0xe53935);    // 빨간 ×
        s.circle(ox + 7 * cell, oy + 7 * cell, cell * 0.46f, 0x786435);                  // 반투명 흑돌(미리보기)
        xMark(s, ox + 9 * cell, oy + 9 * cell, cell * 0.30f, cell * 0.10f, 0x202020);    // 검은 ×
        s.circle(ox + 11 * cell, oy + 11 * cell, cell * 0.25f, 0xc62828);                // 진한 빨간 점
        // 진짜 돌도 함께 (마지막 수 빨간 점 포함)
        s.circle(ox + 2 * cell, oy + 9 * cell, cell * 0.46f, 0x151515);
        s.circle(ox + 2 * cell, oy + 9 * cell, cell * 0.10f, 0xff2020);
        s.circle(ox + 12 * cell, oy + 2 * cell, cell * 0.46f, 0x808080);
        s.circle(ox + 12 * cell, oy + 2 * cell, cell * 0.43f, 0xf2f2f2);

        int[] expected = new int[n * n];
        expected[9 * n + 2] = OmokEngine.BLACK;
        expected[2 * n + 12] = OmokEngine.WHITE;
        assertArrayEquals(expected, BoardDetector.detect(s, ox, oy, ox + cell * 14, oy + cell * 14, n, null));
    }

    private static void xMark(FakeScreen s, float cx, float cy, float r, float t, int c) {
        for (int y = (int) (cy - r); y <= cy + r; y++)
            for (int x = (int) (cx - r); x <= cx + r; x++) {
                float dx = x - cx, dy = y - cy;
                if (Math.abs(dx - dy) <= t * 0.7f || Math.abs(dx + dy) <= t * 0.7f) s.px[y * s.w + x] = c;
            }
    }

    private static void ring(FakeScreen s, float cx, float cy, float r0, float r1, int c) {
        for (int y = (int) (cy - r1) - 1; y <= cy + r1 + 1; y++)
            for (int x = (int) (cx - r1) - 1; x <= cx + r1 + 1; x++) {
                float d2 = (x - cx) * (x - cx) + (y - cy) * (y - cy);
                if (d2 >= r0 * r0 && d2 <= r1 * r1) s.px[y * s.w + x] = c;
            }
    }

    @Test
    public void skippedAreaIsUnknown() {
        FakeScreen s = new FakeScreen(600, 600, 0xdcb35c);
        int[] got = BoardDetector.detect(s, 20, 20, 580, 580, 15, new int[]{0, 0, 100, 100});
        assertEquals(BoardDetector.UNKNOWN, got[0]);
        assertEquals(OmokEngine.EMPTY, got[14 * 15 + 14]);
    }
}
