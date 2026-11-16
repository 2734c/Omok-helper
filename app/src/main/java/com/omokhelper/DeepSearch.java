package com.omokhelper;

import java.util.ArrayList;
import java.util.List;

/**
 * 깊이 탐색 AI.
 *
 *  - 네가맥스(negamax) + 알파-베타 가지치기 : "내가 두면 → 상대는 최선으로 받고 → 나는 또 …" 를 여러 수 앞까지 읽는다.
 *    상대에게 가장 좋은 수는 나에게 가장 나쁜 수이므로, 점수의 부호를 바꿔 가며 같은 함수로 양쪽을 계산한다.
 *  - 반복 심화 : 1수, 2수, 3수 … 순서로 깊이를 늘리다가 제한 시간이 되면 마지막으로 끝까지 계산한 결과를 쓴다.
 *  - 치환표 : 이미 계산한 판(Zobrist 해시)의 결과를 기억해서 다시 계산하지 않는다.
 *  - 위협 확장 : 상대가 4를 만들어 "반드시 막아야 하는" 상황은 깊이를 줄이지 않고 끝까지 따라간다.
 *  - 후보 줄이기 : 모든 빈칸이 아니라 점수가 높은 상위 몇 개의 수만 검토한다.
 *              상대에게 열린 3·4-4 같은 큰 위협이 있으면 막는 수와 내 4만 검토한다.
 */
public final class DeepSearch {

    static final int INF = 1_000_000_000;
    static final int WIN_SCORE = 100_000_000;
    private static final int MAX_PLY = 30;
    private static final long SIDE_KEY = 0x5DEECE66DL * 0x9E3779B97F4A7C15L;

    private final OmokEngine e;
    private long deadline;
    private boolean stopped;
    private int nodes;

    // 치환표
    private static final int TT_SIZE = 1 << 18;
    private static final int EXACT = 0, LOWER = 1, UPPER = 2;
    private final long[] ttKey = new long[TT_SIZE];
    private final int[] ttVal = new int[TT_SIZE];
    private final byte[] ttDepth = new byte[TT_SIZE];
    private final byte[] ttFlag = new byte[TT_SIZE];
    private final short[] ttMove = new short[TT_SIZE];

    public DeepSearch(OmokEngine engine) {
        this.e = engine;
    }

    /** 루트(지금 판)의 후보 수 하나와 그 탐색 점수 */
    public static final class RootMove {
        public final int x, y;
        public int value;

        RootMove(int x, int y) {
            this.x = x;
            this.y = y;
        }
    }

    public static final class Outcome {
        public final List<RootMove> moves = new ArrayList<>();
        public List<int[]> pv = new ArrayList<>(); // 예상 수순 (최선의 수부터)
        public int depth;                         // 끝까지 계산한 깊이 (수)
        public int nodes;                         // 검토한 판의 수
    }

    // ------------------------------------------------------------------ 후보 수 만들기

    private static final class MoveList {
        int n;
        final int[] xs = new int[400], ys = new int[400];
        boolean win;    // 바로 5목 가능
        boolean forced; // 상대의 5목을 막아야만 하는 상황
        boolean lost;   // 막을 수 없음 (막을 자리가 금수 등)
    }

    private MoveList genMoves(int p, int width) {
        int opp = OmokEngine.opponent(p);
        List<int[]> cands = e.candidates(2);
        int m = cands.size();
        int[] xs = new int[m], ys = new int[m], fours = new int[m];
        long[] as = new long[m], ds = new long[m];
        int k = 0;
        boolean oppFive = false, oppBig = false;
        MoveList ml = new MoveList();

        for (int[] c : cands) {
            int x = c[0], y = c[1];
            boolean myForbidden = e.isForbidden(x, y, p);
            OmokEngine.Shape a = myForbidden ? null : e.analyze(x, y, p);
            long av = a == null ? -1 : OmokEngine.attackScore(a);
            if (av >= OmokEngine.WIN) {
                ml.n = 1;
                ml.xs[0] = x;
                ml.ys[0] = y;
                ml.win = true;
                return ml;
            }
            long dv = e.isForbidden(x, y, opp) ? 0 : OmokEngine.attackScore(e.analyze(x, y, opp));
            if (dv >= OmokEngine.WIN) oppFive = true;
            if (dv >= 100_000) oppBig = true;
            xs[k] = x;
            ys[k] = y;
            as[k] = av;
            ds[k] = dv;
            fours[k] = a == null ? 0 : a.fours();
            k++;
        }

        // 정렬 점수 계산 + 상황에 따라 후보 거르기
        long[] key = new long[k];
        boolean[] keep = new boolean[k];
        for (int i = 0; i < k; i++) {
            if (as[i] < 0) continue; // 내 금수
            if (oppFive) keep[i] = ds[i] >= OmokEngine.WIN;
            else if (oppBig) keep[i] = ds[i] >= 20_000 || fours[i] > 0;
            else keep[i] = true;
            key[i] = as[i] + ds[i] * 8 / 10 + e.centerBonus(xs[i], ys[i]);
        }
        if (oppFive) {
            ml.forced = true;
            boolean any = false;
            for (boolean b : keep) any |= b;
            if (!any) {
                ml.lost = true;
                return ml;
            }
        }

        // 점수 높은 순으로 width개 선택 (선택 정렬 — 후보가 많지 않아 충분히 빠르다)
        int limit = oppFive ? k : width;
        while (ml.n < limit) {
            int best = -1;
            for (int i = 0; i < k; i++) if (keep[i] && (best < 0 || key[i] > key[best])) best = i;
            if (best < 0) break;
            keep[best] = false;
            ml.xs[ml.n] = xs[best];
            ml.ys[ml.n] = ys[best];
            ml.n++;
        }
        return ml;
    }

    // ------------------------------------------------------------------ 형세 판단

    /** p(둘 차례) 입장에서 판의 유리함. 양쪽이 만들 수 있는 모양의 점수 합을 비교한다. */
    private int evaluate(int p) {
        int opp = OmokEngine.opponent(p);
        long my = 0, op = 0;
        for (int[] c : e.candidates(2)) {
            int x = c[0], y = c[1];
            if (!e.isForbidden(x, y, p)) my += Math.min(OmokEngine.attackScore(e.analyze(x, y, p)), 100_000);
            if (!e.isForbidden(x, y, opp)) op += Math.min(OmokEngine.attackScore(e.analyze(x, y, opp)), 100_000);
        }
        long v = my - op * 9 / 10;
        return (int) Math.max(-50_000_000, Math.min(50_000_000, v));
    }

    private static int width(int ply) {
        return ply <= 1 ? 12 : (ply <= 3 ? 10 : 8);
    }

    // ------------------------------------------------------------------ 알파-베타

    private int negamax(int p, int depth, int alpha, int beta, int ply) {
        if ((++nodes & 255) == 0 && System.nanoTime() > deadline) stopped = true;
        if (stopped) return 0;

        long h = e.hash() ^ (p == OmokEngine.BLACK ? SIDE_KEY : 0);
        int idx = (int) (h & (TT_SIZE - 1));
        int ttBest = -1;
        if (ttKey[idx] == h) {
            ttBest = ttMove[idx];
            if (ttDepth[idx] >= depth) {
                int v = ttVal[idx];
                if (ttFlag[idx] == EXACT) return v;
                if (ttFlag[idx] == LOWER && v >= beta) return v;
                if (ttFlag[idx] == UPPER && v <= alpha) return v;
            }
        }

        MoveList ml = genMoves(p, width(ply));
        if (ml.win) return WIN_SCORE - ply;
        if (ml.lost) return -(WIN_SCORE - ply - 1);
        if (ml.n == 0) return 0; // 둘 곳 없음 = 무승부
        if ((depth <= 0 && !ml.forced) || ply >= MAX_PLY) return evaluate(p);

        // 치환표에 기억된 최선의 수를 먼저 검토하면 가지치기가 잘 된다
        if (ttBest >= 0) {
            for (int i = 1; i < ml.n; i++) {
                if (ml.ys[i] * e.size + ml.xs[i] == ttBest) {
                    int tx = ml.xs[i], ty = ml.ys[i];
                    ml.xs[i] = ml.xs[0];
                    ml.ys[i] = ml.ys[0];
                    ml.xs[0] = tx;
                    ml.ys[0] = ty;
                    break;
                }
            }
        }

        int opp = OmokEngine.opponent(p);
        int newDepth = ml.forced ? depth : depth - 1; // 반드시 막아야 하는 수는 깊이를 줄이지 않는다
        int origAlpha = alpha;
        int best = -INF, bestMove = -1;
        for (int i = 0; i < ml.n; i++) {
            int x = ml.xs[i], y = ml.ys[i];
            e.set(x, y, p);
            int v = -negamax(opp, newDepth, -beta, -alpha, ply + 1);
            e.set(x, y, OmokEngine.EMPTY);
            if (stopped) return 0;
            if (v > best) {
                best = v;
                bestMove = y * e.size + x;
            }
            if (v > alpha) alpha = v;
            if (alpha >= beta) break;
        }

        ttKey[idx] = h;
        ttVal[idx] = best;
        ttDepth[idx] = (byte) Math.max(0, Math.min(127, depth));
        ttFlag[idx] = (byte) (best <= origAlpha ? UPPER : (best >= beta ? LOWER : EXACT));
        ttMove[idx] = (short) bestMove;
        return best;
    }

    // ------------------------------------------------------------------ 루트

    /**
     * p가 둘 차례에서 timeMs 동안 생각한다.
     * 각 후보 수의 점수를 정확히 계산하기 위해 후보마다 전체 범위로 탐색한다.
     */
    public Outcome run(int p, long timeMs, int maxDepth) {
        Outcome out = new Outcome();
        deadline = System.nanoTime() + timeMs * 1_000_000L;
        stopped = false;
        nodes = 0;

        MoveList root = genMoves(p, width(0));
        for (int i = 0; i < root.n; i++) out.moves.add(new RootMove(root.xs[i], root.ys[i]));
        if (out.moves.isEmpty() || root.win || root.n == 1) {
            out.depth = 1;
            return out;
        }

        int opp = OmokEngine.opponent(p);
        for (int depth = 1; depth <= maxDepth; depth++) {
            int[] vals = new int[out.moves.size()];
            for (int i = 0; i < out.moves.size() && !stopped; i++) {
                RootMove m = out.moves.get(i);
                e.set(m.x, m.y, p);
                vals[i] = -negamax(opp, depth - 1, -INF, INF, 1);
                e.set(m.x, m.y, OmokEngine.EMPTY);
            }
            if (stopped) break; // 이번 깊이는 다 못 끝냈으므로 이전 결과를 쓴다

            for (int i = 0; i < vals.length; i++) out.moves.get(i).value = vals[i];
            out.moves.sort((a, b) -> Integer.compare(b.value, a.value)); // 안정 정렬
            out.depth = depth;
            int top = out.moves.get(0).value;
            if (Math.abs(top) >= WIN_SCORE - 100) break; // 승패가 확정됨
        }
        out.nodes = nodes;
        out.pv = principalVariation(p, out.moves.get(0));
        return out;
    }

    /** 치환표를 따라가며 예상 수순을 만든다 */
    private List<int[]> principalVariation(int p, RootMove first) {
        List<int[]> pv = new ArrayList<>();
        List<int[]> undo = new ArrayList<>();
        int side = p;
        int x = first.x, y = first.y;
        for (int step = 0; step < 10; step++) {
            if (e.get(x, y) != OmokEngine.EMPTY) break;
            pv.add(new int[]{x, y});
            boolean five = e.makesFive(x, y, side);
            e.set(x, y, side);
            undo.add(new int[]{x, y});
            if (five) break;
            side = OmokEngine.opponent(side);
            long h = e.hash() ^ (side == OmokEngine.BLACK ? SIDE_KEY : 0);
            int idx = (int) (h & (TT_SIZE - 1));
            if (ttKey[idx] != h || ttMove[idx] < 0) break;
            x = ttMove[idx] % e.size;
            y = ttMove[idx] / e.size;
        }
        for (int i = undo.size() - 1; i >= 0; i--) e.set(undo.get(i)[0], undo.get(i)[1], OmokEngine.EMPTY);
        return pv;
    }

    /** 탐색 점수를 사람이 읽을 수 있는 말로 */
    public static String describe(int value) {
        if (value >= WIN_SCORE - 100) {
            int plies = WIN_SCORE - value; // 5목을 두는 시점까지의 반수(ply)
            return "깊이 탐색: " + (plies / 2 + 1) + "수 안에 필승";
        }
        if (value <= -(WIN_SCORE - 100)) return "깊이 탐색: 상대가 최선으로 두면 불리";
        return null;
    }
}
