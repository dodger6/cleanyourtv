package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/**
 * home_panel: a small weather icon drawn from Home Assistant's condition names (sunny, clear-night,
 * partlycloudy, cloudy, rainy, pouring, lightning, lightning-rainy, snowy, snowy-rainy, hail, fog, windy,
 * windy-variant, exceptional). Plain shapes, so it stays crisp at any size and needs no assets.
 */
final class PanelGlyph extends View {
    private static final int SUN = 0xFFFFCF5C;
    private static final int MOON = 0xFFE6EAF5;
    private static final int CLOUD = 0xF2FFFFFF;
    private static final int CLOUD_DARK = 0xFFB9C2D0;
    private static final int RAIN = 0xFF79C2FF;
    private static final int BOLT = 0xFFFFD84D;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Path cut = new Path();
    private String condition = "";
    private boolean night;

    PanelGlyph(Context ctx) {
        super(ctx);
        setFocusable(false);
    }

    void set(String condition, boolean night) {
        String c = condition == null ? "" : condition;
        if (c.equals(this.condition) && night == this.night) return;
        this.condition = c;
        this.night = night;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float s = Math.min(getWidth(), getHeight());
        if (s <= 0) return;
        c.save();
        c.translate((getWidth() - s) / 2f, (getHeight() - s) / 2f);
        switch (condition) {
            case "sunny":
                sun(c, s * 0.5f, s * 0.5f, s * 0.22f, true);
                break;
            case "clear-night":
                moon(c, s * 0.5f, s * 0.5f, s * 0.3f);
                break;
            case "partlycloudy":
                if (night) moon(c, s * 0.62f, s * 0.36f, s * 0.22f);
                else sun(c, s * 0.62f, s * 0.36f, s * 0.15f, true);
                cloud(c, s, s * 0.08f, CLOUD);
                break;
            case "cloudy":
                cloud(c, s * 0.8f, s * 0.0f, CLOUD_DARK);
                c.save();
                c.translate(s * 0.2f, s * 0.12f);
                cloud(c, s * 0.8f, 0, CLOUD);
                c.restore();
                break;
            case "rainy":
            case "pouring":
            case "snowy-rainy":
            case "hail":
                cloud(c, s, -s * 0.12f, CLOUD);
                drops(c, s, "pouring".equals(condition) ? 4 : 3);
                break;
            case "lightning":
            case "lightning-rainy":
                cloud(c, s, -s * 0.12f, CLOUD);
                bolt(c, s);
                break;
            case "snowy":
                cloud(c, s, -s * 0.12f, CLOUD);
                flakes(c, s);
                break;
            case "fog":
            case "windy":
            case "windy-variant":
                lines(c, s);
                break;
            default:
                cloud(c, s, 0, CLOUD);
        }
        c.restore();
    }

    private void sun(Canvas c, float cx, float cy, float r, boolean rays) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(SUN);
        c.drawCircle(cx, cy, r, p);
        if (!rays) return;
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(r * 0.28f);
        for (int i = 0; i < 8; i++) {
            double a = Math.PI / 4 * i;
            float cos = (float) Math.cos(a), sin = (float) Math.sin(a);
            c.drawLine(cx + cos * r * 1.45f, cy + sin * r * 1.45f, cx + cos * r * 1.95f, cy + sin * r * 1.95f, p);
        }
    }

    private void moon(Canvas c, float cx, float cy, float r) {
        path.reset();
        path.addCircle(cx, cy, r, Path.Direction.CW);
        cut.reset();
        cut.addCircle(cx + r * 0.55f, cy - r * 0.4f, r * 0.85f, Path.Direction.CW);
        path.op(cut, Path.Op.DIFFERENCE);
        p.setStyle(Paint.Style.FILL);
        p.setColor(MOON);
        c.drawPath(path, p);
    }

    /** A cloud in the lower part of an s-sized box, moved down by dy. */
    private void cloud(Canvas c, float s, float dy, int color) {
        path.reset();
        float base = s * 0.78f + dy;
        path.addCircle(s * 0.36f, base - s * 0.16f, s * 0.17f, Path.Direction.CW);
        path.addCircle(s * 0.55f, base - s * 0.24f, s * 0.21f, Path.Direction.CW);
        path.addCircle(s * 0.74f, base - s * 0.13f, s * 0.14f, Path.Direction.CW);
        path.addRoundRect(s * 0.2f, base - s * 0.17f, s * 0.88f, base, s * 0.085f, s * 0.085f, Path.Direction.CW);
        path.setFillType(Path.FillType.WINDING);
        p.setStyle(Paint.Style.FILL);
        p.setColor(color);
        c.drawPath(path, p);
    }

    private void drops(Canvas c, float s, int n) {
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(s * 0.06f);
        p.setColor(RAIN);
        float step = s * 0.5f / (n - 1);
        for (int i = 0; i < n; i++) {
            float x = s * 0.3f + step * i;
            c.drawLine(x + s * 0.04f, s * 0.74f, x - s * 0.03f, s * 0.92f, p);
        }
    }

    private void flakes(Canvas c, float s) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFFFFFFFF);
        for (int i = 0; i < 3; i++) c.drawCircle(s * (0.32f + 0.18f * i), s * (i % 2 == 0 ? 0.8f : 0.9f), s * 0.045f, p);
    }

    private void bolt(Canvas c, float s) {
        path.reset();
        path.moveTo(s * 0.55f, s * 0.62f);
        path.lineTo(s * 0.4f, s * 0.82f);
        path.lineTo(s * 0.52f, s * 0.82f);
        path.lineTo(s * 0.44f, s * 0.98f);
        path.lineTo(s * 0.66f, s * 0.74f);
        path.lineTo(s * 0.54f, s * 0.74f);
        path.close();
        p.setStyle(Paint.Style.FILL);
        p.setColor(BOLT);
        c.drawPath(path, p);
    }

    private void lines(Canvas c, float s) {
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(s * 0.07f);
        p.setColor(CLOUD);
        c.drawLine(s * 0.18f, s * 0.38f, s * 0.7f, s * 0.38f, p);
        c.drawLine(s * 0.28f, s * 0.56f, s * 0.84f, s * 0.56f, p);
        c.drawLine(s * 0.18f, s * 0.74f, s * 0.62f, s * 0.74f, p);
    }
}
