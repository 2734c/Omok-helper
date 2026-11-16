package com.omokhelper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 금수 유도 테스트 (렌주룰).
 * 흑 : (7,4)(7,5) 세로, (5,4)(6,5) 대각선 → (7,6)이 흑의 3-3 금수 자리.
 * 백 : (8,6)(9,6)(10,6) 가로 3, 오른쪽 끝 (12,6)과 왼쪽 (6,6)은 흑이 막고 있음.
 * 백이 (11,6)에 두면 4가 되고, 흑이 막을 곳은 (7,6) 하나뿐인데 그곳이 금수 → 백 필승.
 */
public class ForbiddenTrapTest {

    static final String[] POS = {
            "...............",
            "...............",
            "...............",
            "...............",
            ".....X.X.......",
            "......XX.......",
            "......X.OOO.X..",
            "...............",
            "...............",
            ".O.............",
            "..............O"};

    @Test
    public void trapPointIsForbiddenForBlack() {
        OmokEngine e = OmokEngineTest.board(true, POS);
        assertTrue(e.isForbidden(7, 6, OmokEngine.BLACK));
        assertTrue(e.forbiddenTrapFour(11, 6, OmokEngine.WHITE));
    }

    @Test
    public void whiteFindsTheTrap() {
        OmokEngine e = OmokEngineTest.board(true, POS);
        OmokEngine.Move m = e.recommend(OmokEngine.WHITE, 3).moves.get(0);
        assertEquals(11, m.x);
        assertEquals(6, m.y);
        assertTrue(m.reason, m.reason.contains("금수 유도"));

        OmokEngine.Move d = e.recommendDeep(OmokEngine.WHITE, 3, 300).moves.get(0);
        assertEquals(11, d.x);
        assertEquals(6, d.y);
    }

    @Test
    public void blackDefendsAgainstTheTrap() {
        OmokEngine e = OmokEngineTest.board(true, POS);
        OmokEngine.Move m = e.recommend(OmokEngine.BLACK, 3).moves.get(0);
        assertEquals(11, m.x);
        assertEquals(6, m.y);
        assertTrue(m.reason, m.reason.contains("금수 유도"));

        OmokEngine.Move d = e.recommendDeep(OmokEngine.BLACK, 3, 300).moves.get(0);
        assertEquals(11, d.x);
        assertEquals(6, d.y);
    }

    @Test
    public void noTrapUnderFreeRule() {
        OmokEngine e = OmokEngineTest.board(false, POS);
        assertFalse(e.isForbidden(7, 6, OmokEngine.BLACK));
        assertFalse(e.forbiddenTrapFour(11, 6, OmokEngine.WHITE));
        for (OmokEngine.Move m : e.recommend(OmokEngine.WHITE, 3).moves) {
            assertFalse(m.reason, m.reason.contains("금수 유도"));
        }
    }

    @Test
    public void vcfMarksTrap() {
        // 백의 연속 4 수순이 금수 유도로 끝나는 경우 이유에 표시된다
        OmokEngine e = OmokEngineTest.board(true, POS);
        OmokEngine.Result r = e.recommend(OmokEngine.WHITE, 1);
        assertTrue(r.winSequence != null || r.moves.get(0).reason.contains("금수 유도"));
    }
}
