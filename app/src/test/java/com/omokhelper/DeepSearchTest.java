package com.omokhelper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Random;
import org.junit.Test;

public class DeepSearchTest {

    @Test
    public void blocksOpenThreeEvenWithDeepSearch() {
        OmokEngine e = OmokEngineTest.board(false,
                "...............",
                "...............",
                "...............",
                ".....XXX.......",
                "......O........",
                ".......O.......");
        OmokEngine.Move m = e.recommendDeep(OmokEngine.WHITE, 3, 400).moves.get(0);
        assertEquals(3, m.y);
        assertTrue("got x=" + m.x, m.x >= 3 && m.x <= 9 && (m.x < 5 || m.x > 7));
    }

    @Test
    public void winsWithFourThreeFound() {
        OmokEngine e = OmokEngineTest.board(false,
                "...............",
                "...............",
                "...............",
                ".....X.........",
                ".....X.........",
                ".OXXX..........",
                "...............",
                "..O.......O....",
                "........O......");
        OmokEngine.Result r = e.recommendDeep(OmokEngine.BLACK, 3, 400);
        OmokEngine.Move m = r.moves.get(0);
        assertEquals(5, m.x);
        assertEquals(5, m.y);
    }

    @Test
    public void searchReportsDepthAndPrincipalVariation() {
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
        OmokEngine.Result r = e.recommendDeep(OmokEngine.BLACK, 3, 400);
        assertTrue(r.searchDepth >= 2);
        assertTrue(r.pv != null && !r.pv.isEmpty());
        assertEquals(r.moves.get(0).x, r.pv.get(0)[0]);
        assertEquals(r.moves.get(0).y, r.pv.get(0)[1]);
    }

    @Test
    public void boardIsUnchangedAfterSearchAndMovesAreLegal() {
        Random rnd = new Random(7);
        for (int t = 0; t < 6; t++) {
            boolean renju = t % 2 == 0;
            OmokEngine e = new OmokEngine(15, renju);
            int placed = 0;
            while (placed < 16) {
                int x = 3 + rnd.nextInt(9), y = 3 + rnd.nextInt(9);
                if (e.get(x, y) == OmokEngine.EMPTY) {
                    e.set(x, y, placed % 2 == 0 ? OmokEngine.BLACK : OmokEngine.WHITE);
                    placed++;
                }
            }
            long before = e.hash();
            int side = e.sideToMove();
            OmokEngine.Result r = e.recommendDeep(side, 5, 200);
            assertEquals("board must be restored", before, e.hash());
            for (OmokEngine.Move m : r.moves) {
                assertEquals(OmokEngine.EMPTY, e.get(m.x, m.y));
                assertFalse(e.isForbidden(m.x, m.y, side));
            }
        }
    }

    @Test
    public void describesWins() {
        assertTrue(DeepSearch.describe(DeepSearch.WIN_SCORE - 2).contains("2수"));
        assertEquals(null, DeepSearch.describe(1234));
    }
}
