package com.omokhelper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

/**
 * 패배에서 배운 수를 기억하는 책.
 *
 * "이 판(둘 차례 포함)에서는 이 자리에 둔다"를 저장한다.
 * 판을 돌리거나 뒤집은 8가지 모양은 같은 판으로 본다 — 8가지 중 해시값이 가장 작은 모양을 대표로 쓰고,
 * 수도 그 대표 모양 기준 좌표로 저장했다가 꺼낼 때 다시 원래 방향으로 돌린다.
 */
public final class LearnedBook {

    /** 앱 전체에서 쓰는 책 (화면 감지 서비스가 시작할 때 저장된 내용으로 채운다) */
    public static volatile LearnedBook active;

    private static final long[][] Z = new long[2][19 * 19];
    private static final int MAX_ENTRIES = 500;

    static {
        Random r = new Random(0x5EED_0F_0E05L);
        for (int c = 0; c < 2; c++) for (int i = 0; i < 19 * 19; i++) Z[c][i] = r.nextLong();
    }

    /** 대표 모양 키 → 대표 모양 기준 수 (y*size+x) */
    private final Map<Long, Integer> map = new LinkedHashMap<>();

    public synchronized int size() {
        return map.size();
    }

    public synchronized void clear() {
        map.clear();
    }

    // ---------------------------------------------------------------- 대칭

    private static int[] tf(int t, int x, int y, int size) {
        int c = (size - 1) / 2;
        int[] r = Openings.transform(t, x - c, y - c);
        return new int[]{r[0] + c, r[1] + c};
    }

    private static int inverse(int t) {
        for (int u = 0; u < 8; u++) {
            int[] a = Openings.transform(t, 3, 1);
            int[] b = Openings.transform(u, a[0], a[1]);
            if (b[0] == 3 && b[1] == 1) return u;
        }
        return 0;
    }

    private static long hashUnder(int[] board, int size, int t) {
        long h = 0;
        for (int i = 0; i < board.length; i++) {
            int v = board[i];
            if (v != OmokEngine.BLACK && v != OmokEngine.WHITE) continue;
            int[] p = tf(t, i % size, i / size, size);
            h ^= Z[v - 1][p[1] * size + p[0]];
        }
        return h;
    }

    /** {대표 변환 번호, 키} */
    private static long[] canonical(int[] board, int size, int rules, int side) {
        long best = 0;
        int bestT = -1;
        for (int t = 0; t < 8; t++) {
            long h = hashUnder(board, size, t);
            if (bestT < 0 || Long.compareUnsigned(h, best) < 0) {
                best = h;
                bestT = t;
            }
        }
        long key = best ^ (side == OmokEngine.BLACK ? 0x1F2E3D4C5B6A7988L : 0x7A6B5C4D3E2F1A09L)
                ^ ((long) size << 40) ^ ((long) rules << 32);
        return new long[]{bestT, key};
    }

    // ---------------------------------------------------------------- 저장 / 찾기

    /** board에서 side 차례일 때 (x,y)에 두는 것이 좋다고 기억 */
    public synchronized void learn(int[] board, int size, int rules, int side, int x, int y) {
        long[] c = canonical(board, size, rules, side);
        int[] p = tf((int) c[0], x, y, size);
        map.remove(c[1]);
        map.put(c[1], p[1] * size + p[0]);
        while (map.size() > MAX_ENTRIES) map.remove(map.keySet().iterator().next()); // 오래된 것부터 지움
    }

    /** 기억해 둔 수가 있으면 {x, y}, 없으면 null */
    public synchronized int[] lookup(int[] board, int size, int rules, int side) {
        long[] c = canonical(board, size, rules, side);
        Integer m = map.get(c[1]);
        if (m == null) return null;
        int[] p = tf(inverse((int) c[0]), m % size, m / size, size);
        if (p[0] < 0 || p[1] < 0 || p[0] >= size || p[1] >= size) return null;
        if (board[p[1] * size + p[0]] != OmokEngine.EMPTY) return null;
        return p;
    }

    // ---------------------------------------------------------------- 글자로 저장

    public synchronized String serialize() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Long, Integer> e : map.entrySet()) {
            if (sb.length() > 0) sb.append(';');
            sb.append(Long.toHexString(e.getKey())).append(':').append(e.getValue());
        }
        return sb.toString();
    }

    public static LearnedBook parse(String s) {
        LearnedBook b = new LearnedBook();
        if (s == null || s.isEmpty()) return b;
        for (String part : s.split(";")) {
            int colon = part.indexOf(':');
            if (colon <= 0) continue;
            try {
                b.map.put(Long.parseUnsignedLong(part.substring(0, colon), 16), Integer.parseInt(part.substring(colon + 1)));
            } catch (NumberFormatException ignored) {
            }
        }
        return b;
    }
}
