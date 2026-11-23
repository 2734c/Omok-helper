import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Base64;

/**
 * 오목 도우미 키 생성기 (저작권자 전용)
 *
 * 사용법 (Java 11 이상, 이 파일이 있는 폴더에서):
 *   java KeyGen.java issue 12 K7Q2-9XMA              12번 키, 그 기기 전용, 오늘부터 30일
 *   java KeyGen.java issue 12 K7Q2-9XMA +60          60일
 *   java KeyGen.java issue 12 K7Q2-9XMA 2027-03-01   2027-03-01까지
 *
 * 기기 코드(K7Q2-9XMA)는 사용자가 앱 첫 화면의 "🔑 키" 칸에서 복사해 보내 줍니다.
 * 한 달마다 같은 기기 코드로 새 키를 만들어 주면 갱신됩니다. (키 번호는 사람마다 하나씩 정해 두고 계속 써도 됩니다)
 *
 * 비밀 키 파일 omok-private.key 가 같은 폴더에 있어야 합니다.
 * ⚠ 비밀 키 파일은 절대 GitHub에 올리거나 다른 사람에게 주지 마세요. (누구나 키를 만들 수 있게 됩니다)
 *   이 저장소의 .gitignore 가 *.key 파일을 올리지 않도록 막고 있습니다.
 *
 *   java KeyGen.java init                   새 비밀 키/공개 키 만들기 (처음 한 번만)
 *     → 공개 키가 바뀌면 앱의 LicenseKey.PUBLIC_KEY 도 바꿔야 하고, 예전 키는 모두 쓸 수 없게 됩니다.
 */
public class KeyGen {

    static final String PREFIX = "OMOK2-";
    static final Path PRIVATE = Paths.get("omok-private.key");

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            usage();
            return;
        }
        switch (args[0]) {
            case "init":
                init();
                break;
            case "issue":
                issue(args);
                break;
            default:
                usage();
        }
    }

    static void usage() {
        System.out.println("사용법:");
        System.out.println("  java KeyGen.java issue <번호> <기기코드> [+일수 | 만료일 YYYY-MM-DD]   (기본 +30)");
        System.out.println("  java KeyGen.java init");
    }

    static void init() throws Exception {
        if (Files.exists(PRIVATE)) {
            System.out.println("이미 " + PRIVATE + " 파일이 있습니다. 덮어쓰면 지금까지 만든 키를 모두 못 쓰게 되므로 중단합니다.");
            return;
        }
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair kp = g.generateKeyPair();
        Files.write(PRIVATE, Base64.getEncoder().encodeToString(kp.getPrivate().getEncoded()).getBytes(StandardCharsets.US_ASCII));
        System.out.println("비밀 키를 " + PRIVATE.toAbsolutePath() + " 에 저장했습니다. (절대 공유 금지)");
        System.out.println("앱의 LicenseKey.PUBLIC_KEY 에 넣을 공개 키:");
        System.out.println(Base64.getEncoder().encodeToString(kp.getPublic().getEncoded()));
    }

    static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    /** "K7Q2-9XMA" → 40비트 값. 형식이 틀리면 -1 */
    static long parseDevice(String code) {
        String c = code.replaceAll("[\\s-]", "").toUpperCase();
        if (c.length() != 8) return -1;
        long v = 0;
        for (int i = 0; i < 8; i++) {
            int k = ALPHABET.indexOf(c.charAt(i));
            if (k < 0) return -1;
            v = (v << 5) | k;
        }
        return v;
    }

    static void issue(String[] args) throws Exception {
        if (args.length < 3) {
            usage();
            return;
        }
        int id = Integer.parseInt(args[1]);
        if (id < 1 || id > 65535) throw new IllegalArgumentException("번호는 1~65535");
        long device = parseDevice(args[2]);
        if (device < 0) throw new IllegalArgumentException("기기 코드 형식이 올바르지 않습니다 (예: K7Q2-9XMA). I, O, 0, 1 은 쓰지 않습니다");

        LocalDate today = LocalDate.now();
        LocalDate until;
        String when = args.length >= 4 ? args[3] : "+30";
        if (when.startsWith("+")) until = today.plusDays(Integer.parseInt(when.substring(1)));
        else until = LocalDate.parse(when);
        if (!until.isAfter(today)) throw new IllegalArgumentException("만료일은 오늘 이후여야 합니다");
        long expiry = until.toEpochDay();
        if (expiry > 65535) throw new IllegalArgumentException("만료일이 너무 멀어요");

        PrivateKey key = KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(
                Base64.getDecoder().decode(new String(Files.readAllBytes(PRIVATE), StandardCharsets.US_ASCII).trim())));
        System.out.println("#" + id + "  기기 " + args[2].toUpperCase() + "  " + today + " ~ " + until + " (" + (expiry - today.toEpochDay()) + "일)");
        System.out.println(makeKey(key, id, (int) expiry, device));
    }

    static String makeKey(PrivateKey key, int id, int expiry, long device) throws Exception {
        byte[] payload = new byte[10];
        payload[0] = 2;
        payload[1] = (byte) (id >> 8);
        payload[2] = (byte) id;
        payload[3] = (byte) (expiry >> 8);
        payload[4] = (byte) expiry;
        for (int i = 0; i < 5; i++) payload[5 + i] = (byte) (device >> (8 * (4 - i)));
        byte[] head = "OMOK2".getBytes(StandardCharsets.US_ASCII);
        byte[] signed = Arrays.copyOf(head, head.length + payload.length);
        System.arraycopy(payload, 0, signed, head.length, payload.length);

        Signature s = Signature.getInstance("SHA256withECDSA");
        s.initSign(key);
        s.update(signed);
        byte[] sig = derToRaw(s.sign());

        byte[] all = Arrays.copyOf(payload, payload.length + sig.length);
        System.arraycopy(sig, 0, all, payload.length, sig.length);
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(all);
    }

    /** DER 서명 → r, s 각 32바이트 */
    static byte[] derToRaw(byte[] der) {
        int i = 2;
        if ((der[1] & 0x80) != 0) i += der[1] & 0x7f;
        byte[] out = new byte[64];
        for (int k = 0; k < 2; k++) {
            int len = der[i + 1];
            byte[] n = new BigInteger(1, Arrays.copyOfRange(der, i + 2, i + 2 + len)).toByteArray();
            int start = n.length > 32 ? n.length - 32 : 0;
            int count = n.length - start;
            System.arraycopy(n, start, out, k * 32 + 32 - count, count);
            i += 2 + len;
        }
        return out;
    }
}
