package com.omokhelper;

import java.util.ArrayList;
import java.util.List;

/**
 * 렌주 26주형 (초반 세 수의 표준 포진).
 *
 * 흑 1수는 천원(가운데), 백 2수는 흑 바로 옆(직접주형) 또는 대각선 옆(간접주형),
 * 흑 3수의 위치에 따라 각각 13가지 이름이 붙는다.
 *
 * 좌표는 흑 1수를 (0,0)으로 한 상대 좌표이고, 화면 방향(아래쪽이 +y)이다.
 *   직접주형 : 백 2수 = (0,-1)  (흑 바로 위)
 *   간접주형 : 백 2수 = (1,-1)  (흑 오른쪽 위 대각선)
 * 판을 돌리거나 뒤집은 8가지 모양을 모두 같은 주형으로 인식한다.
 *
 * 평가는 여러 자료에서 공통으로 확인되는 것만 적었다 (흑 필승: 화월·우월·운월·포월, 백 유리: 유성(遊星)·혜성).
 */
public final class Openings {

    private Openings() {
    }

    public static final class Opening {
        public final String code;     // D1 ~ D13, I1 ~ I13
        public final String korean;   // 한성, 화월 …
        public final String hanja;    // 寒星, 花月 …
        public final String japanese; // Kansei, Kagetsu …
        final int dx, dy;             // 흑 3수의 상대 좌표 (화면 방향)
        public final String verdict;  // 알려진 평가 (없으면 null)

        Opening(String code, String korean, String hanja, String japanese, int dx, int dyUp, String verdict) {
            this.code = code;
            this.korean = korean;
            this.hanja = hanja;
            this.japanese = japanese;
            this.dx = dx;
            this.dy = -dyUp; // 표는 위쪽이 +인 바둑판 표기로 적고, 화면 방향으로 바꾼다
            this.verdict = verdict;
        }

        public boolean direct() {
            return code.startsWith("D");
        }

        public String title() {
            return korean + "(" + hanja + ")";
        }
    }

    private static final String BLACK_WINS = "흑 필승 주형으로 알려짐";
    private static final String WHITE_GOOD = "백 유리 주형으로 알려짐";

    // 바둑판 표기 기준 : 흑 1수 h8, 백 2수 h9(직접) / i9(간접). 표의 dyUp은 위쪽이 +.
    public static final Opening[] ALL = {
            // 직접주형 (백 2수가 흑 바로 위 h9)
            new Opening("D1", "한성", "寒星", "Kansei", 0, 2, null),          // h10
            new Opening("D2", "계월", "渓月", "Keigetsu", 1, 2, null),        // i10
            new Opening("D3", "소성", "疎星", "Sosei", 2, 2, null),           // j10
            new Opening("D4", "화월", "花月", "Kagetsu", 1, 1, BLACK_WINS),   // i9
            new Opening("D5", "잔월", "残月", "Zangetsu", 2, 1, null),        // j9
            new Opening("D6", "우월", "雨月", "Ugetsu", 1, 0, BLACK_WINS),    // i8
            new Opening("D7", "금성", "金星", "Kinsei", 2, 0, null),          // j8
            new Opening("D8", "송월", "松月", "Shogetsu", 1, -1, null),       // i7
            new Opening("D9", "구월", "丘月", "Kyugetsu", 2, -1, null),       // j7
            new Opening("D10", "신월", "新月", "Shingetsu", 0, -1, null),     // h7
            new Opening("D11", "서성", "瑞星", "Zuisei", 0, -2, null),        // h6
            new Opening("D12", "산월", "山月", "Sangetsu", 1, -2, null),      // i6
            new Opening("D13", "유성", "遊星", "Yusei", 2, -2, WHITE_GOOD),   // j6
            // 간접주형 (백 2수가 흑 오른쪽 위 대각선 i9)
            new Opening("I1", "장성", "長星", "Chosei", 2, 2, null),          // j10
            new Opening("I2", "협월", "峡月", "Kyogetsu", 2, 1, null),        // j9
            new Opening("I3", "항성", "恒星", "Kosei", 2, 0, null),           // j8
            new Opening("I4", "수월", "水月", "Suigetsu", 2, -1, null),       // j7
            new Opening("I5", "유성", "流星", "Ryusei", 2, -2, null),         // j6
            new Opening("I6", "운월", "雲月", "Ungetsu", 1, 0, BLACK_WINS),   // i8
            new Opening("I7", "포월", "浦月", "Hogetsu", 1, -1, BLACK_WINS),  // i7
            new Opening("I8", "람월", "嵐月", "Rangetsu", 1, -2, null),       // i6
            new Opening("I9", "은월", "銀月", "Gingetsu", 0, -1, null),       // h7
            new Opening("I10", "명성", "明星", "Myojo", 0, -2, null),         // h6
            new Opening("I11", "사월", "斜月", "Shagetsu", -1, -1, null),     // g7
            new Opening("I12", "명월", "名月", "Meigetsu", -1, -2, null),     // g6
            new Opening("I13", "혜성", "彗星", "Suisei", -2, -2, WHITE_GOOD), // f6
    };

    // ---------------------------------------------------------------- 대칭 (회전 4 × 뒤집기 2)

    /** t번째 대칭 변환을 (x,y)에 적용 */
    static int[] transform(int t, int x, int y) {
        int tx = x, ty = y;
        if ((t & 4) != 0) tx = -tx;          // 좌우 뒤집기
        for (int r = 0; r < (t & 3); r++) {  // 90도 회전
            int nx = -ty, ny = tx;
            tx = nx;
            ty = ny;
        }
        return new int[]{tx, ty};
    }

    /** 결과 : 찾은 주형 + 흑 1수 위치 */
    public static final class Match {
        public final Opening opening;
        public final int firstX, firstY;

        Match(Opening o, int x, int y) {
            opening = o;
            firstX = x;
            firstY = y;
        }
    }

    /**
     * 판에 돌이 정확히 3개(흑 2, 백 1)일 때 어떤 주형인지 찾는다. 아니면 null.
     * 같은 모양이 두 주형으로 읽히는 경우(예: 우월과 운월)는 흑 1수가 천원에 더 가까운 쪽을 고른다.
     */
    public static Match identify(OmokEngine e) {
        List<int[]> blacks = new ArrayList<>(), whites = new ArrayList<>();
        for (int y = 0; y < e.size; y++) {
            for (int x = 0; x < e.size; x++) {
                int v = e.get(x, y);
                if (v == OmokEngine.BLACK) blacks.add(new int[]{x, y});
                else if (v == OmokEngine.WHITE) whites.add(new int[]{x, y});
            }
        }
        if (blacks.size() != 2 || whites.size() != 1) return null;
        int[] w = whites.get(0);
        int c = e.size / 2;
        Match best = null;
        int bestDist = Integer.MAX_VALUE;
        for (int k = 0; k < 2; k++) {
            int[] b1 = blacks.get(k), b3 = blacks.get(1 - k);
            Opening o = match(w[0] - b1[0], w[1] - b1[1], b3[0] - b1[0], b3[1] - b1[1]);
            if (o == null) continue;
            int dist = Math.abs(b1[0] - c) + Math.abs(b1[1] - c);
            if (dist < bestDist || (dist == bestDist && o.direct() && !best.opening.direct())) {
                best = new Match(o, b1[0], b1[1]);
                bestDist = dist;
            }
        }
        return best;
    }

    /** 흑 1수 기준 상대 좌표 (백 wx,wy / 흑 3수 bx,by) 로 주형 찾기 */
    static Opening match(int wx, int wy, int bx, int by) {
        for (int t = 0; t < 8; t++) {
            int[] tw = transform(t, wx, wy);
            boolean direct = tw[0] == 0 && tw[1] == -1;
            boolean indirect = tw[0] == 1 && tw[1] == -1;
            if (!direct && !indirect) continue;
            int[] tb = transform(t, bx, by);
            for (Opening o : ALL) {
                if (o.direct() == direct && o.dx == tb[0] && o.dy == tb[1]) return o;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- 흑 3수 추천

    /** 추천 주형 : 직접주형이면 화월·우월, 간접주형이면 포월·운월 (흑 필승으로 알려진 주형) */
    private static final String[] DIRECT_BOOK = {"D4", "D6"};
    private static final String[] INDIRECT_BOOK = {"I7", "I6"};

    static Opening byCode(String code) {
        for (Opening o : ALL) if (o.code.equals(code)) return o;
        return null;
    }

    /**
     * 흑 1개, 백 1개가 바로 옆/대각선으로 붙어 있고 흑 차례면, 흑 3수로 둘 주형 자리를 돌려준다.
     * 각 원소 = {x, y} 와 주형. 해당 없으면 빈 목록.
     */
    public static List<Object[]> bookThirdMoves(OmokEngine e) {
        List<Object[]> out = new ArrayList<>();
        int[] b = null, w = null;
        int stones = 0;
        for (int y = 0; y < e.size; y++) {
            for (int x = 0; x < e.size; x++) {
                int v = e.get(x, y);
                if (v == OmokEngine.EMPTY) continue;
                stones++;
                if (v == OmokEngine.BLACK) b = new int[]{x, y};
                else w = new int[]{x, y};
            }
        }
        if (stones != 2 || b == null || w == null) return out;
        int wx = w[0] - b[0], wy = w[1] - b[1];
        if (Math.abs(wx) > 1 || Math.abs(wy) > 1) return out;
        boolean direct = wx == 0 || wy == 0;
        String[] book = direct ? DIRECT_BOOK : INDIRECT_BOOK;
        for (String code : book) {
            Opening o = byCode(code);
            // 표준 방향의 주형 자리를 실제 판 방향으로 옮긴다 (백 위치가 같아지는 대칭을 찾음)
            for (int t = 0; t < 8; t++) {
                int[] tw = transform(t, direct ? 0 : 1, -1);
                if (tw[0] != wx || tw[1] != wy) continue;
                int[] tb = transform(t, o.dx, o.dy);
                int x = b[0] + tb[0], y = b[1] + tb[1];
                if (e.get(x, y) == OmokEngine.EMPTY && !e.isForbidden(x, y, OmokEngine.BLACK)) {
                    out.add(new Object[]{new int[]{x, y}, o});
                    break;
                }
            }
        }
        return out;
    }
}
