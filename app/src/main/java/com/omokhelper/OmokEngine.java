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
            T_FOUR = 5, T_OPEN_FOUR = 6, T_FIVE = 7, T_OVERLINE = 8,
            T_DOUBLE_FOUR = 9; // 한 줄 안의 4-4 (예: X_XXX_X) — 렌주룰에서는 4-4 금수

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
    /** 5목을 만드는 빈칸 위치들의 비트 마스크 */
    private static int completionMask(int[] a, boolean exact) {
        int mask = 0;
        for (int i = 0; i < 11; i++) {
            if (a[i] != 0) continue;
            a[i] = 1;
            if (isFive(a, exact)) mask |= 1 << i;
            a[i] = 0;
        }
        return mask;
    }

    /** 진짜 열린 4 (_XXXX_) : 5목 자리 두 곳이 정확히 5칸 떨어져 있음 */
    private static boolean isStraightFour(int mask) {
        for (int i = 0; i + 5 < 11; i++) if ((mask >> i & 1) != 0 && (mask >> (i + 5) & 1) != 0) return true;
        return false;
    }

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

        int fm = completionMask(a, exact);
        int f = Integer.bitCount(fm);
        if (f >= 2) return isStraightFour(fm) ? T_OPEN_FOUR : T_DOUBLE_FOUR;
        if (f == 1) return T_FOUR;

        // 한 수 더 두면 4가 되는가? -> 3
        boolean open3 = false, three = false;
        for (int i = 0; i < 11 && !open3; i++) {
            if (a[i] != 0) continue;
            a[i] = 1;
            if (centerRun(a) < 6 || !exact) {
                int m2 = completionMask(a, exact);
                int f2 = Integer.bitCount(m2);
                if (f2 >= 2 && isStraightFour(m2)) open3 = true; // 열린 3 = 진짜 열린 4가 될 수 있는 3
                else if (f2 >= 1) three = true;
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
                case T_DOUBLE_FOUR: s.four += 2; break;
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

    // 모양별 점수 (자가 대국으로 조정한 값). 실험할 때는 -Domok.wFour=... 처럼 바꿔 볼 수 있다.
    static final long W_FOUR = Long.getLong("omok.wFour", 1_500L);
    static final long W_OPEN3 = Long.getLong("omok.wOpen3", 1_000L);
    static final long W_THREE = Long.getLong("omok.wThree", 150L);
    static final long W_OPEN2 = Long.getLong("omok.wOpen2", 100L);
    static final long W_TWO = Long.getLong("omok.wTwo", 15L);
    /** 수비 점수 비율 (%) : 상대가 그 자리에 두었을 때의 점수를 얼마나 중요하게 볼지 */
    static final long DEF_PCT = Long.getLong("omok.defPct", 80L);

    static long attackScore(Shape s) {
        if (s.five > 0) return WIN;
        int fours = s.fours();
        if (s.openFour > 0 || fours >= 2) return 100_000;
        if (fours >= 1 && s.openThree >= 1) return 50_000;
        if (s.openThree >= 2) return 20_000;
        return fours * W_FOUR + s.openThree * W_OPEN3 + s.three * W_THREE + s.openTwo * W_OPEN2 + s.two * W_TWO;
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
        public boolean vct;                      // winSequence 가 연속 3·4(VCT) 수순인가
        public String opening;                   // 초반 주형 이름 (알 수 있을 때만)
        public boolean decided;                  // 답이 정해진 상황 (승리·5목 막기·필승 수순)
        public boolean learned;                  // 1순위가 지난 패배에서 배운 수
    }

    // ---------------------------------------------------------------- 추천

    /** p가 둘 차례일 때 좋은 수를 topN개 추천한다. */
    @SuppressWarnings("unchecked")
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

        boolean myTrap = false, threatTrap = false, myVct = false, threatVct = false;

        // 1) 필승 수순 탐색 : 먼저 연속 4(VCF), 없으면 연속 3·4(VCT)
        // (내 필승 수순이 있으면 상대 수순은 볼 필요 없음. VCT는 상대의 반격 4까지 검토하므로 상대 VCF보다 먼저 본다)
        if (!oppHasFive) {
            // 지난번에 찾은 필승 계획대로 상대가 응수했다면 그 계획을 이어서 둔다
            Object[] plan = planFor(p);
            if (plan != null) {
                res.winSequence = (List<int[]>) plan[0];
                myVct = (Boolean) plan[1];
            }
            if (res.winSequence == null) {
                res.winSequence = findVcf(p, 10, 4000);
                myTrap = res.winSequence != null && lastVcfTrap;
            }
            if (res.winSequence == null) {
                res.winSequence = findVct(p, VCT_DEPTH, VCT_NODES, VCT_MS);
                myVct = res.winSequence != null;
            }
            if (res.winSequence != null) rememberPlan(p, res.winSequence, myVct);
            if (res.winSequence == null) {
                res.threatSequence = findVcf(opp, 10, 4000);
                threatTrap = res.threatSequence != null && lastVcfTrap;
                if (res.threatSequence == null) {
                    res.threatSequence = findVct(opp, VCT_DEPTH, VCT_NODES, VCT_MS);
                    threatVct = res.threatSequence != null;
                }
            }
        }
        res.vct = myVct;

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
            long score = a + d * DEF_PCT / 100 + centerBonus(x, y);
            String reason = reasonFor(att, def, a, d);
            if (trapAtt) reason = "금수 유도 — 상대가 막을 자리가 금수라 못 막음 (필승)";
            else if (trapDef && a < 100_000) reason = "상대의 금수 유도 차단 (안 막으면 짐)";

            if (res.winSequence != null) {
                int[] first = res.winSequence.get(0);
                if (first[0] == x && first[1] == y && a < WIN) {
                    score = 900_000;
                    score = myVct ? 880_000 : 900_000;
                    reason = "필승 수순(" + (myVct ? "연속 3·4, VCT" : "연속 4") + (myTrap ? " + 금수 유도" : "") + ") 시작 — "
                            + (myVct && res.winSequence.size() == 1 ? "상대가 막을 곳이 없음"
                            : (res.winSequence.size() + 1) / 2 + "수 만에 승리");
                }
            } else if (res.threatSequence != null && a < WIN && d < WIN) {
                // 상대의 필승 수순에 쓰이는 자리를 막는다 (상대의 첫 수가 가장 중요)
                for (int i = 0; i < res.threatSequence.size(); i += 2) {
                    int[] t = res.threatSequence.get(i);
                    if (t[0] == x && t[1] == y) {
                        score += i == 0 ? 40_000 : 15_000;
                        reason = threatVct ? "상대의 필승 수순(연속 3·4, VCT) 차단"
                                : (threatTrap ? "상대의 필승 수순(연속 4 + 금수 유도) 차단" : "상대의 필승 수순(연속 4) 차단");
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
        return applyLearned(p, topN, recommendDeepInner(p, topN, timeMs));
    }

    /**
     * 지난 패배에서 배운 수(LearnedBook)가 이 판에 있으면 1순위로 올린다.
     * 단, 바로 이기는 수·5목 막기·필승 수순처럼 답이 정해진 경우는 그대로 둔다.
     */
    private Result applyLearned(int p, int topN, Result r) {
        LearnedBook book = LearnedBook.active;
        if (book == null || r.decided) return r;
        int[] lm = book.lookup(cells, size, rules, p);
        if (lm == null || get(lm[0], lm[1]) != EMPTY || isForbidden(lm[0], lm[1], p)) return r;
        Result out = new Result();
        out.player = r.player;
        out.threatSequence = r.threatSequence;
        out.pv = r.pv;
        out.searchDepth = r.searchDepth;
        out.searchNodes = r.searchNodes;
        out.opening = r.opening;
        out.learned = true;
        String why = null;
        for (Move m : r.moves) if (m.x == lm[0] && m.y == lm[1]) why = m.reason;
        long top = r.moves.isEmpty() ? 0 : r.moves.get(0).score;
        out.moves.add(new Move(lm[0], lm[1], top + 1, "📚 지난 패배에서 배운 수" + (why != null ? " · " + why : "")));
        for (Move m : r.moves) {
            if (out.moves.size() >= topN) break;
            if (m.x == lm[0] && m.y == lm[1]) continue;
            out.moves.add(m);
        }
        return out;
    }

    private Result recommendDeepInner(int p, int topN, long timeMs) {
        // 초반 정석 : 흑 3수는 흑 필승으로 알려진 주형 자리를 먼저 추천
        if (p == BLACK) {
            List<Object[]> book = Openings.bookThirdMoves(this);
            if (!book.isEmpty()) {
                Result res = new Result();
                res.player = p;
                for (Object[] b : book) {
                    int[] c = (int[]) b[0];
                    Openings.Opening o = (Openings.Opening) b[1];
                    res.moves.add(new Move(c[0], c[1], 500_000 - res.moves.size(),
                            "주형 " + o.title() + " — " + o.verdict));
                }
                for (Move m : recommend(p, topN + book.size()).moves) {
                    if (res.moves.size() >= topN) break;
                    boolean dup = false;
                    for (Move r : res.moves) dup |= r.x == m.x && r.y == m.y;
                    if (!dup) res.moves.add(m);
                }
                while (res.moves.size() > topN) res.moves.remove(res.moves.size() - 1);
                return res;
            }
        }
        Result base = recommend(p, 30);
        boolean decided = base.moves.isEmpty() || base.winSequence != null
                || base.moves.get(0).score >= 800_000 || candidates(2).isEmpty();
        if (decided) {
            while (base.moves.size() > topN) base.moves.remove(base.moves.size() - 1);
            base.decided = !base.moves.isEmpty();
            return base;
        }

        DeepSearch.Outcome o = new DeepSearch(this).run(p, timeMs, 20);
        if (o.moves.isEmpty()) {
            // 막을 수 없는 형세 (예: 막아야 할 자리가 내 금수) — 그래도 둘 수 있는 수 중 가장 나은 것을 보여 준다
            while (base.moves.size() > topN) base.moves.remove(base.moves.size() - 1);
            for (Move m : base.moves) {
                if (!m.reason.startsWith("이미 불리")) m.reason = "이미 불리 (막을 수 없음) · " + m.reason;
            }
            return base;
        }
        Result res = new Result();
        res.player = p;
        res.threatSequence = base.threatSequence;
        res.searchDepth = o.depth;
        res.searchNodes = o.nodes;
        res.pv = o.pv;
        res.decided = o.moves.get(0).value >= DeepSearch.WIN_SCORE - 100; // 깊이 탐색이 필승을 찾음
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

    // ---------------------------------------------------------------- VCT (연속 3·4 필승 탐색)
    //
    // 열린 3(상대가 안 막으면 다음에 열린 4)과 4(반드시 막아야 함)를 섞어서, 상대가 어떻게 막아도
    // 이기는 수순을 찾는다. 공격 쪽은 위협 수만 두고, 수비 쪽은 위협을 없애는 모든 자리와 반격 4를 검토한다.

    static final int VCT_DEPTH = 6;   // 공격 수 기준 최대 깊이
    static final int VCT_NODES = 3000; // 검토할 판의 수 (기기 속도와 상관없이 같은 결과가 나오도록 주로 이것으로 제한)
    static final long VCT_MS = 400;    // 안전용 시간 제한
    private long vctDeadline;

    public List<int[]> findVct(int p, int maxDepth, int limit, long timeMs) {
        nodeCount = 0;
        nodeLimit = limit;
        vctDeadline = System.nanoTime() + timeMs * 1_000_000L;
        int opp = opponent(p);
        for (int[] c : candidates(1)) if (makesFive(c[0], c[1], opp)) return null;
        // 짧은 수순부터 찾는다 (반복 심화) — 가장 빨리 이기는 길을 먼저 찾고, 다음 수에서도 같은 길을 다시 찾기 쉽다
        for (int depth = 2; depth <= maxDepth; depth++) {
            List<int[]> r = vctAttack(p, depth);
            if (r != null) return r;
            if (vctOutOfBudget()) break;
        }
        return null;
    }

    // ---------------------------------------------------------------- 필승 계획 기억
    // 필승 수순을 찾으면, 상대가 수순대로 응수했을 때의 판을 기억해 두고 다음 수에서 그대로 이어서 둔다.
    // (탐색 시간이 모자라 다음 수에서 수순을 놓치는 일을 막음)

    private static final java.util.Map<Long, Object[]> PLANS = new java.util.HashMap<>();

    private long planKey(int p) {
        return hash ^ (p == BLACK ? 0x9E3779B97F4A7C15L : 0x632BE59BD9B4E019L) ^ ((long) size << 56) ^ ((long) rules << 48);
    }

    private void rememberPlan(int p, List<int[]> line, boolean vct) {
        int opp = opponent(p);
        List<int[]> placed = new ArrayList<>();
        synchronized (PLANS) {
            if (PLANS.size() > 200) PLANS.clear();
            for (int i = 0; i + 1 < line.size(); i += 2) {
                int[] mine = line.get(i), theirs = line.get(i + 1);
                if (get(mine[0], mine[1]) != EMPTY) break;
                set(mine[0], mine[1], p);
                placed.add(mine);
                if (get(theirs[0], theirs[1]) != EMPTY) break;
                set(theirs[0], theirs[1], opp);
                placed.add(theirs);
                if (i + 2 < line.size()) {
                    PLANS.put(planKey(p), new Object[]{new ArrayList<>(line.subList(i + 2, line.size())), vct});
                }
            }
        }
        for (int i = placed.size() - 1; i >= 0; i--) set(placed.get(i)[0], placed.get(i)[1], EMPTY);
    }

    private Object[] planFor(int p) {
        Object[] plan;
        synchronized (PLANS) {
            plan = PLANS.get(planKey(p));
        }
        if (plan == null) return null;
        @SuppressWarnings("unchecked")
        List<int[]> line = (List<int[]>) plan[0];
        int[] next = line.get(0);
        if (get(next[0], next[1]) != EMPTY || isForbidden(next[0], next[1], p)) return null;
        return plan;
    }

    private boolean vctOutOfBudget() {
        return nodeCount > nodeLimit || System.nanoTime() > vctDeadline;
    }

    /** p가 다음 한 수로 막을 수 없는 공격(5목, 열린 4, 4-4, 금수 유도 4)을 만들 수 있는가 */
    private boolean hasWinningThreat(int p) {
        for (int[] c : candidatesNear(2, p)) {
            int x = c[0], y = c[1];
            if (makesFive(x, y, p)) return true;
            if (isForbidden(x, y, p)) continue;
            Shape s = analyze(x, y, p);
            if (attackScore(s) >= 100_000) return true;
            if (s.fours() > 0 && forbiddenTrapFour(x, y, p)) return true;
        }
        return false;
    }

    /** p가 4-3 이상의 강한 공격을 준비하고 있는가 (이때 상대는 3으로 느리게 공격하면 안 된다) */
    private boolean hasStrongThreat(int p) {
        for (int[] c : candidatesNear(2, p)) {
            int x = c[0], y = c[1];
            if (isForbidden(x, y, p)) continue;
            Shape s = analyze(x, y, p);
            if (attackScore(s) >= 50_000) return true;
            if (s.fours() > 0 && forbiddenTrapFour(x, y, p)) return true;
        }
        return false;
    }

    private List<int[]> vctAttack(int a, int depth) {
        if (depth <= 0 || ++nodeCount > nodeLimit || System.nanoTime() > vctDeadline) return null;
        int d = opponent(a);
        List<int[]> cands = candidatesNear(2, a);
        for (int[] c : cands) {
            if (makesFive(c[0], c[1], a)) {
                List<int[]> win = new ArrayList<>();
                win.add(c);
                return win;
            }
        }
        for (int[] c : candidates(1)) if (makesFive(c[0], c[1], d)) return null; // 상대가 먼저 5목 위협

        boolean slowAllowed = !hasStrongThreat(d); // 상대에게 큰 위협이 있으면 4로만 공격
        List<int[]> fours = new ArrayList<>();
        List<long[]> threes = new ArrayList<>();
        for (int[] c : cands) {
            if (isForbidden(c[0], c[1], a)) continue;
            Shape s = analyze(c[0], c[1], a);
            if (s.fours() > 0) fours.add(c);
            else if (slowAllowed && s.openThree > 0) threes.add(new long[]{attackScore(s), c[0], c[1]});
        }

        // 4로 공격 (상대는 반드시 막아야 함)
        for (int[] c : fours) {
            int x = c[0], y = c[1];
            set(x, y, a);
            List<int[]> wins = completionCells(x, y, a);
            List<int[]> result = null;
            if (wins.size() >= 2) {
                result = new ArrayList<>();
                result.add(c);
                result.add(wins.get(0));
                result.add(wins.get(1));
            } else if (wins.size() == 1) {
                int[] w = wins.get(0);
                if (isForbidden(w[0], w[1], d)) {
                    result = new ArrayList<>();
                    result.add(c);
                    result.add(w);
                } else {
                    set(w[0], w[1], d);
                    if (completionCells(w[0], w[1], d).isEmpty()) {
                        List<int[]> sub = vctAttack(a, depth - 1);
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
            if (vctOutOfBudget()) return null;
        }

        // 열린 3으로 공격 (점수 높은 것부터 8개)
        threes.sort((p1, p2) -> Long.compare(p2[0], p1[0]));
        for (int i = 0; i < threes.size() && i < 8; i++) {
            int x = (int) threes.get(i)[1], y = (int) threes.get(i)[2];
            set(x, y, a);
            List<int[]> line = hasWinningThreat(a) ? vctDefend(a, x, y, depth, 2) : null;
            set(x, y, EMPTY);
            if (line != null) {
                List<int[]> result = new ArrayList<>();
                result.add(new int[]{x, y});
                result.addAll(line);
                return result;
            }
            if (vctOutOfBudget()) return null;
        }
        return null;
    }

    /**
     * a가 (tx,ty)에 열린 3을 둔 직후, 수비 쪽 d의 모든 응수를 검토한다.
     * 모든 응수에 대해 a가 이기면 대표 수순(응수, 공격, …)을, 하나라도 버티면 null.
     */
    private List<int[]> vctDefend(int a, int tx, int ty, int depth, int counterBudget) {
        int d = opponent(a);
        List<int[]> defenses = new ArrayList<>();
        // 1) 위협을 없애는 자리 : 열린 3이 놓인 줄 위의 빈칸 중, 막고 나면 a의 필승 위협이 사라지는 곳
        for (int[] dir : DIRS) {
            for (int k = -5; k <= 5; k++) {
                int x = tx + k * dir[0], y = ty + k * dir[1];
                if (get(x, y) != EMPTY || isForbidden(x, y, d)) continue;
                boolean dup = false;
                for (int[] q : defenses) dup |= q[0] == x && q[1] == y;
                if (dup) continue;
                set(x, y, d);
                boolean still = hasWinningThreat(a);
                set(x, y, EMPTY);
                if (!still) defenses.add(new int[]{x, y});
            }
        }
        List<int[]> main = null;
        for (int[] def : defenses) {
            set(def[0], def[1], d);
            List<int[]> sub = vctAttack(a, depth - 1);
            set(def[0], def[1], EMPTY);
            if (sub == null) return null; // 이 응수로 버팀
            if (main == null) {
                main = new ArrayList<>();
                main.add(def);
                main.addAll(sub);
            }
        }
        // 2) 반격 4 : d가 4를 두면 a는 막아야 하고, 그 뒤 d가 다시 수비할 기회를 얻는다
        for (int[] c : candidatesNear(2, d)) {
            int x = c[0], y = c[1];
            if (isForbidden(x, y, d)) continue;
            if (analyze(x, y, d).fours() == 0) continue;
            if (counterBudget <= 0) return null; // 반격이 너무 길어지면 확실하지 않으므로 필승으로 보지 않음
            set(x, y, d);
            List<int[]> wins = completionCells(x, y, d);
            List<int[]> sub = null;
            if (wins.size() == 1 && !isForbidden(wins.get(0)[0], wins.get(0)[1], a)) {
                int[] w = wins.get(0);
                set(w[0], w[1], a);
                if (makesAnyFive(a)) {
                    sub = new ArrayList<>(); // 막으면서 a가 5목 자리를 얻음 → 여전히 a 승리
                } else if (hasWinningThreat(a)) {
                    sub = vctDefend(a, tx, ty, depth - 1, counterBudget - 1);
                }
                set(w[0], w[1], EMPTY);
            }
            set(x, y, EMPTY);
            if (sub == null) return null; // 반격으로 버티거나 오히려 d가 이김
            if (vctOutOfBudget()) return null;
        }
        return main != null ? main : new ArrayList<>();
    }

    private boolean makesAnyFive(int p) {
        for (int[] c : candidatesNear(1, p)) if (makesFive(c[0], c[1], p)) return true;
        return false;
    }

    /** 좌표 표기 (예: H8). 가로 A부터, 세로는 아래가 1 */
    public String coord(int x, int y) {
        return String.valueOf((char) ('A' + x)) + (size - y);
    }
}
