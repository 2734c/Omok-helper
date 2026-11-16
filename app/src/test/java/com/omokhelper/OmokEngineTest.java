package com.omokhelper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

public class OmokEngineTest {

    /** 문자열로 판을 만든다. X = 흑, O = 백, . = 빈칸. 첫 줄이 맨 위(y=0). */
    static OmokEngine board(boolean renju, String... rows) {
        OmokEngine e = new OmokEngine(15, renju);
        for (int y = 0; y < rows.length; y++) {
            for (int x = 0; x < rows[y].length(); x++) {
                char ch = rows[y].charAt(x);
                if (ch == 'X') e.set(x, y, OmokEngine.BLACK);
                if (ch == 'O') e.set(x, y, OmokEngine.WHITE);
            }
        }
        return e;
    }

    static void assertMove(OmokEngine.Move m, int x, int y) {
        assertEquals("x of " + m.reason, x, m.x);
        assertEquals("y of " + m.reason, y, m.y);
    }

    @Test
    public void emptyBoardPlaysCenter() {
        OmokEngine e = new OmokEngine(15, false);
        assertMove(e.recommend(OmokEngine.BLACK, 3).moves.get(0), 7, 7);
    }

    @Test
    public void completesFive() {
        OmokEngine e = board(false,
                "...............",
                "...............",
                "...XXXX........",
                "....OOO........",
                "...........O...");
        OmokEngine.Move m = e.recommend(OmokEngine.BLACK, 3).moves.get(0);
        assertTrue(m.x == 2 || m.x == 7);
        assertEquals(2, m.y);
        assertTrue(m.reason.contains("승리"));
    }

    @Test
    public void blocksOpponentFour() {
        OmokEngine e = board(false,
                "...............",
                "..OXXXX........",
                "...O...........",
                "....O..........");
        OmokEngine.Move m = e.recommend(OmokEngine.WHITE, 3).moves.get(0);
        assertMove(m, 7, 1);
    }

    @Test
    public void blocksOpenThree() {
        OmokEngine e = board(false,
                "...............",
                "...............",
                "...............",
                ".....XXX.......",
                "......O........",
                ".......O.......");
        OmokEngine.Move m = e.recommend(OmokEngine.WHITE, 3).moves.get(0);
        assertEquals(3, m.y);
        assertTrue("got x=" + m.x, m.x == 4 || m.x == 8 || m.x == 3 || m.x == 9);
    }

    @Test
    public void makesFourThree() {
        // 흑: (5,5)에 두면 가로 4 + 세로 열린 3
        OmokEngine e = board(false,
                "...............",
                "...............",
                "...............",
                ".....X.........",
                ".....X.........",
                ".OXXX..........",
                "...............",
                "..O.......O....",
                "........O......");
        OmokEngine.Move m = e.recommend(OmokEngine.BLACK, 3).moves.get(0);
        assertMove(m, 5, 5);
    }

    @Test
    public void findsVcf() {
        // 흑이 4를 연속으로 두어 이기는 모양
        OmokEngine e = board(false,
                "...............",
                "...............",
                "..OXXX.........",
                "......X........",
                "......X........",
                "......X........",
                "......O........",
                "...............",
                "..........O....",
                "...........O...");
        List<int[]> seq = e.findVcf(OmokEngine.BLACK, 10, 4000);
        assertNotNull(seq);
        OmokEngine.Result r = e.recommend(OmokEngine.BLACK, 3);
        assertNotNull(r.winSequence);
    }

    @Test
    public void noVcfOnQuietBoard() {
        OmokEngine e = board(false,
                "...............",
                "......X........",
                ".......O.......");
        assertNull(e.findVcf(OmokEngine.BLACK, 10, 4000));
    }

    @Test
    public void renjuDoubleThreeIsForbidden() {
        OmokEngine e = board(true,
                "...............",
                "...............",
                "...............",
                "......X........",
                ".....X.........",
                "...............",
                "...XX..........",
                "...............");
        // (5,6)? 가로 XX_ -> (5,6)에 두면 가로 열린 3, 대각선 X(6,3) X(5,4) (4,5)... 확인용으로 직접 만든 자리
        // 가로: (3,6)(4,6)+(5,6) = 열린 3 / 세로: (5,4)+(5,6)은 3이 아님
        assertFalse(e.isForbidden(5, 6, OmokEngine.BLACK));

        OmokEngine f = board(true,
                "...............",
                "...............",
                "...............",
                "...............",
                ".......X.......",
                ".......X.......",
                ".....XX........",
                "...............");
        // (7,6)에 두면 가로 XXX(5,6~7,6) 열린 3 + 세로 XXX(7,4~7,6) 열린 3 -> 3-3 금수
        assertTrue(f.isForbidden(7, 6, OmokEngine.BLACK));
        assertFalse(f.isForbidden(7, 6, OmokEngine.WHITE));

        OmokEngine free = board(false,
                "...............",
                "...............",
                "...............",
                "...............",
                ".......X.......",
                ".......X.......",
                ".....XX........",
                "...............");
        assertFalse(free.isForbidden(7, 6, OmokEngine.BLACK));
        // 자유룰에서는 3-3이 좋은 공격 수로 추천된다
        free.set(0, 0, OmokEngine.WHITE);
        free.set(14, 14, OmokEngine.WHITE);
        free.set(14, 0, OmokEngine.WHITE);
        assertMove(free.recommend(OmokEngine.BLACK, 3).moves.get(0), 7, 6);
    }

    @Test
    public void renjuOverlineIsForbidden() {
        OmokEngine e = board(true,
                "...............",
                "..XXX.XX.......");
        assertTrue(e.isForbidden(5, 1, OmokEngine.BLACK));
        assertFalse(e.makesFive(5, 1, OmokEngine.BLACK));
        OmokEngine free = board(false,
                "...............",
                "..XXX.XX.......");
        assertTrue(free.makesFive(5, 1, OmokEngine.BLACK));
    }

    @Test
    public void closedThreeIsNotOpen() {
        // O.XXX.O : 양쪽이 바로 막혀 있어서 열린 3이 아니다
        int[] a = {2, 2, 2, 0, 1, 1, 1, 0, 2, 2, 2};
        // 가운데(5)가 1이 되도록 맞춘 배열
        assertEquals(OmokEngine.T_THREE, OmokEngine.lineType(a, false));
        int[] b = {2, 2, 0, 0, 1, 1, 1, 0, 0, 2, 2};
        assertEquals(OmokEngine.T_OPEN_THREE, OmokEngine.lineType(b, false));
    }

    @Test
    public void coordinates() {
        OmokEngine e = new OmokEngine(15, false);
        assertEquals("H8", e.coord(7, 7));
        assertEquals("A15", e.coord(0, 0));
    }
}
