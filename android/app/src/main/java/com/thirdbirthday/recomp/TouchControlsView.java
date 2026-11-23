package com.thirdbirthday.recomp;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Build;
import android.util.SparseIntArray;
import android.view.DisplayCutout;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import java.io.IOException;
import java.io.InputStream;

public class TouchControlsView extends View {
    public interface Listener {
        void onTouchState(int buttons, int lx, int ly, float rx, float ry);
        void onTouchLook(float dx, float dy);
        void onOpenModMenu();
    }

    private static final int BTN_SELECT   = 0x0001;
    private static final int BTN_START    = 0x0008;
    private static final int BTN_UP       = 0x0010;
    private static final int BTN_RIGHT    = 0x0020;
    private static final int BTN_DOWN     = 0x0040;
    private static final int BTN_LEFT     = 0x0080;
    private static final int BTN_L        = 0x0100;
    private static final int BTN_R        = 0x0200;
    private static final int BTN_TRIANGLE = 0x1000;
    private static final int BTN_CIRCLE   = 0x2000;
    private static final int BTN_CROSS    = 0x4000;
    private static final int BTN_SQUARE   = 0x8000;

    private static final int ID_NONE   = -1;
    private static final int ID_DPAD   = 99;
    private static final int ID_LSTICK = 100;
    private static final int ID_LOOK   = 101;
    private static final int ID_VISOR  = 102;
    private static final int ID_MOD    = 103;

    // Draggable groups in edit mode
    private static final int GRP_L    = 0;
    private static final int GRP_R    = 1;
    private static final int GRP_BAR  = 2;
    private static final int GRP_DPAD = 3;
    private static final int GRP_FACE = 4;
    private static final int GRP_COUNT = 5;

    private static class SpriteBtn {
        final int bit;
        final boolean circle;
        final Bitmap bmp;
        final RectF rect = new RectF();
        float cx, cy, r;
        boolean pressed;

        SpriteBtn(int bit, boolean circle, Bitmap bmp) {
            this.bit = bit;
            this.circle = circle;
            this.bmp = bmp;
        }
    }

    private final Listener listener;
    private final SharedPreferences prefs;

    private final SpriteBtn[] buttons;
    private final Bitmap bmpDpad, bmpVisor, bmpMod, bmpStickBase, bmpStickKnob;
    private final RectF dpadRect = new RectF();
    private final RectF visorRect = new RectF();
    private final RectF modRect   = new RectF();
    private final RectF barRect   = new RectF();
    private final RectF[] grpBounds = new RectF[GRP_COUNT];
    private final float[] offX = new float[GRP_COUNT];
    private final float[] offY = new float[GRP_COUNT];

    private boolean hudVisible = true;
    private boolean externalPad = false;
    private boolean editMode = false;
    private float hudAlpha = 0.90f;
    private float hudScale = 1.0f;
    private float lookSens = 1.0f;
    private int dragGrp = -1;
    private float dragLastX, dragLastY;

    // Floating free left analog stick (appears only while left thumb is down)
    private float lCx, lCy, lRad, lKnobX, lKnobY;
    private int lPointerId = -1;
    // Native touch camera look on right half
    private int lookPointerId = -1;
    private float lookLastX, lookLastY;

    private int dpadMask = 0;
    private final SparseIntArray pointerMap = new SparseIntArray();

    private final Paint imgPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint pressedPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint editPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private int lastButtons = 0, lastLx = 128, lastLy = 128;

    public TouchControlsView(Context ctx, Listener listener) {
        super(ctx);
        this.listener = listener;
        this.prefs = ctx.getSharedPreferences("cti_touch_layout", Context.MODE_PRIVATE);
        for (int i = 0; i < GRP_COUNT; i++) grpBounds[i] = new RectF();
        loadLayoutPrefs();

        AssetManager am = ctx.getAssets();
        buttons = new SpriteBtn[] {
            new SpriteBtn(BTN_L,        false, loadBmp(am, "hud/btn_l.png")),
            new SpriteBtn(BTN_R,        false, loadBmp(am, "hud/btn_r.png")),
            new SpriteBtn(BTN_SELECT,   true,  loadBmp(am, "hud/btn_select.png")),
            new SpriteBtn(BTN_START,    true,  loadBmp(am, "hud/btn_start.png")),
            new SpriteBtn(BTN_TRIANGLE, true,  loadBmp(am, "hud/btn_tri.png")),
            new SpriteBtn(BTN_SQUARE,   true,  loadBmp(am, "hud/btn_sq.png")),
            new SpriteBtn(BTN_CIRCLE,   true,  loadBmp(am, "hud/btn_cir.png")),
            new SpriteBtn(BTN_CROSS,    true,  loadBmp(am, "hud/btn_cross.png")),
        };
        bmpDpad      = loadBmp(am, "hud/dpad.png");
        bmpVisor     = loadBmp(am, "hud/btn_visor.png");
        bmpMod       = loadBmp(am, "hud/btn_mod.png");
        bmpStickBase = loadBmp(am, "hud/stick_base.png");
        bmpStickKnob = loadBmp(am, "hud/stick_knob.png");

        ColorMatrix cm = new ColorMatrix(new float[] {
            1.35f, 0, 0, 0, 28,
            0, 1.35f, 0, 0, 28,
            0, 0, 1.35f, 0, 28,
            0, 0, 0, 1, 0
        });
        pressedPaint.setColorFilter(new ColorMatrixColorFilter(cm));

        editPaint.setStyle(Paint.Style.STROKE);
        editPaint.setPathEffect(new DashPathEffect(new float[]{12f, 8f}, 0f));
        editPaint.setColor(0xCCFB7185);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setFakeBoldText(true);
    }

    private static Bitmap loadBmp(AssetManager am, String path) {
        try (InputStream in = am.open(path)) {
            return BitmapFactory.decodeStream(in);
        } catch (IOException e) {
            return null;
        }
    }

    public void setExternalControllerConnected(boolean connected) {
        if (this.externalPad != connected) {
            this.externalPad = connected;
            if (connected) clearAllInputs();
            invalidate();
        }
    }

    public void setEditMode(boolean on) {
        this.editMode = on;
        if (on) { hudVisible = true; clearAllInputs(); }
        invalidate();
    }
    public boolean isEditMode() { return editMode; }
    public float getHudAlpha() { return hudAlpha; }
    public float getHudScale() { return hudScale; }
    public float getLookSens() { return lookSens; }

    public void setHudAlpha(float a) {
        hudAlpha = Math.max(0.35f, Math.min(1.0f, a));
        prefs.edit().putFloat("alpha", hudAlpha).apply();
        invalidate();
    }

    public void setHudScale(float s) {
        hudScale = Math.max(0.75f, Math.min(1.30f, s));
        prefs.edit().putFloat("scale", hudScale).apply();
        recomputeGeometry(getWidth(), getHeight());
        invalidate();
    }

    public void setLookSens(float s) {
        lookSens = Math.max(0.3f, Math.min(2.5f, s));
        prefs.edit().putFloat("look_sens", lookSens).apply();
    }

    public void resetLayout() {
        for (int i = 0; i < GRP_COUNT; i++) { offX[i] = 0f; offY[i] = 0f; }
        hudAlpha = 0.90f; hudScale = 1.0f; lookSens = 1.0f;
        saveLayoutPrefs();
        recomputeGeometry(getWidth(), getHeight());
        invalidate();
    }

    public void saveLayoutPrefs() {
        SharedPreferences.Editor e = prefs.edit();
        e.putFloat("alpha", hudAlpha);
        e.putFloat("scale", hudScale);
        e.putFloat("look_sens", lookSens);
        for (int i = 0; i < GRP_COUNT; i++) {
            e.putFloat("ox_" + i, offX[i]);
            e.putFloat("oy_" + i, offY[i]);
        }
        e.apply();
    }

    private void loadLayoutPrefs() {
        hudAlpha = prefs.getFloat("alpha", 0.90f);
        hudScale = prefs.getFloat("scale", 1.0f);
        lookSens = prefs.getFloat("look_sens", 1.0f);
        for (int i = 0; i < GRP_COUNT; i++) {
            offX[i] = prefs.getFloat("ox_" + i, 0f);
            offY[i] = prefs.getFloat("oy_" + i, 0f);
        }
    }

    private float safeLeft = 0f, safeRight = 0f;

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets insets) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            DisplayCutout dc = insets.getDisplayCutout();
            if (dc != null) {
                safeLeft = dc.getSafeInsetLeft();
                safeRight = dc.getSafeInsetRight();
                recomputeGeometry(getWidth(), getHeight());
                invalidate();
            }
        }
        return super.onApplyWindowInsets(insets);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        recomputeGeometry(w, h);
    }

    private void recomputeGeometry(int w, int h) {
        if (w <= 0 || h <= 0) return;
        float dp = getResources().getDisplayMetrics().density * hudScale;
        float baseDp = getResources().getDisplayMetrics().density;
        float pad = 16 * baseDp;
        float padL = Math.max(pad, safeLeft + 8 * baseDp);
        float padR = Math.max(pad, safeRight + 8 * baseDp);
        float topPad = 12 * baseDp;

        lRad = 54 * dp;

        // GRP_L: L Trigger (top-left)
        float trigW = 96 * dp, trigH = 48 * dp;
        float lx0 = padL + offX[GRP_L] * w, ly0 = topPad + offY[GRP_L] * h;
        buttons[0].rect.set(lx0, ly0, lx0 + trigW, ly0 + trigH);
        grpBounds[GRP_L].set(buttons[0].rect);

        // GRP_R: R Trigger (top-right)
        float rx0 = w - padR - trigW + offX[GRP_R] * w, ry0 = topPad + offY[GRP_R] * h;
        buttons[1].rect.set(rx0, ry0, rx0 + trigW, ry0 + trigH);
        grpBounds[GRP_R].set(buttons[1].rect);

        // GRP_BAR: SELECT | VISOR | MOD | START (top-center)
        float sm = 38 * baseDp;
        float gap = 10 * baseDp;
        float barW = sm * 4 + gap * 3;
        float bx = w * 0.5f - barW * 0.5f + offX[GRP_BAR] * w;
        float by = topPad + offY[GRP_BAR] * h;
        setCircleBtn(buttons[2], bx + sm * 0.5f, by + sm * 0.5f, sm * 0.5f); // SELECT
        visorRect.set(bx + sm + gap, by, bx + sm * 2 + gap, by + sm);       // VISOR
        modRect.set(bx + (sm + gap) * 2, by, bx + (sm + gap) * 2 + sm, by + sm); // MOD
        setCircleBtn(buttons[3], bx + (sm + gap) * 3 + sm * 0.5f, by + sm * 0.5f, sm * 0.5f); // START
        barRect.set(bx, by, bx + barW, by + sm);
        grpBounds[GRP_BAR].set(barRect);

        // GRP_DPAD: Red/Black D-Pad (bottom-left or mid-left)
        float dSz = 112 * dp;
        float dCx = padL + 68 * baseDp + offX[GRP_DPAD] * w;
        float dCy = h * 0.44f + offY[GRP_DPAD] * h;
        dpadRect.set(dCx - dSz * 0.5f, dCy - dSz * 0.5f, dCx + dSz * 0.5f, dCy + dSz * 0.5f);
        grpBounds[GRP_DPAD].set(dpadRect);

        // GRP_FACE: Face Buttons Diamond (bottom-right)
        float fRad = 27 * dp;
        float fSpread = 52 * dp;
        float fCx = w - padR - 88 * baseDp + offX[GRP_FACE] * w;
        float fCy = h - pad - 86 * baseDp + offY[GRP_FACE] * h;
        setCircleBtn(buttons[4], fCx, fCy - fSpread, fRad); // Triangle
        setCircleBtn(buttons[5], fCx - fSpread, fCy, fRad); // Square
        setCircleBtn(buttons[6], fCx + fSpread, fCy, fRad); // Circle
        setCircleBtn(buttons[7], fCx, fCy + fSpread, fRad); // Cross
        grpBounds[GRP_FACE].set(fCx - fSpread - fRad, fCy - fSpread - fRad,
                                fCx + fSpread + fRad, fCy + fSpread + fRad);
    }

    private static void setCircleBtn(SpriteBtn b, float cx, float cy, float r) {
        b.cx = cx; b.cy = cy; b.r = r;
        b.rect.set(cx - r, cy - r, cx + r, cy + r);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float baseDp = getResources().getDisplayMetrics().density;
        int alphaInt = Math.round(hudAlpha * 255f);
        imgPaint.setAlpha(alphaInt);
        pressedPaint.setAlpha(255);

        // Always draw MOD icon; hide VISOR and all touch controls when external controller is connected
        drawBmp(canvas, bmpMod, modRect, false);
        if (externalPad && !editMode) return;
        drawBmp(canvas, bmpVisor, visorRect, false);

        if (!hudVisible && !editMode) return;

        // D-Pad
        drawBmp(canvas, bmpDpad, dpadRect, dpadMask != 0);

        // Sprite buttons (L, R, SELECT, START, Tri, Sq, Cir, Cross)
        for (SpriteBtn b : buttons) {
            drawBmp(canvas, b.bmp, b.rect, b.pressed);
        }

        // Floating Left Analog Stick: ONLY visible while touching the left side
        if (lPointerId >= 0) {
            RectF baseR = new RectF(lCx - lRad, lCy - lRad, lCx + lRad, lCy + lRad);
            float kr = lRad * 0.50f;
            RectF knobR = new RectF(lKnobX - kr, lKnobY - kr, lKnobX + kr, lKnobY + kr);
            drawBmp(canvas, bmpStickBase, baseR, false);
            drawBmp(canvas, bmpStickKnob, knobR, true);
        }

        if (editMode) {
            editPaint.setStrokeWidth(2f * baseDp);
            for (RectF r : grpBounds) {
                canvas.drawRoundRect(r.left - 6, r.top - 6, r.right + 6, r.bottom + 6, 10 * baseDp, 10 * baseDp, editPaint);
            }
            textPaint.setTextSize(12 * baseDp);
            textPaint.setColor(0xFFFDA4AF);
            canvas.drawText("MODO EDIÇÃO: Arraste os botões • Toque no ícone Casa (MOD) para salvar",
                    getWidth() * 0.5f, barRect.bottom + 20 * baseDp, textPaint);
        }
    }

    private void drawBmp(Canvas c, Bitmap bmp, RectF dst, boolean pressed) {
        if (bmp != null) {
            c.drawBitmap(bmp, null, dst, pressed ? pressedPaint : imgPaint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        int action = ev.getActionMasked();
        int idx = ev.getActionIndex();
        int pid = ev.getPointerId(idx);

        if (editMode) {
            float x = ev.getX(idx), y = ev.getY(idx);
            switch (action) {
                case MotionEvent.ACTION_DOWN:
                    if (modRect.contains(x, y)) { listener.onOpenModMenu(); return true; }
                    dragGrp = hitGroup(x, y);
                    dragLastX = x; dragLastY = y;
                    return dragGrp >= 0;
                case MotionEvent.ACTION_MOVE:
                    if (dragGrp >= 0 && getWidth() > 0 && getHeight() > 0) {
                        offX[dragGrp] += (ev.getX() - dragLastX) / getWidth();
                        offY[dragGrp] += (ev.getY() - dragLastY) / getHeight();
                        dragLastX = ev.getX(); dragLastY = ev.getY();
                        recomputeGeometry(getWidth(), getHeight());
                        invalidate();
                        return true;
                    }
                    return false;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (dragGrp >= 0) { saveLayoutPrefs(); dragGrp = -1; }
                    return true;
            }
            return true;
        }

        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                float x = ev.getX(idx), y = ev.getY(idx);
                if (modRect.contains(x, y)) {
                    pointerMap.put(pid, ID_MOD);
                    clearAllInputs();
                    listener.onOpenModMenu();
                    return true;
                }
                if (externalPad) return false;
                if (visorRect.contains(x, y)) {
                    pointerMap.put(pid, ID_VISOR);
                    hudVisible = !hudVisible;
                    if (!hudVisible) clearAllInputs();
                    invalidate();
                    return true;
                }
                if (!hudVisible) return false;

                int hit = hitTest(x, y);
                if (hit >= 0) {
                    pointerMap.put(pid, hit);
                    updatePressedFromMap();
                    invalidate();
                    return true;
                }
                if (dpadRect.contains(x, y)) {
                    pointerMap.put(pid, ID_DPAD);
                    updateDpad(x, y);
                    emitState();
                    invalidate();
                    return true;
                }
                // Free floating left analog anywhere on left half of screen!
                if (x < getWidth() * 0.48f && lPointerId < 0) {
                    lPointerId = pid;
                    pointerMap.put(pid, ID_LSTICK);
                    lCx = x; lCy = y; lKnobX = x; lKnobY = y;
                    emitState(); invalidate(); return true;
                }
                // Native touch camera aiming anywhere on right half!
                if (lookPointerId < 0) {
                    lookPointerId = pid;
                    pointerMap.put(pid, ID_LOOK);
                    lookLastX = x; lookLastY = y;
                    return true;
                }
                return false;
            }
            case MotionEvent.ACTION_MOVE: {
                if (!hudVisible) return false;
                boolean changed = false;
                for (int i = 0; i < ev.getPointerCount(); i++) {
                    int p = ev.getPointerId(i), target = pointerMap.get(p, ID_NONE);
                    if (target == ID_LSTICK) {
                        updateStick(ev.getX(i), ev.getY(i)); changed = true;
                    } else if (target == ID_DPAD) {
                        updateDpad(ev.getX(i), ev.getY(i)); changed = true;
                    } else if (target == ID_LOOK) {
                        float nx = ev.getX(i), ny = ev.getY(i);
                        float dx = (nx - lookLastX) * lookSens;
                        float dy = (ny - lookLastY) * lookSens;
                        lookLastX = nx; lookLastY = ny;
                        if (dx != 0f || dy != 0f) listener.onTouchLook(dx, dy);
                    } else if (target >= 0) {
                        int nh = hitTest(ev.getX(i), ev.getY(i));
                        if (nh >= 0 && nh != target) { pointerMap.put(p, nh); updatePressedFromMap(); changed = true; }
                    }
                }
                if (changed) { emitState(); invalidate(); }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_CANCEL: {
                int target = pointerMap.get(pid, ID_NONE);
                pointerMap.delete(pid);
                if (target == ID_LSTICK)    { lPointerId = -1; lKnobX = lCx; lKnobY = lCy; }
                else if (target == ID_DPAD) { dpadMask = 0; }
                else if (target == ID_LOOK) { lookPointerId = -1; }
                else if (target >= 0)       { updatePressedFromMap(); }
                else if (target == ID_NONE) { return false; }
                emitState(); invalidate(); return true;
            }
        }
        return super.onTouchEvent(ev);
    }

    private void updateDpad(float x, float y) {
        float dx = x - dpadRect.centerX(), dy = y - dpadRect.centerY();
        float dead = dpadRect.width() * 0.14f;
        int m = 0;
        if (Math.abs(dx) > dead || Math.abs(dy) > dead) {
            if (dy < -dead && Math.abs(dy) >= Math.abs(dx) * 0.55f) m |= BTN_UP;
            if (dy >  dead && Math.abs(dy) >= Math.abs(dx) * 0.55f) m |= BTN_DOWN;
            if (dx < -dead && Math.abs(dx) >= Math.abs(dy) * 0.55f) m |= BTN_LEFT;
            if (dx >  dead && Math.abs(dx) >= Math.abs(dy) * 0.55f) m |= BTN_RIGHT;
        }
        dpadMask = m;
    }

    private int hitGroup(float x, float y) {
        for (int i = 0; i < GRP_COUNT; i++) {
            RectF r = grpBounds[i];
            if (x >= r.left - 14 && x <= r.right + 14 && y >= r.top - 14 && y <= r.bottom + 14) return i;
        }
        return -1;
    }

    private void clearAllInputs() {
        pointerMap.clear();
        lPointerId = -1; lookPointerId = -1; dpadMask = 0;
        lKnobX = lCx; lKnobY = lCy;
        for (SpriteBtn b : buttons) b.pressed = false;
        emitState();
    }

    private int hitTest(float x, float y) {
        for (int i = 0; i < buttons.length; i++) {
            SpriteBtn b = buttons[i];
            if (b.circle) {
                if (Math.hypot(x - b.cx, y - b.cy) <= b.r * 1.18f) return i;
            } else {
                if (x >= b.rect.left - 8 && x <= b.rect.right + 8 &&
                    y >= b.rect.top - 8 && y <= b.rect.bottom + 8) return i;
            }
        }
        return -1;
    }

    private void updatePressedFromMap() {
        for (SpriteBtn b : buttons) b.pressed = false;
        for (int i = 0; i < pointerMap.size(); i++) {
            int t = pointerMap.valueAt(i);
            if (t >= 0 && t < buttons.length) buttons[t].pressed = true;
        }
        emitState();
    }

    private void updateStick(float x, float y) {
        float dx = x - lCx, dy = y - lCy, d = (float) Math.hypot(dx, dy);
        if (d > lRad) { dx = dx / d * lRad; dy = dy / d * lRad; }
        lKnobX = lCx + dx; lKnobY = lCy + dy;
    }

    private void emitState() {
        int mask = dpadMask;
        for (SpriteBtn b : buttons) if (b.pressed) mask |= b.bit;
        int lx = 128, ly = 128;
        if (lPointerId >= 0) {
            lx = Math.max(0, Math.min(255, Math.round(128f + ((lKnobX - lCx) / Math.max(1f, lRad)) * 127f)));
            ly = Math.max(0, Math.min(255, Math.round(128f + ((lKnobY - lCy) / Math.max(1f, lRad)) * 127f)));
        }
        if (mask != lastButtons || lx != lastLx || ly != lastLy) {
            lastButtons = mask; lastLx = lx; lastLy = ly;
            listener.onTouchState(mask, lx, ly, 0f, 0f);
        }
    }
}
