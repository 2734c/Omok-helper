package com.omokhelper;

import java.util.ArrayList;
import java.util.List;

/**
 * 화면에서 읽은 판을 차례로 받아서 한 판의 기보(수순)를 만든다.
 *
 * - 이전 판과 비교해 새로 생긴 돌이 그 사이에 둔 수다.
 * - 돌이 두 개(흑 하나, 백 하나) 한꺼번에 생기면 이전 판의 차례로 순서를 정한다.
 * - 돌이 줄어들거나 색이 바뀌면 새 판이 시작된 것으로 본다.
 * - 5목이 생기면 그 판은 끝나고 승자를 기록한다.
 */
public final class GameTracker {

    public static final int NONE = 0, NEW_GAME = 1, MOVE = 2, FINISHED = 3;

    /** 한 수 : 두기 전의 판 + 둔 자리와 색 */
    public static final class Step {
        public final int[] before;
        public final int x, y, color;

        Step(int[] before, int x, int y, int color) {
            this.before = before;
            this.x = x;
            this.y = y;
            this.color = color;
        }
    }

    private final int size;
    private final int rules;
    private int[] last;
    private final List<Step> steps = new ArrayList<>();
    private boolean finished;
    private int winner;
    private boolean partial; // 판 중간부터 기록을 시작했는가

    public GameTracker(int size, int rules) {
        this.size = size;
        this.rules = rules;
    }

    public List<Step> steps() {
        return steps;
    }

    public boolean finished() {
        return finished;
    }

    public int winner() {
        return winner;
    }

    public boolean partial() {
        return partial;
    }

    private static int stones(int[] b) {
        int c = 0;
        for (int v : b) if (v == OmokEngine.BLACK || v == OmokEngine.WHITE) c++;
        return c;
    }

    private void restart(int[] board) {
        steps.clear();
        finished = false;
        winner = 0;
        last = board.clone();
        int n = stones(board);
        partial = n > 1; // 돌이 0~1개면 처음부터 기록하는 것
        if (n == 1) { // 첫 수(흑)만 놓여 있으면 그 수도 기보에 넣는다
            for (int i = 0; i < board.length; i++) {
                if (board[i] == OmokEngine.BLACK) steps.add(new Step(new int[board.length], i % size, i / size, OmokEngine.BLACK));
            }
        }
    }

    /** 확정된(같은 판이 두 번 연속 읽힌) 판을 넣는다. */
    public int update(int[] board) {
        if (last == null) {
            restart(board);
            return NEW_GAME;
        }
        List<int[]> added = new ArrayList<>();
        boolean removed = false;
        for (int i = 0; i < board.length; i++) {
            if (board[i] == last[i]) continue;
            if (last[i] == OmokEngine.EMPTY) added.add(new int[]{i % size, i / size, board[i]});
            else removed = true; // 돌이 사라지거나 색이 바뀜
        }
        if (removed) {
            restart(board);
            return NEW_GAME;
        }
        if (added.isEmpty()) return NONE;
        if (finished) { // 끝난 판에 돌이 더 생기면 (복기 화면 등) 무시하고 기다린다
            last = board.clone();
            return NONE;
        }

        // 두는 순서 정하기
        int b = 0, w = 0;
        for (int v : last) {
            if (v == OmokEngine.BLACK) b++;
            else if (v == OmokEngine.WHITE) w++;
        }
        int toMove = b <= w ? OmokEngine.BLACK : OmokEngine.WHITE;
        List<int[]> order = new ArrayList<>();
        if (added.size() == 1) {
            order.add(added.get(0));
        } else if (added.size() == 2 && added.get(0)[2] != added.get(1)[2]) {
            int[] first = added.get(0)[2] == toMove ? added.get(0) : added.get(1);
            order.add(first);
            order.add(first == added.get(0) ? added.get(1) : added.get(0));
        } else {
            // 한꺼번에 여러 수가 생겨 순서를 알 수 없음 → 여기부터 다시 기록
            restart(board);
            partial = true;
            return NEW_GAME;
        }

        int[] cur = last.clone();
        OmokEngine judge = new OmokEngine(size, rules);
        for (int[] m : order) {
            judge.load(cur);
            boolean five = judge.makesFive(m[0], m[1], m[2]);
            steps.add(new Step(cur.clone(), m[0], m[1], m[2]));
            cur[m[1] * size + m[0]] = m[2];
            if (five) {
                finished = true;
                winner = m[2];
                break;
            }
        }
        last = board.clone();
        return finished ? FINISHED : MOVE;
    }

    /** 기보를 글자로 (예: "흑 H8, 백 I9, …  → 흑 승") */
    public String record() {
        OmokEngine fmt = new OmokEngine(size, rules);
        StringBuilder sb = new StringBuilder();
        if (partial) sb.append("(중간부터) ");
        for (int i = 0; i < steps.size(); i++) {
            Step s = steps.get(i);
            if (i > 0) sb.append(' ');
            sb.append(i + 1).append('.').append(s.color == OmokEngine.BLACK ? "흑" : "백").append(fmt.coord(s.x, s.y));
        }
        if (finished) sb.append("  → ").append(winner == OmokEngine.BLACK ? "흑" : "백").append(" 승");
        return sb.toString();
    }
}
