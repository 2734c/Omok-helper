package com.omokhelper;

import java.util.ArrayList;
import java.util.List;

/**
 * 진 판 복기 : 진 쪽이 어디서 결정적으로 잘못 두었는지 찾는다.
 *
 * 진 쪽이 둔 수를 마지막부터 거꾸로 살펴본다.
 *  - 실제로 둔 수 뒤에 상대에게 필승 수순(5목 자리·VCF·VCT·깊이 탐색 필승)이 생겼고,
 *  - 같은 판에서 다른 수를 두면 상대의 필승이 없었다면
 * 그 자리가 결정적인 실수다. 그 판과 더 나은 수를 "배운 수"로 돌려준다.
 */
public final class GameReview {

    private GameReview() {
    }

    public static final class Lesson {
        public final int moveNumber;      // 몇 번째 수였는지 (1부터)
        public final int[] board;         // 두기 전 판
        public final int side;            // 둘 차례 (진 쪽)
        public final int playedX, playedY, betterX, betterY;
        public final String note;

        Lesson(int moveNumber, int[] board, int side, int px, int py, int bx, int by, String note) {
            this.moveNumber = moveNumber;
            this.board = board;
            this.side = side;
            this.playedX = px;
            this.playedY = py;
            this.betterX = bx;
            this.betterY = by;
            this.note = note;
        }
    }

    /** side가 (x,y)에 둔 뒤, 상대가 강제로 이길 수 있는가 */
    static boolean losesAfter(int[] board, int size, int rules, int side, int x, int y, long ms) {
        OmokEngine e = new OmokEngine(size, rules);
        e.load(board);
        if (e.makesFive(x, y, side)) return false; // 내가 이김
        e.set(x, y, side);
        int opp = OmokEngine.opponent(side);
        // 상대가 바로 5목
        for (int yy = 0; yy < size; yy++)
            for (int xx = 0; xx < size; xx++)
                if (e.get(xx, yy) == OmokEngine.EMPTY && e.makesFive(xx, yy, opp)) return true;
        // 상대의 연속 4 / 연속 3·4 필승
        if (e.findVcf(opp, 12, 6000) != null) return true;
        if (e.findVct(opp, OmokEngine.VCT_DEPTH, 6000, 600) != null) return true;
        // 깊이 탐색으로 상대 필승
        DeepSearch.Outcome o = new DeepSearch(e).run(opp, ms, 20);
        return !o.moves.isEmpty() && o.moves.get(0).value >= DeepSearch.WIN_SCORE - 100;
    }

    /**
     * @param steps   기보 (GameTracker.steps)
     * @param loser   진 쪽 색
     * @param msPer   한 자리를 분석하는 데 쓸 시간 (밀리초)
     * @param maxBack 마지막부터 몇 수까지 거슬러 볼지
     */
    public static List<Lesson> review(List<GameTracker.Step> steps, int size, int rules, int loser, long msPer, int maxBack) {
        List<Lesson> lessons = new ArrayList<>();
        int checked = 0;
        for (int i = steps.size() - 1; i >= 0 && checked < maxBack; i--) {
            GameTracker.Step s = steps.get(i);
            if (s.color != loser) continue;
            checked++;
            if (!losesAfter(s.before, size, rules, loser, s.x, s.y, msPer)) continue; // 이 수는 아직 지는 수가 아님

            // 같은 판에서 더 나은 수 찾기 : 추천 상위 수 중 상대 필승을 허용하지 않는 첫 수
            OmokEngine e = new OmokEngine(size, rules);
            e.load(s.before);
            OmokEngine.Result r = e.recommendDeep(loser, 5, msPer);
            for (OmokEngine.Move m : r.moves) {
                if (m.x == s.x && m.y == s.y) continue;
                if (r.searchDepth > 0 && m.score <= -(DeepSearch.WIN_SCORE - 100)) continue; // 깊이 탐색도 지는 수라고 봄
                if (!losesAfter(s.before, size, rules, loser, m.x, m.y, msPer)) {
                    String note = (i + 1) + "수째 " + e.coord(s.x, s.y) + " 대신 " + e.coord(m.x, m.y)
                            + " (" + m.reason + ")";
                    lessons.add(new Lesson(i + 1, s.before, loser, s.x, s.y, m.x, m.y, note));
                    return lessons; // 가장 마지막의 결정적 실수 하나를 배운다
                }
            }
            // 더 나은 수도 다 지는 수였다면 이미 그 전에 진 판 → 더 앞으로
        }
        return lessons;
    }
}
