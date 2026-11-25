package com.home.frame;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.Address;
import android.location.Geocoder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Html;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Frame: a minimal TV home screen. Rotating photography with location, a clock,
 * NYC weather, optional subway status, and four app tiles.
 *
 * Config (stored in SharedPreferences), set over adb:
 *   adb shell am start -n com.home.frame/.MainActivity --es xboxLiveId FD00XXXXXXXXXXXX
 *   adb shell am start -n com.home.frame/.MainActivity --es xboxIp 192.168.1.x
 *   adb shell am start -n com.home.frame/.MainActivity --es subwayStops "A27|42 St|A,C,E"
 *   adb shell am start -n com.home.frame/.MainActivity --es sonos "192.168.1.x,192.168.1.y"
 *   adb shell am start -n com.home.frame/.MainActivity --es calendars "Family,Work"
 */
public class MainActivity extends Activity {

    private static final String PARAMOUNT = "com.cbs.ott";   // Paramount+ for Android TV
    private static final String UA ="FrameTV/0.1 (personal home screen)";
    private static final double LAT = 40.7128, LON = -74.0060; // New York
    private static final long PHOTO_MS = 90_000, CLOCK_MS = 10_000, WEATHER_MS = 15 * 60_000, SUBWAY_MS = 30_000;
    private static final String[] CATEGORIES = {
            "Featured_pictures_of_landscapes",
            "Featured_pictures_of_landscapes",
            "Featured_pictures_of_cityscapes",
            "Featured_pictures_of_mountains",
            "Featured_pictures_of_coasts",
            "Featured_pictures_of_lakes",
            "Featured_pictures_of_forests",
            "Featured_pictures_of_waterfalls",
            "Featured_pictures_of_New_York_City",
    };

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newFixedThreadPool(3);
    private final Random rnd = new Random();
    private final List<Photo> pool = Collections.synchronizedList(new ArrayList<Photo>());
    private SharedPreferences prefs;

    private ImageView imgFront, imgBack;
    private FrameLayout rootView;
    private Duck duck;
    private static final int CLOCK_DROP = 15;
    private TextView clock, dateDay, date, weatherNow, weatherDay, photoPlace, photoCredit, subwayAlert;
    private LinearLayout subwayCard, subwayRows, agenda;
    private Subway.Alert lastAlert;
    private int subwayTicks;
    private LinearLayout tiles, side;
    private volatile boolean photoLoading;
    private boolean showingTop, hasPhoto;

    static class Photo {
        String url, title, artist;
        double lat, lon;
        boolean hasCoords;
    }

    // ---------- lifecycle ----------

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("frame", MODE_PRIVATE);
        readConfig(getIntent());
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(buildUi());
        hideSystemUi();
        Runnable refocus = () -> { if (tiles != null && tiles.getChildCount() > 0) tiles.getChildAt(0).requestFocus(); };
        // Own handlers: onResume clears `ui`, which would otherwise kill their timers on every voice intent.
        duck = new Duck(rootView, new Handler(Looper.getMainLooper()), refocus);
        viz = new Visualizer(rootView, new Handler(Looper.getMainLooper()), io, this::font, refocus);
        handleShow(getIntent());
    }

    // ---------- music visualizer ----------

    private static final long MUSIC_MS = 4_000, IDLE_BEFORE_VIZ_MS = 60_000, STOPPED_CLOSE_MS = 20_000, SNOOZE_MS = 10 * 60_000;
    private final Sonos sonos = new Sonos();
    private Visualizer viz;
    private Sonos.NowPlaying lastNp;
    private long lastKeyAt = android.os.SystemClock.uptimeMillis(), snoozeUntil, notPlayingSince;
    private volatile boolean polling;

    private final Runnable musicTick = new Runnable() {
        @Override public void run() {
            ui.postDelayed(this, MUSIC_MS);
            if (polling) return;
            polling = true;
            sonos.setSeeds(prefs.getString("sonos", ""));
            io.execute(() -> {
                Sonos.NowPlaying np = sonos.poll();
                polling = false;
                ui.post(() -> onMusic(np));
            });
        }
    };

    private void onMusic(Sonos.NowPlaying np) {
        lastNp = np;
        long now = android.os.SystemClock.uptimeMillis();
        if (np.playing) notPlayingSince = 0; else if (notPlayingSince == 0) notPlayingSince = now;
        if (viz.showing()) {
            viz.update(np);
            if (!np.playing && now - notPlayingSince > STOPPED_CLOSE_MS) viz.dismiss();
        } else if (np.playing && !duck.showing() && now - lastKeyAt > IDLE_BEFORE_VIZ_MS && now > snoozeUntil) {
            viz.show(np);
        }
    }

    /** While a picture or the visualizer is up, every remote key just closes it (and never reaches the tiles underneath). */
    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        lastKeyAt = android.os.SystemClock.uptimeMillis();
        if (duck != null && duck.showing()) {
            if (e.getAction() == KeyEvent.ACTION_UP) duck.dismiss();
            return true;
        }
        if (viz != null && viz.showing()) {
            // Toddler-proof: Back closes, left/right change scene, every other button is a burst of color.
            int k = e.getKeyCode();
            if (k == KeyEvent.KEYCODE_VOLUME_UP || k == KeyEvent.KEYCODE_VOLUME_DOWN || k == KeyEvent.KEYCODE_VOLUME_MUTE)
                return super.dispatchKeyEvent(e);
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
                if (k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_ESCAPE) { viz.dismiss(); snoozeUntil = lastKeyAt + SNOOZE_MS; }
                else if (k == KeyEvent.KEYCODE_DPAD_LEFT) viz.nextScene(-1);
                else if (k == KeyEvent.KEYCODE_DPAD_RIGHT) viz.nextScene(1);
                else viz.burst();
            } else if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() % 4 == 0) {
                viz.burst();   // held button: keep the fireworks coming
            }
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    /** Voice triggers arrive as `am start ... --es show duck` from the PC's Hue-bridge emulator. */
    private void handleShow(Intent i) {
        if (i == null || duck == null) return;
        String what = i.getStringExtra("show");
        if (what == null) return;
        i.removeExtra("show");
        String noun = what.replace('_', ' ').trim().toLowerCase(java.util.Locale.US);  // "fire_truck" -> "fire truck"
        // Scene words: "music" = record, "dance"/"party" = fireworks, and "bubbles" while the music is up.
        int scene = noun.equals("music") ? Visualizer.RECORD
                : noun.equals("dance") || noun.equals("party") ? Visualizer.FIREWORKS
                : noun.equals("bubbles") && viz.showing() ? Visualizer.BUBBLES : -1;
        if (scene >= 0) {
            if (duck.showing()) duck.dismiss();
            snoozeUntil = 0;
            notPlayingSince = android.os.SystemClock.uptimeMillis() + 5 * 60_000;   // asked for it: stay up a while even if paused
            boolean was = viz.showing();
            viz.show(lastNp);
            viz.setScene(scene);
            if (!was) { ui.removeCallbacks(musicTick); musicTick.run(); }
            return;
        }
        if (noun.equals("home")) { if (duck.showing()) duck.dismiss(); if (viz.showing()) viz.dismiss(); return; }
        // During music, a picture word joins the party as a bouncing friend instead of covering the screen.
        if (viz.showing() && Duck.knows(noun)) { viz.addFriend(noun, font("mont-900", Typeface.NORMAL)); return; }
        if (Duck.knows(noun)) duck.show(noun, font("mont-900", Typeface.NORMAL), font("x-medium", Typeface.BOLD), io);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        readConfig(intent);
        if (intent != null && intent.hasExtra("show")) { handleShow(intent); return; }
        if (tiles != null && tiles.getChildCount() > 0) tiles.getChildAt(0).requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUi();
        ui.removeCallbacksAndMessages(null);
        clockTick.run();
        weatherTick.run();
        agendaTick.run();
        subwayTick.run();
        lastKeyAt = android.os.SystemClock.uptimeMillis();
        ui.postDelayed(musicTick, 1500);
        if (!hasPhoto) photoTick.run();
        else ui.postDelayed(photoTick, PHOTO_MS);
        ui.postDelayed(() -> {
            View f = getCurrentFocus();
            if (f == null || (f.getParent() != tiles && f.getParent() != side)) tiles.getChildAt(0).requestFocus();
        }, 300);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacksAndMessages(null);
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Leaving for YouTube etc.: drop the visualizer. (onPause alone also fires for every voice intent.)
        if (viz != null && viz.showing()) viz.dismiss();
    }

    @Override
    public void onBackPressed() { /* home screen: nowhere to go back to */ }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // Menu / info key skips to the next photo.
        if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_INFO) {
            ui.removeCallbacks(photoTick);
            photoTick.run();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void readConfig(Intent i) {
        if (i == null || i.getExtras() == null) return;
        SharedPreferences.Editor e = prefs.edit();
        for (String k : new String[]{"xboxLiveId", "xboxIp", "subwayStops", "calendars", "sonos"}) {
            String v = i.getStringExtra(k);
            if (v != null) e.putString(k, v.trim());
        }
        e.apply();
    }

    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    // ---------- UI ----------

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    // NY type: Montserrat (free Gotham stand-in) for display, TeX Gyre Heros (free Helvetica clone) for the rest.
    private Typeface heros, herosBold;
    private final Map<Integer, Typeface> mont = new java.util.HashMap<>();

    /** "mont-700" = Montserrat at that weight; "*-medium" or BOLD = Heros Bold; anything else = Heros Regular. */
    Typeface font(String family, int style) {
        try {
            if (family.startsWith("mont-")) {
                int w = Integer.parseInt(family.substring(5));
                Typeface t = mont.get(w);
                if (t == null) {
                    t = new Typeface.Builder(getAssets(), "fonts/Montserrat.ttf")
                            .setFontVariationSettings("'wght' " + w).build();
                    mont.put(w, t);
                }
                return t;
            }
            if (style == Typeface.BOLD || family.endsWith("-medium")) {
                if (herosBold == null) herosBold = Typeface.createFromAsset(getAssets(), "fonts/Sans-Bold.ttf");
                return herosBold;
            }
            if (heros == null) heros = Typeface.createFromAsset(getAssets(), "fonts/Sans-Regular.ttf");
            return heros;
        } catch (Exception e) {
            android.util.Log.w("Frame", "font load failed: " + family, e);
            return Typeface.create("sans-serif", style);
        }
    }

    private TextView text(float sp, int color, String family, int style) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(font(family, style));
        t.setShadowLayer(dp(8), 0, dp(1), 0x99000000);
        t.setIncludeFontPadding(false);
        return t;
    }

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        rootView = root;
        root.setBackgroundColor(0xFF0B0D10);

        imgBack = new ImageView(this);
        imgFront = new ImageView(this);
        for (ImageView iv : new ImageView[]{imgBack, imgFront}) {
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            root.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        }

        // Scrims so text stays legible on any photo.
        View top = new View(this);
        top.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xB3000000, 0x73000000, 0x00000000}));
        root.addView(top, new FrameLayout.LayoutParams(-1, dp(210), Gravity.TOP));
        View bottom = new View(this);
        bottom.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0xCC000000, 0x00000000}));
        root.addView(bottom, new FrameLayout.LayoutParams(-1, dp(300), Gravity.BOTTOM));

        // Grid: 48dp side margins, two 348dp columns (left: time + agenda, right: weather + subway),
        // a 92dp header band with both headers sitting on its bottom edge, sign panels below it,
        // and the tile row + photo caption sharing one bottom baseline.
        int pad = dp(48), colW = dp(348), headH = dp(92), topM = dp(30), gap = dp(14), bottomM = dp(40);
        int warm = 0xFFF2EDE4, dim = 0xD9F2EDE4;

        // Left column: clock (the primary element) with the date locked up beside it, then the agenda sign.
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setClipChildren(false);
        FrameLayout lh = new FrameLayout(this);
        lh.setClipChildren(false);
        LinearLayout lockup = new LinearLayout(this);
        lockup.setOrientation(LinearLayout.HORIZONTAL);
        clock = text(74, warm, "mont-700", Typeface.NORMAL);
        clock.setLetterSpacing(-0.02f);
        LinearLayout dbox = new LinearLayout(this);
        dbox.setOrientation(LinearLayout.VERTICAL);
        dateDay = text(15, warm, "mont-700", Typeface.NORMAL);
        dateDay.setLetterSpacing(0.12f);
        date = text(15, dim, "mont-600", Typeface.NORMAL);
        date.setLetterSpacing(0.12f);
        date.setPadding(0, dp(3), 0, 0);
        dbox.addView(dateDay);
        dbox.addView(date);
        dbox.setBaselineAlignedChildIndex(1);   // second line sits on the clock's baseline
        lockup.addView(clock);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-2, -2);
        dlp.leftMargin = dp(14);
        lockup.addView(dbox, dlp);
        lockup.setTranslationY(dp(CLOCK_DROP));   // cancel the clock's descender space so digits sit on the band edge
        lh.addView(lockup, new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START));
        left.addView(lh, new LinearLayout.LayoutParams(-1, headH));
        agenda = panel();
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(-1, -2);
        alp.topMargin = gap;
        left.addView(agenda, alp);
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(colW, -2, Gravity.TOP | Gravity.START);
        tlp.setMargins(pad, topM, 0, 0);
        root.addView(left, tlp);

        // Right column: weather header, then the subway sign.
        LinearLayout tr = new LinearLayout(this);
        tr.setOrientation(LinearLayout.VERTICAL);
        FrameLayout rh = new FrameLayout(this);
        LinearLayout wbox = new LinearLayout(this);
        wbox.setOrientation(LinearLayout.VERTICAL);
        weatherNow = text(40, warm, "mont-700", Typeface.NORMAL);
        weatherNow.setSingleLine(true);
        weatherDay = text(14, warm, "sans-serif-medium", Typeface.NORMAL);
        weatherDay.setSingleLine(true);
        weatherDay.setEllipsize(TextUtils.TruncateAt.END);
        weatherDay.setPadding(dp(2), dp(4), 0, 0);
        wbox.addView(weatherNow);
        wbox.addView(weatherDay);
        rh.addView(wbox, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM | Gravity.START));
        tr.addView(rh, new LinearLayout.LayoutParams(-1, headH));

        // Subway sign: station headers with line bullets, then next trains per direction.
        subwayCard = panel();
        subwayRows = new LinearLayout(this);
        subwayRows.setOrientation(LinearLayout.VERTICAL);
        subwayCard.addView(subwayRows, new LinearLayout.LayoutParams(-1, -2));
        subwayAlert = text(16, 0xFFFFD866, "sans-serif-medium", Typeface.NORMAL);
        subwayAlert.setShadowLayer(0, 0, 0, 0);
        subwayAlert.setLineSpacing(dp(5), 1f);
        subwayAlert.setMaxLines(2);
        subwayAlert.setEllipsize(TextUtils.TruncateAt.END);
        subwayAlert.setPadding(0, dp(8), 0, 0);
        subwayAlert.setVisibility(View.GONE);
        subwayCard.addView(subwayAlert, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams sclp = new LinearLayout.LayoutParams(-1, -2);
        sclp.topMargin = gap;
        tr.addView(subwayCard, sclp);
        FrameLayout.LayoutParams trp = new FrameLayout.LayoutParams(colW, -2, Gravity.TOP | Gravity.END);
        trp.setMargins(0, topM, pad, 0);
        root.addView(tr, trp);

        // Bottom-right: photo caption (place + credit only), kept quiet.
        LinearLayout bl = new LinearLayout(this);
        bl.setOrientation(LinearLayout.VERTICAL);
        bl.setGravity(Gravity.END);
        photoPlace = text(15, warm, "mont-600", Typeface.NORMAL);
        photoCredit = text(11, 0x99F2EDE4, "sans-serif", Typeface.NORMAL);
        photoCredit.setPadding(0, dp(4), 0, 0);
        for (TextView t : new TextView[]{photoPlace, photoCredit}) {
            t.setMaxWidth(dp(180));   // clears the four-tile row on a 960dp-wide screen
            t.setSingleLine(true);
            t.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-2, -2);
            cl.gravity = Gravity.END;
            bl.addView(t, cl);
        }
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        blp.setMargins(0, 0, pad, bottomM);
        root.addView(bl, blp);

        // Bottom-left: tiles. Four big app tiles, then Apps / Settings stacked to the same height.
        tiles = new LinearLayout(this);
        tiles.setOrientation(LinearLayout.HORIZONTAL);
        tiles.setGravity(Gravity.BOTTOM);
        tiles.setClipChildren(false);
        tiles.addView(tile("YouTube", "com.google.android.youtube.tv", 0, true, v -> launch("com.google.android.youtube.tv")));
        tiles.addView(tile("Netflix", "com.netflix.ninja", 0, true, v -> launch("com.netflix.ninja")));
        tiles.addView(tile("Paramount+", PARAMOUNT, 0, true, v -> launch(PARAMOUNT)));
        tiles.addView(tile("XBOX", null, 0xFF107C10, true, v -> wakeXbox()));
        side = new LinearLayout(this);
        side.setOrientation(LinearLayout.VERTICAL);
        side.setClipChildren(false);
        View apps = tile("Apps", null, 0, false, v -> showAllApps());
        View settings = tile("Settings", null, 0, false, v -> safeStart(new Intent(Settings.ACTION_SETTINGS)));
        side.addView(apps, new LinearLayout.LayoutParams(dp(96), dp(36)));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(dp(96), dp(36));
        slp.topMargin = dp(9);
        side.addView(settings, slp);
        LinearLayout.LayoutParams sidelp = new LinearLayout.LayoutParams(-2, -2);
        sidelp.setMargins(dp(8), 0, dp(8), 0);
        tiles.addView(side, sidelp);
        FrameLayout.LayoutParams brp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        brp.setMargins(pad - dp(8), 0, 0, bottomM);
        root.setClipChildren(false);
        root.addView(tiles, brp);
        ui.post(() -> tiles.getChildAt(0).requestFocus());
        return root;
    }

    /** MTA station-sign panel: near-solid black, shared by the agenda and the subway board. */
    private LinearLayout panel() {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(6));
        g.setColor(0xE6000000);
        p.setBackground(g);
        p.setPadding(dp(18), dp(13), dp(18), dp(14));
        p.setVisibility(View.GONE);
        return p;
    }

    /** Sign-panel text: no drop shadow. */
    private TextView sign(float sp, int color, boolean bold) {
        TextView t = text(sp, color, bold ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL);
        t.setShadowLayer(0, 0, 0, 0);
        return t;
    }

    /** A tile shows the app's own TV banner (its logo) when installed, else a brand-colored or plain label. */
    private View tile(String label, String pkg, int brand, boolean big, View.OnClickListener click) {
        final FrameLayout f = new FrameLayout(this);
        f.setFocusable(true);
        f.setFocusableInTouchMode(true);
        f.setClickable(true);
        f.setOnClickListener(click);
        final float r = dp(big ? 12 : 9);
        final GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(r);
        final GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(r);
        ring.setColor(0);
        ring.setStroke(dp(1), 0x40F2EDE4);
        f.setBackground(bg);
        f.setForeground(ring);
        f.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
        f.setClipToOutline(true);

        android.graphics.drawable.Drawable banner = null;
        if (pkg != null) {
            PackageManager pm = getPackageManager();
            Intent li = pm.getLeanbackLaunchIntentForPackage(pkg);
            try { if (li != null) banner = pm.getActivityBanner(li); } catch (Exception ignored) { }
            try { if (banner == null) banner = pm.getApplicationBanner(pkg); } catch (Exception ignored) { }
        }
        final TextView t;
        if (banner != null) {
            bg.setColor(0xFF000000);
            ImageView iv = new ImageView(this);
            iv.setImageDrawable(banner);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            f.addView(iv, new FrameLayout.LayoutParams(-1, -1));
            t = null;
        } else {
            t = text(big ? 17 : 13, 0xFFF2EDE4, "sans-serif", Typeface.BOLD);
            t.setText(label);
            t.setGravity(Gravity.CENTER);
            t.setShadowLayer(0, 0, 0, 0);
            if (brand != 0) {
                t.setLetterSpacing(0.25f);
                t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
                t.setTextColor(0xFFFFFFFF);
            }
            bg.setColor(brand != 0 ? brand : 0xB3000000);
            if (label.equals("XBOX")) {
                // Logo mark + wordmark, centered.
                LinearLayout lockup = new LinearLayout(this);
                lockup.setOrientation(LinearLayout.HORIZONTAL);
                lockup.setGravity(Gravity.CENTER);
                ImageView mark = new ImageView(this);
                mark.setImageResource(R.drawable.xbox_logo);
                lockup.addView(mark, new LinearLayout.LayoutParams(dp(36), dp(36)));
                ImageView word = new ImageView(this);
                word.setImageResource(R.drawable.xbox_word);
                LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(dp(64), dp(18));
                wl.leftMargin = dp(10);
                lockup.addView(word, wl);
                f.addView(lockup, new FrameLayout.LayoutParams(-1, -1));
            } else {
                f.addView(t, new FrameLayout.LayoutParams(-1, -1));
            }
        }
        final boolean plain = banner == null && brand == 0;
        if (!plain) f.setAlpha(0.85f);

        LinearLayout.LayoutParams lp = big
                ? new LinearLayout.LayoutParams(dp(128), dp(72))
                : new LinearLayout.LayoutParams(dp(84), dp(40));
        lp.setMargins(dp(8), 0, dp(8), 0);
        f.setLayoutParams(lp);
        f.setOnFocusChangeListener((v, has) -> {
            ring.setStroke(has ? dp(4) : dp(1), has ? 0xFFFFFFFF : 0x40F2EDE4);
            if (plain) {
                bg.setColor(has ? 0xFFFFFFFF : 0xB3000000);
                t.setTextColor(has ? 0xFF111418 : 0xFFF2EDE4);
            } else {
                v.setAlpha(has ? 1f : 0.85f);
            }
            v.setElevation(has ? dp(10) : 0);
            v.animate().scaleX(has ? 1.1f : 1f).scaleY(has ? 1.1f : 1f).setDuration(160).start();
        });
        return f;
    }

    private TextView bullet(String route, int sizeDp) {
        TextView b = new TextView(this);
        b.setText(route);
        b.setGravity(Gravity.CENTER);
        b.setIncludeFontPadding(false);
        b.setTypeface(font("x-medium", Typeface.BOLD));
        b.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(sizeDp) * 0.62f);
        b.setTextColor(Subway.darkText(route) ? 0xFF111111 : 0xFFFFFFFF);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(Subway.color(route));
        b.setBackground(g);
        return b;
    }

    /** Turns MTA alert text like "[E][F] trains are delayed" into inline line bullets. */
    private CharSequence withBullets(String s, float textPx) {
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
        // Leading filled warning icon.
        android.graphics.drawable.Drawable warn = getDrawable(R.drawable.alert);
        int ws = Math.round(textPx * 1.6f);
        warn.setBounds(0, 0, ws, ws);
        sb.append("!");
        sb.setSpan(new android.text.style.ImageSpan(warn, android.text.style.DynamicDrawableSpan.ALIGN_CENTER),
                0, 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.append("  ");
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\[([A-Z0-9]{1,2})\\]").matcher(s);
        int last = 0;
        while (m.find()) {
            sb.append(s, last, m.start());
            int st = sb.length();
            sb.append(m.group(1));
            sb.setSpan(new RouteBullet(m.group(1), textPx * 1.45f, font("x-medium", Typeface.BOLD)),
                    st, sb.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            last = m.end();
        }
        sb.append(s.substring(last));
        return sb;
    }

    /** Draws a route letter as a colored circle bullet, inline with text. */
    static final class RouteBullet extends android.text.style.ReplacementSpan {
        final String route; final float d; final Typeface tf;
        RouteBullet(String r, float diameter, Typeface tf) { route = r; d = diameter; this.tf = tf; }

        @Override public int getSize(android.graphics.Paint p, CharSequence t, int s, int e, android.graphics.Paint.FontMetricsInt fm) {
            return Math.round(d + d * 0.18f);
        }

        @Override public void draw(android.graphics.Canvas c, CharSequence t, int s, int e, float x, int top, int y, int bottom, android.graphics.Paint p) {
            android.graphics.Paint q = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            float cy = (top + bottom) / 2f, cx = x + d / 2f;
            q.setColor(Subway.color(route));
            c.drawCircle(cx, cy, d / 2f, q);
            q.setColor(Subway.darkText(route) ? 0xFF111111 : 0xFFFFFFFF);
            q.setTypeface(tf);
            q.setTextSize(d * 0.66f);
            q.setTextAlign(android.graphics.Paint.Align.CENTER);
            android.graphics.Paint.FontMetrics fm = q.getFontMetrics();
            c.drawText(route, cx, cy - (fm.ascent + fm.descent) / 2f, q);
        }
    }

    private static void add(LinearLayout parent, View v, int w, int h, int left) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
        lp.leftMargin = left;
        lp.gravity = Gravity.CENTER_VERTICAL;
        parent.addView(v, lp);
    }

    // ---------- launching ----------

    private void launch(String pkg) {
        PackageManager pm = getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        if (i == null) {
            // Not installed: open its Play Store page instead.
            Intent store = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("market://details?id=" + pkg));
            if (store.resolveActivity(pm) != null) safeStart(store);
            else toast("Not installed: " + pkg);
            return;
        }
        safeStart(i);
    }

    private void safeStart(Intent i) {
        try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            toast("Couldn't open that");
        }
    }

    private void showAllApps() {
        PackageManager pm = getPackageManager();
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        List<ResolveInfo> apps = pm.queryIntentActivities(q, 0);
        final List<String> pkgs = new ArrayList<>();
        final List<String> names = new ArrayList<>();
        Collections.sort(apps, (a, b) -> a.loadLabel(pm).toString().compareToIgnoreCase(b.loadLabel(pm).toString()));
        for (ResolveInfo r : apps) {
            if (r.activityInfo.packageName.equals(getPackageName())) continue;
            pkgs.add(r.activityInfo.packageName);
            names.add(r.loadLabel(pm).toString());
        }
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Apps")
                .setItems(names.toArray(new String[0]), (d, which) -> launch(pkgs.get(which)))
                .show();
    }

    /** Xbox SmartGlass power-on packet. Console must be in "Sleep" power mode. When it boots
     *  it announces itself over HDMI-CEC and the projector switches to its input. */
    private void wakeXbox() {
        final String liveId = prefs.getString("xboxLiveId", "");
        final String ip = prefs.getString("xboxIp", "");
        if (liveId.isEmpty()) {
            toast("Xbox ID not set yet (Xbox: Settings > System > Console info)");
            return;
        }
        toast("Waking Xbox…");
        io.execute(() -> {
            try {
                byte[] id = liveId.getBytes(StandardCharsets.UTF_8);
                int payloadLen = 2 + id.length + 1;
                byte[] pkt = new byte[6 + payloadLen];
                pkt[0] = (byte) 0xDD; pkt[1] = 0x02;                       // type: power-on request
                pkt[2] = (byte) (payloadLen >> 8); pkt[3] = (byte) payloadLen; // unprotected payload length
                pkt[4] = 0; pkt[5] = 0;                                    // version
                pkt[6] = (byte) (id.length >> 8); pkt[7] = (byte) id.length;
                System.arraycopy(id, 0, pkt, 8, id.length);
                pkt[8 + id.length] = 0;
                DatagramSocket s = new DatagramSocket();
                s.setBroadcast(true);
                List<InetAddress> targets = new ArrayList<>();
                targets.add(InetAddress.getByName("255.255.255.255"));
                if (!ip.isEmpty()) targets.add(InetAddress.getByName(ip));
                for (int n = 0; n < 5; n++) {
                    for (InetAddress a : targets) s.send(new DatagramPacket(pkt, pkt.length, a, 5050));
                    Thread.sleep(100);
                }
                s.close();
            } catch (Exception e) {
                ui.post(() -> toast("Couldn't reach Xbox: " + e.getMessage()));
            }
        });
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    // ---------- clock ----------

    // ---------- calendar agenda (device calendars, on-device only) ----------

    private final Runnable agendaTick = new Runnable() {
        @Override public void run() {
            if (checkSelfPermission(android.Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED) {
                final String only = prefs.getString("calendars", "");
                io.execute(() -> {
                    try {
                        final List<Agenda.Event> ev = Agenda.upcoming(getContentResolver(), only, 3, 4);
                        ui.post(() -> renderAgenda(ev));
                    } catch (Exception e) {
                        android.util.Log.w("Frame", "agenda failed", e);
                    }
                });
            }
            ui.postDelayed(this, 5 * 60_000);
        }
    };

    private void renderAgenda(List<Agenda.Event> events) {
        agenda.removeAllViews();
        agenda.setVisibility(events.isEmpty() ? View.GONE : View.VISIBLE);
        int dim = 0xB3FFFFFF;
        java.util.Calendar today = java.util.Calendar.getInstance();
        String lastDay = null;
        for (Agenda.Event e : events) {
            java.util.Calendar d = java.util.Calendar.getInstance();
            d.setTimeInMillis(Math.max(e.begin, e.allDay ? e.begin : 0));
            int diff = dayDiff(today, d);
            if (diff < 0) diff = 0; // in-progress multi-day events count as today
            String day = diff == 0 ? "Today" : diff == 1 ? "Tomorrow"
                    : new SimpleDateFormat("EEEE", Locale.US).format(d.getTime());
            if (!day.equals(lastDay)) {
                TextView h = text(12, dim, "mont-700", Typeface.NORMAL);
                h.setShadowLayer(0, 0, 0, 0);
                h.setText(day.toUpperCase(Locale.US));
                h.setLetterSpacing(0.14f);
                LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(-2, -2);
                hl.topMargin = lastDay == null ? 0 : dp(12);
                agenda.addView(h, hl);
                lastDay = day;
            }
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            View dot = new View(this);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(e.color);
            dot.setBackground(g);
            add(row, dot, dp(10), dp(10), 0);
            TextView time = sign(15, 0xFFFFFFFF, true);
            time.setText(e.allDay ? "All day" : new SimpleDateFormat("h:mm a", Locale.US).format(new Date(e.begin)).toLowerCase(Locale.US));
            add(row, time, dp(72), -2, dp(10));
            TextView title = sign(16, 0xFFFFFFFF, false);
            title.setText(e.title);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, -2, 1f);
            tl.leftMargin = dp(8);
            tl.gravity = Gravity.CENTER_VERTICAL;
            row.addView(title, tl);
            LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2);
            rl.topMargin = dp(6);
            agenda.addView(row, rl);
        }
    }

    private static int dayDiff(java.util.Calendar a, java.util.Calendar b) {
        java.util.Calendar x = (java.util.Calendar) a.clone(), y = (java.util.Calendar) b.clone();
        for (java.util.Calendar c : new java.util.Calendar[]{x, y}) {
            c.set(java.util.Calendar.HOUR_OF_DAY, 0); c.set(java.util.Calendar.MINUTE, 0);
            c.set(java.util.Calendar.SECOND, 0); c.set(java.util.Calendar.MILLISECOND, 0);
        }
        return (int) Math.round((y.getTimeInMillis() - x.getTimeInMillis()) / 86_400_000.0);
    }

    private final Runnable clockTick = new Runnable() {
        @Override public void run() {
            Date now = new Date();
            clock.setText(new SimpleDateFormat("h:mm", Locale.US).format(now));
            dateDay.setText(new SimpleDateFormat("EEEE", Locale.US).format(now).toUpperCase(Locale.US));
            date.setText(new SimpleDateFormat("MMM d", Locale.US).format(now).toUpperCase(Locale.US));
            ui.postDelayed(this, CLOCK_MS);
        }
    };

    // ---------- network helpers ----------

    static byte[] fetch(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestProperty("User-Agent", UA);
        c.setConnectTimeout(10_000);
        c.setReadTimeout(20_000);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        } finally {
            c.disconnect();
        }
    }

    static JSONObject json(String url) throws Exception {
        return new JSONObject(new String(fetch(url), StandardCharsets.UTF_8));
    }

    // ---------- weather (Open-Meteo, no key) ----------

    private final Runnable weatherTick = new Runnable() {
        @Override public void run() {
            io.execute(() -> {
                try {
                    JSONObject w = json("https://api.open-meteo.com/v1/forecast?latitude=" + LAT + "&longitude=" + LON
                            + "&current=temperature_2m,weather_code&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max"
                            + "&temperature_unit=fahrenheit&timezone=America%2FNew_York&forecast_days=1");
                    JSONObject cur = w.getJSONObject("current");
                    JSONObject d = w.getJSONObject("daily");
                    double f = cur.getDouble("temperature_2m");
                    final String now = Math.round(f) + "°F   " + c(f) + "°C";
                    double hi = d.getJSONArray("temperature_2m_max").getDouble(0);
                    double lo = d.getJSONArray("temperature_2m_min").getDouble(0);
                    int rain = d.getJSONArray("precipitation_probability_max").optInt(0, 0);
                    final String day = describe(cur.getInt("weather_code")) + "  ·  H " + Math.round(hi) + "°/" + c(hi) + "°  ·  L " + Math.round(lo) + "°/" + c(lo) + "°"
                            + (rain >= 20 ? "  ·  " + rain + "% rain" : "");
                    ui.post(() -> { weatherNow.setText(now); weatherDay.setText(day); });
                } catch (Exception ignored) { }
            });
            ui.postDelayed(this, WEATHER_MS);
        }
    };

    private static long c(double f) {
        return Math.round((f - 32) * 5 / 9);
    }

    private static String describe(int code) {
        if (code == 0) return "Clear";
        if (code <= 2) return "Partly cloudy";
        if (code == 3) return "Cloudy";
        if (code == 45 || code == 48) return "Fog";
        if (code >= 51 && code <= 57) return "Drizzle";
        if (code >= 61 && code <= 67) return "Rain";
        if (code >= 71 && code <= 77) return "Snow";
        if (code >= 80 && code <= 82) return "Showers";
        if (code == 85 || code == 86) return "Snow showers";
        if (code >= 95) return "Storms";
        return "";
    }

    // ---------- subway (MTA realtime arrivals + alerts, no key) ----------

    private final Runnable subwayTick = new Runnable() {
        @Override public void run() {
            final List<Subway.Station> stations = Subway.parseStops(prefs.getString("subwayStops", Subway.DEFAULT_STOPS));
            if (stations.isEmpty()) {
                subwayCard.setVisibility(View.GONE);
            } else {
                final boolean refreshAlert = subwayTicks++ % 4 == 0;   // alerts every ~2 min, trains every 30 s
                io.execute(() -> {
                    Map<String, List<Subway.Arrival>> arr = null;
                    try {
                        arr = Subway.arrivals(stations);
                    } catch (Exception e) {
                        android.util.Log.w("Frame", "subway arrivals failed", e);
                    }
                    Subway.Alert al = lastAlert;
                    if (refreshAlert) {
                        Set<String> routes = new LinkedHashSet<>();
                        for (Subway.Station s : stations) routes.addAll(s.routes);
                        try { al = Subway.alert(routes); } catch (Exception ignored) { }
                    }
                    final Map<String, List<Subway.Arrival>> fa = arr;
                    final Subway.Alert fal = al;
                    ui.post(() -> { lastAlert = fal; renderSubway(stations, fa, fal); });
                });
            }
            ui.postDelayed(this, SUBWAY_MS);
        }
    };

    private void renderSubway(List<Subway.Station> stations, Map<String, List<Subway.Arrival>> arr, Subway.Alert alert) {
        if (arr == null && subwayRows.getChildCount() > 0) return;   // keep last board on a failed refresh
        subwayRows.removeAllViews();
        long now = System.currentTimeMillis() / 1000;
        int dim = 0xCCFFFFFF;
        for (int si = 0; si < stations.size(); si++) {
            Subway.Station st = stations.get(si);
            LinearLayout head = new LinearLayout(this);
            head.setOrientation(LinearLayout.HORIZONTAL);
            head.setGravity(Gravity.CENTER_VERTICAL);
            for (int i = 0; i < st.routes.size(); i++) add(head, bullet(st.routes.get(i), 26), dp(26), dp(26), i == 0 ? 0 : dp(4));
            TextView name = text(19, 0xFFFFFFFF, "sans-serif-medium", Typeface.NORMAL);
            name.setShadowLayer(0, 0, 0, 0);
            name.setText(st.name);
            add(head, name, -2, -2, dp(10));
            LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(-2, -2);
            hlp.topMargin = si == 0 ? 0 : dp(12);
            subwayRows.addView(head, hlp);

            for (String dir : new String[]{"N", "S"}) {
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                TextView lbl = sign(15, dim, false);
                lbl.setText(dir.equals("N") ? "Manhattan" : "Brooklyn");
                add(row, lbl, dp(100), -2, 0);
                List<Subway.Arrival> list = arr == null ? null : arr.get(st.id + dir);
                int shown = 0;
                if (list != null) {
                    for (Subway.Arrival a : list) {
                        if (shown == 3) break;
                        long min = Math.max(0, (a.time - now) / 60);
                        if (st.routes.size() > 1) add(row, bullet(a.route, 18), dp(18), dp(18), shown == 0 ? 0 : dp(12));
                        TextView m = sign(20, 0xFFFFFFFF, true);
                        m.setText(min == 0 ? "now" : String.valueOf(min));
                        add(row, m, -2, -2, st.routes.size() > 1 ? dp(5) : (shown == 0 ? 0 : dp(14)));
                        shown++;
                    }
                }
                TextView unit = sign(13, dim, false);
                unit.setText(shown == 0 ? "no trains listed" : "min");
                add(row, unit, -2, -2, shown == 0 ? 0 : dp(6));
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-2, -2);
                rlp.topMargin = dp(4);
                subwayRows.addView(row, rlp);
            }
        }
        if (alert != null && alert.text != null) {
            subwayAlert.setText(withBullets(alert.text, subwayAlert.getTextSize()));
            subwayAlert.setVisibility(View.VISIBLE);
        } else {
            subwayAlert.setVisibility(View.GONE);
        }
        subwayCard.setVisibility(View.VISIBLE);
    }

    // ---------- photos (Wikimedia Commons featured pictures, no key) ----------

    private final Runnable photoTick = new Runnable() {
        @Override public void run() {
            nextPhoto();
            ui.postDelayed(this, PHOTO_MS);
        }
    };

    private void refillPool() throws Exception {
        for (int attempt = 0; attempt < 4 && pool.size() < 3; attempt++) {
            String cat = CATEGORIES[rnd.nextInt(CATEGORIES.length)];
            char prefix = (char) ('A' + rnd.nextInt(26));
            JSONObject r = json("https://commons.wikimedia.org/w/api.php?action=query&format=json"
                    + "&generator=categorymembers&gcmtitle=Category:" + URLEncoder.encode(cat, "UTF-8")
                    + "&gcmtype=file&gcmlimit=40&gcmsort=sortkey&gcmstartsortkeyprefix=" + prefix
                    + "&prop=imageinfo%7Ccoordinates&iiprop=url%7Csize%7Cextmetadata&iiurlwidth=1920"
                    + "&iiextmetadatafilter=ObjectName%7CArtist");
            JSONObject q = r.optJSONObject("query");
            if (q == null) continue;
            JSONObject pages = q.getJSONObject("pages");
            List<Photo> batch = new ArrayList<>();
            Iterator<String> it = pages.keys();
            while (it.hasNext()) {
                JSONObject p = pages.getJSONObject(it.next());
                JSONArray ii = p.optJSONArray("imageinfo");
                if (ii == null) continue;
                JSONObject info = ii.getJSONObject(0);
                int w = info.optInt("width"), h = info.optInt("height");
                if (w < 1600 || h == 0) continue;
                double ratio = (double) w / h;
                if (ratio < 1.3 || ratio > 2.2) continue; // landscape-ish only, no tall shots or huge panoramas
                Photo ph = new Photo();
                ph.url = info.optString("thumburl", info.optString("url"));
                JSONObject meta = info.optJSONObject("extmetadata");
                String title = meta != null && meta.optJSONObject("ObjectName") != null
                        ? meta.getJSONObject("ObjectName").optString("value") : "";
                if (title.isEmpty()) title = p.optString("title").replaceFirst("^File:", "").replaceFirst("\\.[A-Za-z]+$", "");
                // Tidy filename-style titles: drop trailing "-- 2019 -- 3489" style serials, use dots as separators.
                title = strip(title).replace('_', ' ')
                        .replaceAll("(\\s*(--|–|—|-)\\s*\\d+)+\\s*$", "")
                        .replaceAll("\\s+(--|–|—)\\s+", "  ·  ");
                ph.title = title;
                String artist = meta != null && meta.optJSONObject("Artist") != null
                        ? meta.getJSONObject("Artist").optString("value") : "";
                ph.artist = strip(artist).replaceAll("(?i)\\s*\\((talk|website|contribs)\\)", "").trim();
                JSONArray co = p.optJSONArray("coordinates");
                if (co != null && co.length() > 0) {
                    ph.lat = co.getJSONObject(0).optDouble("lat");
                    ph.lon = co.getJSONObject(0).optDouble("lon");
                    ph.hasCoords = true;
                }
                batch.add(ph);
            }
            Collections.shuffle(batch, rnd);
            // Take only a few per batch so one photographer's series doesn't dominate.
            for (int i = 0; i < Math.min(4, batch.size()); i++) pool.add(batch.get(i));
        }
    }

    private static String strip(String html) {
        String s = Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY).toString().replaceAll("[\\s\\u00A0\\u200B-\\u200F\\uFEFF]+", " ").trim();
        return s.length() > 90 ? s.substring(0, 87) + "…" : s;
    }

    private void nextPhoto() {
        if (photoLoading) return;
        photoLoading = true;
        io.execute(() -> {
            try {
                if (pool.size() < 2) refillPool();
                if (pool.isEmpty()) return;
                Photo ph = pool.remove(0);
                byte[] data = fetch(ph.url);
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inPreferredConfig = Bitmap.Config.RGB_565;
                final Bitmap bmp = BitmapFactory.decodeByteArray(data, 0, data.length, o);
                if (bmp == null) return;
                final String place = placeName(ph);
                ui.post(() -> showPhoto(bmp, ph, place));
            } catch (Exception ignored) {
            } finally {
                photoLoading = false;
            }
        });
    }

    private String placeName(Photo ph) {
        if (!ph.hasCoords) return cleanTitle(ph.title);
        try {
            if (Geocoder.isPresent()) {
                @SuppressWarnings("deprecation")
                List<Address> a = new Geocoder(this, Locale.US).getFromLocation(ph.lat, ph.lon, 1);
                if (a != null && !a.isEmpty()) {
                    Address ad = a.get(0);
                    List<String> parts = new ArrayList<>();
                    for (String s : new String[]{ad.getLocality(), ad.getAdminArea(), ad.getCountryName()}) {
                        if (s != null && !s.isEmpty() && !parts.contains(s) && parts.size() < 3) parts.add(s);
                    }
                    // Keep it short enough to read whole: drop the region when city + region + country runs long,
                    // and fall back to the country alone if it is still too long for the caption.
                    if (parts.size() == 3 && TextUtils.join(", ", parts).length() > 28) parts.remove(1);
                    if (parts.size() == 2 && TextUtils.join(", ", parts).length() > 30) parts.remove(0);
                    if (!parts.isEmpty()) return TextUtils.join(", ", parts);
                }
            }
        } catch (Exception ignored) { }
        return cleanTitle(ph.title);
    }

    /** A photo title is only worth showing as the place if it reads like words: short, Latin, not a camera filename. */
    private static String cleanTitle(String t) {
        if (t == null) return "";
        t = t.trim();
        if (t.isEmpty() || t.length() > 48) return "";
        if (t.matches(".*\\d{3,}.*")) return "";                       // serials, dates, DSC_1234
        if (t.matches("(?i).*\\b(img|dsc|dscn|dscf|pano|jpe?g|png)\\b.*")) return "";
        for (int i = 0; i < t.length(); i++) {                          // non-Latin scripts aren't readable here
            char ch = t.charAt(i);
            if (Character.isLetter(ch) && Character.UnicodeScript.of(ch) != Character.UnicodeScript.LATIN) return "";
        }
        return t;
    }

    private void showPhoto(Bitmap bmp, Photo ph, String place) {
        // imgFront sits above imgBack; crossfade by fading the top layer in or out.
        if (showingTop) {
            imgBack.setImageBitmap(bmp);
            imgFront.animate().alpha(0f).setDuration(2000).withEndAction(() -> imgFront.setImageDrawable(null)).start();
            showingTop = false;
        } else {
            imgFront.setAlpha(0f);
            imgFront.setImageBitmap(bmp);
            imgFront.animate().alpha(1f).setDuration(2000).withEndAction(() -> imgBack.setImageDrawable(null)).start();
            showingTop = true;
        }
        hasPhoto = true;
        photoPlace.setText(place);
        photoPlace.setVisibility(place == null || place.isEmpty() ? View.GONE : View.VISIBLE);
        photoCredit.setText(ph.artist.isEmpty() ? "Wikimedia Commons" : ph.artist + "  ·  Wikimedia Commons");
    }
}
