package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.SurfaceView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.exoplayer.ExoPlayer;

import java.util.concurrent.ExecutorService;

/**
 * home_panel: the live camera filling the screen behind Home. Home Assistant is the only source:
 * camera/stream over its websocket gives an HLS playlist, played muted by Media3 on a SurfaceView (composited by the display hardware, so the
 * video costs the app's UI and render threads nothing; a TextureView here made the dock stutter); when that
 * fails (twice in a row) the panel falls back to the camera_proxy snapshot every few seconds and tries the
 * stream again later. The picture is center-cropped to the screen and shows through the clear glass cards. Nothing runs while Home is not visible: stop() releases the
 * player and the polling.
 */
final class PanelCamera extends FrameLayout {
    private static final String TAG = "HomePanel";
    private static final long HLS_RETRY_MS = 5 * 60_000L;

    interface Listener {
        /** live: video is playing; snapshot: showing stills; neither: no picture yet / unreachable. */
        void onCameraState(boolean live, boolean snapshot);
    }

    private final HaClient ha;
    private final String entity;
    private final String mode;
    private final long snapshotMs;
    private final float focusY;
    private final ExecutorService io;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SurfaceView video;
    private final ImageView still;

    private ExoPlayer player;
    private boolean running;
    private boolean snapshotMode;
    private boolean live;
    private int hlsFailures;
    private long hlsRetryAt;
    private int contentW, contentH;
    private int session;   // bumps on every start/stop so late answers from the worker are dropped

    PanelCamera(Context ctx, HaClient ha, String entity, String mode, int snapshotSeconds, float focusY,
                ExecutorService io, Listener listener) {
        super(ctx);
        this.ha = ha;
        this.entity = entity;
        this.mode = mode == null ? "auto" : mode;
        this.snapshotMs = Math.max(1, snapshotSeconds) * 1000L;
        this.focusY = Math.max(0f, Math.min(1f, focusY));
        this.io = io;
        this.listener = listener;
        setBackgroundColor(Color.BLACK);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        still = new ImageView(ctx);
        still.setScaleType(ImageView.ScaleType.FIT_XY);
        addView(still, new LayoutParams(0, 0));
        // Below the window: the views above it (scrim, glass, dock) draw over the video through the hole it
        // punches. GONE while showing stills, so the snapshot ImageView is not punched through.
        video = new SurfaceView(ctx);
        addView(video, new LayoutParams(0, 0));
        video.setVisibility(GONE);
        snapshotMode = "snapshot".equalsIgnoreCase(this.mode);
    }

    // ---- layout: center-crop the content to the screen ----

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int w = r - l, h = b - t;
        int cw = contentW > 0 ? contentW : 16, ch = contentH > 0 ? contentH : 9;
        float s = Math.max(w / (float) cw, h / (float) ch);
        int vw = Math.round(cw * s), vh = Math.round(ch * s);
        int x = (w - vw) / 2;
        int y = Math.round((h - vh) * focusY);
        for (int i = 0; i < getChildCount(); i++) getChildAt(i).layout(x, y, x + vw, y + vh);
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        setMeasuredDimension(MeasureSpec.getSize(wSpec), MeasureSpec.getSize(hSpec));
        for (int i = 0; i < getChildCount(); i++) {
            View c = getChildAt(i);
            int s = MeasureSpec.makeMeasureSpec(Math.max(1, getMeasuredWidth() * 2), MeasureSpec.AT_MOST);
            c.measure(s, MeasureSpec.makeMeasureSpec(Math.max(1, getMeasuredHeight() * 2), MeasureSpec.AT_MOST));
        }
    }

    private void setContentSize(int w, int h) {
        if (w <= 0 || h <= 0 || (w == contentW && h == contentH)) return;
        contentW = w;
        contentH = h;
        requestLayout();
    }

    // ---- lifecycle ----

    void start() {
        if (running) return;
        running = true;
        session++;
        if (snapshotMode && !"snapshot".equalsIgnoreCase(mode) && SystemClock.elapsedRealtime() >= hlsRetryAt) {
            snapshotMode = false;   // time to give the stream another go
            hlsFailures = 0;
        }
        if (snapshotMode) startSnapshots();
        else startHls();
    }

    void stop() {
        if (!running) return;
        running = false;
        session++;
        main.removeCallbacksAndMessages(null);
        releasePlayer();
        setLive(false, snapshotMode);
    }

    /** Memory pressure or teardown: also drop the pictures. */
    void release() {
        stop();
        still.setImageDrawable(null);
    }

    private void setLive(boolean isLive, boolean snapshot) {
        live = isLive;
        listener.onCameraState(isLive, snapshot);
    }

    // ---- HLS ----

    private void startHls() {
        final int my = session;
        io.execute(() -> {
            String url = null;
            try {
                url = ha.hlsUrl(entity);
            } catch (Exception e) {
                Log.w(TAG, "camera/stream: " + e.getMessage());
            }
            final String u = url;
            main.post(() -> {
                if (my != session || !running) return;
                if (u == null) hlsFailed();
                else play(u);
            });
        });
    }

    private void play(String url) {
        releasePlayer();
        player = new ExoPlayer.Builder(getContext()).build();
        player.setVolume(0f);
        // The doorbell's microphone never plays on Home: no audio track is even decoded.
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build());
        video.setVisibility(VISIBLE);
        player.setVideoSurfaceView(video);
        player.addListener(new Player.Listener() {
            @Override
            public void onVideoSizeChanged(@NonNull VideoSize s) {
                if (s.width > 0 && s.height > 0) setContentSize(Math.round(s.width * s.pixelWidthHeightRatio), s.height);
            }

            @Override
            public void onRenderedFirstFrame() {
                hlsFailures = 0;
                setLive(true, false);
            }

            @Override
            public void onPlayerError(@NonNull PlaybackException e) {
                Log.w(TAG, "player: " + e.getErrorCodeName());
                if (e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW && player != null) {
                    player.seekToDefaultPosition();
                    player.prepare();
                    return;
                }
                hlsFailed();
            }
        });
        player.setMediaItem(new MediaItem.Builder()
                .setUri(url)
                .setMimeType(MimeTypes.APPLICATION_M3U8)
                .setLiveConfiguration(new MediaItem.LiveConfiguration.Builder().setTargetOffsetMs(2500).build())
                .build());
        player.setPlayWhenReady(true);
        player.prepare();
    }

    private void hlsFailed() {
        releasePlayer();
        if (!running) return;
        hlsFailures++;
        setLive(false, false);
        if ("hls".equalsIgnoreCase(mode) || hlsFailures < 2) {
            main.postDelayed(() -> { if (running) startHls(); }, Math.min(30_000L, 3000L * hlsFailures));
            return;
        }
        Log.w(TAG, "camera stream unavailable; using snapshots");
        snapshotMode = true;
        hlsRetryAt = SystemClock.elapsedRealtime() + HLS_RETRY_MS;
        startSnapshots();
    }

    private void releasePlayer() {
        if (player != null) {
            player.release();
            player = null;
        }
    }

    // ---- snapshots ----

    private void startSnapshots() {
        main.removeCallbacks(snapshotTick);
        main.post(snapshotTick);
    }

    private final Runnable snapshotTick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            final int my = session;
            io.execute(() -> {
                Bitmap b = null;
                try {
                    b = ha.snapshot(entity, 1280);
                    if (b != null) {
                    }
                } catch (Exception e) {
                    Log.w(TAG, "snapshot: " + e.getMessage());
                }
                final Bitmap pic = b;
                main.post(() -> {
                    if (my != session || !running) return;
                    if (pic != null) {
                        video.setVisibility(GONE);
                        still.setImageBitmap(pic);
                        setContentSize(pic.getWidth(), pic.getHeight());
                                        setLive(false, true);
                    }
                    if (SystemClock.elapsedRealtime() >= hlsRetryAt && !"snapshot".equalsIgnoreCase(mode)) {
                        snapshotMode = false;
                        hlsFailures = 0;
                        startHls();
                    } else {
                        main.postDelayed(snapshotTick, snapshotMs);
                    }
                });
            });
        }
    };
}
