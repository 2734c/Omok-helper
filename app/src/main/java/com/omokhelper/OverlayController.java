package com.omokhelper;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * 다른 앱(게임) 화면 위에 띄우는 창들을 관리한다.
 *  - MarkerView : 추천 자리에 고리 모양 표시 (터치는 그대로 게임으로 전달됨)
 *  - 조작 패널   : 상태 표시 + 판 맞추기 / 멈춤 / 종료 버튼 (끌어서 옮길 수 있음)
 *  - 판 맞추기 화면 : 바둑판의 왼쪽 위·오른쪽 아래 교차점을 터치해서 위치를 저장
 */
public final class OverlayController {

    public interface Listener {
        void onCalibrationChanged();

        void onPauseChanged(boolean paused);

        void onQuit();
    }

    /** 화면에 그릴 추천 표시 하나 */
    public static final class Marker {
        final float x, y;      // 화면 좌표
        final int rank;        // 1부터
        final boolean winning; // 필승/승리 수

        public Marker(float x, float y, int rank, boolean winning) {
            this.x = x;
            this.y = y;
            this.rank = rank;
            this.winning = winning;
        }
    }

    private final Context ctx;
    private final WindowManager wm;
    private final Listener listener;

    private MarkerView markerView;
    private LinearLayout panel;
    private WindowManager.LayoutParams panelParams;
    private TextView statusText;
    private Button pauseButton;
    private LinearLayout panelBody;
    private LinearLayout settingsBox;
    private TextView title, fold;
    private GradientDrawable panelBg;
    private final List<TextView> themedTexts = new ArrayList<>();
    private final Button[] presetLoad = new Button[Prefs.PRESET_SLOTS];
    private final Button[] sizeButtons = new Button[3];
    private Button markerColorButton, panelColorButton;
    private FrameLayout calibLayer;

    private boolean paused = false;
    /** 패널이 차지하는 화면 영역 [left, top, right, bottom] — 캡처 스레드에서 읽는다 */
    private volatile int[] panelRect = null;

    public OverlayController(Context ctx, Listener listener) {
        this.ctx = ctx;
        this.listener = listener;
        this.wm = ctx.getSystemService(WindowManager.class);
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.getResources().getDisplayMetrics()));
    }

    private static int overlayType() {
        return WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
    }

    private static void allowCutout(WindowManager.LayoutParams lp) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
    }

    // ------------------------------------------------------------------ 표시 / 제거

    public void show() {
        // 1) 추천 표시 창 : 전체 화면, 터치 통과
        markerView = new MarkerView(ctx);
        WindowManager.LayoutParams mlp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        mlp.gravity = Gravity.TOP | Gravity.START;
        // 안드로이드 12부터는 불투명도 0.8 이하인 창만 터치를 아래 앱으로 넘겨준다
        mlp.alpha = 0.8f;
        allowCutout(mlp);
        wm.addView(markerView, mlp);

        // 2) 조작 패널
        buildPanel();
        applyLook();
        wm.addView(panel, panelParams);
        panel.post(this::updatePanelRect);
    }

    public void hide() {
        removeSafely(calibLayer);
        removeSafely(panel);
        removeSafely(markerView);
        calibLayer = null;
        panel = null;
        markerView = null;
    }

    private void removeSafely(View v) {
        if (v == null) return;
        try {
            wm.removeView(v);
        } catch (Exception ignored) {
        }
    }

    public int[] getPanelRect() {
        return panelRect;
    }

    // ------------------------------------------------------------------ 결과 표시

    public void showResult(List<Marker> markers, float cell, String status) {
        if (markerView == null) return;
        markerView.setMarkers(markers, cell);
        if (statusText != null) statusText.setText(status);
    }

    public void setStatus(String status) {
        if (statusText != null) statusText.setText(status);
    }

    // ------------------------------------------------------------------ 조작 패널

    @SuppressLint("ClickableViewAccessibility")
    private void buildPanel() {
        panel = new LinearLayout(ctx);
        panel.setOrientation(LinearLayout.VERTICAL);
        panelBg = new GradientDrawable();
        panelBg.setCornerRadius(dp(14));
        panel.setBackground(panelBg);
        panel.setPadding(dp(10), dp(6), dp(10), dp(8));

        // 제목줄 (끌어서 이동, 접기)
        LinearLayout titleRow = new LinearLayout(ctx);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        title = new TextView(ctx);
        title.setText("⠿ 오목 도우미");
        title.setTextSize(14);
        title.setPadding(0, dp(4), dp(12), dp(4));
        titleRow.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        fold = new TextView(ctx);
        fold.setText("—");
        fold.setTextSize(16);
        fold.setPadding(dp(12), dp(2), dp(4), dp(2));
        titleRow.addView(fold);
        panel.addView(titleRow);

        panelBody = new LinearLayout(ctx);
        panelBody.setOrientation(LinearLayout.VERTICAL);
        statusText = new TextView(ctx);
        themedTexts.add(statusText);
        statusText.setMaxWidth(dp(250));
        statusText.setText("화면 감지 준비 완료");
        panelBody.addView(statusText);

        LinearLayout buttons = new LinearLayout(ctx);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        Button calib = smallButton("판 맞추기");
        pauseButton = smallButton("멈춤");
        Button settings = smallButton("설정");
        Button quit = smallButton("종료");
        buttons.addView(calib);
        buttons.addView(pauseButton);
        buttons.addView(settings);
        buttons.addView(quit);
        panelBody.addView(buttons);
        settingsBox = buildSettings();
        settingsBox.setVisibility(View.GONE);
        panelBody.addView(settingsBox);
        panel.addView(panelBody);

        settings.setOnClickListener(v -> {
            boolean open = settingsBox.getVisibility() == View.VISIBLE;
            settingsBox.setVisibility(open ? View.GONE : View.VISIBLE);
            if (!open) refreshSettings();
            panel.post(this::updatePanelRect);
        });

        calib.setOnClickListener(v -> startCalibration());
        pauseButton.setOnClickListener(v -> {
            paused = !paused;
            pauseButton.setText(paused ? "재개" : "멈춤");
            if (paused && markerView != null) markerView.setMarkers(new ArrayList<>(), 0);
            setStatus(paused ? "일시정지됨" : "다시 감지하는 중…");
            listener.onPauseChanged(paused);
        });
        quit.setOnClickListener(v -> listener.onQuit());
        fold.setOnClickListener(v -> {
            boolean visible = panelBody.getVisibility() == View.VISIBLE;
            panelBody.setVisibility(visible ? View.GONE : View.VISIBLE);
            fold.setText(visible ? "+" : "—");
            panel.post(this::updatePanelRect);
        });

        panelParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        panelParams.gravity = Gravity.TOP | Gravity.START;
        panelParams.x = dp(8);
        panelParams.y = dp(40);

        // 제목줄을 끌어서 패널 이동
        final float[] start = new float[4];
        title.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    start[0] = e.getRawX();
                    start[1] = e.getRawY();
                    start[2] = panelParams.x;
                    start[3] = panelParams.y;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    panelParams.x = Math.round(start[2] + e.getRawX() - start[0]);
                    panelParams.y = Math.round(start[3] + e.getRawY() - start[1]);
                    wm.updateViewLayout(panel, panelParams);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    panel.post(this::updatePanelRect);
                    return true;
                default:
                    return false;
            }
        });
    }

    // ------------------------------------------------------------------ 패널 설정 (프리셋, 크기, 색)

    private TextView settingsLabel(String s) {
        TextView t = new TextView(ctx);
        t.setText(s);
        t.setTextSize(12);
        t.setPadding(0, dp(8), 0, 0);
        themedTexts.add(t);
        return t;
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(ctx);
        r.setOrientation(LinearLayout.HORIZONTAL);
        return r;
    }

    private LinearLayout buildSettings() {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);

        // 판 프리셋
        box.addView(settingsLabel("판 프리셋 — 번호를 누르면 불러오기"));
        LinearLayout loadRow = row();
        LinearLayout saveRow = row();
        for (int i = 0; i < Prefs.PRESET_SLOTS; i++) {
            final int slot = i + 1;
            presetLoad[i] = smallButton(String.valueOf(slot));
            presetLoad[i].setOnClickListener(v -> loadPreset(slot));
            loadRow.addView(presetLoad[i]);
            Button save = smallButton("저장 " + slot);
            save.setOnClickListener(v -> savePreset(slot));
            saveRow.addView(save);
        }
        box.addView(loadRow);
        box.addView(saveRow);

        // 표시 크기
        box.addView(settingsLabel("표시 크기"));
        LinearLayout sizeRow = row();
        String[] sizeNames = {"작게", "보통", "크게"};
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            sizeButtons[i] = smallButton(sizeNames[i]);
            sizeButtons[i].setOnClickListener(v -> {
                Prefs p = Prefs.load(ctx);
                p.displaySize = idx;
                p.save(ctx);
                applyLook();
                refreshSettings();
                panel.post(this::updatePanelRect);
            });
            sizeRow.addView(sizeButtons[i]);
        }
        box.addView(sizeRow);

        // 색 (누를 때마다 다음 색)
        box.addView(settingsLabel("색 — 누를 때마다 바뀜"));
        markerColorButton = smallButton("");
        markerColorButton.setOnClickListener(v -> {
            Prefs p = Prefs.load(ctx);
            p.markerTheme = (p.markerTheme + 1) % Themes.MARKER_THEMES.length;
            p.save(ctx);
            applyLook();
            refreshSettings();
        });
        panelColorButton = smallButton("");
        panelColorButton.setOnClickListener(v -> {
            Prefs p = Prefs.load(ctx);
            p.panelTheme = (p.panelTheme + 1) % Themes.PANEL_THEMES.length;
            p.save(ctx);
            applyLook();
            refreshSettings();
        });
        box.addView(markerColorButton);
        box.addView(panelColorButton);
        return box;
    }

    private void loadPreset(int slot) {
        Prefs p = Prefs.load(ctx);
        if (!p.loadPreset(ctx, slot)) {
            setStatus("프리셋 " + slot + "이(가) 비어 있어요.\n[판 맞추기] 후 [저장 " + slot + "]을 눌러 저장하세요.");
            return;
        }
        if (markerView != null) markerView.setMarkers(new ArrayList<>(), 0);
        listener.onCalibrationChanged();
        setStatus("프리셋 " + slot + " 불러옴 (" + p.boardSize + "×" + p.boardSize + ") — 감지 중…");
    }

    private void savePreset(int slot) {
        Prefs p = Prefs.load(ctx);
        if (!p.savePreset(ctx, slot)) {
            setStatus("먼저 [판 맞추기]로 바둑판 위치를 맞춰 주세요.");
            return;
        }
        refreshSettings();
        setStatus("지금 바둑판 위치(" + p.boardSize + "×" + p.boardSize + ")를 프리셋 " + slot + "에 저장했어요.");
    }

    /** 설정 버튼들의 글자를 현재 값에 맞게 갱신 */
    private void refreshSettings() {
        Prefs p = Prefs.load(ctx);
        for (int i = 0; i < Prefs.PRESET_SLOTS; i++) {
            String label = Prefs.presetLabel(ctx, i + 1);
            presetLoad[i].setText((i + 1) + (label == null ? " (비어 있음)" : " (" + label + ")"));
        }
        String[] sizeNames = {"작게", "보통", "크게"};
        for (int i = 0; i < 3; i++) sizeButtons[i].setText((i == p.displaySize ? "✓ " : "") + sizeNames[i]);

        // 추천 색 : 색 점 3개로 미리보기
        int[] mc = Themes.MARKER_THEMES[p.markerTheme % Themes.MARKER_THEMES.length];
        SpannableStringBuilder sb = new SpannableStringBuilder("추천 색  ");
        for (int i = 0; i < 3; i++) {
            int start = sb.length();
            sb.append("●");
            sb.setSpan(new ForegroundColorSpan(mc[i]), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        sb.append("  ").append(Themes.MARKER_THEME_NAMES[p.markerTheme % Themes.MARKER_THEMES.length]).append(" ▸");
        markerColorButton.setText(sb);
        panelColorButton.setText("패널 색  " + Themes.PANEL_THEME_NAMES[p.panelTheme % Themes.PANEL_THEMES.length] + " ▸");
    }

    /** 저장된 색·크기 설정을 패널과 추천 표시에 적용 */
    private void applyLook() {
        Prefs p = Prefs.load(ctx);
        int[] pc = Themes.PANEL_THEMES[p.panelTheme % Themes.PANEL_THEMES.length];
        if (panelBg != null) panelBg.setColor(pc[0]);
        float scale = p.sizeScale();
        if (title != null) {
            title.setTextColor(pc[2]);
            title.setTextSize(14 * scale);
        }
        if (fold != null) {
            fold.setTextColor(pc[1]);
            fold.setTextSize(16 * scale);
        }
        for (TextView t : themedTexts) t.setTextColor(pc[1]);
        if (statusText != null) {
            statusText.setTextSize(13 * scale);
            statusText.setMaxWidth(dp(250 * scale));
        }
        if (markerView != null) {
            markerView.colors = Themes.MARKER_THEMES[p.markerTheme % Themes.MARKER_THEMES.length];
            markerView.scale = scale;
            markerView.invalidate();
        }
    }

    private Button smallButton(String text) {
        Button b = new Button(ctx);
        b.setText(text);
        b.setTextSize(12);
        b.setAllCaps(false);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(10), dp(6), dp(10), dp(6));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(6), dp(6), 0);
        b.setLayoutParams(lp);
        return b;
    }

    private void updatePanelRect() {
        if (panel == null) return;
        int[] loc = new int[2];
        panel.getLocationOnScreen(loc);
        panelRect = new int[]{loc[0], loc[1], loc[0] + panel.getWidth(), loc[1] + panel.getHeight()};
    }

    // ------------------------------------------------------------------ 판 맞추기

    private void startCalibration() {
        if (calibLayer != null) return;
        Prefs prefs = Prefs.load(ctx);
        if (markerView != null) markerView.setMarkers(new ArrayList<>(), 0);
        if (panel != null) panel.setVisibility(View.GONE);

        calibLayer = new FrameLayout(ctx);
        calibLayer.setBackgroundColor(0x33000000);
        CalibView canvas = new CalibView(ctx, prefs.boardSize);
        if (prefs.calibrated) canvas.setPoints(prefs.x0, prefs.y0, prefs.x1, prefs.y1);
        calibLayer.addView(canvas, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        TextView guide = new TextView(ctx);
        guide.setTextColor(Color.WHITE);
        guide.setTextSize(14);
        guide.setBackgroundColor(0xCC000000);
        guide.setPadding(dp(12), dp(10), dp(12), dp(10));
        guide.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams glp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP);
        glp.topMargin = dp(36);
        calibLayer.addView(guide, glp);
        canvas.guide = guide;
        canvas.updateGuide();

        LinearLayout bar = new LinearLayout(ctx);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER);
        bar.setBackgroundColor(0xCC000000);
        bar.setPadding(dp(8), dp(4), dp(8), dp(4));
        Button retry = smallButton("처음부터");
        Button cancel = smallButton("취소");
        Button ok = smallButton("저장");
        bar.addView(retry);
        bar.addView(cancel);
        bar.addView(ok);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        blp.bottomMargin = dp(56);
        calibLayer.addView(bar, blp);

        retry.setOnClickListener(v -> canvas.reset());
        cancel.setOnClickListener(v -> endCalibration());
        ok.setOnClickListener(v -> {
            if (!canvas.isComplete()) {
                canvas.flashGuide();
                return;
            }
            Prefs p = Prefs.load(ctx);
            p.x0 = canvas.px[0];
            p.y0 = canvas.py[0];
            p.x1 = canvas.px[1];
            p.y1 = canvas.py[1];
            p.calibrated = true;
            p.save(ctx);
            endCalibration();
            listener.onCalibrationChanged();
        });

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        allowCutout(lp);
        wm.addView(calibLayer, lp);
    }

    private void endCalibration() {
        removeSafely(calibLayer);
        calibLayer = null;
        if (panel != null) {
            panel.setVisibility(View.VISIBLE);
            panel.post(this::updatePanelRect);
        }
    }

    /** 바둑판 모서리 두 점을 찍고, 격자 미리보기를 보여 주는 화면 */
    private final class CalibView extends View {
        final int n;
        final float[] px = new float[2], py = new float[2]; // 화면 좌표
        int stage = 0;          // 0: 첫 점 대기, 1: 둘째 점 대기, 2: 완료(미세 조정 가능)
        boolean touching = false;
        float tx, ty;           // 현재 손가락 위치 (화면 좌표)
        int dragging = -1;
        TextView guide;
        final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint cross = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        final int[] loc = new int[2];

        CalibView(Context c, int n) {
            super(c);
            this.n = n;
            line.setColor(0xFF00E5FF);
            line.setStrokeWidth(dp(1));
            cross.setColor(0xFFFF4081);
            cross.setStrokeWidth(dp(1.2f));
            dot.setColor(0xFFFF4081);
        }

        void setPoints(float x0, float y0, float x1, float y1) {
            px[0] = x0;
            py[0] = y0;
            px[1] = x1;
            py[1] = y1;
            stage = 2;
        }

        boolean isComplete() {
            return stage == 2;
        }

        void reset() {
            stage = 0;
            updateGuide();
            invalidate();
        }

        void updateGuide() {
            if (guide == null) return;
            if (stage == 0) guide.setText("① 바둑판 맨 왼쪽 위 교차점(선이 만나는 점)을 누른 채로 맞추고 손을 떼세요.\n분홍 십자선이 교차점에 딱 맞으면 됩니다.");
            else if (stage == 1) guide.setText("② 이번엔 맨 오른쪽 아래 교차점을 맞추고 손을 떼세요.");
            else guide.setText("③ 하늘색 격자가 바둑판 선과 겹치는지 확인하세요.\n어긋나면 모서리 점을 끌어서 조정하고 [저장]을 누르세요.");
        }

        void flashGuide() {
            if (guide != null) guide.setBackgroundColor(0xCCD32F2F);
            postDelayed(() -> { if (guide != null) guide.setBackgroundColor(0xCC000000); }, 600);
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouchEvent(MotionEvent e) {
            tx = e.getRawX();
            ty = e.getRawY();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    touching = true;
                    if (stage == 2) {
                        float d0 = dist(tx, ty, px[0], py[0]), d1 = dist(tx, ty, px[1], py[1]);
                        dragging = d0 <= d1 ? 0 : 1;
                    }
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (stage == 2 && dragging >= 0) {
                        px[dragging] = tx;
                        py[dragging] = ty;
                    }
                    break;
                case MotionEvent.ACTION_UP:
                    touching = false;
                    if (stage < 2) {
                        px[stage] = tx;
                        py[stage] = ty;
                        stage++;
                        if (stage == 2 && (Math.abs(px[1] - px[0]) < dp(40) || Math.abs(py[1] - py[0]) < dp(40))) {
                            stage = 1; // 두 점이 너무 가까우면 다시
                        }
                        updateGuide();
                    }
                    dragging = -1;
                    break;
                case MotionEvent.ACTION_CANCEL:
                    touching = false;
                    dragging = -1;
                    break;
                default:
                    break;
            }
            invalidate();
            return true;
        }

        private float dist(float ax, float ay, float bx, float by) {
            return (float) Math.hypot(ax - bx, ay - by);
        }

        @Override
        protected void onDraw(Canvas c) {
            getLocationOnScreen(loc);
            float ox = loc[0], oy = loc[1];
            if (stage == 2) {
                float cw = (px[1] - px[0]) / (n - 1), ch = (py[1] - py[0]) / (n - 1);
                for (int i = 0; i < n; i++) {
                    c.drawLine(px[0] + i * cw - ox, py[0] - oy, px[0] + i * cw - ox, py[1] - oy, line);
                    c.drawLine(px[0] - ox, py[0] + i * ch - oy, px[1] - ox, py[0] + i * ch - oy, line);
                }
                c.drawCircle(px[0] - ox, py[0] - oy, dp(7), dot);
                c.drawCircle(px[1] - ox, py[1] - oy, dp(7), dot);
            } else if (stage == 1) {
                c.drawCircle(px[0] - ox, py[0] - oy, dp(6), dot);
            }
            if (touching) {
                // 손가락에 가려지지 않도록 화면 끝까지 이어지는 십자선
                c.drawLine(0, ty - oy, getWidth(), ty - oy, cross);
                c.drawLine(tx - ox, 0, tx - ox, getHeight(), cross);
            }
        }
    }

    // ------------------------------------------------------------------ 추천 표시 뷰

    /**
     * 추천 자리에 고리를 그린다.
     * 주의 : 이 창도 화면 캡처에 같이 찍히므로, 돌 인식에 쓰는 교차점 한가운데(반지름 0.26칸)는 비워 두고
     *        바깥쪽 고리와 모서리 숫자만 그린다.
     */
    private static boolean isLight(int c) {
        int r = (c >> 16) & 0xff, g = (c >> 8) & 0xff, b = c & 0xff;
        return 0.299 * r + 0.587 * g + 0.114 * b > 160;
    }

    private static final class MarkerView extends View {
        private List<Marker> markers = new ArrayList<>();
        private float cell;
        int[] colors = Themes.MARKER_THEMES[0];
        float scale = 1f; // 0.85 ~ 1.25 : 이 범위에서는 돌 인식 영역(반지름 0.26칸)을 가리지 않는다
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textBg = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int[] loc = new int[2];

        MarkerView(Context c) {
            super(c);
            ring.setStyle(Paint.Style.STROKE);
            text.setColor(Color.WHITE);
            text.setTextAlign(Paint.Align.CENTER);
            text.setFakeBoldText(true);
        }

        void setMarkers(List<Marker> m, float cell) {
            this.markers = m;
            this.cell = cell;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            if (markers.isEmpty() || cell <= 0) return;
            getLocationOnScreen(loc);
            for (int i = markers.size() - 1; i >= 0; i--) {
                Marker m = markers.get(i);
                float x = m.x - loc[0], y = m.y - loc[1];
                int color = m.winning ? colors[3] : (m.rank == 1 ? colors[0] : (m.rank == 2 ? colors[1] : colors[2]));
                ring.setColor(color);
                ring.setStrokeWidth(Math.max(3f, cell * scale * (m.rank == 1 ? 0.12f : 0.08f)));
                c.drawCircle(x, y, cell * scale * 0.42f, ring);

                // 순위 숫자 (오른쪽 위 바깥)
                float r = cell * scale * 0.2f;
                float bx = x + cell * scale * 0.38f, by = y - cell * scale * 0.38f;
                textBg.setColor(color);
                c.drawCircle(bx, by, r, textBg);
                text.setTextSize(r * 1.4f);
                text.setColor(isLight(color) ? Color.BLACK : Color.WHITE);
                c.drawText(String.valueOf(m.rank), bx, by + r * 0.5f, text);
            }
        }
    }
}
