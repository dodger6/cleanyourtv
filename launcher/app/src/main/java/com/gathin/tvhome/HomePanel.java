package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.format.DateFormat;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * home_panel (config.json): a Home Assistant panel behind Home. A live camera fills the screen (PanelCamera),
 * clear glass cards above the dock (the camera shows through, nothing is blurred) show the weather station's current readings and the forecast, and chips
 * at the top show a lock and the NWS alerts. Opt-in: with no enabled home_panel block, create() returns null
 * and Home is exactly as before.
 *
 * Nothing here takes focus; the dock and the grid keep the D-pad. Data: entity states every state_seconds
 * (default 60), forecasts every forecast_minutes (default 30). When Home Assistant can't be reached the last
 * values stay up and a small "Offline" chip says since when.
 */
final class HomePanel implements PanelCamera.Listener {
    private static final String TAG = "HomePanel";

    private static final int TEXT = 0xFFF5F1EA;
    private static final int TEXT_2 = 0xFFE2DACD;
    private static final int TEXT_3 = 0xFFC9BFAF;
    private static final int GOOD = 0xFFA9ECB2;
    private static final int WARN = 0xFFFFC76B;
    private static final int BAD = 0xFFFF8A80;
    private static final int MUTED = 0xFFB8B8B8;

    /** The home_panel block, read once at create. */
    static final class Settings {
        String haUrl, token, camera, cameraName, cameraMode, weather, stationName;
        String temperature, feelsLike, humidity, windSpeed, windCardinal, windDescription, rainToday, pressure,
                pressureTrend, dewPoint, uvIndex, lock, lockName, alerts;
        int snapshotSeconds, stateSeconds, forecastMinutes;
        float glassDim, focusY;

        static Settings parse(JSONObject o) {
            if (o == null || !o.optBoolean("enabled", false)) return null;
            Settings s = new Settings();
            s.haUrl = o.optString("ha_url", "").trim();
            s.token = o.optString("ha_token", "").trim();
            if (s.haUrl.isEmpty() || s.token.isEmpty()) return null;
            s.camera = str(o, "camera");
            s.cameraName = o.optString("camera_name", "Camera");
            s.cameraMode = o.optString("camera_mode", "auto");
            s.weather = str(o, "weather");
            s.stationName = o.optString("station_name", "");
            s.temperature = str(o, "temperature");
            s.feelsLike = str(o, "feels_like");
            s.humidity = str(o, "humidity");
            s.windSpeed = str(o, "wind_speed");
            s.windCardinal = str(o, "wind_cardinal");
            s.windDescription = str(o, "wind_description");
            s.rainToday = str(o, "rain_today");
            s.pressure = str(o, "pressure");
            s.pressureTrend = str(o, "pressure_trend");
            s.dewPoint = str(o, "dew_point");
            s.uvIndex = str(o, "uv_index");
            s.lock = str(o, "lock");
            s.lockName = o.optString("lock_name", "Door");
            s.alerts = str(o, "alerts");
            s.snapshotSeconds = clamp(o.optInt("snapshot_seconds", 3), 1, 60);
            s.stateSeconds = clamp(o.optInt("state_seconds", 60), 10, 3600);
            s.forecastMinutes = clamp(o.optInt("forecast_minutes", 30), 5, 720);
            s.glassDim = clamp(o.optInt("glass_dim", 0), 0, 100) / 100f;
            s.focusY = clamp(o.optInt("camera_focus_y", 50), 0, 100) / 100f;
            return s;
        }

        private static String str(JSONObject o, String k) {
            String v = o.optString(k, "").trim();
            return v.isEmpty() ? null : v;
        }

        private static int clamp(int v, int lo, int hi) {
            return Math.max(lo, Math.min(hi, v));
        }
    }

    /** The home_panel block from the theme's config.json (Config.load keeps a copy in files/), or null. */
    static Settings readSettings(Context ctx) {
        for (File f : new File[]{new File(ctx.getFilesDir(), Config.FILE_NAME), Config.dir(ctx) == null ? null
                : new File(Config.dir(ctx), Config.FILE_NAME)}) {
            if (f == null || !f.isFile() || f.length() > 64 * 1024) continue;
            try (InputStream in = new FileInputStream(f)) {
                byte[] buf = new byte[(int) f.length()];
                int n = 0, r;
                while (n < buf.length && (r = in.read(buf, n, buf.length - n)) > 0) n += r;
                String text = new String(buf, 0, n, StandardCharsets.UTF_8);
                if (text.startsWith("\uFEFF")) text = text.substring(1);
                return Settings.parse(new JSONObject(text).optJSONObject("home_panel"));
            } catch (Exception e) {
                Log.w(TAG, "home_panel: " + e.getMessage());
            }
        }
        return null;
    }

    /** Builds the panel into root (just above the sky, under the header and the grid); null when it is off. */
    static HomePanel create(Context ctx, ViewGroup root, View sky, RecyclerView grid, View header, View topButtons) {
        Settings s = readSettings(ctx);
        if (s == null) return null;
        return new HomePanel(ctx, s, root, sky, grid, header, topButtons);
    }

    private final Context ctx;
    private final Settings s;
    private final ViewGroup root;
    private final RecyclerView grid;
    private final View header;
    private final View topButtons;
    private final float density;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "HomePanel");
        t.setPriority(Thread.MIN_PRIORITY + 1);
        return t;
    });
    private final HaClient ha;
    private final PanelCamera camera;
    private final View scrim;
    private final FrameLayout overlay;
    private final Typeface light, regular, medium;

    private final LinearLayout cards;
    private final View liveRow;
    private final TextView liveBadge;
    private final LinearLayout chips;
    private TextView lockChip, alertChip, offlineChip;
    private PanelGlass offlineGlass;

    // Weather card.
    private PanelGlyph nowGlyph;
    private TextView nowTemp, nowCondition, nowRange, nowStation;
    private final TextView[] metricValue = new TextView[6];
    // Forecast card.
    private final List<TextView> hourLabel = new ArrayList<>(), hourTemp = new ArrayList<>();
    private final List<PanelGlyph> hourGlyph = new ArrayList<>();
    private final List<View> hourCell = new ArrayList<>();
    private final List<TextView> dayLabel = new ArrayList<>(), dayTemp = new ArrayList<>();
    private final List<PanelGlyph> dayGlyph = new ArrayList<>();

    private boolean running;
    private int session;
    private final Map<String, JSONObject> states = new HashMap<>();
    private JSONArray hourly, daily;
    private long lastForecastAt;
    private long offlineSince;   // wall clock ms of the first failed refresh, 0 while fine
    private int scrollShift;
    private boolean cardsTooSmall;

    private HomePanel(Context ctx, Settings s, ViewGroup root, View sky, RecyclerView grid, View header, View topButtons) {
        this.ctx = ctx;
        this.s = s;
        this.root = root;
        this.grid = grid;
        this.header = header;
        this.topButtons = topButtons;
        this.density = ctx.getResources().getDisplayMetrics().density;
        // fonts.xml points these at bundled Outfit files (or a system family). getString() cannot load a font.
        this.light = UiFont.get(ctx, R.string.font_light);
        this.regular = UiFont.get(ctx, R.string.font_regular);
        this.medium = UiFont.get(ctx, R.string.font_medium);
        ha = new HaClient(s.haUrl, s.token);

        int at = Math.max(0, root.indexOfChild(sky) + 1);
        camera = s.camera == null ? null
                : new PanelCamera(ctx, ha, s.camera, s.cameraMode, s.snapshotSeconds, s.focusY, io, this);
        if (camera != null) root.addView(camera, at++, new FrameLayout.LayoutParams(-1, -1));

        // The design's hero shading: darker under the clock and toward the dock, the porch clear in between.
        scrim = new View(ctx);
        scrim.setBackground(verticalScrim());
        root.addView(scrim, at++, new FrameLayout.LayoutParams(-1, -1));

        overlay = new FrameLayout(ctx);
        overlay.setFocusable(false);
        overlay.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        overlay.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        overlay.setClipChildren(false);
        root.addView(overlay, at, new FrameLayout.LayoutParams(-1, -1));

        // "LIVE · Doorbell" under the clock.
        LinearLayout live = new LinearLayout(ctx);
        live.setOrientation(LinearLayout.HORIZONTAL);
        live.setGravity(Gravity.CENTER_VERTICAL);
        liveBadge = text(7.5f, Color.WHITE, medium);
        liveBadge.setText("LIVE");
        liveBadge.setLetterSpacing(0.08f);
        liveBadge.setPadding(dp(6), dp(3), dp(6), dp(3));
        liveBadge.setTypeface(Typeface.create(medium, Typeface.BOLD));
        setLiveBadge(false, false);
        live.addView(liveBadge, new LinearLayout.LayoutParams(-2, -2));
        PanelGlass camName = pill();
        TextView camText = text(9f, TEXT, regular);
        camText.setText(s.cameraName);
        camText.setPadding(dp(8), dp(3), dp(8), dp(3));
        camName.addView(camText);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = dp(5);
        live.addView(camName, lp);
        liveRow = live;
        if (camera != null) overlay.addView(liveRow, new FrameLayout.LayoutParams(-2, -2));

        // Chips: lock and alerts, plus the offline note when Home Assistant can't be reached.
        chips = new LinearLayout(ctx);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setClipChildren(false);
        if (s.lock != null) lockChip = chip();
        if (s.alerts != null) alertChip = chip();
        offlineChip = chip();
        offlineGlass = (PanelGlass) offlineChip.getParent();
        offlineGlass.setVisibility(View.GONE);
        overlay.addView(chips, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.RIGHT));

        // The two cards above the dock.
        cards = new LinearLayout(ctx);
        cards.setOrientation(LinearLayout.HORIZONTAL);
        cards.setClipChildren(false);
        if (s.weather != null || s.temperature != null) {
            cards.addView(weatherCard(), new LinearLayout.LayoutParams(0, -1, 0.455f));
        }
        if (s.weather != null) {
            LinearLayout.LayoutParams fl = new LinearLayout.LayoutParams(0, -1, 0.545f);
            if (cards.getChildCount() > 0) fl.leftMargin = dp(14);
            cards.addView(forecastCard(), fl);
        }
        overlay.addView(cards, new FrameLayout.LayoutParams(-1, 0));

        bindAll();
        placeOnLayout();
    }

    // ---- building ----

    private int dp(float v) {
        return Math.round(v * density);
    }

    private TextView text(float sp, int color, Typeface tf) {
        TextView t = new TextView(ctx);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(tf);
        t.setIncludeFontPadding(false);
        t.setSingleLine(true);
        t.setFocusable(false);
        // Clear glass over a live picture: a soft shadow keeps text legible on a bright porch or snow.
        t.setShadowLayer(dp(3), 0, dp(1), 0x8C000000);
        return t;
    }

    private PanelGlass glass(float radiusDp) {
        PanelGlass g = new PanelGlass(ctx, radiusDp, s.glassDim);
        return g;
    }

    private PanelGlass pill() {
        return glass(-1);
    }

    private TextView chip() {
        PanelGlass g = pill();
        TextView t = text(9f, TEXT, regular);
        t.setPadding(dp(10), dp(5), dp(10), dp(5));
        g.addView(t);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        if (chips.getChildCount() > 0) lp.leftMargin = dp(6);
        chips.addView(g, lp);
        return t;
    }

    private GradientDrawable verticalScrim() {
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{
                0x80060812, 0x00060812, 0x00060812, 0x26060812, 0xB3060812});
        d.setGradientType(GradientDrawable.LINEAR_GRADIENT);
        return d;
    }

    private View weatherCard() {
        PanelGlass card = glass(14);
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), dp(12), dp(16), dp(12));
        card.addView(col, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout now = new LinearLayout(ctx);
        now.setOrientation(LinearLayout.HORIZONTAL);
        now.setGravity(Gravity.CENTER_VERTICAL);
        nowGlyph = new PanelGlyph(ctx);
        now.addView(nowGlyph, new LinearLayout.LayoutParams(dp(44), dp(44)));
        nowTemp = text(46f, Color.WHITE, light);
        nowTemp.setShadowLayer(dp(6), 0, dp(1), 0x8C000000);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-2, -2);
        tl.leftMargin = dp(10);
        now.addView(nowTemp, tl);
        LinearLayout words = new LinearLayout(ctx);
        words.setOrientation(LinearLayout.VERTICAL);
        nowCondition = text(13.5f, TEXT, regular);
        nowRange = text(9.5f, TEXT_2, regular);
        nowStation = text(7.5f, TEXT_3, regular);
        words.addView(nowCondition);
        LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(-2, -2);
        wl.topMargin = dp(4);
        words.addView(nowRange, wl);
        LinearLayout.LayoutParams wl2 = new LinearLayout.LayoutParams(-2, -2);
        wl2.topMargin = dp(4);
        words.addView(nowStation, wl2);
        LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(0, -2, 1f);
        wp.leftMargin = dp(12);
        now.addView(words, wp);
        col.addView(now, new LinearLayout.LayoutParams(-1, -2));

        String[] keys = {"FEELS LIKE", "HUMIDITY", "WIND", "RAIN TODAY", "PRESSURE", "DEW POINT"};
        for (int row = 0; row < 2; row++) {
            LinearLayout r = new LinearLayout(ctx);
            r.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, 0, 1f);
            rl.topMargin = dp(row == 0 ? 10 : 6);
            col.addView(r, rl);
            for (int c = 0; c < 3; c++) {
                int i = row * 3 + c;
                LinearLayout m = new LinearLayout(ctx);
                m.setOrientation(LinearLayout.VERTICAL);
                m.setGravity(Gravity.CENTER_VERTICAL);
                m.setPadding(dp(8), dp(4), dp(6), dp(4));
                m.setBackground(tileBackground(8));
                TextView k = text(6.5f, TEXT_3, medium);
                k.setLetterSpacing(0.12f);
                k.setText(keys[i]);
                m.addView(k);
                TextView v = text(13.5f, TEXT, regular);
                v.setEllipsize(android.text.TextUtils.TruncateAt.END);
                LinearLayout.LayoutParams vl = new LinearLayout.LayoutParams(-1, -2);
                vl.topMargin = dp(2);
                m.addView(v, vl);
                metricValue[i] = v;
                LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(0, -1, 1f);
                if (c > 0) ml.leftMargin = dp(6);
                r.addView(m, ml);
            }
        }
        return card;
    }

    /** The inner glass tiles: a faint fill with a hairline that is brighter along the top. */
    private android.graphics.drawable.Drawable tileBackground(float radiusDp) {
        GradientDrawable fill = new GradientDrawable();
        fill.setCornerRadius(dp(radiusDp));
        fill.setColor(0x12FFFFFF);
        fill.setStroke(Math.max(1, dp(0.5f)), 0x24FFFFFF);
        GradientDrawable top = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x26FFFFFF, 0x00FFFFFF});
        top.setCornerRadius(dp(radiusDp));
        return new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{fill, top});
    }

    private View forecastCard() {
        PanelGlass card = glass(14);
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(14), dp(10), dp(14), dp(10));
        card.addView(col, new FrameLayout.LayoutParams(-1, -1));
        TextView lbl = text(7.5f, TEXT_2, medium);
        lbl.setLetterSpacing(0.16f);
        lbl.setText("NEXT HOURS");
        lbl.setAlpha(0.85f);
        col.addView(lbl);

        LinearLayout hours = new LinearLayout(ctx);
        hours.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(-1, 0, 1.15f);
        hl.topMargin = dp(6);
        col.addView(hours, hl);
        for (int i = 0; i < 8; i++) {
            LinearLayout h = new LinearLayout(ctx);
            h.setOrientation(LinearLayout.VERTICAL);
            h.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.CENTER_VERTICAL);
            if (i == 0) h.setBackground(tileBackground(9));
            TextView t = text(8.5f, TEXT_3, regular);
            PanelGlyph g = new PanelGlyph(ctx);
            TextView v = text(13f, TEXT, regular);
            h.addView(t);
            LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(dp(22), dp(22));
            gl.topMargin = dp(3);
            gl.bottomMargin = dp(3);
            h.addView(g, gl);
            h.addView(v);
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, -1, 1f);
            cl.leftMargin = i == 0 ? 0 : dp(3);
            hours.addView(h, cl);
            hourCell.add(h);
            hourLabel.add(t);
            hourGlyph.add(g);
            hourTemp.add(v);
        }

        View rule = new View(ctx);
        rule.setBackgroundColor(0x24FFFFFF);
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, Math.max(1, dp(0.5f)));
        rl.topMargin = dp(7);
        col.addView(rule, rl);

        LinearLayout days = new LinearLayout(ctx);
        days.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(-1, 0, 1f);
        dl.topMargin = dp(6);
        col.addView(days, dl);
        for (int i = 0; i < 6; i++) {
            LinearLayout d = new LinearLayout(ctx);
            d.setOrientation(LinearLayout.VERTICAL);
            d.setGravity(Gravity.CENTER);
            TextView n = text(8.5f, TEXT_3, regular);
            PanelGlyph g = new PanelGlyph(ctx);
            TextView v = text(11f, TEXT, regular);
            d.addView(n);
            LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(dp(17), dp(17));
            gl.topMargin = dp(2);
            gl.bottomMargin = dp(2);
            d.addView(g, gl);
            d.addView(v);
            days.addView(d, new LinearLayout.LayoutParams(0, -1, 1f));
            dayLabel.add(n);
            dayGlyph.add(g);
            dayTemp.add(v);
        }
        return card;
    }

    // ---- placement: follow the header and the dock ----

    private void placeOnLayout() {
        View.OnLayoutChangeListener l = (v, a, b, c, d, e, f, g, h) -> root.post(this::place);
        grid.addOnLayoutChangeListener(l);
        header.addOnLayoutChangeListener(l);
        root.addOnLayoutChangeListener(l);
        // The cards ride up with the rows and fade out by the time they reach the clock (like game_day's panel).
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                applyScroll(rv.canScrollVertically(-1) ? rv.computeVerticalScrollOffset() : 0);
            }
        });
    }

    private final int[] tmpA = new int[2], tmpB = new int[2];

    private int yIn(View v) {
        v.getLocationInWindow(tmpA);
        root.getLocationInWindow(tmpB);
        return tmpA[1] - tmpB[1] - Math.round(v.getTranslationY());
    }

    private int xIn(View v) {
        v.getLocationInWindow(tmpA);
        root.getLocationInWindow(tmpB);
        return tmpA[0] - tmpB[0];
    }

    private void place() {
        if (root.getWidth() == 0 || grid.getWidth() == 0) return;
        int headerBottom = yIn(header) + header.getHeight();
        // LIVE tag under the clock, chips under the top buttons on the right.
        FrameLayout.LayoutParams ll = (FrameLayout.LayoutParams) liveRow.getLayoutParams();
        ll.leftMargin = xIn(header) + dp(4);
        ll.topMargin = headerBottom + dp(8);
        liveRow.setLayoutParams(ll);
        FrameLayout.LayoutParams cl = (FrameLayout.LayoutParams) chips.getLayoutParams();
        cl.rightMargin = root.getWidth() - (xIn(topButtons) + topButtons.getWidth());
        cl.topMargin = headerBottom + dp(8);
        chips.setLayoutParams(cl);

        // Cards: between the tags and the dock, as wide as the dock's tiles.
        int dockTop = yIn(grid) + grid.getPaddingTop();
        int left = xIn(grid) + grid.getPaddingLeft() + dp(6);
        int right = root.getWidth() - (xIn(grid) + grid.getWidth() - grid.getPaddingRight()) + dp(6);
        int bottom = dockTop - dp(10);
        int top = Math.max(headerBottom + dp(40), bottom - dp(200));
        FrameLayout.LayoutParams kl = (FrameLayout.LayoutParams) cards.getLayoutParams();
        int height = bottom - top;
        cardsTooSmall = height < dp(120);
        if (kl.leftMargin != left || kl.rightMargin != right || kl.topMargin != top || kl.height != height) {
            kl.leftMargin = left;
            kl.rightMargin = right;
            kl.topMargin = top;
            kl.height = Math.max(0, height);
            cards.setLayoutParams(kl);
        }
        applyScroll(grid.canScrollVertically(-1) ? grid.computeVerticalScrollOffset() : 0);
    }

    private void applyScroll(int shift) {
        scrollShift = shift;
        cards.setTranslationY(-shift);
        int room = Math.max(1, cards.getTop() - (yIn(header) + header.getHeight()));
        float a = Math.max(0f, 1f - shift / (float) room);
        cards.setAlpha(a);
        cards.setVisibility(cardsTooSmall ? View.GONE : a <= 0.01f ? View.INVISIBLE : View.VISIBLE);
    }

    // ---- lifecycle (MainActivity) ----

    void start() {
        if (running) return;
        running = true;
        session++;
        if (camera != null) camera.start();
        main.post(stateTick);
    }

    void stop() {
        if (!running) return;
        running = false;
        session++;
        main.removeCallbacks(stateTick);
        if (camera != null) camera.stop();
    }

    void trimMemory() {
        if (camera != null) camera.release();
    }

    void destroy() {
        stop();
        if (camera != null) camera.release();
        io.shutdownNow();
    }

    // ---- PanelCamera.Listener ----

    @Override
    public void onCameraState(boolean live, boolean snapshot) {
        setLiveBadge(live, snapshot);
    }

    private void setLiveBadge(boolean live, boolean snapshot) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(20));
        if (live) {
            liveBadge.setText("LIVE");
            bg.setColor(0xD9D64545);
        } else if (snapshot) {
            liveBadge.setText("STILL");
            bg.setColor(0xB3555B66);
        } else {
            liveBadge.setText("···");
            bg.setColor(0x99555B66);
        }
        liveBadge.setBackground(bg);
    }

    // ---- data ----

    private final Runnable stateTick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            refresh();
            main.postDelayed(this, s.stateSeconds * 1000L);
        }
    };

    private void refresh() {
        final int my = session;
        final boolean wantForecast = s.weather != null
                && (hourly == null || SystemClock.elapsedRealtime() - lastForecastAt >= s.forecastMinutes * 60_000L);
        final List<String> ids = new ArrayList<>();
        for (String id : new String[]{s.weather, s.temperature, s.feelsLike, s.humidity, s.windSpeed, s.windCardinal,
                s.windDescription, s.rainToday, s.pressure, s.pressureTrend, s.dewPoint, s.uvIndex, s.lock, s.alerts,
                "sun.sun"}) {
            if (id != null && !ids.contains(id)) ids.add(id);
        }
        io.execute(() -> {
            Map<String, JSONObject> got = new HashMap<>();
            boolean ok = true;
            for (String id : ids) {
                try {
                    got.put(id, ha.state(id));
                } catch (HaClient.HttpError e) {
                    Log.w(TAG, id + ": " + e.getMessage());   // a missing entity; the rest still count
                } catch (Exception e) {
                    ok = false;   // unreachable: don't wait out a timeout per entity
                    Log.w(TAG, "Home Assistant unreachable: " + e.getClass().getSimpleName());
                    break;
                }
            }
            JSONArray h = null, d = null;
            if (wantForecast && ok) {
                try {
                    JSONObject fh = ha.forecast(s.weather, "hourly");
                    JSONObject fd = ha.forecast(s.weather, "daily");
                    h = fh == null ? null : fh.optJSONArray("forecast");
                    d = fd == null ? null : fd.optJSONArray("forecast");
                } catch (Exception e) {
                    Log.w(TAG, "forecast: " + e.getMessage());
                }
            }
            final boolean reached = ok && !got.isEmpty();
            final JSONArray fh = h, fd = d;
            main.post(() -> {
                if (my != session) return;
                states.putAll(got);
                if (fh != null) {
                    hourly = fh;
                    lastForecastAt = SystemClock.elapsedRealtime();
                }
                if (fd != null) daily = fd;
                if (reached) offlineSince = 0;
                else if (offlineSince == 0) offlineSince = System.currentTimeMillis();
                bindAll();
            });
        });
    }

    private JSONObject st(String id) {
        return id == null ? null : states.get(id);
    }

    private static boolean usable(JSONObject o) {
        if (o == null) return false;
        String v = o.optString("state", "");
        return !v.isEmpty() && !"unavailable".equals(v) && !"unknown".equals(v);
    }

    private Double num(String id) {
        JSONObject o = st(id);
        if (!usable(o)) return null;
        try {
            return Double.parseDouble(o.optString("state"));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String unit(String id, String fallback) {
        JSONObject o = st(id);
        JSONObject a = o == null ? null : o.optJSONObject("attributes");
        String u = a == null ? null : a.optString("unit_of_measurement", null);
        return u == null || u.isEmpty() ? fallback : u;
    }

    private String word(String id) {
        JSONObject o = st(id);
        return usable(o) ? o.optString("state") : null;
    }

    private JSONObject weatherAttrs() {
        JSONObject w = st(s.weather);
        return w == null ? null : w.optJSONObject("attributes");
    }

    private static String round(Double v) {
        return v == null ? "–" : String.valueOf(Math.round(v));
    }

    /** Decimals by unit: rain and inHg to hundredths, mm and hPa sensibly. */
    private static String fmt(Double v, String unit) {
        if (v == null) return "–";
        String u = unit == null ? "" : unit;
        if (u.equals("in") || u.equals("inHg")) return String.format(Locale.US, "%.2f", v);
        if (u.equals("mm")) return String.format(Locale.US, "%.1f", v);
        return String.valueOf(Math.round(v));
    }

    private CharSequence valueWithUnit(String value, String small) {
        SpannableStringBuilder b = new SpannableStringBuilder(value);
        if (small != null && !small.isEmpty()) {
            int st = b.length();
            b.append(' ').append(small);
            b.setSpan(new AbsoluteSizeSpan(Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 8.5f, ctx.getResources().getDisplayMetrics())), false), st, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            b.setSpan(new ForegroundColorSpan(TEXT_3), st, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return b;
    }

    private static String capital(String w) {
        if (w == null || w.isEmpty()) return w;
        return Character.toUpperCase(w.charAt(0)) + w.substring(1);
    }

    static String conditionText(String c) {
        if (c == null) return "–";
        switch (c) {
            case "clear-night": return "Clear";
            case "partlycloudy": return "Partly cloudy";
            case "lightning-rainy": return "Thunderstorms";
            case "lightning": return "Lightning";
            case "snowy-rainy": return "Sleet";
            case "windy-variant": return "Windy";
            case "exceptional": return "Severe weather";
            default: return capital(c.replace('-', ' '));
        }
    }

    /** Night by the sun entity's next rising/setting (local time of day), else 6 PM - 6 AM. */
    private boolean isNight(ZonedDateTime t) {
        JSONObject sun = st("sun.sun");
        JSONObject a = sun == null ? null : sun.optJSONObject("attributes");
        LocalTime rise = LocalTime.of(6, 30), set = LocalTime.of(17, 30);
        try {
            if (a != null && a.has("next_rising")) rise = OffsetDateTime.parse(a.getString("next_rising"))
                    .atZoneSameInstant(ZoneId.systemDefault()).toLocalTime();
            if (a != null && a.has("next_setting")) set = OffsetDateTime.parse(a.getString("next_setting"))
                    .atZoneSameInstant(ZoneId.systemDefault()).toLocalTime();
        } catch (Exception ignored) {
        }
        LocalTime x = t.toLocalTime();
        return x.isBefore(rise) || !x.isBefore(set);
    }

    private void bindAll() {
        bindChips();
        bindWeather();
        bindForecast();
    }

    private void bindChips() {
        if (lockChip != null) {
            String v = word(s.lock);
            int color;
            String label;
            if (v == null) { label = "Unavailable"; color = MUTED; }
            else if ("locked".equals(v)) { label = "Locked"; color = GOOD; }
            else if ("unlocked".equals(v) || "open".equals(v)) { label = capital(v); color = WARN; }
            else if ("jammed".equals(v)) { label = "Jammed"; color = BAD; }
            else { label = capital(v); color = TEXT; }
            lockChip.setText(chipText(s.lockName, label, color));
        }
        if (alertChip != null) {
            Double n = num(s.alerts);
            String label;
            int color;
            if (n == null) { label = "–"; color = MUTED; }
            else if (n < 0.5) { label = "None"; color = GOOD; }
            else {
                String event = null;
                JSONObject o = st(s.alerts);
                JSONObject a = o == null ? null : o.optJSONObject("attributes");
                JSONArray list = a == null ? null : a.optJSONArray("Alerts");
                if (list != null && list.length() > 0) {
                    JSONObject first = list.optJSONObject(0);
                    if (first != null) event = first.optString("Event", first.optString("Headline", null));
                }
                label = n < 1.5 && event != null ? event : Math.round(n) + " active";
                color = BAD;
            }
            alertChip.setText(chipText("NWS alerts", label, color));
        }
        if (offlineSince != 0) {
            String when = DateFormat.getTimeFormat(ctx).format(new java.util.Date(offlineSince));
            offlineChip.setText(chipText("HA offline", "since " + when, MUTED));
            offlineGlass.setVisibility(View.VISIBLE);
        } else {
            offlineGlass.setVisibility(View.GONE);
        }
    }

    private CharSequence chipText(String name, String value, int color) {
        SpannableStringBuilder b = new SpannableStringBuilder(name).append("  ");
        int st = b.length();
        b.append(value);
        b.setSpan(new ForegroundColorSpan(color), st, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        b.setSpan(new android.text.style.StyleSpan(Typeface.BOLD), st, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return b;
    }

    private void bindWeather() {
        if (nowTemp == null) return;
        JSONObject wa = weatherAttrs();
        String cond = word(s.weather);
        Double t = num(s.temperature);
        if (t == null && wa != null && wa.has("temperature")) t = wa.optDouble("temperature");
        nowTemp.setText(t == null ? "–°" : round(t) + "°");
        nowGlyph.set(cond, isNight(ZonedDateTime.now()));
        nowCondition.setText(conditionText(cond));

        StringBuilder range = new StringBuilder();
        JSONObject today = todayForecast();
        if (today != null) {
            if (today.has("temperature")) range.append("High ").append(Math.round(today.optDouble("temperature"))).append('°');
            if (today.has("templow")) range.append(range.length() > 0 ? " · " : "").append("Low ")
                    .append(Math.round(today.optDouble("templow"))).append('°');
            if (today.has("precipitation_probability")) range.append(range.length() > 0 ? " · " : "")
                    .append(Math.round(today.optDouble("precipitation_probability"))).append("% rain");
        }
        nowRange.setText(range);
        StringBuilder st = new StringBuilder();
        if (!s.stationName.isEmpty()) st.append(s.stationName).append(" · Tempest station");
        Double uv = num(s.uvIndex);
        if (uv != null) st.append(st.length() > 0 ? " · " : "").append("UV ").append(Math.round(uv));
        nowStation.setText(st);

        Double feels = num(s.feelsLike);
        metricValue[0].setText(feels == null ? "–" : round(feels) + "°");
        Double hum = num(s.humidity);
        if (hum == null && wa != null && wa.has("humidity")) hum = wa.optDouble("humidity");
        metricValue[1].setText(valueWithUnit(round(hum), "%"));
        Double wind = num(s.windSpeed);
        String card = word(s.windCardinal), desc = word(s.windDescription);
        StringBuilder wu = new StringBuilder(unit(s.windSpeed, "mph"));
        if (card != null) wu.append(' ').append(card.toUpperCase(Locale.US));
        if (desc != null) wu.append(" · ").append(desc.toLowerCase(Locale.US));
        metricValue[2].setText(valueWithUnit(round(wind), wu.toString()));
        Double rain = num(s.rainToday);
        String ru = unit(s.rainToday, "in");
        metricValue[3].setText(valueWithUnit(fmt(rain, ru), ru));
        Double p = num(s.pressure);
        String pu = unit(s.pressure, "inHg");
        String trend = word(s.pressureTrend);
        metricValue[4].setText(valueWithUnit(fmt(p, pu), trend == null ? pu : pu + " · " + trend.toLowerCase(Locale.US)));
        Double dew = num(s.dewPoint);
        if (dew == null && wa != null && wa.has("dew_point")) dew = wa.optDouble("dew_point");
        metricValue[5].setText(dew == null ? "–" : round(dew) + "°");
    }

    private JSONObject todayForecast() {
        if (daily == null || daily.length() == 0) return null;
        LocalDate today = LocalDate.now();
        for (int i = 0; i < daily.length(); i++) {
            JSONObject d = daily.optJSONObject(i);
            ZonedDateTime t = time(d);
            if (t != null && t.toLocalDate().equals(today)) return d;
        }
        return daily.optJSONObject(0);
    }

    private static ZonedDateTime time(JSONObject f) {
        if (f == null) return null;
        try {
            return OffsetDateTime.parse(f.getString("datetime")).atZoneSameInstant(ZoneId.systemDefault());
        } catch (Exception e) {
            return null;
        }
    }

    private void bindForecast() {
        if (hourLabel.isEmpty()) return;
        DateTimeFormatter hf = DateTimeFormatter.ofPattern(DateFormat.is24HourFormat(ctx) ? "HH:mm" : "h a", Locale.US);
        ZonedDateTime now = ZonedDateTime.now();
        int k = 0;
        if (hourly != null) {
            for (int i = 0; i < hourly.length() && k < hourLabel.size(); i++) {
                JSONObject f = hourly.optJSONObject(i);
                ZonedDateTime t = time(f);
                if (t == null || !t.plusHours(1).isAfter(now)) continue;   // past hours; the current one stays as "Now"
                hourLabel.get(k).setText(k == 0 && !t.isAfter(now) ? "Now" : hf.format(t));
                boolean night = f.has("is_daytime") ? !f.optBoolean("is_daytime") : isNight(t);
                hourGlyph.get(k).set(f.optString("condition"), night);
                hourTemp.get(k).setText(f.has("temperature") ? Math.round(f.optDouble("temperature")) + "°" : "–");
                hourCell.get(k).setVisibility(View.VISIBLE);
                k++;
            }
        }
        for (int i = k; i < hourLabel.size(); i++) {
            hourLabel.get(i).setText(hourly == null && i == 0 ? "…" : "");
            hourGlyph.get(i).set("", false);
            hourTemp.get(i).setText("");
        }

        DateTimeFormatter df = DateTimeFormatter.ofPattern("EEE", Locale.getDefault());
        LocalDate today = LocalDate.now();
        int j = 0;
        if (daily != null) {
            for (int i = 0; i < daily.length() && j < dayLabel.size(); i++) {
                JSONObject f = daily.optJSONObject(i);
                ZonedDateTime t = time(f);
                if (t == null || t.toLocalDate().isBefore(today)) continue;
                dayLabel.get(j).setText(t.toLocalDate().equals(today) ? "Today" : df.format(t));
                dayGlyph.get(j).set(f.optString("condition"), false);
                SpannableStringBuilder b = new SpannableStringBuilder();
                b.append(f.has("temperature") ? Math.round(f.optDouble("temperature")) + "°" : "–");
                if (f.has("templow")) {
                    int st = b.length();
                    b.append("  ").append(String.valueOf(Math.round(f.optDouble("templow")))).append('°');
                    b.setSpan(new ForegroundColorSpan(0xFFBFB5A5), st, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                dayTemp.get(j).setText(b);
                j++;
            }
        }
        for (int i = j; i < dayLabel.size(); i++) {
            dayLabel.get(i).setText("");
            dayGlyph.get(i).set("", false);
            dayTemp.get(i).setText("");
        }
    }
}
