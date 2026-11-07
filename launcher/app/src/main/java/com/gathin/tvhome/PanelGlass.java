package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.os.Build;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;

/**
 * home_panel: a clear glass card. Nothing is blurred: the live camera shows through sharply under a light
 * smoke tint, a diagonal sheen, a hairline border with a brighter top edge, rounded corners and a soft
 * shadow. Everything is a drawRoundRect (no clipPath, which the MT5895's renderer does in software, and no
 * read-back of the video), so the card costs a few fills per frame.
 */
final class PanelGlass extends FrameLayout {

    private final float density;
    private final float radius;
    private final float dim;
    private final Paint tint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private int lastW = -1, lastH = -1;

    /** radiusDp < 0: a pill (half the height). dim: 0..1, a darker tint for busier scenes (glass_dim). */
    PanelGlass(Context ctx, float radiusDp, float dim) {
        super(ctx);
        this.density = ctx.getResources().getDisplayMetrics().density;
        this.radius = radiusDp * density;
        this.dim = Math.max(0f, Math.min(1f, dim));
        setWillNotDraw(false);
        setClipToPadding(false);
        tint.setStyle(Paint.Style.FILL);
        // Translucent smoke: enough to lift white text off a bright porch, low enough to see the scene.
        tint.setColor(((int) (255 * (0.16f + 0.5f * this.dim)) << 24) | 0x0A0F1A);
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(Math.max(1f, density * 0.75f));
        border.setColor(0x40FFFFFF);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(Math.max(1f, density * 0.9f));
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), cornerRadius());
                // Outline.setAlpha is API 31. On Android 11 the shadow stays at the platform default.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) o.setAlpha(0.35f);
            }
        });
        setElevation(6 * density);
        // Cache each card (and its text) in a GPU layer: the content changes once a minute, but the window is
        // redrawn every frame of a dock focus move, and re-issuing every card's fills and text over the video
        // took the D-pad from ~5% to ~80% janky frames on the MT5895.
        setLayerType(LAYER_TYPE_HARDWARE, null);
    }

    private float cornerRadius() {
        return radius < 0 ? getHeight() / 2f : radius;
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        float r = cornerRadius();
        if (w != lastW || h != lastH) {
            lastW = w;
            lastH = h;
            // A faint diagonal sheen, brightest at the top left, as light catches a pane.
            sheen.setShader(new LinearGradient(0, 0, w * 0.6f, h, new int[]{0x26FFFFFF, 0x0AFFFFFF, 0x03FFFFFF},
                    new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
            rim.setShader(new LinearGradient(0, 0, 0, Math.min(h, 46 * density), 0xA6FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        }
        rect.set(0, 0, w, h);
        c.drawRoundRect(rect, r, r, tint);
        c.drawRoundRect(rect, r, r, sheen);
        float in = border.getStrokeWidth() / 2f;
        rect.inset(in, in);
        c.drawRoundRect(rect, r - in, r - in, border);
        c.drawRoundRect(rect, r - in, r - in, rim);
    }
}
