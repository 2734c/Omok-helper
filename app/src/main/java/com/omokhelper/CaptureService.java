package com.omokhelper;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 화면 감지 서비스.
 *  1) MediaProjection 으로 화면을 0.6초마다 캡처한다.
 *  2) BoardDetector 로 바둑판의 돌을 읽는다.
 *  3) 판이 바뀌었으면 OmokEngine 으로 추천 수를 계산한다.
 *  4) OverlayController 로 게임 화면 위에 추천 자리를 표시한다.
 */
public class CaptureService extends Service implements OverlayController.Listener {

    public static final String ACTION_START = "com.omokhelper.START";
    public static final String ACTION_STOP = "com.omokhelper.STOP";
    public static final String EXTRA_CODE = "code";
    public static final String EXTRA_DATA = "data";

    private static final String TAG = "OmokHelper";
    private static final String CHANNEL = "capture";
    private static final long INTERVAL_MS = 600;

    public static volatile boolean running = false;

    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader reader;
    private HandlerThread workerThread;
    private Handler worker;
    private final Handler main = new Handler(Looper.getMainLooper());
    private OverlayController overlay;

    private int screenW, screenH, dpi;
    private volatile boolean paused = false;
    private volatile boolean forceUpdate = true;

    // 같은 판이 두 번 연속으로 읽혀야 확정 (돌이 놓이는 애니메이션 중간을 무시)
    private int[] pending;
    private int stableCount;
    private String lastKey = "";
    private String lastOpening;
    private volatile boolean panelCovers;
    private boolean countMismatch;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (projection != null) return START_NOT_STICKY; // 이미 실행 중

        startForegroundNow();

        int code = intent.getIntExtra(EXTRA_CODE, 0);
        Intent data = Build.VERSION.SDK_INT >= 33
                ? intent.getParcelableExtra(EXTRA_DATA, Intent.class)
                : intent.getParcelableExtra(EXTRA_DATA);
        if (data == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        try {
            startCapture(code, data);
        } catch (Exception e) {
            Log.e(TAG, "capture start failed", e);
            stopSelf();
            return START_NOT_STICKY;
        }

        overlay = new OverlayController(this, this);
        overlay.show();
        if (!Prefs.load(this).calibrated) {
            overlay.setStatus("먼저 [판 맞추기]를 눌러 바둑판 위치를 알려 주세요.");
        }
        running = true;
        worker.postDelayed(tick, INTERVAL_MS);
        return START_NOT_STICKY;
    }

    // ------------------------------------------------------------------ 알림 + 포그라운드

    private void startForegroundNow() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "화면 감지", NotificationManager.IMPORTANCE_LOW));

        Intent stop = new Intent(this, CaptureService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stop, PendingIntent.FLAG_IMMUTABLE);
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent openPi = PendingIntent.getActivity(this, 2, open, PendingIntent.FLAG_IMMUTABLE);

        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("오목 도우미")
                .setContentText("화면을 감지하는 중입니다")
                .setContentIntent(openPi)
                .addAction(new Notification.Action.Builder(null, "중지", stopPi).build())
                .setOngoing(true)
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(1, n);
        }
    }

    // ------------------------------------------------------------------ 화면 캡처

    private void startCapture(int code, Intent data) {
        WindowManager wm = getSystemService(WindowManager.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Rect b = wm.getMaximumWindowMetrics().getBounds();
            screenW = b.width();
            screenH = b.height();
        } else {
            DisplayMetrics dm = new DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(dm);
            screenW = dm.widthPixels;
            screenH = dm.heightPixels;
        }
        dpi = getResources().getDisplayMetrics().densityDpi;

        workerThread = new HandlerThread("omok-capture");
        workerThread.start();
        worker = new Handler(workerThread.getLooper());

        MediaProjectionManager mpm = getSystemService(MediaProjectionManager.class);
        projection = mpm.getMediaProjection(code, data);
        projection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                main.post(CaptureService.this::stopSelf);
            }
        }, main);

        reader = ImageReader.newInstance(screenW, screenH, PixelFormat.RGBA_8888, 2);
        virtualDisplay = projection.createVirtualDisplay("omok-helper", screenW, screenH, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, worker);
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            Image img = null;
            try {
                img = reader.acquireLatestImage();
                if (img != null && !paused) process(img);
            } catch (Exception e) {
                Log.e(TAG, "frame failed", e);
            } finally {
                if (img != null) img.close();
            }
            if (running) worker.postDelayed(this, INTERVAL_MS);
        }
    };

    /** Image(RGBA_8888)를 BoardDetector가 읽을 수 있게 감싼다 */
    private static final class ImageSource implements BoardDetector.PixelSource {
        final ByteBuffer buf;
        final int w, h, rowStride, pixelStride;

        ImageSource(Image img) {
            Image.Plane p = img.getPlanes()[0];
            buf = p.getBuffer();
            rowStride = p.getRowStride();
            pixelStride = p.getPixelStride();
            w = img.getWidth();
            h = img.getHeight();
        }

        @Override public int width() { return w; }
        @Override public int height() { return h; }

        @Override
        public int rgb(int x, int y) {
            int i = y * rowStride + x * pixelStride;
            int r = buf.get(i) & 0xff, g = buf.get(i + 1) & 0xff, b = buf.get(i + 2) & 0xff;
            return (r << 16) | (g << 8) | b;
        }
    }

    private void process(Image img) {
        Prefs p = Prefs.load(this);
        if (!p.calibrated || overlay.isCalibrating()) return;
        int n = p.boardSize;

        // 화면 좌표 -> 캡처 이미지 좌표 (보통 1:1)
        ImageSource src = new ImageSource(img);
        float sx = src.w / (float) screenW, sy = src.h / (float) screenH;
        int[] pr = overlay.getPanelRect();
        int[] skip = pr == null ? null : new int[]{
                Math.round(pr[0] * sx), Math.round(pr[1] * sy), Math.round(pr[2] * sx), Math.round(pr[3] * sy)};
        // 이 앱이 그린 표시(추천 고리 등)는 게임 화면이 아니므로 인식에서 뺀다
        float[] ms = overlay.getMasks();
        float[] masks = null;
        if (ms != null) {
            float sr = (sx + sy) / 2;
            masks = new float[ms.length];
            for (int i = 0; i + 3 < ms.length; i += 4) {
                masks[i] = ms[i] * sx;
                masks[i + 1] = ms[i + 1] * sy;
                masks[i + 2] = ms[i + 2] * sr;
                masks[i + 3] = ms[i + 3] * sr;
            }
        }
        int[] board = BoardDetector.detect(src, p.x0 * sx, p.y0 * sy, p.x1 * sx, p.y1 * sy, n, skip, masks);
        boolean covered = false;
        for (int v : board) covered |= v == BoardDetector.UNKNOWN;
        panelCovers = covered && overlay.panelCoversBoard();

        // 패널에 가려 읽지 못한 칸은 이전 값을 사용
        for (int i = 0; i < board.length; i++) {
            if (board[i] == BoardDetector.UNKNOWN) {
                board[i] = (pending != null && pending.length == board.length && pending[i] != BoardDetector.UNKNOWN)
                        ? pending[i] : OmokEngine.EMPTY;
            }
        }

        if (pending != null && Arrays.equals(board, pending)) stableCount++;
        else {
            pending = board;
            stableCount = 1;
        }
        if (stableCount < 2) return;

        String key = Arrays.toString(board) + p.key();
        if (!forceUpdate && key.equals(lastKey)) return;
        lastKey = key;
        forceUpdate = false;

        analyzeAndShow(board, p);
    }

    private void analyzeAndShow(int[] board, Prefs p) {
        int n = p.boardSize;
        OmokEngine engine = new OmokEngine(n, p.rules);
        engine.load(board);
        int blacks = engine.count(OmokEngine.BLACK), whites = engine.count(OmokEngine.WHITE);
        // 흑이 먼저 두므로 보통 흑 = 백 또는 흑 = 백 + 1. 아니면 인식이 틀렸을 가능성이 크다
        countMismatch = blacks - whites < 0 || blacks - whites > 1;
        int side = engine.sideToMove();
        String counts = "흑 " + blacks + " · 백 " + whites + " · " + OmokEngine.ruleName(p.rules);

        // 초반 주형 : 돌이 3개일 때 이름을 알아내 그 판이 끝날 때까지 보여 준다
        int total = blacks + whites;
        if (total < 3) lastOpening = null;
        else if (total == 3) {
            Openings.Match om = Openings.identify(engine);
            lastOpening = om == null ? null : om.opening.title() + " " + om.opening.code
                    + (om.opening.verdict != null ? " · " + om.opening.verdict : "");
        }
        if (lastOpening != null) counts += "\n📖 주형: " + lastOpening;

        float cw = (p.x1 - p.x0) / (n - 1), ch = (p.y1 - p.y0) / (n - 1);
        float cell = Math.min(Math.abs(cw), Math.abs(ch));

        // 금수 자리 (규칙에 금수가 있는 쪽만)
        List<OverlayController.Dot> dots = new ArrayList<>();
        StringBuilder forbidden = new StringBuilder();
        int forbiddenCount = 0;
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                int v = board[y * n + x];
                float sx = p.x0 + x * cw, sy = p.y0 + y * ch;
                if (v != OmokEngine.EMPTY) {
                    if (p.showDetection) dots.add(new OverlayController.Dot(sx, sy, v));
                    continue;
                }
                for (int color = OmokEngine.BLACK; color <= OmokEngine.WHITE; color++) {
                    if (!engine.hasBans(color) || !engine.isForbidden(x, y, color)) continue;
                    if (p.showDetection) dots.add(new OverlayController.Dot(sx, sy, color == OmokEngine.BLACK ? 3 : 4));
                    if (color == side) {
                        if (forbiddenCount < 5) forbidden.append(forbiddenCount == 0 ? "" : ", ").append(engine.coord(x, y));
                        forbiddenCount++;
                    }
                }
            }
        }
        String forbiddenLine = forbiddenCount == 0 ? ""
                : "\n🚫 " + colorName(side) + " 금수 " + forbiddenCount + "곳: " + forbidden + (forbiddenCount > 5 ? " …" : "");

        if (p.myColor != 0 && side != p.myColor) {
            String s = "상대(" + colorName(side) + ") 차례 — 기다리는 중\n" + counts + coverWarning();
            main.post(() -> overlay.showResult(new ArrayList<>(), dots, cell, s, side));
            return;
        }

        OmokEngine.Result r = engine.recommendDeep(side, p.topN, p.thinkMs());
        List<OverlayController.Marker> markers = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        sb.append(colorName(side)).append(" 차례 추천 (").append(counts).append(")").append(forbiddenLine).append('\n');
        for (int i = 0; i < r.moves.size(); i++) {
            OmokEngine.Move m = r.moves.get(i);
            boolean win = m.reason.startsWith("5목") || m.reason.startsWith("필승") || m.reason.startsWith("금수 유도");
            markers.add(new OverlayController.Marker(p.x0 + m.x * cw, p.y0 + m.y * ch, i + 1, win));
            if (i < 3) sb.append(i + 1).append(". ").append(engine.coord(m.x, m.y)).append("  ").append(m.reason).append('\n');
        }
        if (r.winSequence != null) {
            sb.append(r.vct ? "★ 필승 수순(VCT): " : "★ 필승 수순(VCF): ");
            for (int i = 0; i < r.winSequence.size() && i < 9; i += 2) {
                int[] c = r.winSequence.get(i);
                if (i > 0) sb.append(" → ");
                sb.append(engine.coord(c[0], c[1]));
            }
        } else {
            if (r.pv != null && r.pv.size() > 1) {
                sb.append("예상 수순: ");
                for (int i = 0; i < r.pv.size() && i < 7; i++) {
                    int[] c = r.pv.get(i);
                    if (i > 0) sb.append(" → ");
                    sb.append(engine.coord(c[0], c[1]));
                }
                sb.append('\n');
            }
            if (r.searchDepth > 0) {
                sb.append(r.searchDepth).append("수 앞까지 탐색 (").append(r.searchNodes).append("개 판 검토)\n");
            }
            if (r.threatSequence != null) sb.append("⚠ 상대에게 연속 4 필승 수순이 있어요!");
        }
        String status = sb.toString().trim() + coverWarning();
        main.post(() -> overlay.showResult(markers, dots, cell, status, side));
    }

    private String coverWarning() {
        String w = panelCovers ? "\n⚠ 패널이 바둑판을 가려 일부 칸을 못 읽어요. 제목줄을 끌어 바둑판 밖으로 옮겨 주세요." : "";
        if (countMismatch) w += "\n⚠ 흑·백 돌 개수가 맞지 않아요. 판 맞추기나 [설정] → 인식 결과 보기로 확인해 주세요.";
        return w;
    }

    private static String colorName(int c) {
        return c == OmokEngine.BLACK ? "흑" : "백";
    }

    // ------------------------------------------------------------------ 패널 버튼

    @Override
    public void onCalibrationChanged() {
        pending = null;
        stableCount = 0;
        forceUpdate = true;
        overlay.setStatus("바둑판 위치 저장됨 — 감지 중…");
    }

    @Override
    public void onSettingsChanged() {
        forceUpdate = true;
    }

    @Override
    public void onPauseChanged(boolean paused) {
        this.paused = paused;
        forceUpdate = true;
    }

    @Override
    public void onQuit() {
        stopSelf();
    }

    // ------------------------------------------------------------------ 종료

    @Override
    public void onDestroy() {
        running = false;
        if (overlay != null) overlay.hide();
        final VirtualDisplay vd = virtualDisplay;
        final ImageReader ir = reader;
        if (worker != null) {
            // 캡처 스레드가 이미지를 쓰는 중일 수 있으므로 그 스레드에서 정리한다
            worker.removeCallbacks(tick);
            worker.post(() -> {
                if (vd != null) vd.release();
                if (ir != null) ir.close();
            });
        }
        if (projection != null) {
            try {
                projection.stop();
            } catch (Exception ignored) {
            }
        }
        if (workerThread != null) workerThread.quitSafely();
        projection = null;
        super.onDestroy();
    }

    static void stop(Context c) {
        c.startService(new Intent(c, CaptureService.class).setAction(ACTION_STOP));
    }
}
