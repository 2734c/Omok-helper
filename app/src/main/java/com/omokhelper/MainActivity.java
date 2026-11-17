package com.omokhelper;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.text.InputType;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** 첫 화면 : 설정을 고르고 화면 감지를 시작/중지한다. (레이아웃 XML 없이 코드로 화면을 만든다) */
public class MainActivity extends Activity {

    private static final int REQ_CAPTURE = 100;

    private Prefs prefs;
    private TextView overlayState;
    private Button startButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    /** 화면을 만든다. 패널에서 프리셋을 불러와 설정이 바뀌었을 수 있으므로 돌아올 때마다 새로 만든다. */
    private void buildUi() {
        prefs = Prefs.load(this);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFFF6F1E7);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(28), dp(20), dp(28));
        scroll.addView(root);

        TextView title = text("오목 도우미", 26, 0xFF2B2118, true);
        root.addView(title);
        root.addView(text("게임 화면의 바둑판을 감지해서 다음에 둘 좋은 자리를 표시해 줍니다.", 14, 0xFF6B5B4B, false));

        // ---------------- 키
        boolean pro = License.isPro(this);
        root.addView(section("🔑 키"));
        LinearLayout keyCard = card();
        root.addView(keyCard);
        buildKeyCard(keyCard, pro);

        // ---------------- 설정
        root.addView(section("설정"));
        LinearLayout card = card();
        root.addView(card);

        card.addView(label("바둑판 크기"));
        card.addView(radios(new String[]{"15 × 15", "19 × 19"}, prefs.boardSize == 19 ? 1 : 0,
                i -> prefs.boardSize = i == 1 ? 19 : 15));

        card.addView(label("규칙"));
        buildRules(card);

        card.addView(label("추천 받을 돌"));
        card.addView(radios(new String[]{"자동 (차례인 쪽)", "흑", "백"}, prefs.myColor,
                i -> prefs.myColor = i, pro ? null : new boolean[]{false, true, true}));

        card.addView(label("추천 개수"));
        int topIdx = prefs.topN == 1 ? 0 : (prefs.topN == 5 ? 2 : 1);
        card.addView(radios(new String[]{"1개", "3개", "5개"}, topIdx,
                i -> prefs.topN = i == 0 ? 1 : (i == 2 ? 5 : 3), pro ? null : new boolean[]{false, false, true}));

        card.addView(label("AI 생각 시간 (길수록 더 깊게 읽음)"));
        card.addView(radios(new String[]{"0.5초", "1.5초", "3초"}, prefs.thinkLevel,
                i -> prefs.thinkLevel = i, pro ? null : new boolean[]{false, false, true}));
        if (!pro) card.addView(text("🔒 표시된 기능은 키를 등록하면 쓸 수 있어요.", 13, 0xFF8D6E63, false));

        // ---------------- 시작
        root.addView(section("시작하기"));
        LinearLayout card2 = card();
        root.addView(card2);

        overlayState = text("", 14, 0xFF2B2118, false);
        card2.addView(overlayState);
        Button perm = button("① 다른 앱 위에 표시 권한 허용", 0xFF8D6E63);
        perm.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()))));
        card2.addView(perm);

        startButton = button("② 화면 감지 시작", 0xFF2E7D32);
        startButton.setOnClickListener(v -> startDetection());
        card2.addView(startButton);

        Button stop = button("화면 감지 중지", 0xFFB71C1C);
        stop.setOnClickListener(v -> {
            CaptureService.stop(this);
            Toast.makeText(this, "중지했습니다", Toast.LENGTH_SHORT).show();
        });
        card2.addView(stop);

        // ---------------- 오목 기술
        root.addView(section("📖 오목 기술·공식"));
        LinearLayout guideCard = card();
        root.addView(guideCard);
        guideCard.addView(text("4-3, 3-3, VCF, VCT, 금수 유도, 26주형 등 이 앱이 쓰는 기술을 설명합니다.", 14, 0xFF3E3329, false));
        Button guide = button("기술·공식 보기", 0xFF6D4C41);
        guide.setOnClickListener(v -> showGuide());
        guideCard.addView(guide);

        // ---------------- 사용 방법
        root.addView(section("사용 방법"));
        LinearLayout card3 = card();
        root.addView(card3);
        card3.addView(text(
                "1. 위에서 판 크기와 규칙을 게임과 똑같이 맞춥니다.\n"
                        + "2. [다른 앱 위에 표시] 권한을 허용합니다.\n"
                        + "3. [화면 감지 시작] → 화면 녹화 허용(전체 화면 권장).\n"
                        + "4. 오목 게임을 열고, 떠 있는 패널의 [판 맞추기]를 누릅니다.\n"
                        + "5. 바둑판의 맨 왼쪽 위 교차점, 맨 오른쪽 아래 교차점을 차례로 맞추고 [저장].\n"
                        + "6. 이제 돌이 놓일 때마다 추천 자리에 고리가 표시됩니다.\n"
                        + "   (기본 색) 빨강 = 1순위, 주황 = 2순위, 파랑 = 3순위 이하\n\n"
                        + "• 게임 중에 패널 제목줄의 [나: 자동 ⇄]를 누르면 자동 → 흑 → 백으로 바뀝니다.\n"
                        + "• 패널의 [설정]에서 바꿀 수 있는 것\n"
                        + "  - 내 돌: 자동 / 흑 / 백\n"
                        + "  - 판 프리셋: [저장 1~3]으로 지금 바둑판 위치·크기를 저장하고,\n"
                        + "    [1~3]을 누르면 바로 불러옵니다. (게임마다, 화면 배치마다 저장)\n"
                        + "  - 표시 크기: 작게 / 보통 / 크게\n"
                        + "  - 추천 색, 패널 색: 누를 때마다 다음 색으로 바뀝니다.\n"
                        + "• 세로 화면 기준입니다. 화면을 돌리면 [판 맞추기]를 다시 하세요.\n"
                        + "• 패널은 제목줄을 끌어서 바둑판 밖으로 옮겨 두세요.\n"
                        + "• 연습·AI 대전·모의 테스트용입니다. 사람과 하는 온라인 대국에서 사용하면\n"
                        + "  상대에게 불공정하고 게임 계정이 제재될 수 있어요.",
                14, 0xFF3E3329, false));

        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        buildUi();
        boolean ok = Settings.canDrawOverlays(this);
        overlayState.setText(ok ? "✓ 다른 앱 위에 표시 권한: 허용됨" : "✗ 다른 앱 위에 표시 권한: 필요함");
        overlayState.setTextColor(ok ? 0xFF2E7D32 : 0xFFB71C1C);
        startButton.setText(CaptureService.running ? "② 화면 감지 중 (다시 시작)" : "② 화면 감지 시작");
    }

    private void startDetection() {
        prefs.saveSettings(this);
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "먼저 '다른 앱 위에 표시' 권한을 허용해 주세요", Toast.LENGTH_LONG).show();
            return;
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
        if (CaptureService.running) CaptureService.stop(this);
        MediaProjectionManager mpm = getSystemService(MediaProjectionManager.class);
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_CAPTURE) return;
        if (resultCode != RESULT_OK || data == null) {
            Toast.makeText(this, "화면 녹화가 허용되지 않았습니다", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent svc = new Intent(this, CaptureService.class)
                .setAction(CaptureService.ACTION_START)
                .putExtra(CaptureService.EXTRA_CODE, resultCode)
                .putExtra(CaptureService.EXTRA_DATA, data);
        startForegroundService(svc);
        Toast.makeText(this, "시작했습니다. 오목 게임을 열어 주세요", Toast.LENGTH_LONG).show();
        moveTaskToBack(true);
    }

    // ------------------------------------------------------------------ 화면 구성 도우미

    private interface OnPick {
        void pick(int index);
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private TextView text(String s, float size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setLineSpacing(0, 1.15f);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(4), 0, dp(4));
        return t;
    }

    private TextView section(String s) {
        TextView t = text(s, 17, 0xFF8D6E63, true);
        t.setPadding(0, dp(20), 0, dp(8));
        return t;
    }

    private TextView label(String s) {
        TextView t = text(s, 14, 0xFF2B2118, true);
        t.setPadding(0, dp(10), 0, 0);
        return t;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(10), dp(16), dp(14));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(dp(14));
        c.setBackground(bg);
        return c;
    }

    private RadioGroup radios(String[] labels, int checked, OnPick onPick) {
        return radios(labels, checked, onPick, null);
    }

    /** locked[i]가 true인 항목은 키가 없어서 고를 수 없음 (🔒 표시) */
    private RadioGroup radios(String[] labels, int checked, OnPick onPick, boolean[] locked) {
        RadioGroup g = new RadioGroup(this);
        g.setOrientation(labels.length <= 3 && labels[labels.length - 1].length() < 12
                ? RadioGroup.HORIZONTAL : RadioGroup.VERTICAL);
        for (int i = 0; i < labels.length; i++) {
            RadioButton b = new RadioButton(this);
            b.setId(View.generateViewId());
            boolean lock = locked != null && locked[i];
            b.setText(lock ? labels[i] + " 🔒" : labels[i]);
            b.setEnabled(!lock);
            b.setTextSize(14);
            b.setPadding(0, 0, dp(12), 0);
            final int idx = i;
            b.setOnCheckedChangeListener((btn, isChecked) -> {
                if (isChecked) {
                    onPick.pick(idx);
                    prefs.saveSettings(this);
                }
            });
            g.addView(b);
            if (i == checked) b.setChecked(true);
        }
        return g;
    }

    private void showGuide() {
        ScrollView sv = new ScrollView(this);
        TextView t = text(Guide.text(), 14, 0xFF2B2118, false);
        t.setPadding(dp(20), dp(12), dp(20), dp(12));
        t.setTextIsSelectable(true);
        sv.addView(t);
        new AlertDialog.Builder(this)
                .setTitle("오목 기술·공식")
                .setView(sv)
                .setPositiveButton("닫기", null)
                .show();
    }

    // ------------------------------------------------------------------ 규칙

    private void buildRules(LinearLayout card) {
        TextView current = text("", 13, 0xFF6B5B4B, false);
        Runnable updateName = () -> current.setText("현재 규칙: " + OmokEngine.ruleName(prefs.rules)
                + "  — 게임의 규칙과 똑같이 맞춰 주세요");
        updateName.run();
        card.addView(current);

        // 한 번에 맞추기
        LinearLayout presets = new LinearLayout(this);
        presets.setOrientation(LinearLayout.HORIZONTAL);
        String[] names = {"자유룰", "렌주룰"};
        int[] values = {OmokEngine.RULE_FREE, OmokEngine.RULE_RENJU};
        for (int i = 0; i < 2; i++) {
            final int v = values[i];
            Button b = button(names[i], prefs.rules == v ? 0xFF5D4037 : 0xFFBCAAA4);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(40), 1);
            lp.setMargins(i == 0 ? 0 : dp(6), dp(6), i == 0 ? dp(6) : 0, dp(4));
            b.setLayoutParams(lp);
            b.setTextSize(14);
            b.setOnClickListener(x -> {
                prefs.rules = v;
                prefs.saveSettings(this);
                buildUi();
            });
            presets.addView(b);
        }
        card.addView(presets);

        // 항목별 설정
        String[] labels = {"흑 6목 이상(장목) 금지", "흑 3-3 금지", "흑 4-4 금지",
                "백 6목 이상(장목) 금지", "백 3-3 금지", "백 4-4 금지"};
        int[] flags = {OmokEngine.B_OVERLINE, OmokEngine.B_33, OmokEngine.B_44,
                OmokEngine.W_OVERLINE, OmokEngine.W_33, OmokEngine.W_44};
        for (int i = 0; i < labels.length; i++) {
            final int f = flags[i];
            CheckBox cb = new CheckBox(this);
            cb.setText(labels[i]);
            cb.setTextSize(14);
            cb.setChecked((prefs.rules & f) != 0);
            cb.setOnCheckedChangeListener((btn, on) -> {
                prefs.rules = on ? (prefs.rules | f) : (prefs.rules & ~f);
                prefs.saveSettings(this);
                updateName.run();
            });
            card.addView(cb);
        }
        card.addView(text("※ 금지된 자리는 추천하지 않고, 상대가 그 자리로 이기는 것도 걱정하지 않습니다.\n"
                + "※ 6목 이상 금지를 끄면 6목 이상도 승리로 계산합니다.", 12, 0xFF8D6E63, false));
    }

    // ------------------------------------------------------------------ 키 입력

    private void buildKeyCard(LinearLayout keyCard, boolean pro) {
        LicenseKey k = License.current(this);
        if (pro && k != null) {
            TextView ok = text("✓ 키 등록됨 (" + License.describe(k) + ")\n모든 기능을 쓸 수 있어요.", 14, 0xFF2E7D32, true);
            keyCard.addView(ok);
            Button remove = button("키 삭제", 0xFF9E9E9E);
            remove.setOnClickListener(v -> {
                License.clear(this);
                Toast.makeText(this, "키를 삭제했습니다", Toast.LENGTH_SHORT).show();
                buildUi();
            });
            keyCard.addView(remove);
            return;
        }
        keyCard.addView(text("키가 없으면 흑/백 선택, 추천 5개, 생각 시간 3초를 쓸 수 없어요.\n"
                + "받은 키(OMOK1-로 시작)를 붙여 넣고 [키 등록]을 누르세요.", 14, 0xFF3E3329, false));
        EditText input = new EditText(this);
        input.setHint("OMOK1-…");
        input.setTypeface(Typeface.MONOSPACE);
        input.setTextSize(13);
        input.setMinLines(2);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        keyCard.addView(input);
        Button reg = button("키 등록", 0xFF5D4037);
        reg.setOnClickListener(v -> {
            int r = License.register(this, input.getText().toString());
            if (r == 0) {
                Toast.makeText(this, "키가 등록되었습니다. 모든 기능이 열렸어요!", Toast.LENGTH_LONG).show();
                buildUi();
            } else {
                Toast.makeText(this, r == 2 ? "사용 기간이 지난 키입니다" : "올바르지 않은 키입니다. 다시 확인해 주세요",
                        Toast.LENGTH_LONG).show();
            }
        });
        keyCard.addView(reg);
        keyCard.addView(text("키 문의 : Discord axkoth", 12, 0xFF8D6E63, false));
    }

    private Button button(String s, int color) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(15);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(12));
        b.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(50));
        lp.topMargin = dp(10);
        b.setLayoutParams(lp);
        b.setGravity(Gravity.CENTER);
        return b;
    }
}
