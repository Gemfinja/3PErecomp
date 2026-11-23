package com.thirdbirthday.recomp;

import android.app.AlertDialog;
import android.content.Context;
import android.content.res.AssetManager;
import android.hardware.input.InputManager;
import android.view.InputDevice;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.system.Os;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import org.libsdl.app.SDLActivity;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.function.IntConsumer;

public class ThirdBirthdayActivity extends SDLActivity {
    private static native void nativeSetTouchState(int buttons, int lx, int ly, float rx, float ry);
    private static native void nativeTouchLook(float dx, float dy);
    private static native int nativeGetGameTid();
    private static native void nativeSetActivity(ThirdBirthdayActivity act);
    private static native void nativeOnSaveDialogResult(int result, int sel);
    private static native void nativeApplyGraphics(
            int fsr, int fsrSharp, int brightness, int contrast, int saturation,
            int widescreen, int glow, int showFps, int texFilter, int texUpscale,
            int texPacks, int buttonPrompts, int frameGen, int scale, int perfProfile);

    private TouchControlsView touchView;
    private AlertDialog modDialog;
    private AlertDialog saveDialog;
    private Object perfHintSession;
    private LinearLayout[] saveSlotRows;
    private TextView saveDetailView;
    private String[] saveDetailsArr;
    private int saveCurSel = 0;
    private InputManager inputManager;
    private final InputManager.InputDeviceListener inputListener = new InputManager.InputDeviceListener() {
        @Override public void onInputDeviceAdded(int id)   { updateControllerState(); }
        @Override public void onInputDeviceChanged(int id) { updateControllerState(); }
        @Override public void onInputDeviceRemoved(int id) { updateControllerState(); }
    };

    private void updateControllerState() {
        if (touchView == null) return;
        boolean hasPad = false;
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice dev = InputDevice.getDevice(id);
            if (dev == null || dev.isVirtual()) continue;
            int src = dev.getSources();
            if ((src & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                    || (src & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) {
                hasPad = true;
                break;
            }
        }
        final boolean pad = hasPad;
        runOnUiThread(() -> touchView.setExternalControllerConnected(pad));
    }

    @Override
    protected String[] getLibraries() {
        return new String[] { "SDL3", "game", "main" };
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        File ext = getExternalFilesDir(null);
        if (ext != null) {
            File cfg = new File(ext, "launch.cfg");
            if (!cfg.exists()) copyAssetDir(getAssets(), "gamefiles", ext);
            setupPersistentSaves(ext);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(lp);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        super.onCreate(savedInstanceState);
        nativeSetActivity(this);
        int initPerf = cfgInt(readGraphicsCfg(), "perf_profile", 0);
        if (initPerf > 0) applyHardwareProfile(initPerf);
        if (mLayout != null) {
            touchView = new TouchControlsView(this, new TouchControlsView.Listener() {
                @Override public void onTouchState(int buttons, int lx, int ly, float rx, float ry) {
                    nativeSetTouchState(buttons, lx, ly, rx, ry);
                }
                @Override public void onTouchLook(float dx, float dy) {
                    nativeTouchLook(dx, dy);
                }
                @Override public void onOpenModMenu() {
                    runOnUiThread(() -> showModMenu());
                }
            });
            mLayout.addView(touchView, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        inputManager = (InputManager) getSystemService(Context.INPUT_SERVICE);
        if (inputManager != null) inputManager.registerInputDeviceListener(inputListener, null);
        updateControllerState();
        applyFullscreen();
    }

    @Override
    protected void onDestroy() {
        if (inputManager != null) inputManager.unregisterInputDeviceListener(inputListener);
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) applyFullscreen();
    }

    private void applyFullscreen() {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
            android.view.WindowInsetsController ctrl = getWindow().getInsetsController();
            if (ctrl != null) {
                ctrl.hide(android.view.WindowInsets.Type.statusBars()
                        | android.view.WindowInsets.Type.navigationBars());
                ctrl.setSystemBarsBehavior(
                        android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        }
        View decor = getWindow().getDecorView();
        decor.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    private Map<String, Integer> readGraphicsCfg() {
        Map<String, Integer> map = new HashMap<>();
        File ext = getExternalFilesDir(null);
        if (ext == null) return map;
        File cfg = new File(ext, "graphics.cfg");
        if (!cfg.exists()) return map;
        try (BufferedReader br = new BufferedReader(new FileReader(cfg))) {
            String line;
            while ((line = br.readLine()) != null) {
                int eq = line.indexOf('=');
                if (eq > 0) {
                    try {
                        map.put(line.substring(0, eq).trim(), Integer.parseInt(line.substring(eq + 1).trim()));
                    } catch (NumberFormatException ignored) {}
                }
            }
        } catch (IOException ignored) {}
        return map;
    }

    private static int cfgInt(Map<String, Integer> m, String k, int def) {
        Integer v = m.get(k);
        return v != null ? v : def;
    }

    private GradientDrawable crimsonBox(int fill, int stroke, int radDp, float strokeDp) {
        float dp = getResources().getDisplayMetrics().density;
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(fill);
        gd.setStroke((int) (strokeDp * dp), stroke);
        gd.setCornerRadius(radDp * dp);
        return gd;
    }

    private static class CtiToggle {
        boolean checked;
        Runnable syncUi;
        boolean isChecked() { return checked; }
    }

    private void showModMenu() {
        if (modDialog != null && modDialog.isShowing()) return;
        Map<String, Integer> cfg = readGraphicsCfg();

        float dp = getResources().getDisplayMetrics().density;
        int p12 = (int) (12 * dp), p8 = (int) (8 * dp);

        // Outer crimson Mod Menu card matching the concept
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setBackground(crimsonBox(0xED140305, 0xCCEF4444, 6, 1.5f));
        outer.setPadding(p12, p8, p12, p8);

        // Header: ⚙ Mod Menu                     ✕
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding((int) (4 * dp), (int) (2 * dp), (int) (4 * dp), p8);

        TextView title = new TextView(this);
        title.setText("⚙   Mod Menu");
        title.setTextColor(0xFFF8FAFC);
        title.setTextSize(15f);
        title.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        title.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(title);

        Button closeBtn = new Button(this);
        closeBtn.setText("✕");
        closeBtn.setTextColor(Color.WHITE);
        closeBtn.setTextSize(12f);
        closeBtn.setPadding(0, 0, 0, 0);
        closeBtn.setBackground(crimsonBox(0x667F1D1D, 0x88FCA5A5, 4, 1.0f));
        closeBtn.setLayoutParams(new LinearLayout.LayoutParams((int) (30 * dp), (int) (26 * dp)));
        header.addView(closeBtn);
        outer.addView(header);

        View topSep = new View(this);
        topSep.setBackgroundColor(0x66FCA5A5);
        outer.addView(topSep, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (1 * dp)));

        int screenH = getResources().getDisplayMetrics().heightPixels;
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int midH = Math.min((int) (240 * dp), Math.round(screenH * 0.56f));
        int sideW = Math.min((int) (165 * dp), Math.round(screenW * 0.25f));

        // Middle split: Left categories | Right options
        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.HORIZONTAL);
        mid.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, midH));

        LinearLayout side = new LinearLayout(this);
        side.setOrientation(LinearLayout.VERTICAL);
        side.setPadding(0, p8, p8, p8);
        side.setLayoutParams(new LinearLayout.LayoutParams(sideW, ViewGroup.LayoutParams.MATCH_PARENT));

        String[] catLabels = {"⚡  Desempenho", "🎞  Vídeo", "✨  FSR & Cor", "🧩  Texturas", "🎮  Touch HUD"};
        LinearLayout[] catRows = new LinearLayout[5];
        TextView[] catTvs = new TextView[5];
        LinearLayout[] panes = new LinearLayout[5];

        for (int i = 0; i < 5; i++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding((int) (10 * dp), (int) (8 * dp), (int) (8 * dp), (int) (8 * dp));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = (int) (5 * dp);

            TextView tv = new TextView(this);
            tv.setText(catLabels[i]);
            tv.setTypeface(Typeface.MONOSPACE);
            tv.setTextSize(12f);
            tv.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView arr = new TextView(this);
            arr.setText("›");
            arr.setTextColor(0xFFFCA5A5);
            arr.setTextSize(15f);

            row.addView(tv);
            row.addView(arr);
            catRows[i] = row;
            catTvs[i] = tv;
            side.addView(row, lp);

            panes[i] = new LinearLayout(this);
            panes[i].setOrientation(LinearLayout.VERTICAL);
            if (i > 0) panes[i].setVisibility(View.GONE);
        }
        mid.addView(side);

        View vSep = new View(this);
        vSep.setBackgroundColor(0x44FCA5A5);
        mid.addView(vSep, new LinearLayout.LayoutParams((int) (1 * dp), ViewGroup.LayoutParams.MATCH_PARENT));

        ScrollView scroll = new ScrollView(this);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        scroll.setPadding(p12, p8, (int) (4 * dp), p8);
        LinearLayout paneHost = new LinearLayout(this);
        paneHost.setOrientation(LinearLayout.VERTICAL);
        for (LinearLayout p : panes) paneHost.addView(p);
        scroll.addView(paneHost);
        mid.addView(scroll);
        outer.addView(mid);

        // Populate TAB 0: Desempenho
        int curPerf = Math.max(0, Math.min(3, cfgInt(cfg, "perf_profile", 0)));
        Spinner spPerf = addSpinner(panes[0], "Perfil de Desempenho",
                new String[]{"Padrão (0%)", "Light (30%)", "Medium (60%)", "High (85%)"}, curPerf);
        TextView tvPerfInfo = new TextView(this);
        tvPerfInfo.setTextColor(0xFFD6C7C2);
        tvPerfInfo.setTypeface(Typeface.MONOSPACE);
        tvPerfInfo.setTextSize(10.5f);
        tvPerfInfo.setPadding((int)(4 * dp), (int)(6 * dp), (int)(4 * dp), (int)(6 * dp));
        panes[0].addView(tvPerfInfo);

        // Populate TAB 1: Vídeo
        Spinner spScale = addSpinner(panes[1], "Resolução (Reiniciar)",
                new String[]{"Auto (Dispositivo)", "1x (480x272)", "2x (960x544)", "3x (1440x816)", "4x (1920x1088)", "5x (2400x1360)"},
                Math.max(0, Math.min(5, cfgInt(cfg, "scale", cfgInt(cfg, "render_scale", 0)))));
        CtiToggle swWs   = addToggle(panes[1], "Widescreen 16:9", cfgInt(cfg, "widescreen", 1) != 0);
        CtiToggle swGlow = addToggle(panes[1], "Glow / Bloom", cfgInt(cfg, "glow", 1) != 0);
        CtiToggle swFg   = addToggle(panes[1], "Frame Gen 60 FPS", cfgInt(cfg, "frame_interpolation", 0) != 0);
        CtiToggle swFps  = addToggle(panes[1], "Mostrar FPS na Tela", cfgInt(cfg, "show_fps", 1) != 0);

        // Populate TAB 2: FSR & Cor
        CtiToggle swFsr  = addToggle(panes[2], "AMD FSR 1.0 (EASU+RCAS)", cfgInt(cfg, "fsr", 0) != 0);
        SeekBar sbSharp  = addSeek(panes[2], "Nitidez FSR (%)", cfgInt(cfg, "fsr_sharpness", 50), 0, 100, null);
        SeekBar sbBright = addSeek(panes[2], "Brilho (%)", cfgInt(cfg, "brightness", 100), 50, 150, null);
        SeekBar sbContr  = addSeek(panes[2], "Contraste (%)", cfgInt(cfg, "contrast", 100), 50, 150, null);
        SeekBar sbSat    = addSeek(panes[2], "Saturação (%)", cfgInt(cfg, "saturation", 100), 50, 150, null);

        // Populate TAB 3: Texturas
        Spinner spFilt  = addSpinner(panes[3], "Filtro de Textura",
                new String[]{"Auto (Jogo)", "Nítido (Nearest)", "Suave (Linear)"},
                Math.max(0, Math.min(2, cfgInt(cfg, "tex_filter", 0))));
        Spinner spXbrz  = addSpinner(panes[3], "Upscale xBRZ",
                new String[]{"Off (1x)", "2x xBRZ", "3x xBRZ", "4x xBRZ"},
                Math.max(0, Math.min(3, cfgInt(cfg, "tex_upscale", 1) - 1)));
        CtiToggle swPacks = addToggle(panes[3], "Texture Packs HD", cfgInt(cfg, "texture_packs", 1) != 0);
        Spinner spPrompts = addSpinner(panes[3], "Ícones de Botões",
                new String[]{"PSP (Originais)", "Xbox (ABXY)"},
                Math.max(0, Math.min(1, cfgInt(cfg, "button_prompts", 0))));

        // Populate TAB 4: Touch HUD
        int alphaPct = touchView != null ? Math.round(touchView.getHudAlpha() * 100f) : 90;
        int scalePct = touchView != null ? Math.round(touchView.getHudScale() * 100f) : 100;
        int sensPct  = touchView != null ? Math.round(touchView.getLookSens() * 100f) : 100;
        SeekBar sbAlpha = addSeek(panes[4], "Opacidade HUD (%)", alphaPct, 35, 100,
                v -> { if (touchView != null) touchView.setHudAlpha(v / 100f); });
        SeekBar sbScale = addSeek(panes[4], "Tamanho Botões (%)", scalePct, 75, 130,
                v -> { if (touchView != null) touchView.setHudScale(v / 100f); });
        SeekBar sbSens  = addSeek(panes[4], "Sensibilidade Mira (%)", sensPct, 30, 250,
                v -> { if (touchView != null) touchView.setLookSens(v / 100f); });

        final String[] perfDescs = {
                "Padrão (0%): Escalonamento automático do sistema (todos os núcleos).",
                "Light (30%): Economia de bateria/calor (núcleos leves + clock suave).",
                "Medium (60%): Equilibrado (núcleos médios/grandes + ADPF moderado).",
                "High (85%): Máxima performance (núcleos Big/Prime Cortex-X1 + ADPF turbo)."
        };
        tvPerfInfo.setText(perfDescs[curPerf]);
        spPerf.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> p, View v, int pos, long id) {
                tvPerfInfo.setText(perfDescs[Math.max(0, Math.min(3, pos))]);
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> p) {}
        });

        // Category switching
        java.util.function.IntConsumer pickCat = idx -> {
            for (int i = 0; i < 5; i++) {
                boolean act = (i == idx);
                panes[i].setVisibility(act ? View.VISIBLE : View.GONE);
                catRows[i].setBackground(crimsonBox(
                        act ? 0xCC7F1D1D : 0x441E0709,
                        act ? 0xFFFCA5A5 : 0x33EF4444, 3, act ? 1.5f : 0.8f));
                catTvs[i].setTextColor(act ? Color.WHITE : 0xFFCBD5E1);
            }
        };
        for (int i = 0; i < 5; i++) {
            final int idx = i;
            catRows[i].setOnClickListener(v -> pickCat.accept(idx));
        }
        pickCat.accept(0);

        View botSep = new View(this);
        botSep.setBackgroundColor(0x55FCA5A5);
        outer.addView(botSep, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (1 * dp)));

        // Footer bar
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.CENTER_VERTICAL);
        btnRow.setPadding(0, p8, 0, 0);

        Button bEdit  = makeBtn(touchView != null && touchView.isEditMode() ? "Salvar Posições" : "Mover Botões", 0x997F1D1D);
        Button bReset = makeBtn("Resetar Touch", 0x991F2937);
        Button bApply = makeBtn("✓ Aplicar & Voltar", 0xCC991B1B);

        btnRow.addView(bEdit);
        btnRow.addView(bReset);
        btnRow.addView(bApply);
        outer.addView(btnRow);

        modDialog = new AlertDialog.Builder(this)
                .setView(outer)
                .setCancelable(true)
                .create();
        if (modDialog.getWindow() != null) {
            modDialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }

        closeBtn.setOnClickListener(v -> { modDialog.dismiss(); applyFullscreen(); });
        bEdit.setOnClickListener(v -> {
            if (touchView != null) {
                touchView.setEditMode(!touchView.isEditMode());
                if (touchView.isEditMode()) modDialog.dismiss();
                else { touchView.saveLayoutPrefs(); bEdit.setText("Mover Botões"); }
            }
        });
        bReset.setOnClickListener(v -> {
            if (touchView != null) {
                touchView.resetLayout();
                sbAlpha.setProgress(90 - 35);
                sbScale.setProgress(100 - 75);
                sbSens.setProgress(100 - 30);
            }
        });
        bApply.setOnClickListener(v -> {
            nativeApplyGraphics(
                    swFsr.isChecked() ? 1 : 0,
                    sbSharp.getProgress(),
                    50 + sbBright.getProgress(),
                    50 + sbContr.getProgress(),
                    50 + sbSat.getProgress(),
                    swWs.isChecked() ? 1 : 0,
                    swGlow.isChecked() ? 1 : 0,
                    swFps.isChecked() ? 1 : 0,
                    spFilt.getSelectedItemPosition(),
                    spXbrz.getSelectedItemPosition() + 1,
                    swPacks.isChecked() ? 1 : 0,
                    spPrompts.getSelectedItemPosition(),
                    swFg.isChecked() ? 1 : 0,
                    spScale.getSelectedItemPosition(),
                    spPerf.getSelectedItemPosition());
            applyHardwareProfile(spPerf.getSelectedItemPosition());
            if (touchView != null && touchView.isEditMode()) {
                touchView.setEditMode(false);
                touchView.saveLayoutPrefs();
            }
            modDialog.dismiss();
            applyFullscreen();
        });

        modDialog.setOnDismissListener(d -> applyFullscreen());
        modDialog.show();
    }

    private CtiToggle addToggle(LinearLayout parent, String label, boolean checked) {
        float dp = getResources().getDisplayMetrics().density;
        CtiToggle t = new CtiToggle();
        t.checked = checked;

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding((int) (4 * dp), (int) (6 * dp), (int) (4 * dp), (int) (6 * dp));
        row.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        View sq = new View(this);
        LinearLayout.LayoutParams sqlp = new LinearLayout.LayoutParams((int) (9 * dp), (int) (9 * dp));
        sqlp.rightMargin = (int) (10 * dp);
        sq.setLayoutParams(sqlp);

        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextColor(0xFFF1F5F9);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextSize(12f);
        tv.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout pill = new LinearLayout(this);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setPadding((int) (5 * dp), (int) (3 * dp), (int) (5 * dp), (int) (3 * dp));
        pill.setLayoutParams(new LinearLayout.LayoutParams((int) (62 * dp), (int) (25 * dp)));

        TextView stateTxt = new TextView(this);
        stateTxt.setTextSize(10f);
        stateTxt.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        stateTxt.setGravity(Gravity.CENTER);
        stateTxt.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        View knob = new View(this);
        knob.setLayoutParams(new LinearLayout.LayoutParams((int) (15 * dp), (int) (15 * dp)));

        t.syncUi = () -> {
            sq.setBackground(crimsonBox(t.checked ? 0xFFEF4444 : 0xFF334155,
                    t.checked ? 0xFFFCA5A5 : 0xFF475569, 2, 0.8f));
            pill.removeAllViews();
            if (t.checked) {
                pill.setBackground(crimsonBox(0xCC991B1B, 0xFFF87171, 13, 1.2f));
                stateTxt.setText("ON");
                stateTxt.setTextColor(Color.WHITE);
                knob.setBackground(crimsonBox(0xFFEF4444, 0xFFFCA5A5, 8, 1.0f));
                pill.addView(stateTxt);
                pill.addView(knob);
            } else {
                pill.setBackground(crimsonBox(0xAA1E1B24, 0x5564748B, 13, 1.0f));
                stateTxt.setText("OFF");
                stateTxt.setTextColor(0xFF94A3B8);
                knob.setBackground(crimsonBox(0xFF475569, 0xFF64748B, 8, 1.0f));
                pill.addView(knob);
                pill.addView(stateTxt);
            }
        };
        t.syncUi.run();

        View.OnClickListener click = v -> { t.checked = !t.checked; t.syncUi.run(); };
        row.setOnClickListener(click);
        pill.setOnClickListener(click);

        row.addView(sq);
        row.addView(tv);
        row.addView(pill);
        parent.addView(row);
        return t;
    }

    private SeekBar addSeek(LinearLayout parent, String label, int val, int min, int max, IntConsumer onChange) {
        float dp = getResources().getDisplayMetrics().density;
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding((int) (4 * dp), (int) (5 * dp), (int) (4 * dp), (int) (5 * dp));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextColor(0xFFF1F5F9);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextSize(11.5f);
        tv.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView valTv = new TextView(this);
        valTv.setText(String.valueOf(val));
        valTv.setTextColor(0xFFFCA5A5);
        valTv.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        valTv.setTextSize(11.5f);
        top.addView(tv);
        top.addView(valTv);

        SeekBar sb = new SeekBar(this);
        sb.setMax(max - min);
        sb.setProgress(Math.max(0, Math.min(max - min, val - min)));
        sb.getProgressDrawable().setColorFilter(0xFFEF4444, PorterDuff.Mode.SRC_IN);
        sb.getThumb().setColorFilter(0xFFFCA5A5, PorterDuff.Mode.SRC_IN);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean f) {
                int v = min + p;
                valTv.setText(String.valueOf(v));
                if (f && onChange != null) onChange.accept(v);
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        row.addView(top);
        row.addView(sb);
        parent.addView(row);
        return sb;
    }

    private Spinner addSpinner(LinearLayout parent, String label, String[] items, int sel) {
        float dp = getResources().getDisplayMetrics().density;
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding((int) (4 * dp), (int) (5 * dp), (int) (4 * dp), (int) (5 * dp));
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextColor(0xFFF1F5F9);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextSize(11.5f);
        tv.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Spinner sp = new Spinner(this);
        ArrayAdapter<String> ad = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, items);
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp.setAdapter(ad);
        sp.setSelection(sel);
        row.addView(tv);
        row.addView(sp);
        parent.addView(row);
        return sp;
    }

    private Button makeBtn(String txt, int bg) {
        float dp = getResources().getDisplayMetrics().density;
        Button b = new Button(this);
        b.setText(txt);
        b.setTextColor(Color.WHITE);
        b.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        b.setBackground(crimsonBox(bg, 0x88FCA5A5, 4, 1.0f));
        b.setTextSize(11f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, (int) (36 * dp), 1f);
        lp.setMargins(5, 0, 5, 0);
        b.setLayoutParams(lp);
        return b;
    }

    public void showSaveDialogFromNative(final String title, final String[] items, final String[] details,
                                         final int initialSel, final String help) {
        runOnUiThread(() -> {
            if (isFinishing()) return;
            if (saveDialog != null && saveDialog.isShowing()) {
                try { saveDialog.dismiss(); } catch (Exception ignored) {}
            }
            final float dp = getResources().getDisplayMetrics().density;
            saveDetailsArr = details != null ? details : new String[0];
            saveCurSel = (items != null && initialSel >= 0 && initialSel < items.length) ? initialSel : 0;

            LinearLayout root = new LinearLayout(this);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setPadding((int)(14 * dp), (int)(12 * dp), (int)(14 * dp), (int)(12 * dp));
            root.setBackground(crimsonBox(0xF50E090B, 0xFFDC1E3C, 6, 1.5f));

            LinearLayout hdr = new LinearLayout(this);
            hdr.setOrientation(LinearLayout.HORIZONTAL);
            hdr.setGravity(Gravity.CENTER_VERTICAL);
            hdr.setPadding(0, 0, 0, (int)(8 * dp));

            TextView tvTitle = new TextView(this);
            tvTitle.setText(title != null ? title.toUpperCase() : "MEMORY STICK");
            tvTitle.setTextColor(0xFFFCA5A5);
            tvTitle.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            tvTitle.setTextSize(14f);
            tvTitle.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            hdr.addView(tvTitle);

            TextView btnX = new TextView(this);
            btnX.setText(" ✕ ");
            btnX.setTextColor(0xFFFCA5A5);
            btnX.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            btnX.setTextSize(14f);
            btnX.setPadding((int)(8 * dp), (int)(2 * dp), (int)(8 * dp), (int)(2 * dp));
            btnX.setBackground(crimsonBox(0xFF281016, 0xFFDC1E3C, 4, 1f));
            hdr.addView(btnX);
            root.addView(hdr);

            View div = new View(this);
            div.setBackgroundColor(0x66DC1E3C);
            div.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int)(1 * dp)));
            root.addView(div);

            ScrollView scroll = new ScrollView(this);
            LinearLayout listBox = new LinearLayout(this);
            listBox.setOrientation(LinearLayout.VERTICAL);
            listBox.setPadding(0, (int)(8 * dp), 0, (int)(6 * dp));
            scroll.addView(listBox);
            int maxListH = (int)(175 * dp);
            scroll.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxListH));
            root.addView(scroll);

            int n = items != null ? items.length : 0;
            saveSlotRows = new LinearLayout[n];
            for (int i = 0; i < n; i++) {
                final int idx = i;
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding((int)(10 * dp), (int)(8 * dp), (int)(10 * dp), (int)(8 * dp));
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rlp.setMargins(0, 0, 0, (int)(5 * dp));
                row.setLayoutParams(rlp);

                TextView tvItem = new TextView(this);
                tvItem.setText(items[i] != null ? items[i] : "");
                tvItem.setTextColor(0xFFF4EAE6);
                tvItem.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
                tvItem.setTextSize(12f);
                row.addView(tvItem);

                row.setOnClickListener(v -> {
                    if (saveCurSel == idx) {
                        nativeOnSaveDialogResult(1, idx);
                        if (saveDialog != null) saveDialog.dismiss();
                        applyFullscreen();
                    } else {
                        saveCurSel = idx;
                        refreshSaveDialogSelection();
                    }
                });
                saveSlotRows[i] = row;
                listBox.addView(row);
            }

            saveDetailView = new TextView(this);
            saveDetailView.setTextColor(0xFFD6C7C2);
            saveDetailView.setTypeface(Typeface.MONOSPACE);
            saveDetailView.setTextSize(10.5f);
            saveDetailView.setPadding((int)(8 * dp), (int)(6 * dp), (int)(8 * dp), (int)(6 * dp));
            saveDetailView.setBackground(crimsonBox(0xCC160C10, 0x44DC1E3C, 4, 1f));
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            dlp.setMargins(0, (int)(4 * dp), 0, (int)(8 * dp));
            saveDetailView.setLayoutParams(dlp);
            root.addView(saveDetailView);

            refreshSaveDialogSelection();

            LinearLayout btns = new LinearLayout(this);
            btns.setOrientation(LinearLayout.HORIZONTAL);
            Button bCancel = makeBtn("CANCELAR", 0xFF281218);
            Button bOk     = makeBtn("CONFIRMAR", 0xFF991B1B);
            btns.addView(bCancel);
            btns.addView(bOk);
            root.addView(btns);

            final boolean[] decided = { false };
            btnX.setOnClickListener(v -> {
                if (!decided[0]) { decided[0] = true; nativeOnSaveDialogResult(0, saveCurSel); }
                if (saveDialog != null) saveDialog.dismiss();
                applyFullscreen();
            });
            bCancel.setOnClickListener(v -> {
                if (!decided[0]) { decided[0] = true; nativeOnSaveDialogResult(0, saveCurSel); }
                if (saveDialog != null) saveDialog.dismiss();
                applyFullscreen();
            });
            bOk.setOnClickListener(v -> {
                if (!decided[0]) { decided[0] = true; nativeOnSaveDialogResult(1, saveCurSel); }
                if (saveDialog != null) saveDialog.dismiss();
                applyFullscreen();
            });

            AlertDialog.Builder b = new AlertDialog.Builder(this);
            b.setView(root);
            b.setCancelable(true);
            saveDialog = b.create();
            saveDialog.setOnCancelListener(d -> {
                if (!decided[0]) { decided[0] = true; nativeOnSaveDialogResult(0, saveCurSel); }
                applyFullscreen();
            });
            if (saveDialog.getWindow() != null) {
                saveDialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            }
            saveDialog.show();
            if (saveDialog.getWindow() != null) {
                int w = (int)(getResources().getDisplayMetrics().widthPixels * 0.72f);
                saveDialog.getWindow().setLayout(w, ViewGroup.LayoutParams.WRAP_CONTENT);
            }
        });
    }

    private void refreshSaveDialogSelection() {
        if (saveSlotRows != null) {
            for (int i = 0; i < saveSlotRows.length; i++) {
                boolean sel = (i == saveCurSel);
                saveSlotRows[i].setBackground(crimsonBox(
                        sel ? 0xFF3E121B : 0xFF181013,
                        sel ? 0xFFEF4444 : 0x33FCA5A5,
                        4, sel ? 1.5f : 0.8f));
            }
        }
        if (saveDetailView != null) {
            String det = (saveDetailsArr != null && saveCurSel >= 0 && saveCurSel < saveDetailsArr.length)
                    ? saveDetailsArr[saveCurSel] : "";
            if (det == null || det.trim().isEmpty()) {
                saveDetailView.setVisibility(View.GONE);
            } else {
                saveDetailView.setVisibility(View.VISIBLE);
                saveDetailView.setText(det.trim());
            }
        }
    }

    public void updateSaveDialogSelectionFromNative(final int sel) {
        runOnUiThread(() -> {
            if (saveSlotRows != null && sel >= 0 && sel < saveSlotRows.length) {
                saveCurSel = sel;
                refreshSaveDialogSelection();
            }
        });
    }

    public void closeSaveDialogFromNative() {
        runOnUiThread(() -> {
            if (saveDialog != null && saveDialog.isShowing()) {
                try { saveDialog.dismiss(); } catch (Exception ignored) {}
            }
            applyFullscreen();
        });
    }

    private static void copyAssetDir(AssetManager am, String assetPath, File dstDir) {
        try {
            String[] list = am.list(assetPath);
            if (list == null || list.length == 0) {
                dstDir.getParentFile().mkdirs();
                try (InputStream in = am.open(assetPath);
                     FileOutputStream out = new FileOutputStream(dstDir)) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                return;
            }
            dstDir.mkdirs();
            for (String name : list) copyAssetDir(am, assetPath + "/" + name, new File(dstDir, name));
        } catch (IOException ignored) {}
    }

    private void setupPersistentSaves(File extDir) {
        try {
            boolean canShared = Build.VERSION.SDK_INT < Build.VERSION_CODES.R
                    || Environment.isExternalStorageManager();
            if (!canShared) return;
            File sharedRoot = new File(Environment.getExternalStorageDirectory(), "The3rdBirthday/memstick");
            File sharedSaves = new File(sharedRoot, "PSP/SAVEDATA");
            sharedSaves.mkdirs();
            File oldSaves = new File(extDir, "memstick/PSP/SAVEDATA");
            if (oldSaves.isDirectory()) {
                copyDirIfMissing(oldSaves, sharedSaves);
            }
            Os.setenv("SR_MEMSTICK", sharedRoot.getAbsolutePath(), true);
        } catch (Throwable ignored) {}
    }

    private static void copyDirIfMissing(File src, File dst) {
        if (src.isDirectory()) {
            dst.mkdirs();
            File[] children = src.listFiles();
            if (children != null) {
                for (File c : children) {
                    copyDirIfMissing(c, new File(dst, c.getName()));
                }
            }
        } else if (src.isFile() && !dst.exists()) {
            try (InputStream in = new java.io.FileInputStream(src);
                 FileOutputStream out = new FileOutputStream(dst)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            } catch (IOException ignored) {}
        }
    }

    private void applyHardwareProfile(int profile) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                getWindow().setSustainedPerformanceMode(profile == 1);
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                android.os.PerformanceHintManager phm = getSystemService(android.os.PerformanceHintManager.class);
                if (phm != null) {
                    if (perfHintSession != null) {
                        try { ((android.os.PerformanceHintManager.Session) perfHintSession).close(); } catch (Throwable ignored) {}
                        perfHintSession = null;
                    }
                    if (profile > 0) {
                        int tid = nativeGetGameTid();
                        int[] tids = tid > 0 ? new int[]{ tid, android.os.Process.myTid() } : new int[]{ android.os.Process.myTid() };
                        long targetNs = (profile == 1) ? 33_333_333L : (profile == 2) ? 16_666_666L : 8_333_333L;
                        long actualNs = (profile == 1) ? 10_000_000L : (profile == 2) ? 14_000_000L : 14_500_000L;
                        android.os.PerformanceHintManager.Session sess = phm.createHintSession(tids, targetNs);
                        if (sess != null) {
                            sess.reportActualWorkDuration(actualNs);
                            perfHintSession = sess;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
    }
}
