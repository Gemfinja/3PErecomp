package com.thirdbirthday.recomp;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.function.IntConsumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class LauncherActivity extends Activity {
    private static final int REQ_PICK_ISO = 1001;
    private static final int REQ_PICK_DRIVER = 1002;

    private TextView statusText, footerDesc, isoBadge, driverText;
    private Button isoBtn, drvBtn, resetDrvBtn, playBtn;
    private LinearLayout[] navRows = new LinearLayout[6];
    private TextView[] navTitles = new TextView[6];
    private View[] tabPanes = new View[5];

    private static class CrimsonToggle {
        boolean checked;
        Runnable syncUi;
        boolean isChecked() { return checked; }
        void setChecked(boolean v) { checked = v; if (syncUi != null) syncUi.run(); }
    }

    private Spinner spScale, spVsync, spFilt, spXbrz, spPrompts;
    private CrimsonToggle cbWide, cbGlow, cbFps, cbFsr, cbPacks, cbInterp, cbGpu, cbNoDual;
    private SeekBar sbSharp, sbBright, sbContr, sbSat, sbAlpha, sbHudScale, sbLookSens;

    private float dp;

    private static final String[] NAV_TITLES = {
        "▶   Iniciar",
        "🎞   Vídeo & Gráficos",
        "✨   FSR & Texturas",
        "⚡   GPU & Turnip",
        "🎮   Controles Touch",
        "↺   Restaurar Padrões"
    };
    private static final String[] NAV_DESCS = {
        "Verifique a ISO do jogo (ULUS10567) e inicie The 3rd Birthday.",
        "Ajuste resolução interna, proporção Widescreen, VSync, Glow e FPS.",
        "Configure AMD FSR 1.0, nitidez, brilho, contraste, saturação e xBRZ.",
        "Gerencie driver Vulkan Turnip, Dual-Source Blend e Frame Gen 60 FPS.",
        "Personalize opacidade, tamanho e sensibilidade dos controles touch.",
        "Restaura todas as configurações gráficas e de compatibilidade padrão."
    };

    private GradientDrawable crimsonBox(int fill, int stroke, int radDp, float strokeDp) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(fill);
        gd.setStroke((int) (strokeDp * dp), stroke);
        gd.setCornerRadius(radDp * dp);
        return gd;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        dp = getResources().getDisplayMetrics().density;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(lp);
        }

        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(0xFF140204);

        // Background Aya Brea crimson wallpaper
        try (InputStream in = getAssets().open("launcher_bg.jpg")) {
            Bitmap bgBmp = BitmapFactory.decodeStream(in);
            if (bgBmp != null) {
                ImageView iv = new ImageView(this);
                iv.setImageBitmap(bgBmp);
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                frame.addView(iv, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            }
        } catch (Exception ignored) {}

        LinearLayout main = new LinearLayout(this);
        main.setOrientation(LinearLayout.VERTICAL);
        main.setPadding((int) (26 * dp), (int) (14 * dp), (int) (26 * dp), (int) (12 * dp));

        // TOP HEADER: [ The 3rd Birthday ] ────────────────────────
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, 0, 0, (int) (10 * dp));

        TextView hdrTitle = new TextView(this);
        hdrTitle.setText("[  The 3rd Birthday  ]");
        hdrTitle.setTextColor(0xFFF8FAFC);
        hdrTitle.setTextSize(17f);
        hdrTitle.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        header.addView(hdrTitle);

        View hLine = new View(this);
        hLine.setBackgroundColor(0x66FCA5A5);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(0, (int) (1 * dp), 1f);
        hlp.leftMargin = (int) (14 * dp);
        header.addView(hLine, hlp);

        isoBadge = new TextView(this);
        isoBadge.setTextSize(11f);
        isoBadge.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        isoBadge.setPadding((int) (12 * dp), 0, 0, 0);
        header.addView(isoBadge);

        main.addView(header);

        // MIDDLE SPLIT: Left Crimson Menu List | Right Options Panel
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.HORIZONTAL);
        body.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // Left menu list
        LinearLayout navCol = new LinearLayout(this);
        navCol.setOrientation(LinearLayout.VERTICAL);
        navCol.setGravity(Gravity.CENTER_VERTICAL);
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int navW = Math.min((int) (245 * dp), Math.round(screenW * 0.34f));
        LinearLayout.LayoutParams navLp = new LinearLayout.LayoutParams(
                navW, ViewGroup.LayoutParams.MATCH_PARENT);
        navLp.rightMargin = (int) (18 * dp);
        body.addView(navCol, navLp);

        for (int i = 0; i < NAV_TITLES.length; i++) {
            final int idx = i;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding((int) (14 * dp), (int) (9 * dp), (int) (12 * dp), (int) (9 * dp));
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.bottomMargin = (int) (6 * dp);

            TextView tv = new TextView(this);
            tv.setText(NAV_TITLES[i]);
            tv.setTextSize(12.5f);
            tv.setTypeface(Typeface.MONOSPACE);
            tv.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView arrow = new TextView(this);
            arrow.setText("›");
            arrow.setTextColor(0xFFFCA5A5);
            arrow.setTextSize(16f);

            row.addView(tv);
            row.addView(arrow);
            row.setOnClickListener(v -> {
                if (idx == 5) restoreDefaults();
                else selectTab(idx);
            });
            navRows[i] = row;
            navTitles[i] = tv;
            navCol.addView(row, rlp);
        }

        // Right detail panel
        ScrollView scroll = new ScrollView(this);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        scroll.setBackground(crimsonBox(0xB8140406, 0x55EF4444, 6, 1.0f));
        scroll.setPadding((int) (16 * dp), (int) (12 * dp), (int) (16 * dp), (int) (12 * dp));

        LinearLayout contentHost = new LinearLayout(this);
        contentHost.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(contentHost);
        body.addView(scroll);

        buildHomeTab(contentHost);
        buildVideoTab(contentHost);
        buildFsrTab(contentHost);
        buildGpuTab(contentHost);
        buildCtrlTab(contentHost);

        main.addView(body);

        // BOTTOM FOOTER: ─────────────────────── Description | ▶ JOGAR
        View bLine = new View(this);
        bLine.setBackgroundColor(0x55FCA5A5);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (1 * dp));
        blp.topMargin = (int) (8 * dp);
        blp.bottomMargin = (int) (8 * dp);
        main.addView(bLine, blp);

        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.CENTER_VERTICAL);

        footerDesc = new TextView(this);
        footerDesc.setTextColor(0xFFE2E8F0);
        footerDesc.setTextSize(12f);
        footerDesc.setTypeface(Typeface.MONOSPACE);
        footerDesc.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        footer.addView(footerDesc);

        playBtn = new Button(this);
        playBtn.setText("✓   JOGAR AGORA");
        playBtn.setTextColor(Color.WHITE);
        playBtn.setTextSize(13f);
        playBtn.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        playBtn.setPadding((int) (22 * dp), (int) (8 * dp), (int) (22 * dp), (int) (8 * dp));
        playBtn.setBackground(crimsonBox(0xCC991B1B, 0xFFFCA5A5, 4, 1.5f));
        playBtn.setOnClickListener(v -> { saveOptions(); launchGame(); });
        footer.addView(playBtn);

        main.addView(footer);
        frame.addView(main);
        setContentView(frame);

        selectTab(0);
        loadOptions();
        refreshStatus();
        applyFullscreen();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) applyFullscreen();
    }

    private void applyFullscreen() {
        View decor = getWindow().getDecorView();
        decor.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    private void selectTab(int idx) {
        for (int i = 0; i < 5; i++) tabPanes[i].setVisibility(i == idx ? View.VISIBLE : View.GONE);
        for (int i = 0; i < NAV_TITLES.length; i++) {
            boolean act = (i == idx);
            navRows[i].setBackground(crimsonBox(
                    act ? 0xCC7F1D1D : 0x771C0608,
                    act ? 0xFFFCA5A5 : 0x33EF4444,
                    3, act ? 1.8f : 1.0f));
            navTitles[i].setTextColor(act ? Color.WHITE : 0xFFCBD5E1);
        }
        footerDesc.setText(NAV_DESCS[idx]);
    }

    private LinearLayout makePane(LinearLayout host, String title) {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        TextView h = new TextView(this);
        h.setText("[ " + title + " ]");
        h.setTextColor(0xFFFCA5A5);
        h.setTextSize(13f);
        h.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        h.setPadding(0, 0, 0, (int) (8 * dp));
        p.addView(h);
        host.addView(p);
        return p;
    }

    private void buildHomeTab(LinearLayout host) {
        LinearLayout p = makePane(host, "Missão CTI // ULUS10567");
        tabPanes[0] = p;

        statusText = new TextView(this);
        statusText.setTextColor(0xFFF1F5F9);
        statusText.setTextSize(13f);
        statusText.setTypeface(Typeface.MONOSPACE);
        statusText.setPadding(0, 0, 0, (int) (10 * dp));
        p.addView(statusText);

        isoBtn = new Button(this);
        isoBtn.setText("Selecionar / Trocar ULUS10567.iso");
        isoBtn.setTextColor(Color.WHITE);
        isoBtn.setTypeface(Typeface.MONOSPACE);
        isoBtn.setBackground(crimsonBox(0x993B070B, 0x88EF4444, 4, 1.0f));
        isoBtn.setOnClickListener(v -> openPicker(REQ_PICK_ISO));
        p.addView(isoBtn);

        TextView info = new TextView(this);
        info.setText("\n• Motor: Vulkan + OpenSL ES + AMediaCodec\n• Controles: HUD Vermelha/Preta + Touch Nativo\n• Mod Menu: Toque no ícone Casa dentro do jogo");
        info.setTextColor(0xFFCBD5E1);
        info.setTextSize(12f);
        info.setTypeface(Typeface.MONOSPACE);
        p.addView(info);
    }

    private void buildVideoTab(LinearLayout host) {
        LinearLayout p = makePane(host, "Vídeo & Gráficos");
        tabPanes[1] = p;
        spScale = addSpinner(p, "Resolução Interna",
                new String[]{"Auto (Dispositivo)", "1x (480x272)", "2x (960x544)", "3x (1440x816)", "4x (1920x1088)", "5x (2400x1360)"});
        spVsync = addSpinner(p, "VSync",
                new String[]{"Off (Immediate)", "On (FIFO)", "Auto (Mailbox)"});
        cbWide = addCheck(p, "Widescreen 16:9 (Tela Cheia)", true);
        cbGlow = addCheck(p, "Glow / Bloom", true);
        cbFps  = addCheck(p, "Mostrar FPS na Tela", true);
    }

    private void buildFsrTab(LinearLayout host) {
        LinearLayout p = makePane(host, "FSR 1.0, Cor & Texturas");
        tabPanes[2] = p;
        cbFsr    = addCheck(p, "AMD FSR 1.0 (EASU + RCAS)", false);
        sbSharp  = addSeek(p, "Nitidez FSR (%)", 50, 0, 100);
        sbBright = addSeek(p, "Brilho (%)", 100, 50, 150);
        sbContr  = addSeek(p, "Contraste (%)", 100, 50, 150);
        sbSat    = addSeek(p, "Saturação (%)", 100, 50, 150);
        spFilt   = addSpinner(p, "Filtro de Textura",
                new String[]{"Auto (Jogo)", "Nítido (Nearest)", "Suave (Linear)"});
        spXbrz   = addSpinner(p, "Upscale xBRZ",
                new String[]{"Off (1x)", "2x xBRZ", "3x xBRZ", "4x xBRZ"});
        cbPacks  = addCheck(p, "Carregar Texture Packs HD", true);
        spPrompts = addSpinner(p, "Ícones de Botões",
                new String[]{"PSP (Originais)", "Xbox (ABXY)"});
    }

    private void buildGpuTab(LinearLayout host) {
        LinearLayout p = makePane(host, "GPU, Frame Gen & Turnip");
        tabPanes[3] = p;
        cbGpu    = addCheck(p, "Renderizador Vulkan por Hardware", true);
        cbNoDual = addCheck(p, "Disable Dual-Source Blend (Adreno padrão)", true);
        cbInterp = addCheck(p, "Frame Gen 60 FPS (Experimental)", false);

        driverText = new TextView(this);
        driverText.setTextColor(0xFFFCA5A5);
        driverText.setTextSize(12f);
        driverText.setTypeface(Typeface.MONOSPACE);
        driverText.setPadding(0, (int) (10 * dp), 0, (int) (8 * dp));
        p.addView(driverText);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        drvBtn = new Button(this);
        drvBtn.setText("Carregar Turnip (.zip/.so)");
        drvBtn.setTextColor(Color.WHITE);
        drvBtn.setBackground(crimsonBox(0x997F1D1D, 0xAAEF4444, 4, 1.0f));
        drvBtn.setOnClickListener(v -> openPicker(REQ_PICK_DRIVER));

        resetDrvBtn = new Button(this);
        resetDrvBtn.setText("Driver Padrão");
        resetDrvBtn.setTextColor(Color.WHITE);
        resetDrvBtn.setBackground(crimsonBox(0x991F2937, 0x6694A3B8, 4, 1.0f));
        resetDrvBtn.setOnClickListener(v -> {
            File df = new File(getExternalFilesDir(null), "vulkan_driver.txt");
            if (df.exists()) df.delete();
            refreshStatus();
        });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        blp.rightMargin = (int) (8 * dp);
        row.addView(drvBtn, blp);
        row.addView(resetDrvBtn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        p.addView(row);
    }

    private void buildCtrlTab(LinearLayout host) {
        LinearLayout p = makePane(host, "Controles Touch");
        tabPanes[4] = p;
        sbAlpha    = addSeek(p, "Opacidade HUD (%)", 90, 35, 100);
        sbHudScale = addSeek(p, "Tamanho Botões (%)", 100, 75, 130);
        sbLookSens = addSeek(p, "Sensibilidade Câmera (%)", 100, 30, 250);

        TextView tip = new TextView(this);
        tip.setText("\n• Lado esquerdo livre: toque e arraste para mover.\n• Lado direito livre: deslize para mirar.\n• Toque no ícone Casa (topo) para abrir o Mod Menu.");
        tip.setTextColor(0xFFCBD5E1);
        tip.setTextSize(12f);
        tip.setTypeface(Typeface.MONOSPACE);
        p.addView(tip);
    }

    private void restoreDefaults() {
        spScale.setSelection(1);
        spVsync.setSelection(2);
        cbWide.setChecked(true);
        cbGlow.setChecked(true);
        cbFps.setChecked(true);
        cbFsr.setChecked(false);
        sbSharp.setProgress(50);
        sbBright.setProgress(50);
        sbContr.setProgress(50);
        sbSat.setProgress(50);
        spFilt.setSelection(0);
        spXbrz.setSelection(0);
        cbPacks.setChecked(true);
        spPrompts.setSelection(0);
        cbGpu.setChecked(true);
        cbNoDual.setChecked(true);
        cbInterp.setChecked(false);
        sbAlpha.setProgress(55);
        sbHudScale.setProgress(25);
        sbLookSens.setProgress(70);
        saveOptions();
        footerDesc.setText("Padrões restaurados com sucesso!");
    }

    private CrimsonToggle addCheck(LinearLayout parent, String label, boolean checked) {
        CrimsonToggle t = new CrimsonToggle();
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
        tv.setTextSize(12.5f);
        tv.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // Modern pill toggle (ON crimson / OFF dark)
        LinearLayout pill = new LinearLayout(this);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setPadding((int) (6 * dp), (int) (3 * dp), (int) (6 * dp), (int) (3 * dp));
        pill.setLayoutParams(new LinearLayout.LayoutParams((int) (64 * dp), (int) (26 * dp)));

        TextView stateTxt = new TextView(this);
        stateTxt.setTextSize(10.5f);
        stateTxt.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        stateTxt.setGravity(Gravity.CENTER);
        stateTxt.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        View knob = new View(this);
        knob.setLayoutParams(new LinearLayout.LayoutParams((int) (16 * dp), (int) (16 * dp)));

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

    private Spinner addSpinner(LinearLayout parent, String label, String[] items) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, (int) (4 * dp), 0, (int) (4 * dp));
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextColor(0xFFF1F5F9);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextSize(12.5f);
        tv.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Spinner sp = new Spinner(this);
        ArrayAdapter<String> ad = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, items);
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp.setAdapter(ad);
        row.addView(tv);
        row.addView(sp);
        parent.addView(row);
        return sp;
    }

    private SeekBar addSeek(LinearLayout parent, String label, int val, int min, int max) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding((int) (4 * dp), (int) (6 * dp), (int) (4 * dp), (int) (6 * dp));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextColor(0xFFF1F5F9);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextSize(12f);
        tv.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView valTv = new TextView(this);
        valTv.setText(String.valueOf(val));
        valTv.setTextColor(0xFFFCA5A5);
        valTv.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        valTv.setTextSize(12f);
        top.addView(tv);
        top.addView(valTv);

        SeekBar sb = new SeekBar(this);
        sb.setMax(max - min);
        sb.setProgress(Math.max(0, Math.min(max - min, val - min)));
        sb.getProgressDrawable().setColorFilter(0xFFEF4444, android.graphics.PorterDuff.Mode.SRC_IN);
        sb.getThumb().setColorFilter(0xFFFCA5A5, android.graphics.PorterDuff.Mode.SRC_IN);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean f) { valTv.setText(String.valueOf(min + p)); }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        row.addView(top);
        row.addView(sb);
        parent.addView(row);
        return sb;
    }

    private Map<String, Integer> readCfg(File f) {
        Map<String, Integer> m = new HashMap<>();
        if (!f.exists()) return m;
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = br.readLine()) != null) {
                int eq = line.indexOf('=');
                if (eq > 0) m.put(line.substring(0, eq).trim(), Integer.parseInt(line.substring(eq + 1).trim()));
            }
        } catch (Exception ignored) {}
        return m;
    }

    private static int cfg(Map<String, Integer> m, String k, int def) {
        Integer v = m.get(k);
        return v != null ? v : def;
    }

    private void loadOptions() {
        File ext = getExternalFilesDir(null);
        Map<String, Integer> g = readCfg(new File(ext, "graphics.cfg"));
        Map<String, Integer> a = readCfg(new File(ext, "android_opts.cfg"));

        int sc = cfg(g, "scale", cfg(g, "render_scale", 0));
        spScale.setSelection(Math.max(0, Math.min(5, sc)));
        spVsync.setSelection(Math.max(0, Math.min(2, cfg(g, "vsync", 2))));
        cbWide.setChecked(cfg(g, "widescreen", 1) != 0);
        cbGlow.setChecked(cfg(g, "glow", 1) != 0);
        cbFps.setChecked(cfg(g, "show_fps", 1) != 0);
        cbFsr.setChecked(cfg(g, "fsr", 0) != 0);
        sbSharp.setProgress(Math.max(0, Math.min(100, cfg(g, "fsr_sharpness", 50))));
        sbBright.setProgress(Math.max(0, Math.min(100, cfg(g, "brightness", 100) - 50)));
        sbContr.setProgress(Math.max(0, Math.min(100, cfg(g, "contrast", 100) - 50)));
        sbSat.setProgress(Math.max(0, Math.min(100, cfg(g, "saturation", 100) - 50)));
        spFilt.setSelection(Math.max(0, Math.min(2, cfg(g, "tex_filter", 0))));
        spXbrz.setSelection(Math.max(0, Math.min(3, cfg(g, "tex_upscale", 1) - 1)));
        cbPacks.setChecked(cfg(g, "texture_packs", 1) != 0);
        spPrompts.setSelection(Math.max(0, Math.min(1, cfg(g, "button_prompts", 0))));
        cbInterp.setChecked(cfg(g, "frame_interpolation", 0) != 0);

        cbGpu.setChecked(cfg(a, "gpu", 1) != 0);
        cbNoDual.setChecked(cfg(a, "nodualsrc", 1) != 0);

        SharedPreferences prefs = getSharedPreferences("cti_touch_layout", MODE_PRIVATE);
        sbAlpha.setProgress(Math.max(0, Math.min(65, Math.round(prefs.getFloat("alpha", 0.90f) * 100f) - 35)));
        sbHudScale.setProgress(Math.max(0, Math.min(55, Math.round(prefs.getFloat("scale", 1.0f) * 100f) - 75)));
        sbLookSens.setProgress(Math.max(0, Math.min(220, Math.round(prefs.getFloat("look_sens", 1.0f) * 100f) - 30)));
    }

    private void refreshStatus() {
        File ext = getExternalFilesDir(null);
        File iso = new File(ext, "ULUS10567.iso");
        boolean hasIso = iso.exists() && iso.length() > 100_000_000L;
        statusText.setText(hasIso ? "ISO Pronta: ULUS10567.iso (1.36 GB)" : "ISO Ausente — selecione ULUS10567.iso");
        isoBadge.setText(hasIso ? "● ISO OK" : "● SEM ISO");
        isoBadge.setTextColor(hasIso ? 0xFF34D399 : 0xFFFB7185);
        playBtn.setEnabled(hasIso);

        File df = new File(ext, "vulkan_driver.txt");
        driverText.setText((df.exists() && df.length() > 0)
                ? "Driver Vulkan: Turnip Customizado ativo"
                : "Driver Vulkan: Padrão do Sistema (Adreno 660)");
    }

    private void saveOptions() {
        File ext = getExternalFilesDir(null);
        if (ext == null) return;
        ext.mkdirs();
        int sc = spScale.getSelectedItemPosition();
        int vs = spVsync.getSelectedItemPosition();
        try (FileOutputStream out = new FileOutputStream(new File(ext, "graphics.cfg"))) {
            String cfg = "render_scale=" + sc + "\n"
                    + "scale=" + sc + "\n"
                    + "vsync=" + vs + "\n"
                    + "frame_interpolation=" + (cbInterp.isChecked() ? 1 : 0) + "\n"
                    + "show_fps=" + (cbFps.isChecked() ? 1 : 0) + "\n"
                    + "widescreen=" + (cbWide.isChecked() ? 1 : 0) + "\n"
                    + "glow=" + (cbGlow.isChecked() ? 1 : 0) + "\n"
                    + "fsr=" + (cbFsr.isChecked() ? 1 : 0) + "\n"
                    + "fsr_sharpness=" + sbSharp.getProgress() + "\n"
                    + "brightness=" + (50 + sbBright.getProgress()) + "\n"
                    + "contrast=" + (50 + sbContr.getProgress()) + "\n"
                    + "saturation=" + (50 + sbSat.getProgress()) + "\n"
                    + "tex_filter=" + spFilt.getSelectedItemPosition() + "\n"
                    + "tex_upscale=" + (spXbrz.getSelectedItemPosition() + 1) + "\n"
                    + "texture_packs=" + (cbPacks.isChecked() ? 1 : 0) + "\n"
                    + "button_prompts=" + spPrompts.getSelectedItemPosition() + "\n";
            out.write(cfg.getBytes());
        } catch (Exception ignored) {}

        try (FileOutputStream out = new FileOutputStream(new File(ext, "android_opts.cfg"))) {
            String opts = "gpu=" + (cbGpu.isChecked() ? 1 : 0) + "\n"
                    + "nodualsrc=" + (cbNoDual.isChecked() ? 1 : 0) + "\n";
            out.write(opts.getBytes());
        } catch (Exception ignored) {}

        try (FileOutputStream out = new FileOutputStream(new File(ext, "adreno_paths.cfg"))) {
            String hookDir = getApplicationInfo().nativeLibraryDir;
            if (!hookDir.endsWith("/")) hookDir += "/";
            String tmpDir = getCacheDir().getAbsolutePath();
            if (!tmpDir.endsWith("/")) tmpDir += "/";
            out.write(("hook_dir=" + hookDir + "\ntmp_dir=" + tmpDir + "\n").getBytes());
        } catch (Exception ignored) {}

        getSharedPreferences("cti_touch_layout", MODE_PRIVATE).edit()
                .putFloat("alpha", (35 + sbAlpha.getProgress()) / 100f)
                .putFloat("scale", (75 + sbHudScale.getProgress()) / 100f)
                .putFloat("look_sens", (30 + sbLookSens.getProgress()) / 100f)
                .apply();
    }

    private void openPicker(int req) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, req);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == REQ_PICK_ISO) copyIso(uri);
        else if (requestCode == REQ_PICK_DRIVER) installDriver(uri);
    }

    private void copyIso(Uri uri) {
        isoBtn.setEnabled(false);
        playBtn.setEnabled(false);
        statusText.setText("Copiando ISO... Aguarde.");
        new Thread(() -> {
            File ext = getExternalFilesDir(null);
            if (ext != null) ext.mkdirs();
            File tmp = new File(ext, "ULUS10567.iso.tmp");
            File dst = new File(ext, "ULUS10567.iso");
            try (InputStream in = getContentResolver().openInputStream(uri);
                 FileOutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[262144];
                int n;
                long total = 0;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    total += n;
                    long mb = total / (1024 * 1024);
                    if ((total & 0xFFFFFFL) < 262144) {
                        runOnUiThread(() -> statusText.setText("Copiando ISO... " + mb + " MB"));
                    }
                }
                out.flush();
                if (dst.exists()) dst.delete();
                tmp.renameTo(dst);
                runOnUiThread(() -> { isoBtn.setEnabled(true); refreshStatus(); });
            } catch (Exception e) {
                tmp.delete();
                runOnUiThread(() -> { isoBtn.setEnabled(true); statusText.setText("Falha ao copiar ISO: " + e.getMessage()); });
            }
        }).start();
    }

    private void installDriver(Uri uri) {
        driverText.setText("Instalando driver Vulkan...");
        new Thread(() -> {
            File drvDir = new File(getFilesDir(), "driver");
            drvDir.mkdirs();
            File outSo = null;
            try {
                try (InputStream in = getContentResolver().openInputStream(uri);
                     ZipInputStream zin = new ZipInputStream(in)) {
                    ZipEntry ze;
                    while ((ze = zin.getNextEntry()) != null) {
                        if (!ze.isDirectory() && ze.getName().endsWith(".so")) {
                            String base = new File(ze.getName()).getName();
                            File f = new File(drvDir, base);
                            try (FileOutputStream fos = new FileOutputStream(f)) {
                                byte[] buf = new byte[65536];
                                int n;
                                while ((n = zin.read(buf)) > 0) fos.write(buf, 0, n);
                            }
                            if (base.contains("vulkan") || outSo == null) outSo = f;
                        }
                    }
                }
                if (outSo == null) {
                    outSo = new File(drvDir, "vulkan.adreno.so");
                    try (InputStream in = getContentResolver().openInputStream(uri);
                         FileOutputStream fos = new FileOutputStream(outSo)) {
                        byte[] buf = new byte[65536];
                        int n;
                        while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
                    }
                }
                File df = new File(getExternalFilesDir(null), "vulkan_driver.txt");
                try (FileOutputStream fos = new FileOutputStream(df)) {
                    fos.write(outSo.getAbsolutePath().getBytes());
                }
                runOnUiThread(this::refreshStatus);
            } catch (Exception e) {
                runOnUiThread(() -> driverText.setText("Falha no driver: " + e.getMessage()));
            }
        }).start();
    }

    private void launchGame() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            SharedPreferences sp = getSharedPreferences("t3b_launcher", MODE_PRIVATE);
            if (!sp.getBoolean("asked_all_files", false)) {
                sp.edit().putBoolean("asked_all_files", true).apply();
                new AlertDialog.Builder(this)
                        .setTitle("Manter Saves Intactos")
                        .setMessage("Conceda acesso aos arquivos para salvar seus jogos em /sdcard/The3rdBirthday/memstick.\n\nAssim seus saves nunca serão perdidos mesmo se você desinstalar ou atualizar o APK.")
                        .setPositiveButton("Permitir", (d, w) -> {
                            try {
                                Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                                i.setData(Uri.parse("package:" + getPackageName()));
                                startActivity(i);
                            } catch (Exception e) {
                                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                            }
                        })
                        .setNegativeButton("Agora não", (d, w) -> {
                            startActivity(new Intent(this, ThirdBirthdayActivity.class));
                            finish();
                        })
                        .show();
                return;
            }
        }
        startActivity(new Intent(this, ThirdBirthdayActivity.class));
        finish();
    }
}
