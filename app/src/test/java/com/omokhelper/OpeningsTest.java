package com.omokhelper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;

public class OpeningsTest {

    static OmokEngine place(int[][] stones) {
        OmokEngine e = new OmokEngine(15, true);
        for (int[] s : stones) e.set(s[0], s[1], s[2]);
        return e;
    }

    /** 26개 주형은 서로 다른 모양이어야 한다 (8가지 대칭을 모두 고려해도) */
    @Test
    public void all26AreDistinct() {
        assertEquals(26, Openings.ALL.length);
        Set<String> names = new HashSet<>();
        for (Openings.Opening o : Openings.ALL) {
            names.add(o.code);
            // 자기 자신으로 인식되는지
            int wx = o.direct() ? 0 : 1, wy = -1;
            assertEquals(o.code, Openings.match(wx, wy, o.dx, o.dy).code);
        }
        assertEquals(26, names.size());
    }

    /** 판을 돌리거나 뒤집어도 같은 주형으로 인식 */
    @Test
    public void recognizedUnderAllSymmetries() {
        int c = 7;
        for (Openings.Opening o : Openings.ALL) {
            int wx = o.direct() ? 0 : 1, wy = -1;
            for (int t = 0; t < 8; t++) {
                int[] w = Openings.transform(t, wx, wy);
                int[] b = Openings.transform(t, o.dx, o.dy);
                OmokEngine e = place(new int[][]{
                        {c, c, OmokEngine.BLACK},
                        {c + w[0], c + w[1], OmokEngine.WHITE},
                        {c + b[0], c + b[1], OmokEngine.BLACK}});
                Openings.Match m = Openings.identify(e);
                assertNotNull(o.code + " t=" + t, m);
                assertEquals(o.code + " t=" + t, o.code, m.opening.code);
                assertEquals(c, m.firstX);
                assertEquals(c, m.firstY);
            }
        }
    }

    /** 우월(D6)과 운월(I6)은 같은 모양 — 천원에 있는 흑을 1수로 보고 구분 */
    @Test
    public void ugetsuVersusUngetsuDecidedByCenter() {
        // 흑 (7,7) 천원, 백 (7,6) 위, 흑 (8,7) 오른쪽 → 우월
        OmokEngine d6 = place(new int[][]{{7, 7, 1}, {7, 6, 2}, {8, 7, 1}});
        assertEquals("D6", Openings.identify(d6).opening.code);
        // 흑 (7,7) 천원, 백 (8,6) 대각선, 흑 (8,7) → 운월
        OmokEngine i6 = place(new int[][]{{7, 7, 1}, {8, 6, 2}, {8, 7, 1}});
        assertEquals("I6", Openings.identify(i6).opening.code);
    }

    @Test
    public void notAnOpening() {
        assertNull(Openings.identify(place(new int[][]{{7, 7, 1}, {7, 6, 2}})));
        assertNull(Openings.identify(place(new int[][]{{7, 7, 1}, {2, 2, 2}, {8, 7, 1}}))); // 백이 멀리
        assertNull(Openings.identify(place(new int[][]{{7, 7, 1}, {7, 6, 2}, {12, 12, 1}}))); // 흑 3수가 멀리
    }

    @Test
    public void bookThirdMoveDirect() {
        // 흑 천원, 백 바로 위 → 화월(i9 = (8,6)) 과 우월(i8 = (8,7)) 계열 추천
        OmokEngine e = place(new int[][]{{7, 7, 1}, {7, 6, 2}});
        List<Object[]> book = Openings.bookThirdMoves(e);
        assertEquals(2, book.size());
        Openings.Opening first = (Openings.Opening) book.get(0)[1];
        assertEquals("D4", first.code);
        int[] at = (int[]) book.get(0)[0];
        e.set(at[0], at[1], OmokEngine.BLACK);
        assertEquals("D4", Openings.identify(e).opening.code);

        OmokEngine e2 = place(new int[][]{{7, 7, 1}, {7, 6, 2}});
        OmokEngine.Move m = e2.recommendDeep(OmokEngine.BLACK, 3, 100).moves.get(0);
        assertTrue(m.reason, m.reason.contains("화월"));
    }

    @Test
    public void bookThirdMoveIndirectRotated() {
        // 흑 천원, 백 왼쪽 아래 대각선 → 포월
        OmokEngine e = place(new int[][]{{7, 7, 1}, {6, 8, 2}});
        List<Object[]> book = Openings.bookThirdMoves(e);
        Openings.Opening first = (Openings.Opening) book.get(0)[1];
        assertEquals("I7", first.code);
        int[] at = (int[]) book.get(0)[0];
        e.set(at[0], at[1], OmokEngine.BLACK);
        assertEquals("I7", Openings.identify(e).opening.code);
    }

    @Test
    public void guideListsAllOpenings() {
        String g = Guide.text();
        for (Openings.Opening o : Openings.ALL) assertTrue(o.korean + o.hanja, g.contains(o.korean + "(" + o.hanja + ")"));
        assertTrue(g.contains("VCT"));
        assertTrue(g.contains("4-3"));
    }
}
