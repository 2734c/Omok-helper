package com.omokhelper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

public class VctTest {

    // 흑 (6,7)(7,7) 가로 2개, (8,5)(8,6) 세로 2개 → (8,7)이 3-3 자리. 4는 없으므로 VCF로는 못 찾는다.
    static final String[] DOUBLE_THREE = {
            "O.............O",
            "...............",
            "...............",
            "...............",
            "...............",
            "........X......",
            "........X......",
            "......XX.......",
            "...............",
            "...............",
            "...............",
            "...............",
            "...............",
            "...............",
            "O.............O"};

    /** 끝까지 두어 보아 공격 쪽이 정말 이기는지 확인 (수비는 깊이 탐색 AI가 최선으로 막음) */
    static int playOut(OmokEngine e, int attacker, int maxMoves) {
        int p = attacker;
        for (int i = 0; i < maxMoves; i++) {
            OmokEngine.Result r = p == attacker ? e.recommend(p, 1) : e.recommendDeep(p, 1, 200);
            if (r.moves.isEmpty()) return 0;
            OmokEngine.Move m = r.moves.get(0);
            boolean five = e.makesFive(m.x, m.y, p);
            e.set(m.x, m.y, p);
            if (five) return p;
            p = OmokEngine.opponent(p);
        }
        return 0;
    }

    @Test
    public void vctFindsWinThatVcfCannot() {
        OmokEngine e = OmokEngineTest.board(false, DOUBLE_THREE);
        assertNull(e.findVcf(OmokEngine.BLACK, 10, 4000));
        List<int[]> vct = e.findVct(OmokEngine.BLACK, OmokEngine.VCT_DEPTH, 20_000, 2000);
        assertNotNull(vct);
        OmokEngine.Result r = e.recommend(OmokEngine.BLACK, 1);
        assertTrue(r.vct);
        assertTrue(r.moves.get(0).reason, r.moves.get(0).reason.contains("VCT"));
        assertEquals(OmokEngine.BLACK, playOut(e, OmokEngine.BLACK, 20));
    }

    @Test
    public void vctRespectsForbiddenMoves() {
        // 렌주룰에서는 (8,7)이 흑의 3-3 금수라 첫 수로 쓸 수 없다
        OmokEngine e = OmokEngineTest.board(true, DOUBLE_THREE);
        assertTrue(e.isForbidden(8, 7, OmokEngine.BLACK));
        List<int[]> vct = e.findVct(OmokEngine.BLACK, OmokEngine.VCT_DEPTH, 20_000, 2000);
        if (vct != null) {
            for (int i = 0; i < vct.size(); i += 2) {
                int[] c = vct.get(i);
                assertFalse("forbidden move in VCT", c[0] == 8 && c[1] == 7);
            }
        }
    }

    @Test
    public void whiteDefendsAgainstVct() {
        // 같은 판에서 백 차례면, 흑의 3-3 자리를 막아야 한다
        OmokEngine e = OmokEngineTest.board(false, DOUBLE_THREE);
        OmokEngine.Result r = e.recommend(OmokEngine.WHITE, 3);
        assertNotNull(r.threatSequence);
        OmokEngine.Move m = r.moves.get(0);
        assertEquals(8, m.x);
        assertEquals(7, m.y);
    }

    @Test
    public void noVctOnQuietBoard() {
        OmokEngine e = OmokEngineTest.board(false,
                "...............",
                "...............",
                "...............",
                "...............",
                "...............",
                "...............",
                "......XO.......",
                ".......X.......",
                "........O......");
        assertNull(e.findVct(OmokEngine.BLACK, OmokEngine.VCT_DEPTH, 20_000, 2000));
    }

    /** 렌주룰 흑이 막을 자리가 전부 금수인 진 형세에서도 추천이 비어 있으면 안 된다 */
    @Test
    public void lostPositionStillGivesMoves() {
        OmokEngine e = OmokEngineTest.board(true,
                "...............",
                "...............",
                ".......X.......",
                "....XOOO.O.....",
                "......XOXX.....",
                ".....OXOX......",
                ".........O.....",
                "......OX.X.....");
        // 백이 (8,3)에 두면 5목, 흑은 (8,3)이 금수라 막을 수 없음
        assertTrue(e.makesFive(8, 3, OmokEngine.WHITE));
        assertTrue(e.isForbidden(8, 3, OmokEngine.BLACK));
        OmokEngine.Result r = e.recommendDeep(OmokEngine.BLACK, 3, 200);
        assertFalse(r.moves.isEmpty());
        assertTrue(r.moves.get(0).reason, r.moves.get(0).reason.startsWith("이미 불리"));
    }
}
