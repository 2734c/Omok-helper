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
 *   java KeyGen.java make 7                 7번 키, 무기한
 *   java KeyGen.java make 8 2027-03-01      8번 키, 2027-03-01까지 사용 가능
 *   java KeyGen.java make 10 20             10번부터 20번까지 여러 개 (무기한)
 *
 * 비밀 키 파일 omok-private.key 가 같은 폴더에 있어야 합니다.
 * ⚠ 비밀 키 파일은 절대 GitHub에 올리거나 다른 사람에게 주지 마세요. (누구나 키를 만들 수 있게 됩니다)
 *   이 저장소의 .gitignore 가 *.key 파일을 올리지 않도록 막고 있습니다.
 *
 *   java KeyGen.java init                   새 비밀 키/공개 키 만들기 (처음 한 번만)
 *     → 공개 키가 바뀌면 앱의 LicenseKey.PUBLIC_KEY 도 바꿔야 하고, 예전 키는 모두 쓸 수 없게 됩니다.
 */
public class KeyGen {

    static final String PREFIX = "OMOK1-";
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
            case "make":
                make(args);
                break;
            default:
                usage();
        }
    }

    static void usage() {
        System.out.println("사용법:");
        System.out.println("  java KeyGen.java make <번호> [만료일 YYYY-MM-DD]");
        System.out.println("  java KeyGen.java make <시작번호> <끝번호>");
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

    static void make(String[] args) throws Exception {
        if (args.length < 2) {
            usage();
            return;
        }
        PrivateKey key = KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(
                Base64.getDecoder().decode(new String(Files.readAllBytes(PRIVATE), StandardCharsets.US_ASCII).trim())));

        int from = Integer.parseInt(args[1]);
        int to = from;
        int expiry = 0;
        if (args.length >= 3) {
            if (args[2].contains("-")) {
                expiry = (int) LocalDate.parse(args[2]).toEpochDay();
            } else {
                to = Integer.parseInt(args[2]);
            }
        }
        if (from < 1 || to > 65535 || to < from) throw new IllegalArgumentException("번호는 1~65535");
        for (int id = from; id <= to; id++) {
            System.out.println("#" + id + (expiry == 0 ? " (무기한)" : " (" + LocalDate.ofEpochDay(expiry) + "까지)")
                    + "  " + makeKey(key, id, expiry));
        }
    }

    static String makeKey(PrivateKey key, int id, int expiry) throws Exception {
        byte[] payload = {1, (byte) (id >> 8), (byte) id, (byte) (expiry >> 8), (byte) expiry};
        byte[] head = "OMOK1".getBytes(StandardCharsets.US_ASCII);
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
