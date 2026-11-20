package com.omokhelper;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.After;
import org.junit.Test;

public class LearningTest {

    static final int N = 15;

    @After
    public void clearBook() {
        LearnedBook.active = null;
    }

    /** 수를 하나씩 두며 판 사진을 만든다 */
    static int[][] snapshots(int[][] moves) {
        int[][] out = new int[moves.length + 1][];
        int[] b = new int[N * N];
        out[0] = b.clone();
        for (int i = 0; i < moves.length; i++) {
            b[moves[i][1] * N + moves[i][0]] = i % 2 == 0 ? OmokEngine.BLACK : OmokEngine.WHITE;
            out[i + 1] = b.clone();
        }
        return out;
    }

    // 백이 6수째에 흑의 열린 3을 안 막아서 지는 판
    static final int[][] LOST_BY_WHITE = {
            {7, 7}, {0, 0}, {8, 7}, {0, 14}, {9, 7}, {14, 0}, {10, 7}, {6, 7}, {11, 7}};

    @Test
    public void trackerRecordsMovesAndWinner() {
        int[][] snaps = snapshots(LOST_BY_WHITE);
        GameTracker t = new GameTracker(N, OmokEngine.RULE_FREE);
        assertEquals(GameTracker.NEW_GAME, t.update(snaps[0]));
        for (int i = 1; i < snaps.length - 1; i++) assertEquals(GameTracker.MOVE, t.update(snaps[i]));
        assertEquals(GameTracker.FINISHED, t.update(snaps[snaps.length - 1]));
        assertTrue(t.finished());
        assertEquals(OmokEngine.BLACK, t.winner());
        assertEquals(9, t.steps().size());
        assertFalse(t.partial());
        assertTrue(t.record(), t.record().startsWith("1.흑H8 2.백A15"));
        assertTrue(t.record().endsWith("흑 승"));
    }

    @Test
    public void trackerOrdersTwoMovesSeenTogether() {
        int[][] snaps = snapshots(LOST_BY_WHITE);
        GameTracker t = new GameTracker(N, OmokEngine.RULE_FREE);
        t.update(snaps[0]);
        t.update(snaps[1]);
        t.update(snaps[3]); // 백 2수와 흑 3수가 한꺼번에 보임
        List<GameTracker.Step> s = t.steps();
        assertEquals(3, s.size());
        assertEquals(OmokEngine.WHITE, s.get(1).color);
        assertEquals(OmokEngine.BLACK, s.get(2).color);
    }

    @Test
    public void trackerDetectsNewGame() {
        int[][] snaps = snapshots(LOST_BY_WHITE);
        GameTracker t = new GameTracker(N, OmokEngine.RULE_FREE);
        t.update(snaps[0]);
        t.update(snaps[4]); // 4수가 한꺼번에 → 순서를 모름 → 여기부터 기록
        assertTrue(t.partial());
        assertEquals(GameTracker.NEW_GAME, t.update(snaps[1])); // 돌이 줄어듦 → 새 판
        assertFalse(t.partial());
        assertEquals(1, t.steps().size());
    }

    @Test
    public void bookFindsSameBoardUnderRotationAndMirror() {
        int[] board = snapshots(LOST_BY_WHITE)[5];
        LearnedBook book = new LearnedBook();
        book.learn(board, N, OmokEngine.RULE_FREE, OmokEngine.WHITE, 6, 7);
        assertArrayEquals(new int[]{6, 7}, book.lookup(board, N, OmokEngine.RULE_FREE, OmokEngine.WHITE));
        assertNull(book.lookup(board, N, OmokEngine.RULE_FREE, OmokEngine.BLACK)); // 차례가 다르면 다른 판
        assertNull(book.lookup(board, N, OmokEngine.RULE_RENJU, OmokEngine.WHITE)); // 규칙이 다르면 다른 판
        for (int t = 0; t < 8; t++) {
            int[] rot = new int[N * N];
            for (int i = 0; i < board.length; i++) {
                if (board[i] == 0) continue;
                int[] p = Openings.transform(t, i % N - 7, i / N - 7);
                rot[(p[1] + 7) * N + p[0] + 7] = board[i];
            }
            int[] expect = Openings.transform(t, 6 - 7, 7 - 7);
            assertArrayEquals("t=" + t, new int[]{expect[0] + 7, expect[1] + 7},
                    book.lookup(rot, N, OmokEngine.RULE_FREE, OmokEngine.WHITE));
        }
        LearnedBook copy = LearnedBook.parse(book.serialize());
        assertEquals(1, copy.size());
        assertArrayEquals(new int[]{6, 7}, copy.lookup(board, N, OmokEngine.RULE_FREE, OmokEngine.WHITE));
    }

    @Test
    public void reviewFindsTheDecisiveMistake() {
        int[][] snaps = snapshots(LOST_BY_WHITE);
        GameTracker t = new GameTracker(N, OmokEngine.RULE_FREE);
        for (int[] s : snaps) t.update(s);
        List<GameReview.Lesson> lessons = GameReview.review(t.steps(), N, OmokEngine.RULE_FREE, OmokEngine.WHITE, 300, 6);
        assertEquals(1, lessons.size());
        GameReview.Lesson l = lessons.get(0);
        assertEquals(6, l.moveNumber);             // 6수째 (14,0)이 실수
        assertEquals(14, l.playedX);
        assertEquals(0, l.playedY);
        assertTrue(l.note, l.betterY == 7 && (l.betterX == 6 || l.betterX == 10)); // 열린 3의 양 끝 중 하나
    }

    @Test
    public void learnedMoveIsRecommendedFirstButNotOverTactics() {
        int[] board = snapshots(LOST_BY_WHITE)[3]; // 흑 2개, 백 1개, 백 차례 — 조용한 판
        LearnedBook book = new LearnedBook();
        book.learn(board, N, OmokEngine.RULE_FREE, OmokEngine.WHITE, 3, 3);
        LearnedBook.active = book;
        OmokEngine e = new OmokEngine(N, OmokEngine.RULE_FREE);
        e.load(board);
        OmokEngine.Result r = e.recommendDeep(OmokEngine.WHITE, 3, 200);
        assertTrue(r.learned);
        assertEquals(3, r.moves.get(0).x);
        assertEquals(3, r.moves.get(0).y);
        assertTrue(r.moves.get(0).reason.startsWith("📚"));

        // 흑이 가로 4(7~10)를 만들고 왼쪽 끝 (6,7)은 막힌 판 : 백은 (11,7)을 막아야 한다.
        // 이 판에 다른 수 (3,3)을 배워 두었어도 5목 막기가 먼저다.
        int[] urgent = snapshots(LOST_BY_WHITE)[7].clone();
        urgent[7 * N + 6] = OmokEngine.WHITE;
        book.learn(urgent, N, OmokEngine.RULE_FREE, OmokEngine.WHITE, 3, 3);
        OmokEngine u = new OmokEngine(N, OmokEngine.RULE_FREE);
        u.load(urgent);
        OmokEngine.Result ur = u.recommendDeep(OmokEngine.WHITE, 3, 200);
        assertFalse(ur.learned);
        assertEquals(11, ur.moves.get(0).x);
        assertEquals(7, ur.moves.get(0).y);
    }
}
