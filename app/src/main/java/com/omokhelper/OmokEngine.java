package com.omokhelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 오목 수 추천 엔진.
 *
 * 동작 원리
 *  1) 패턴 분석 : 빈 칸에 돌을 놓았다고 가정하고 가로/세로/대각선 4방향의 모양
 *                (5목, 열린4, 4, 열린3, 3, 열린2, 2)을 판별한다.
 *  2) 점수 계산 : 내가 그 자리에 둘 때의 공격 점수 + 상대가 그 자리에 둘 때의 점수(수비)를 더한다.
 *  3) VCF 탐색  : "4를 연속으로 두어서 이기는 수순(Victory by Continuous Fours)"을 깊이 우선 탐색으로 찾는다.
 *                내 필승 수순이 있으면 최우선으로 추천하고, 상대의 필승 수순이 있으면 그 자리를 막도록 가산점을 준다.
 *  4) 한 수 앞 보기 : 상위 후보에 대해 "내가 두고 난 뒤 상대의 최선 공격"을 계산해 위험한 수를 감점한다.
 *
 * 렌주룰을 켜면 흑에게 3-3, 4-4, 장목(6목 이상) 금수를 적용한다.
 */
public final class OmokEngine {

    public static final int EMPTY = 0;
    public static final int BLACK = 1;
    public static final int WHITE = 2;

    private static final int[][] DIRS = {{1, 0}, {0, 1}, {1, 1}, {1, -1}};

    // 한 줄(방향)의 모양 종류
    static final int T_NONE = 0, T_TWO = 1, T_OPEN_TWO = 2, T_THREE = 3, T_OPEN_THREE = 4,
            T_FOUR = 5, T_OPEN_FOUR = 6, T_FIVE = 7, T_OVERLINE = 8;

    // ---------------------------------------------------------------- 규칙 (항목별로 켜고 끔)
    public static final int B_OVERLINE = 1;  // 흑 6목 이상(장목) 금지 : 승리로 치지 않고, 그 자리에 둘 수 없음
    public static final int B_33 = 2;        // 흑 3-3 금지
    public static final int B_44 = 4;        // 흑 4-4 금지
    public static final int W_OVERLINE = 8;  // 백 6목 이상 금지
    public static final int W_33 = 16;       // 백 3-3 금지
    public static final int W_44 = 32;       // 백 4-4 금지
    public static final int RULE_FREE = 0;
    public static final int RULE_RENJU = B_OVERLINE | B_33 | B_44;

    public final int size;
    public final int rules;
    private final int[] cells;

    private int nodeCount;
    private int nodeLimit;

    public OmokEngine(int size, int rules) {
        this.size = size;
        this.rules = rules;
        this.cells = new int[size * size];
    }

    /** renju = true 이면 렌주룰, false 이면 자유룰 */
    public OmokEngine(int size, boolean renju) {
        this(size, renju ? RULE_RENJU : RULE_FREE);
    }

    private boolean rule(int p, int blackFlag) {
        int flag = p == BLACK ? blackFlag : blackFlag << 3; // 백 규칙은 흑 규칙의 3비트 위
        return (rules & flag) != 0;
    }

    /** 규칙 이름 (화면 표시용) */
    public static String ruleName(int rules) {
        if (rules == RULE_FREE) return "자유룰";
        if (rules == RULE_RENJU) return "렌주룰";
        return "사용자 규칙";
    }

    // ---------------------------------------------------------------- 판 조작

    public int get(int x, int y) {
        if (x < 0 || y < 0 || x >= size || y >= size) return -1; // 판 밖
        return cells[y * size + x];
    }

    public void set(int x, int y, int v) {
        int i = y * size + x;
        int old = cells[i];
        if (old == BLACK || old == WHITE) hash ^= ZOBRIST[old - 1][i];
        if (v == BLACK || v == WHITE) hash ^= ZOBRIST[v - 1][i];
        cells[i] = v;
    }

    public void load(int[] board) {
        for (int i = 0; i < cells.length; i++) set(i % size, i / size, board[i]);
    }

    // ---------------------------------------------------------------- 판 해시 (Zobrist)
    // 판 모양마다 거의 겹치지 않는 64비트 번호. 깊이 탐색에서 "이미 계산한 판"을 기억하는 데 쓴다.

    private static final long[][] ZOBRIST = new long[2][19 * 19];

    static {
        java.util.Random r = new java.util.Random(20261116L);
        for (int c = 0; c < 2; c++) for (int i = 0; i < 19 * 19; i++) ZOBRIST[c][i] = r.nextLong();
    }

    private long hash = 0;

    public long hash() {
        return hash;
    }

    public static int opponent(int p) {
        return p == BLACK ? WHITE : BLACK;
    }

    public int count(int color) {
        int c = 0;
        for (int v : cells) if (v == color) c++;
        return c;
    }

    /** 돌 개수로 다음 차례를 추정한다. (흑이 먼저 둔다) */
    public int sideToMove() {
        return count(BLACK) <= count(WHITE) ? BLACK : WHITE;
    }

    private boolean exact(int p) {
        return rule(p, B_OVERLINE); // 장목 금지면 정확히 5개만 승리
    }

    // ---------------------------------------------------------------- 5목 판정

    /** (x,y)에 p를 놓으면 5목이 되는가 */
    public boolean makesFive(int x, int y, int p) {
        for (int[] d : DIRS) {
            int run = 1 + runLength(x, y, d[0], d[1], p) + runLength(x, y, -d[0], -d[1], p);
            if (exact(p) ? run == 5 : run >= 5) return true;
        }
        return false;
    }

    private boolean makesOverline(int x, int y, int p) {
        for (int[] d : DIRS) {
            int run = 1 + runLength(x, y, d[0], d[1], p) + runLength(x, y, -d[0], -d[1], p);
            if (run >= 6) return true;
        }
        return false;
    }

    private int runLength(int x, int y, int dx, int dy, int p) {
        int n = 0;
        x += dx;
        y += dy;
        while (get(x, y) == p) {
            n++;
            x += dx;
            y += dy;
        }
        return n;
    }

    // ---------------------------------------------------------------- 줄 모양 분석

    /*
     * 한 줄은 길이 11의 배열로 나타낸다 (가운데 5번이 지금 놓는 돌).
     * 1 = 내 돌, 0 = 빈칸, 2 = 막힘(상대 돌이나 판 밖).
     */

    /** 가운데 돌을 포함하는 연속된 돌의 길이 */
    private static int centerRun(int[] a) {
        int l = 5, r = 5;
        while (l > 0 && a[l - 1] == 1) l--;
        while (r < 10 && a[r + 1] == 1) r++;
        return r - l + 1;
    }

    private static boolean isFive(int[] a, boolean exact) {
        int run = centerRun(a);
        return exact ? run == 5 : run >= 5;
    }

    /** 돌 하나를 더 놓아 5목을 만들 수 있는 빈칸의 수 (1이면 4, 2 이상이면 열린 4) */
    private static int fiveCompletions(int[] a, boolean exact) {
        int c = 0;
        for (int i = 0; i < 11; i++) {
            if (a[i] != 0) continue;
            a[i] = 1;
            if (isFive(a, exact)) c++;
            a[i] = 0;
        }
        return c;
    }

    static int lineType(int[] a, boolean exact) {
        int run = centerRun(a);
        if (run >= 5) return (exact && run > 5) ? T_OVERLINE : T_FIVE;

        int f = fiveCompletions(a, exact);
        if (f >= 2) return T_OPEN_FOUR;
        if (f == 1) return T_FOUR;

        // 한 수 더 두면 4가 되는가? -> 3
        boolean open3 = false, three = false;
        for (int i = 0; i < 11 && !open3; i++) {
            if (a[i] != 0) continue;
            a[i] = 1;
            if (centerRun(a) < 6 || !exact) {
                int f2 = fiveCompletions(a, exact);
                if (f2 >= 2) open3 = true;
                else if (f2 == 1) three = true;
            }
            a[i] = 0;
        }
        if (open3) return T_OPEN_THREE;
        if (three) return T_THREE;

        // 한 수 더 두면 3이 되는가? -> 2
        boolean open2 = false, two = false;
        for (int i = 0; i < 11 && !open2; i++) {
            if (a[i] != 0) continue;
            a[i] = 1;
            for (int j = 0; j < 11 && !open2; j++) {
                if (a[j] != 0) continue;
                a[j] = 1;
                int f3 = fiveCompletions(a, exact);
                if (f3 >= 2) open2 = true;
                else if (f3 == 1) two = true;
                a[j] = 0;
            }
            a[i] = 0;
        }
        if (open2) return T_OPEN_TWO;
        if (two) return T_TWO;
        return T_NONE;
    }

    /** 한 칸에 돌을 놓았을 때 4방향 모양의 개수 */
    public static final class Shape {
        public int five, overline, openFour, four, openThree, three, openTwo, two;

        public int fours() {
            return openFour + four;
        }
    }

    // 줄 모양 계산은 느리므로, 줄(가운데 제외 10칸, 각 칸 3가지 = 3^10 가지)마다 결과를 한 번만 계산해 저장한다.
    private static final byte[][] TYPE_CACHE = new byte[2][59049];

    private static int cachedType(int code, boolean exact) {
        byte[] t = TYPE_CACHE[exact ? 1 : 0];
        int v = t[code];
        if (v != 0) return v - 1;
        int[] a = new int[11];
        int c = code;
        for (int i = 0; i < 11; i++) {
            if (i == 5) {
                a[i] = 1;
                continue;
            }
            a[i] = c % 3;
            c /= 3;
        }
        int type = lineType(a, exact);
        t[code] = (byte) (type + 1);
        return type;
    }

    /** (x,y) 중심 줄을 3진수 번호로 바꾼다 (가운데 칸 제외) */
    private int lineCode(int x, int y, int dx, int dy, int p) {
        int code = 0, mul = 1;
        for (int k = -5; k <= 5; k++) {
            if (k == 0) continue;
            int v = get(x + k * dx, y + k * dy);
            code += (v == p ? 1 : (v == EMPTY ? 0 : 2)) * mul;
            mul *= 3;
        }
        return code;
    }

    public Shape analyze(int x, int y, int p) {
        Shape s = new Shape();
        boolean ex = exact(p);
        for (int[] d : DIRS) {
            switch (cachedType(lineCode(x, y, d[0], d[1], p), ex)) {
                case T_FIVE: s.five++; break;
                case T_OVERLINE: s.overline++; break;
                case T_OPEN_FOUR: s.openFour++; break;
                case T_FOUR: s.four++; break;
                case T_OPEN_THREE: s.openThree++; break;
                case T_THREE: s.three++; break;
                case T_OPEN_TWO: s.openTwo++; break;
                case T_TWO: s.two++; break;
                default: break;
            }
        }
        return s;
    }

    /** p에게 금수 규칙이 하나라도 있는가 */
    public boolean hasBans(int p) {
        return rule(p, B_OVERLINE) || rule(p, B_33) || rule(p, B_44);
    }

    /**
     * 금수 유도 : p가 (x,y)에 두어 4를 만들었는데, 상대가 막아야 할 자리가 전부 상대의 금수라서 막을 수 없는가.
     * (예: 렌주룰에서 백이 4를 두었는데, 흑이 막을 자리가 흑의 3-3 자리) → 다음 수에 5목이 되므로 필승.
     */
    public boolean forbiddenTrapFour(int x, int y, int p) {
        int opp = opponent(p);
        if (!hasBans(opp) || get(x, y) != EMPTY) return false;
        set(x, y, p);
        List<int[]> wins = completionCells(x, y, p);
        boolean trap = !wins.isEmpty();
        for (int[] w : wins) {
            if (!isForbidden(w[0], w[1], opp)) {
                trap = false;
                break;
            }
        }
        set(x, y, EMPTY);
        return trap;
    }

    /** 금수(규칙상 둘 수 없는 자리) 여부 : 장목, 3-3, 4-4. 5목이 되는 자리는 금수가 아니다. */
    public boolean isForbidden(int x, int y, int p) {
        if (get(x, y) != EMPTY) return false;
        boolean ban6 = rule(p, B_OVERLINE), ban33 = rule(p, B_33), ban44 = rule(p, B_44);
        if (!ban6 && !ban33 && !ban44) return false;
        if (makesFive(x, y, p)) return false;
        if (ban6 && makesOverline(x, y, p)) return true;
        if (!ban33 && !ban44) return false;
        Shape s = analyze(x, y, p);
        return (ban44 && s.fours() >= 2) || (ban33 && s.openThree >= 2);
    }

    // ---------------------------------------------------------------- 점수

    static final long WIN = 1_000_000L;

    static long attackScore(Shape s) {
        if (s.five > 0) return WIN;
        int fours = s.fours();
        if (s.openFour > 0 || fours >= 2) return 100_000;
        if (fours >= 1 && s.openThree >= 1) return 50_000;
        if (s.openThree >= 2) return 20_000;
        return fours * 1_500L + s.openThree * 1_000L + s.three * 150L + s.openTwo * 100L + s.two * 15L;
    }

    // ---------------------------------------------------------------- 결과 객체

    public static final class Move implements Comparable<Move> {
        public final int x, y;
        public long score;
        public String reason;

        Move(int x, int y, long score, String reason) {
            this.x = x;
            this.y = y;
            this.score = score;
            this.reason = reason;
        }

        @Override
        public int compareTo(Move o) {
            return Long.compare(o.score, score);
        }
    }

    public static final class Result {
        public int player;                      // 추천 대상(차례인 쪽)
        public final List<Move> moves = new ArrayList<>();
        public List<int[]> winSequence;          // 내 필승 수순 (없으면 null)
        public List<int[]> threatSequence;       // 상대의 필승 수순 (없으면 null)
        public List<int[]> pv;                   // 깊이 탐색의 예상 수순 (없으면 null)
        public int searchDepth;                  // 깊이 탐색으로 읽은 수 (0 = 탐색 안 함)
        public int searchNodes;                  // 검토한 판의 수
    }

    // ---------------------------------------------------------------- 추천

    /** p가 둘 차례일 때 좋은 수를 topN개 추천한다. */
    public Result recommend(int p, int topN) {
        Result res = new Result();
        res.player = p;
        int opp = opponent(p);

        List<int[]> cands = candidates(2);
        if (cands.isEmpty()) { // 빈 판이면 정중앙
            int c = size / 2;
            res.moves.add(new Move(c, c, 1, "첫 수는 정중앙(천원)"));
            return res;
        }

        boolean oppHasFive = false;
        for (int[] c : cands) if (makesFive(c[0], c[1], opp)) oppHasFive = true;

        boolean myTrap = false, threatTrap = false;

        // 1) 필승 수순 탐색
        if (!oppHasFive) {
            res.winSequence = findVcf(p, 10, 4000);
            myTrap = res.winSequence != null && lastVcfTrap;
            if (res.winSequence == null) {
                res.threatSequence = findVcf(opp, 10, 4000);
                threatTrap = res.threatSequence != null && lastVcfTrap;
            }
        }

        // 2) 모든 후보의 점수 계산
        List<Move> moves = new ArrayList<>();
        for (int[] c : cands) {
            int x = c[0], y = c[1];
            if (isForbidden(x, y, p)) continue;
            Shape att = analyze(x, y, p);
            Shape def = isForbidden(x, y, opp) ? new Shape() : analyze(x, y, opp);
            long a = attackScore(att), d = attackScore(def);
            boolean trapAtt = a < 100_000 && att.fours() > 0 && forbiddenTrapFour(x, y, p);
            boolean trapDef = d < 100_000 && def.fours() > 0 && forbiddenTrapFour(x, y, opp);
            if (trapAtt) a = 100_000;
            if (trapDef) d = 100_000;
            long score = a + d * 8 / 10 + centerBonus(x, y);
            String reason = reasonFor(att, def, a, d);
            if (trapAtt) reason = "금수 유도 — 상대가 막을 자리가 금수라 못 막음 (필승)";
            else if (trapDef && a < 100_000) reason = "상대의 금수 유도 차단 (안 막으면 짐)";

            if (res.winSequence != null) {
                int[] first = res.winSequence.get(0);
                if (first[0] == x && first[1] == y && a < WIN) {
                    score = 900_000;
                    reason = "필승 수순(연속 4" + (myTrap ? " + 금수 유도" : "") + ") 시작 — "
                            + (res.winSequence.size() + 1) / 2 + "수 만에 승리";
                }
            } else if (res.threatSequence != null && a < WIN && d < WIN) {
                // 상대의 필승 수순에 쓰이는 자리를 막는다 (상대의 첫 수가 가장 중요)
                for (int i = 0; i < res.threatSequence.size(); i += 2) {
                    int[] t = res.threatSequence.get(i);
                    if (t[0] == x && t[1] == y) {
                        score += i == 0 ? 40_000 : 15_000;
                        reason = threatTrap ? "상대의 필승 수순(연속 4 + 금수 유도) 차단" : "상대의 필승 수순(연속 4) 차단";
                        break;
                    }
                }
            }
            moves.add(new Move(x, y, score, reason));
        }
        Collections.sort(moves);

        // 3) 한 수 앞 보기 : 상위 후보에 대해 상대의 최선 반격을 계산해 감점
        int look = Math.min(10, moves.size());
        for (int i = 0; i < look; i++) {
            Move m = moves.get(i);
            if (m.score >= 800_000) continue; // 승리/필승/5목 방어는 그대로
            set(m.x, m.y, p);
            boolean madeFour = analyzeStone(m.x, m.y, p);
            if (!madeFour) {
                long oppBest = 0;
                for (int[] c : candidates(2)) {
                    if (isForbidden(c[0], c[1], opp)) continue;
                    oppBest = Math.max(oppBest, attackScore(analyze(c[0], c[1], opp)));
                }
                m.score -= oppBest * 3 / 10;
            }
            set(m.x, m.y, EMPTY);
        }
        Collections.sort(moves);

        for (int i = 0; i < Math.min(topN, moves.size()); i++) res.moves.add(moves.get(i));
        return res;
    }

    /**
     * 깊이 탐색 추천.
     * 바로 이기는 수 / 5목 막기 / 연속 4 필승처럼 답이 정해진 상황은 빠른 분석 결과를 그대로 쓰고,
     * 그 밖에는 DeepSearch(알파-베타)로 timeMs 동안 여러 수 앞을 읽어 순위를 정한다.
     */
    public Result recommendDeep(int p, int topN, long timeMs) {
        Result base = recommend(p, 30);
        boolean decided = base.moves.isEmpty() || base.winSequence != null
                || base.moves.get(0).score >= 800_000 || candidates(2).isEmpty();
        if (decided) {
            while (base.moves.size() > topN) base.moves.remove(base.moves.size() - 1);
            return base;
        }

        DeepSearch.Outcome o = new DeepSearch(this).run(p, timeMs, 20);
        Result res = new Result();
        res.player = p;
        res.threatSequence = base.threatSequence;
        res.searchDepth = o.depth;
        res.searchNodes = o.nodes;
        res.pv = o.pv;
        int opp = opponent(p);
        for (DeepSearch.RootMove rm : o.moves) {
            if (res.moves.size() >= topN) break;
            String reason = null;
            for (Move m : base.moves) if (m.x == rm.x && m.y == rm.y) reason = m.reason;
            if (reason == null) {
                Shape att = analyze(rm.x, rm.y, p);
                Shape def = isForbidden(rm.x, rm.y, opp) ? new Shape() : analyze(rm.x, rm.y, opp);
                reason = reasonFor(att, def, attackScore(att), attackScore(def));
            }
            String deep = DeepSearch.describe(rm.value);
            if (deep != null) reason = deep + " · " + reason;
            res.moves.add(new Move(rm.x, rm.y, rm.value, reason));
        }
        return res;
    }

    /** 이미 놓인 (x,y)의 돌이 4(5목 직전)를 만들었는가 */
    private boolean analyzeStone(int x, int y, int p) {
        set(x, y, EMPTY);
        Shape s = analyze(x, y, p);
        set(x, y, p);
        return s.fours() > 0;
    }

    long centerBonus(int x, int y) {
        int c = size / 2;
        return Math.max(0, 8 - (Math.abs(x - c) + Math.abs(y - c)) / 2);
    }

    static String reasonFor(Shape att, Shape def, long a, long d) {
        if (a >= WIN) return "5목 완성 — 바로 승리!";
        if (d >= WIN) return "상대의 5목을 막아야 합니다";
        if (att.openFour > 0) return "열린 4 — 막을 수 없는 필승";
        if (att.fours() >= 2) return "4-4 공격 (필승)";
        if (att.fours() >= 1 && att.openThree >= 1) return "4-3 공격 (필승)";
        if (def.openFour > 0) return "상대의 열린 3 막기 (안 막으면 열린 4)";
        if (def.fours() >= 2) return "상대의 4-4 자리 차단";
        if (def.fours() >= 1 && def.openThree >= 1) return "상대의 4-3 자리 차단";
        if (att.openThree >= 2) return "3-3 공격";
        if (def.openThree >= 2) return "상대의 3-3 자리 차단";
        if (a >= 1_000 && a >= d) {
            if (att.fours() >= 1) return "4 만들기 (상대가 반드시 막아야 함)";
            return "열린 3 만들기 (공격)";
        }
        if (d >= 1_000) {
            if (def.fours() >= 1) return "상대의 4 자리 선점 (수비)";
            return "상대의 열린 3 자리 선점 (수비)";
        }
        if (att.three > 0 || att.openTwo > 0) return "공격 모양 만들기";
        if (def.three > 0 || def.openTwo > 0) return "상대 모양 견제";
        return "주변 자리 확보";
    }

    /** 돌이 있는 곳에서 거리 dist 이내의 빈칸들 */
    List<int[]> candidates(int dist) {
        List<int[]> list = new ArrayList<>();
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                if (get(x, y) != EMPTY) continue;
                boolean near = false;
                for (int dy = -dist; dy <= dist && !near; dy++)
                    for (int dx = -dist; dx <= dist && !near; dx++) {
                        int v = get(x + dx, y + dy);
                        if (v == BLACK || v == WHITE) near = true;
                    }
                if (near) list.add(new int[]{x, y});
            }
        }
        return list;
    }

    private List<int[]> candidatesNear(int dist, int p) {
        List<int[]> list = new ArrayList<>();
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                if (get(x, y) != EMPTY) continue;
                boolean near = false;
                for (int dy = -dist; dy <= dist && !near; dy++)
                    for (int dx = -dist; dx <= dist && !near; dx++)
                        if (get(x + dx, y + dy) == p) near = true;
                if (near) list.add(new int[]{x, y});
            }
        }
        return list;
    }

    // ---------------------------------------------------------------- VCF (연속 4 필승 탐색)

    /** (x,y)를 지나는 줄 위에서, p가 두면 5목이 되는 빈칸들 */
    private List<int[]> completionCells(int x, int y, int p) {
        List<int[]> res = new ArrayList<>();
        for (int[] d : DIRS) {
            for (int k = -4; k <= 4; k++) {
                int cx = x + k * d[0], cy = y + k * d[1];
                if (get(cx, cy) != EMPTY) continue;
                if (!makesFive(cx, cy, p)) continue;
                boolean dup = false;
                for (int[] r : res) if (r[0] == cx && r[1] == cy) dup = true;
                if (!dup) res.add(new int[]{cx, cy});
            }
        }
        return res;
    }

    /**
     * p가 4를 계속 두어 이기는 수순을 찾는다. (p가 둘 차례라고 가정)
     * 반환값: [내 수, 상대 응수, 내 수, ...] 마지막은 5목이 되는 수. 없으면 null.
     */
    public List<int[]> findVcf(int p, int maxDepth, int limit) {
        nodeCount = 0;
        nodeLimit = limit;
        lastVcfTrap = false;
        int opp = opponent(p);
        for (int[] c : candidates(1)) if (makesFive(c[0], c[1], opp)) return null; // 상대가 먼저 5목
        return vcf(p, maxDepth);
    }

    /** 마지막으로 찾은 VCF 수순이 금수 유도를 포함하는가 */
    private boolean lastVcfTrap;

    private List<int[]> vcf(int p, int depth) {
        if (depth <= 0 || ++nodeCount > nodeLimit) return null;
        int opp = opponent(p);
        List<int[]> cands = candidatesNear(2, p);

        for (int[] c : cands) {
            if (makesFive(c[0], c[1], p)) {
                List<int[]> win = new ArrayList<>();
                win.add(c);
                return win;
            }
        }
        for (int[] c : cands) {
            int x = c[0], y = c[1];
            if (isForbidden(x, y, p)) continue;
            set(x, y, p);
            List<int[]> wins = completionCells(x, y, p);
            List<int[]> result = null;
            if (wins.size() >= 2) {
                // 열린 4 (또는 4-4) : 상대가 한 곳만 막을 수 있다
                result = new ArrayList<>();
                result.add(c);
                int[] block = wins.get(0);
                result.add(block);
                result.add(wins.get(1));
            } else if (wins.size() == 1) {
                int[] w = wins.get(0);
                if (isForbidden(w[0], w[1], opp)) {
                    // 상대는 그 자리가 금수라 막을 수 없음 (금수 유도) -> 다음 수에 5목
                    lastVcfTrap = true;
                    result = new ArrayList<>();
                    result.add(c);
                    result.add(w);
                } else {
                    set(w[0], w[1], opp);
                    if (completionCells(w[0], w[1], opp).isEmpty()) { // 막으면서 상대가 4를 만들면 중단
                        List<int[]> sub = vcf(p, depth - 1);
                        if (sub != null) {
                            result = new ArrayList<>();
                            result.add(c);
                            result.add(w);
                            result.addAll(sub);
                        }
                    }
                    set(w[0], w[1], EMPTY);
                }
            }
            set(x, y, EMPTY);
            if (result != null) return result;
            if (nodeCount > nodeLimit) return null;
        }
        return null;
    }

    // ---------------------------------------------------------------- 표기

    /** 좌표 표기 (예: H8). 가로 A부터, 세로는 아래가 1 */
    public String coord(int x, int y) {
        return String.valueOf((char) ('A' + x)) + (size - y);
    }
}
