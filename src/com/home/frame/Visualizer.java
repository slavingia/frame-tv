package com.home.frame;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.BlendMode;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Handler;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ExecutorService;

/**
 * Music visualizer with three scenes that all move to the music the PC's mic hears (duck/Beat.java):
 *   RECORD    the album cover spinning like a record in a ring of frequency bars
 *   BUBBLES   bubbles rising and wobbling, more when it's loud, popping on the beat
 *   FIREWORKS rockets launched on every kick over a bouncing equalizer skyline
 * Built for a toddler: remote buttons make bursts, arrows change scene, and saying a picture word
 * ("dog!") drops that animal in as a friend who bounces along to the beat.
 */
final class Visualizer {

    static final int PORT = 7777, BANDS = 32;
    static final int RECORD = 0, BUBBLES = 1, FIREWORKS = 2, SCENES = 3;
    private static final String[] SCENE_NAMES = {"Record", "Bubbles", "Fireworks"};

    interface Fonts { Typeface get(String family, int style); }

    private final FrameLayout root;
    private final Handler ui;
    private final ExecutorService io;
    private final Fonts fonts;
    private final Runnable onDismiss;

    private FrameLayout overlay;
    private MusicView view;
    private View textCol;
    private TextView title, artist, album, rooms, label;
    private View progressFill;
    private LinearLayout progressRow;
    private String artUrl, trackKey;
    private Sonos.NowPlaying np;
    private long npAt;
    private int scene = RECORD;
    private volatile boolean listening;

    // Audio state written by the UDP thread, read by the render loop.
    private final float[] bandsIn = new float[BANDS];
    private volatile float levelIn, beatIn, bassIn, clapIn, volIn;
    private volatile long lastPacket;

    Visualizer(FrameLayout root, Handler ui, ExecutorService io, Fonts fonts, Runnable onDismiss) {
        this.root = root; this.ui = ui; this.io = io; this.fonts = fonts; this.onDismiss = onDismiss;
    }

    boolean showing() { return overlay != null; }

    void show(Sonos.NowPlaying now) {
        Context c = root.getContext();
        if (overlay == null) {
            overlay = new FrameLayout(c);
            overlay.setBackgroundColor(0xFF000000);
            view = new MusicView(c);
            overlay.addView(view, new FrameLayout.LayoutParams(-1, -1));
            textCol = buildText(c);
            overlay.addView(textCol, textParams(c));
            textCol.setAlpha(scene == RECORD ? 1f : 0f);
            overlay.setAlpha(0f);
            root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
            overlay.animate().alpha(1f).setDuration(600).start();
            startUdp();
            ui.post(progressTick);
        }
        if (now != null) update(now);
    }

    void dismiss() {
        final FrameLayout o = overlay;
        overlay = null;
        listening = false;
        ui.removeCallbacks(progressTick);
        artUrl = null; trackKey = null;
        if (o == null) return;
        o.animate().alpha(0f).setDuration(400).withEndAction(() -> {
            root.removeView(o);
            if (onDismiss != null) onDismiss.run();
        }).start();
    }

    // ---------- interaction ----------

    void setScene(int s) {
        scene = ((s % SCENES) + SCENES) % SCENES;
        if (textCol != null) textCol.animate().alpha(scene == RECORD ? 1f : 0f).setDuration(400).start();
        if (view != null) view.sceneChanged();
    }

    void nextScene(int dir) { setScene(scene + dir); }

    /** A big colorful burst — what a remote button press does. */
    void burst() { if (view != null) view.burst(); }

    /** The picture word drops in as a round sticker that bounces to the beat. */
    void addFriend(String noun, Typeface labelFont) {
        String[][] photos = Nouns.PHOTOS.get(noun);
        if (photos == null || view == null) return;
        final String[] pick = photos[new Random().nextInt(photos.length)];
        final String word = noun.toUpperCase(Locale.US) + "!";
        final String url = "https://commons.wikimedia.org/wiki/Special:FilePath/" + Uri.encode(pick[0]) + "?width=640";
        io.execute(() -> {
            try {
                byte[] d = MainActivity.fetch(url);
                Bitmap b = BitmapFactory.decodeByteArray(d, 0, d.length);
                if (b == null) return;
                int side = Math.min(b.getWidth(), b.getHeight());
                final Bitmap sq = Bitmap.createBitmap(b, (b.getWidth() - side) / 2, (b.getHeight() - side) / 2, side, side);
                ui.post(() -> { if (view != null) view.addFriend(sq, word, labelFont); });
            } catch (Exception e) {
                android.util.Log.w("Frame", "friend " + noun + ": " + e);
            }
        });
    }

    void update(Sonos.NowPlaying now) {
        if (overlay == null || now == null) return;
        np = now; npAt = SystemClock.uptimeMillis();
        String key = now.key();
        if (!key.equals(trackKey)) {
            trackKey = key;
            final boolean first = title.getText().length() == 0;
            Runnable apply = () -> {
                title.setText(now.title.isEmpty() ? "Music" : now.title);
                artist.setText(now.artist);
                album.setText(now.album);
                album.setVisibility(now.album.isEmpty() ? View.GONE : View.VISIBLE);
                if (view != null) view.setCaption(now.title, now.artist);
            };
            if (first) apply.run();
            else {
                View block = (View) title.getParent();
                block.animate().alpha(0f).setDuration(300).withEndAction(() -> {
                    apply.run();
                    block.animate().alpha(1f).setDuration(500).start();
                }).start();
            }
        }
        rooms.setText(now.rooms.isEmpty() ? "" : "●  " + TextUtils.join("  ·  ", now.rooms));
        label.setText(now.playing ? "NOW PLAYING" : "PAUSED");
        progressRow.setVisibility(now.duration > 0 ? View.VISIBLE : View.INVISIBLE);
        if (now.art != null && !now.art.equals(artUrl)) {
            artUrl = now.art;
            final String url = now.art;
            io.execute(() -> {
                try {
                    byte[] d = Sonos.get(url);
                    final Bitmap b = BitmapFactory.decodeByteArray(d, 0, d.length);
                    if (b != null) ui.post(() -> { if (view != null && url.equals(artUrl)) view.setArt(b); });
                } catch (Exception e) {
                    android.util.Log.w("Frame", "album art: " + e);
                }
            });
        }
    }

    private final Runnable progressTick = new Runnable() {
        @Override public void run() {
            if (overlay == null) return;
            if (np != null && np.duration > 0) {
                float pos = np.position + (np.playing ? (SystemClock.uptimeMillis() - npAt) / 1000f : 0);
                float f = Math.max(0, Math.min(1, pos / np.duration));
                View track = (View) progressFill.getParent();
                progressFill.getLayoutParams().width = Math.round(track.getWidth() * f);
                progressFill.requestLayout();
            }
            ui.postDelayed(this, 500);
        }
    };

    // ---------- text block (record scene) ----------

    private View buildText(Context c) {
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);

        label = tv(c, 15, fonts.get("x-medium", Typeface.BOLD), 0xB3FFFFFF);
        label.setLetterSpacing(0.32f);
        label.setText("NOW PLAYING");
        col.addView(label);

        LinearLayout block = new LinearLayout(c);
        block.setOrientation(LinearLayout.VERTICAL);
        title = tv(c, 56, fonts.get("mont-800", Typeface.NORMAL), 0xFFFFFFFF);
        title.setMaxLines(3);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setLineSpacing(0, 0.95f);
        title.setPadding(0, dp(c, 14), 0, 0);
        block.addView(title);
        artist = tv(c, 30, fonts.get("mont-600", Typeface.NORMAL), 0xFFFFFFFF);
        artist.setMaxLines(2);
        artist.setPadding(0, dp(c, 12), 0, 0);
        block.addView(artist);
        album = tv(c, 18, fonts.get("x", Typeface.NORMAL), 0x99FFFFFF);
        album.setMaxLines(1);
        album.setEllipsize(TextUtils.TruncateAt.END);
        album.setPadding(0, dp(c, 6), 0, 0);
        block.addView(album);
        col.addView(block);

        progressRow = new LinearLayout(c);
        FrameLayout track = new FrameLayout(c);
        track.setBackgroundColor(0x33FFFFFF);
        progressFill = new View(c);
        progressFill.setBackgroundColor(0xE6FFFFFF);
        track.addView(progressFill, new FrameLayout.LayoutParams(0, -1));
        progressRow.addView(track, new LinearLayout.LayoutParams(-1, dp(c, 3)));
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(-1, -2);
        pl.topMargin = dp(c, 30);
        col.addView(progressRow, pl);

        rooms = tv(c, 15, fonts.get("x-medium", Typeface.BOLD), 0x99FFFFFF);
        rooms.setLetterSpacing(0.06f);
        rooms.setPadding(0, dp(c, 18), 0, 0);
        col.addView(rooms);
        return col;
    }

    private FrameLayout.LayoutParams textParams(Context c) {
        int w = c.getResources().getDisplayMetrics().widthPixels;
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Math.round(w * 0.36f), -2, Gravity.CENTER_VERTICAL | Gravity.START);
        lp.leftMargin = Math.round(w * 0.585f);
        return lp;
    }

    private static TextView tv(Context c, float sp, Typeface tf, int color) {
        TextView t = new TextView(c);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(tf);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        t.setShadowLayer(dp(c, 10), 0, dp(c, 2), 0x99000000);
        return t;
    }

    private static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    // ---------- audio from the PC ----------

    private void startUdp() {
        listening = true;
        new Thread(() -> {
            try (DatagramSocket s = new DatagramSocket(null)) {
                s.setReuseAddress(true);
                s.bind(new java.net.InetSocketAddress(PORT));
                s.setSoTimeout(500);
                byte[] buf = new byte[256];
                while (listening) {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    try { s.receive(p); } catch (SocketTimeoutException e) { continue; }
                    if (p.getLength() < 32 + BANDS || buf[0] != 'B' || buf[3] != 'T') continue;
                    ByteBuffer b = ByteBuffer.wrap(buf, 0, p.getLength());
                    b.position(8);
                    levelIn = b.getFloat();
                    beatIn = Math.max(beatIn, b.getFloat());
                    bassIn = b.getFloat();
                    b.getFloat(); b.getFloat();
                    synchronized (bandsIn) { for (int i = 0; i < BANDS; i++) bandsIn[i] = (b.get() & 0xFF) / 255f; }
                    if (b.remaining() >= 8) { clapIn = Math.max(clapIn, b.getFloat()); volIn = b.getFloat(); }
                    lastPacket = SystemClock.uptimeMillis();
                }
            } catch (Exception e) {
                android.util.Log.w("Frame", "visualizer udp: " + e);
            }
        }, "viz-udp").start();
    }

    // ---------- the picture ----------

    private static final class Friend {
        Bitmap img; String word; Typeface font;
        float x, y, vx, vy, born, hop, spin, seed;
    }

    private final class MusicView extends View {
        private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG), glow = new Paint(Paint.ANTI_ALIAS_FLAG),
                bg = new Paint(Paint.FILTER_BITMAP_FLAG), artPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG),
                ring = new Paint(Paint.ANTI_ALIAS_FLAG), spark = new Paint(Paint.ANTI_ALIAS_FLAG), vignette = new Paint(),
                fill = new Paint(Paint.ANTI_ALIAS_FLAG), text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float[] bands = new float[BANDS];
        private final Random rnd = new Random();
        private final List<float[]> sparks = new ArrayList<>();     // x, y, vx, vy, life, size, colorIndex, gravity
        private final List<float[]> waves = new ArrayList<>();      // x, y, radius, life, colorIndex
        private final List<float[]> bubbles = new ArrayList<>();    // x, y, r, vy, phase, colorIndex, wobble
        private final List<float[]> rockets = new ArrayList<>();    // x, y, vx, vy, colorIndex
        private final List<Friend> friends = new ArrayList<>();
        private Bitmap art, blur;
        private int c1 = 0xFFFFD866, c2 = 0xFF7FD1FF, c3 = 0xFFFF6FA8;
        private float level, bass, beat, angle, hueT, bubbleDebt, sceneFlash;
        private float loud, flash, spinBoost, fountainDebt, shake;
        private int flashColor = 0xFFFFFFFF;
        private long lastFrame;
        private float beatGate;
        private String capTitle = "", capArtist = "";

        MusicView(Context c) {
            super(c);
            bar.setStrokeCap(Paint.Cap.ROUND);
            ring.setStyle(Paint.Style.STROKE);
            glow.setBlendMode(BlendMode.PLUS);
            spark.setBlendMode(BlendMode.PLUS);
            text.setTextAlign(Paint.Align.CENTER);
        }

        void setCaption(String t, String a) { capTitle = t == null ? "" : t; capArtist = a == null ? "" : a; }

        void setArt(Bitmap b) {
            art = b;
            // A cheap, very soft blur: shrink hard, then let bilinear filtering stretch it back up.
            Bitmap tiny = Bitmap.createScaledBitmap(b, 12, 12, true);
            blur = Bitmap.createScaledBitmap(tiny, 96, 54, true);
            int[] pal = palette(b);
            c1 = pal[0]; c2 = pal[1]; c3 = pal[2];
        }

        void sceneChanged() {
            sceneFlash = 1;
            burst();
        }

        void addFriend(Bitmap img, String word, Typeface font) {
            int w = getWidth(), h = getHeight();
            if (w == 0) return;
            Friend f = new Friend();
            f.img = img; f.word = word; f.font = font;
            f.x = w * (0.2f + rnd.nextFloat() * 0.6f);
            f.y = -h * 0.2f;                                 // drops in from the top
            f.vx = (rnd.nextBoolean() ? 1 : -1) * h * (0.04f + rnd.nextFloat() * 0.05f);
            f.vy = h * 0.9f;
            f.born = SystemClock.uptimeMillis() / 1000f;
            f.seed = rnd.nextFloat() * 10;
            friends.add(f);
            while (friends.size() > 5) friends.remove(0);
            bloom(f.x, h * 0.45f, 12, h);
        }

        void burst() {
            int w = getWidth(), h = getHeight();
            if (w == 0) return;
            float x = w * (0.2f + rnd.nextFloat() * 0.6f), y = h * (0.25f + rnd.nextFloat() * 0.45f);
            bloom(x, y, 16, h);
            waves.add(new float[]{x, y, h * 0.02f, 0.8f, rnd.nextInt(3)});
            if (scene == BUBBLES) for (int i = 0; i < 4; i++) spawnBubble(w, h, x + (rnd.nextFloat() - 0.5f) * w * 0.2f, h * 1.05f);
            if (scene == FIREWORKS) launch(w, h);
        }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            vignette.setShader(new RadialGradient(w * 0.5f, h * 0.5f, Math.max(w, h) * 0.75f,
                    new int[]{0x00000000, 0x00000000, 0xCC000000}, new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        }

        @Override protected void onDraw(Canvas cv) {
            long now = SystemClock.uptimeMillis();
            float dt = lastFrame == 0 ? 0.016f : Math.min(0.05f, (now - lastFrame) / 1000f);
            lastFrame = now;
            int w = getWidth(), h = getHeight();
            boolean live = now - lastPacket < 1500;
            float t = now / 1000f;
            int[] cols = {c1, c2, c3};

            // --- ease the incoming audio: smooth, but punchy on beats ---
            float tl, tb;
            boolean beatNow = false;
            if (live) {
                synchronized (bandsIn) {
                    for (int i = 0; i < BANDS; i++) {
                        float target = bandsIn[i];
                        bands[i] += (target - bands[i]) * (target > bands[i] ? 0.55f : 0.18f);
                    }
                }
                tl = levelIn; tb = bassIn;
                if (beatIn > 0.5f && beatGate <= 0) { beatNow = true; beatGate = 0.2f; }
                beatIn = 0;
                float cl = clapIn;
                if (cl > 0) { clapIn = 0; onClap(w, h, cl); }
                float v = volIn;
                loud += (v - loud) * (v > loud ? 0.5f : 0.08f);
            } else {
                // No mic stream: breathe slowly at ~96 bpm so it's still alive.
                for (int i = 0; i < BANDS; i++)
                    bands[i] += ((0.22f + 0.16f * (float) Math.sin(t * 1.3f + i * 0.45f) * (1 - i / 48f)) - bands[i]) * 0.08f;
                tl = 0.35f; tb = 0.3f + 0.15f * (float) Math.sin(t * 2 * Math.PI * 1.6f);
                beatNow = (int) (t * 1.6f) != (int) ((t - dt) * 1.6f);
            }
            beatGate -= dt;
            level += (tl - level) * 0.15f;
            bass += (tb - bass) * (tb > bass ? 0.5f : 0.12f);
            beat = Math.max(0, beat - dt * 3.2f);
            if (beatNow) onBeat(w, h);
            angle = (angle + dt * (8 + 12 * level + spinBoost)) % 360;
            spinBoost *= (float) Math.pow(0.15, dt);
            hueT += dt;
            sceneFlash = Math.max(0, sceneFlash - dt * 2);
            flash = Math.max(0, flash - dt * 0.8f);   // slow fade, no strobe
            shake = Math.max(0, shake - dt * 4);
            // How much louder than "quiet room" it is right now (talking ~0.2, squealing/shouting ~0.7+).
            float noise = Math.max(0, (loud - 0.35f) / 0.65f);

            // Louder room = the picture gently swells (no shaking).
            cv.save();
            float z = 1 + 0.012f * noise;
            cv.scale(z, z, w / 2f, h / 2f);

            drawBackdrop(cv, w, h, cols);

            if (scene == RECORD) drawRecord(cv, w, h, dt);
            else if (scene == BUBBLES) drawBubbles(cv, w, h, dt, t, cols);
            else drawFireworks(cv, w, h, dt, cols);

            // Louder room = more soft lights drifting up from the bottom, like lanterns. Slow and few.
            fountainDebt += dt * 14 * noise;
            while (fountainDebt > 1 && sparks.size() < 300) {
                fountainDebt -= 1;
                float x = w * (0.05f + rnd.nextFloat() * 0.9f);
                sparks.add(new float[]{x, h * 1.02f, (rnd.nextFloat() - 0.5f) * h * 0.03f, -h * (0.12f + rnd.nextFloat() * 0.12f),
                        4f + rnd.nextFloat() * 2, h * (0.008f + rnd.nextFloat() * 0.012f), rnd.nextInt(3), 0});
            }
            if (fountainDebt > 1) fountainDebt = 0;

            drawWaves(cv, dt, h, cols);
            drawSparks(cv, dt, h, cols);
            drawFriends(cv, w, h, dt, t);
            cv.restore();
            if (scene != RECORD) drawCaption(cv, w, h);
            if (flash > 0) cv.drawColor(withAlpha(flashColor, (int) (flash * 60)));

            if (sceneFlash > 0) {
                text.setTypeface(fonts.get("mont-900", Typeface.NORMAL));
                text.setTextSize(h * 0.09f);
                text.setColor(withAlpha(0xFFFFFFFF, (int) (255 * Math.min(1, sceneFlash * 1.5f))));
                text.setShadowLayer(h * 0.02f, 0, h * 0.005f, 0xAA000000);
                cv.drawText(SCENE_NAMES[scene].toUpperCase(Locale.US) + "!", w / 2f, h * 0.16f, text);
                text.clearShadowLayer();
            }

            postInvalidateOnAnimation();
        }

        private void drawBackdrop(Canvas cv, int w, int h, int[] cols) {
            if (blur != null) {
                ColorMatrix m = new ColorMatrix();
                float k = 0.42f + 0.12f * bass + 0.05f * beat;
                m.setScale(k, k, k, 1);
                bg.setColorFilter(new ColorMatrixColorFilter(m));
                float zoom = 1.08f + 0.04f * bass;
                Rect src = new Rect(0, 0, blur.getWidth(), blur.getHeight());
                float bw = w * zoom, bh = h * zoom;
                cv.drawBitmap(blur, src, new RectF((w - bw) / 2, (h - bh) / 2, (w + bw) / 2, (h + bh) / 2), bg);
            } else {
                cv.drawColor(0xFF0B0B10);
            }
            for (int i = 0; i < 3; i++) {
                float px = w * (0.5f + 0.38f * (float) Math.sin(hueT * (0.07f + 0.03f * i) + i * 2.1f));
                float py = h * (0.5f + 0.34f * (float) Math.cos(hueT * (0.05f + 0.02f * i) + i * 1.3f));
                float r = h * (0.55f + 0.25f * bass + 0.1f * i);
                int a = (int) (40 + 45 * level + 20 * beat);
                glow.setShader(new RadialGradient(px, py, r, withAlpha(cols[i], Math.min(255, a)), 0, Shader.TileMode.CLAMP));
                cv.drawCircle(px, py, r, glow);
            }
            glow.setShader(null);
            cv.drawRect(0, 0, w, h, vignette);
        }

        // --- scene 1: the record ---
        private void drawRecord(Canvas cv, int w, int h, float dt) {
            float cx = w * 0.30f, cy = h * 0.5f;
            float r = h * 0.215f * (1 + 0.05f * bass + 0.045f * beat);
            int n = 120;
            float inner = r + h * 0.022f;
            bar.setStrokeWidth(Math.max(3, h * 0.0052f));
            for (int i = 0; i < n; i++) {
                float u = i / (float) n;
                float m = u < 0.5f ? u * 2 : (1 - u) * 2;
                float v = band(m) * (0.75f + 0.5f * level);
                float len = h * (0.012f + 0.16f * v);
                double a = Math.toRadians(u * 360 - 90);
                float ca = (float) Math.cos(a), sa = (float) Math.sin(a);
                bar.setColor(withAlpha(lerpColor(c1, c2, m), (int) (150 + 105 * v)));
                cv.drawLine(cx + ca * inner, cy + sa * inner, cx + ca * (inner + len), cy + sa * (inner + len), bar);
            }
            ring.setStrokeWidth(h * 0.006f);
            ring.setColor(withAlpha(c2, (int) (90 + 120 * beat)));
            cv.drawCircle(cx, cy, r + h * 0.008f, ring);
            if (art != null) {
                drawRound(cv, art, cx, cy, r, angle);
            } else {
                artPaint.setShader(null);
                artPaint.setColor(0xFF1C1C22);
                cv.drawCircle(cx, cy, r, artPaint);
            }
            artPaint.setShader(null);
            artPaint.setColor(0xE6000000);
            cv.drawCircle(cx, cy, r * 0.075f, artPaint);
            artPaint.setColor(withAlpha(c1, 230));
            cv.drawCircle(cx, cy, r * 0.03f, artPaint);
        }

        // --- scene 2: bubbles ---
        private void drawBubbles(Canvas cv, int w, int h, float dt, float t, int[] cols) {
            bubbleDebt += dt * (0.8f + 2.5f * level + 1.5f * bass);
            while (bubbleDebt > 1) { bubbleDebt -= 1; spawnBubble(w, h, rnd.nextFloat() * w, h * 1.08f); }
            for (int i = bubbles.size() - 1; i >= 0; i--) {
                float[] b = bubbles.get(i);
                b[1] -= b[3] * dt * (0.8f + 0.6f * level);
                b[4] += dt;
                float x = b[0] + (float) Math.sin(b[4] * b[6]) * h * 0.025f;
                float r = b[2] * (1 + 0.12f * bass + 0.08f * beat);
                if (b[1] < -r * 2) { bubbles.remove(i); continue; }
                int col = cols[(int) b[5]];
                fill.setShader(new RadialGradient(x, b[1], r, new int[]{withAlpha(col, 18), withAlpha(col, 60), withAlpha(col, 170)},
                        new float[]{0f, 0.7f, 1f}, Shader.TileMode.CLAMP));
                cv.drawCircle(x, b[1], r, fill);
                fill.setShader(null);
                ring.setStrokeWidth(Math.max(2, r * 0.05f));
                ring.setColor(withAlpha(lerpColor(col, 0xFFFFFFFF, 0.5f), 200));
                cv.drawCircle(x, b[1], r, ring);
                fill.setColor(0xCCFFFFFF);
                cv.drawCircle(x - r * 0.38f, b[1] - r * 0.4f, r * 0.16f, fill);   // shine
                fill.setColor(0x66FFFFFF);
                cv.drawCircle(x - r * 0.15f, b[1] - r * 0.55f, r * 0.06f, fill);
            }
            // Gentle frequency hills along the bottom.
            drawSkyline(cv, w, h, 0.10f, 140);
        }

        private void spawnBubble(int w, int h, float x, float y) {
            if (bubbles.size() > 70) return;
            bubbles.add(new float[]{x, y, h * (0.03f + rnd.nextFloat() * rnd.nextFloat() * 0.09f), h * (0.08f + rnd.nextFloat() * 0.12f),
                    rnd.nextFloat() * 6, rnd.nextInt(3), 1.2f + rnd.nextFloat() * 1.6f});
        }

        // --- scene 3: fireworks ---
        private void drawFireworks(Canvas cv, int w, int h, float dt, int[] cols) {
            cv.drawColor(withAlpha(0xFF02030A, 120));   // a night sky over the backdrop
            for (int i = rockets.size() - 1; i >= 0; i--) {
                float[] r = rockets.get(i);
                r[0] += r[2] * dt; r[1] += r[3] * dt; r[3] += h * 0.45f * dt;   // slow, floaty rise
                spark.setColor(withAlpha(cols[(int) r[4]], 230));
                cv.drawCircle(r[0], r[1], h * 0.006f, spark);
                if (rnd.nextFloat() < 0.25f) sparks.add(new float[]{r[0], r[1], (rnd.nextFloat() - 0.5f) * h * 0.05f, h * 0.05f, 0.4f, h * 0.003f, r[4], 0});
                if (r[3] >= -h * 0.05f) {   // apex: explode
                    rockets.remove(i);
                    // A slow, soft "dandelion" burst that drifts down.
                    int n = 22 + rnd.nextInt(10);
                    float sp = h * (0.12f + 0.06f * level);
                    for (int k = 0; k < n; k++) {
                        double a = k * Math.PI * 2 / n + rnd.nextFloat() * 0.1f;
                        float s = sp * (0.6f + rnd.nextFloat() * 0.5f);
                        sparks.add(new float[]{r[0], r[1], (float) Math.cos(a) * s, (float) Math.sin(a) * s,
                                2.2f + rnd.nextFloat() * 1.2f, h * (0.005f + rnd.nextFloat() * 0.005f), k % 2 == 0 ? r[4] : rnd.nextInt(3), h * 0.06f});
                    }
                }
            }
            drawSkyline(cv, w, h, 0.22f, 220);
        }

        private void launch(int w, int h) {
            if (rockets.size() > 8) return;
            if (rockets.size() > 3) return;
            rockets.add(new float[]{w * (0.15f + rnd.nextFloat() * 0.7f), h * 1.02f, (rnd.nextFloat() - 0.5f) * h * 0.06f,
                    -h * (0.8f + rnd.nextFloat() * 0.2f), rnd.nextInt(3)});
        }

        /** Rounded equalizer bars along the bottom, like a city skyline dancing. */
        private void drawSkyline(Canvas cv, int w, int h, float maxH, int alpha) {
            int n = 48;
            float gap = w / (float) n;
            bar.setStrokeWidth(gap * 0.55f);
            for (int i = 0; i < n; i++) {
                float m = Math.abs(i - (n - 1) / 2f) / ((n - 1) / 2f);   // centre = bass, edges = treble
                float v = band(m);
                float x = gap * (i + 0.5f);
                float top = h - h * (0.015f + maxH * v);
                bar.setColor(withAlpha(lerpColor(c1, c2, m), alpha));
                cv.drawLine(x, h + gap, x, top, bar);
            }
        }

        private void drawWaves(Canvas cv, float dt, int h, int[] cols) {
            ring.setStrokeWidth(h * 0.004f);
            for (int i = waves.size() - 1; i >= 0; i--) {
                float[] wv = waves.get(i);
                wv[3] -= dt / 2.2f;
                if (wv[3] <= 0) { waves.remove(i); continue; }
                wv[2] += dt * h * 0.2f;
                ring.setColor(withAlpha(cols[(int) wv[4]], (int) (wv[3] * wv[3] * 110)));
                cv.drawCircle(wv[0], wv[1], wv[2], ring);
            }
        }

        private void drawSparks(Canvas cv, float dt, int h, int[] cols) {
            for (int i = sparks.size() - 1; i >= 0; i--) {
                float[] p = sparks.get(i);
                p[4] -= dt;
                if (p[4] <= 0) { sparks.remove(i); continue; }
                p[0] += p[2] * dt; p[1] += p[3] * dt;
                p[2] *= 0.985f; p[3] = p[3] * 0.985f + p[7] * dt;
                spark.setColor(withAlpha(cols[(int) p[6]], (int) (190 * Math.min(1, p[4] / 1.2f))));
                cv.drawCircle(p[0], p[1], p[5], spark);
            }
        }

        private void drawFriends(Canvas cv, int w, int h, float dt, float t) {
            for (int i = friends.size() - 1; i >= 0; i--) {
                Friend f = friends.get(i);
                float age = t - f.born;
                if (age > 45) { friends.remove(i); continue; }
                float r = h * 0.12f;
                // Drop in, then float about, bouncing off the edges.
                if (f.vy > 0 && f.y < h * 0.42f) { f.y += f.vy * dt; }
                else {
                    f.vy = 0;
                    f.x += f.vx * dt;
                    f.y = h * 0.42f + (float) Math.sin(t * 0.4f + f.seed) * h * 0.12f;
                    if (f.x < r * 1.2f || f.x > w - r * 1.2f) f.vx = -f.vx;
                }
                f.hop = Math.max(f.hop - dt * 4, 0);
                float bounce = f.hop * f.hop;
                float fade = Math.min(1, (45 - age) / 2);
                float s = r * (1 + 0.06f * bounce + 0.02f * bass);
                float y = f.y - bounce * h * 0.03f;
                float tilt = (float) Math.sin(t * 1.2f + f.seed) * 5 + bounce * 3 * (f.vx > 0 ? 1 : -1);
                // White sticker border, then the photo.
                fill.setColor(withAlpha(0xFFFFFFFF, (int) (255 * fade)));
                fill.setShadowLayer(h * 0.02f, 0, h * 0.008f, withAlpha(0xFF000000, (int) (140 * fade)));
                cv.drawCircle(f.x, y, s * 1.07f, fill);
                fill.clearShadowLayer();
                artPaint.setAlpha((int) (255 * fade));
                drawRound(cv, f.img, f.x, y, s, tilt);
                artPaint.setAlpha(255);
                // Big friendly word underneath.
                text.setTypeface(f.font);
                text.setTextSize(h * 0.06f * (1 + 0.1f * bounce));
                text.setColor(withAlpha(0xFFFFD866, (int) (255 * fade)));
                text.setShadowLayer(h * 0.012f, 0, h * 0.004f, withAlpha(0xFF000000, (int) (170 * fade)));
                cv.drawText(f.word, f.x, y + s * 1.07f + h * 0.07f, text);
                text.clearShadowLayer();
            }
        }

        private void drawCaption(Canvas cv, int w, int h) {
            if (capTitle.isEmpty()) return;
            text.setTextAlign(Paint.Align.LEFT);
            text.setTypeface(fonts.get("mont-700", Typeface.NORMAL));
            text.setTextSize(h * 0.024f);
            text.setColor(0xCCFFFFFF);
            text.setShadowLayer(h * 0.01f, 0, 0, 0xAA000000);
            String s = "♪  " + capTitle + (capArtist.isEmpty() ? "" : "  ·  " + capArtist);
            cv.drawText(TextUtils.ellipsize(s, new android.text.TextPaint(text), w * 0.6f, TextUtils.TruncateAt.END).toString(),
                    h * 0.045f, h * 0.07f, text);
            text.clearShadowLayer();
            text.setTextAlign(Paint.Align.CENTER);
        }

        private void drawRound(Canvas cv, Bitmap b, float cx, float cy, float r, float rot) {
            BitmapShader sh = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            Matrix mx = new Matrix();
            float s = (2 * r) / Math.min(b.getWidth(), b.getHeight());
            mx.setScale(s, s);
            mx.postTranslate(cx - b.getWidth() * s / 2, cy - b.getHeight() * s / 2);
            mx.postRotate(rot, cx, cy);
            sh.setLocalMatrix(mx);
            artPaint.setShader(sh);
            cv.drawCircle(cx, cy, r, artPaint);
            artPaint.setShader(null);
        }

        /** Band level at position m (0 = bass .. 1 = treble), interpolated and shaped. */
        private float band(float m) {
            float fb = m * (BANDS - 1);
            int lo = (int) fb;
            float v = bands[lo] + (bands[Math.min(BANDS - 1, lo + 1)] - bands[lo]) * (fb - lo);
            return (float) Math.pow(v, 1.4);
        }

        private void onBeat(int w, int h) {
            beat = 1;
            for (Friend f : friends) f.hop = 1;
            if (scene == RECORD) {
                float cx = w * 0.30f, cy = h * 0.5f, r = h * 0.215f;
                if (rnd.nextFloat() < 0.5f) waves.add(new float[]{cx, cy, r, 0.7f, 0});
                int count = 2 + rnd.nextInt(3);
                for (int i = 0; i < count && sparks.size() < 300; i++) {
                    double a = rnd.nextDouble() * Math.PI * 2;
                    float sp = h * (0.04f + rnd.nextFloat() * 0.08f);
                    sparks.add(new float[]{cx + (float) Math.cos(a) * r * 1.15f, cy + (float) Math.sin(a) * r * 1.15f,
                            (float) Math.cos(a) * sp, (float) Math.sin(a) * sp, 2f + rnd.nextFloat() * 1.5f,
                            h * (0.004f + rnd.nextFloat() * 0.006f), rnd.nextInt(3), 0});
                }
            } else if (scene == BUBBLES) {
                // Pop the biggest bubble that's on screen.
                float[] big = null;
                for (float[] b : bubbles) if (b[1] < h * 0.9f && b[1] > h * 0.1f && (big == null || b[2] > big[2])) big = b;
                if (big != null && rnd.nextFloat() < 0.25f) {
                    bubbles.remove(big);
                    bloom(big[0], big[1], 6, h);
                }
            } else if (rnd.nextFloat() < 0.35f) {
                launch(w, h);   // a rocket on some beats, not all
            }
        }

        /** A clap, snap or bang: a flash, a ring, a burst of sparks — bigger for louder claps — and everyone jumps. */
        /** A clap or snap: a soft glow blooms where it "landed" and drifts upward; friends give a little hop. Calm, not loud. */
        private void onClap(int w, int h, float strength) {
            int ci = rnd.nextInt(3);
            flash = Math.max(flash, 0.25f * strength);
            flashColor = lerpColor(new int[]{c1, c2, c3}[ci], 0xFFFFFFFF, 0.5f);
            spinBoost += 30 * strength;
            float x = w * (0.2f + rnd.nextFloat() * 0.6f), y = h * (0.3f + rnd.nextFloat() * 0.4f);
            bloom(x, y, (int) (8 + 10 * strength), h);
            waves.add(new float[]{x, y, h * 0.02f, 0.8f, ci});
            for (Friend f : friends) f.hop = Math.max(f.hop, 0.6f);
            if (scene == BUBBLES) {
                for (int k = 0; k < 3; k++) spawnBubble(w, h, x + (rnd.nextFloat() - 0.5f) * w * 0.2f, h * 1.05f);
            } else if (scene == FIREWORKS) {
                launch(w, h);
            }
        }

        /** Slow, soft orbs that float up and fade — the calm version of a burst. */
        private void bloom(float x, float y, int n, int h) {
            for (int i = 0; i < n && sparks.size() < 300; i++) {
                double a = rnd.nextDouble() * Math.PI * 2;
                float sp = h * (0.02f + rnd.nextFloat() * 0.08f);
                sparks.add(new float[]{x, y, (float) Math.cos(a) * sp, (float) Math.sin(a) * sp - h * 0.04f,
                        2.2f + rnd.nextFloat() * 1.6f, h * (0.007f + rnd.nextFloat() * 0.012f), rnd.nextInt(3), -h * 0.025f});
            }
        }

        private void pop(float x, float y, int n, int h) {
            for (int i = 0; i < n && sparks.size() < 400; i++) {
                double a = rnd.nextDouble() * Math.PI * 2;
                float sp = h * (0.1f + rnd.nextFloat() * 0.45f);
                sparks.add(new float[]{x, y, (float) Math.cos(a) * sp, (float) Math.sin(a) * sp, 0.7f + rnd.nextFloat() * 0.9f,
                        h * (0.004f + rnd.nextFloat() * 0.007f), rnd.nextInt(3), h * 0.15f});
            }
        }
    }

    // ---------- color ----------

    /** Three lively colors from the cover: the two most vivid distinct hues, plus a complement. */
    static int[] palette(Bitmap b) {
        Bitmap s = Bitmap.createScaledBitmap(b, 32, 32, true);
        float[] score = new float[18];
        float[][] sum = new float[18][3];
        float[] hsv = new float[3];
        for (int y = 0; y < 32; y++) for (int x = 0; x < 32; x++) {
            int c = s.getPixel(x, y);
            Color.colorToHSV(c, hsv);
            if (hsv[2] < 0.18f || hsv[1] < 0.18f) continue;
            int bin = (int) (hsv[0] / 20) % 18;
            float wgt = hsv[1] * hsv[2];
            score[bin] += wgt;
            sum[bin][0] += Color.red(c) * wgt; sum[bin][1] += Color.green(c) * wgt; sum[bin][2] += Color.blue(c) * wgt;
        }
        int best = -1, second = -1;
        for (int i = 0; i < 18; i++) if (best < 0 || score[i] > score[best]) best = i;
        for (int i = 0; i < 18; i++) {
            int d = Math.min(Math.abs(i - best), 18 - Math.abs(i - best));
            if (i != best && d >= 2 && (second < 0 || score[i] > score[second])) second = i;
        }
        if (best < 0 || score[best] < 4) return new int[]{0xFFFFD866, 0xFF7FD1FF, 0xFFFF6FA8};   // greyscale cover
        int a = vivid(sum[best], score[best]);
        int c = second >= 0 && score[second] > score[best] * 0.08f ? vivid(sum[second], score[second]) : shiftHue(a, 50);
        return new int[]{a, c, shiftHue(a, 180)};
    }

    private static int vivid(float[] s, float w) {
        float[] hsv = new float[3];
        Color.RGBToHSV((int) (s[0] / w), (int) (s[1] / w), (int) (s[2] / w), hsv);
        hsv[1] = Math.max(0.55f, Math.min(0.9f, hsv[1] * 1.2f));
        hsv[2] = 1f;
        return Color.HSVToColor(hsv);
    }

    private static int shiftHue(int c, float deg) {
        float[] hsv = new float[3];
        Color.colorToHSV(c, hsv);
        hsv[0] = (hsv[0] + deg) % 360;
        return Color.HSVToColor(hsv);
    }

    private static int withAlpha(int c, int a) { return (c & 0x00FFFFFF) | (Math.max(0, Math.min(255, a)) << 24); }

    private static int lerpColor(int a, int b, float t) {
        int r = (int) (Color.red(a) + (Color.red(b) - Color.red(a)) * t);
        int g = (int) (Color.green(a) + (Color.green(b) - Color.green(a)) * t);
        int bl = (int) (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t);
        return Color.rgb(r, g, bl);
    }
}
