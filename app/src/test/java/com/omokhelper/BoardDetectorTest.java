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

    @Test
    public void skippedAreaIsUnknown() {
        FakeScreen s = new FakeScreen(600, 600, 0xdcb35c);
        int[] got = BoardDetector.detect(s, 20, 20, 580, 580, 15, new int[]{0, 0, 100, 100});
        assertEquals(BoardDetector.UNKNOWN, got[0]);
        assertEquals(OmokEngine.EMPTY, got[14 * 15 + 14]);
    }
}
