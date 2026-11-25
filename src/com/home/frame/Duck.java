package com.home.frame;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.Random;
import java.util.concurrent.ExecutorService;

/** "Alexa, duck": a full-screen duck photo with a big DUCK! — dismissed by any remote key or after 30 s. */
final class Duck {

    private static final Random RND = new Random();
    private static final java.util.Map<String, Integer> last = new java.util.HashMap<>();

    static boolean knows(String noun) { return Nouns.PHOTOS.containsKey(noun); }

    private final FrameLayout root;
    private final Handler ui;
    private final Runnable onDismiss;
    private FrameLayout overlay;
    private final Runnable autoClose = this::dismiss;

    Duck(FrameLayout root, Handler ui, Runnable onDismiss) {
        this.root = root;
        this.ui = ui;
        this.onDismiss = onDismiss;
    }

    boolean showing() { return overlay != null; }

    void show(String noun, Typeface display, Typeface caption, ExecutorService io) {
        final String[][] photos = Nouns.PHOTOS.get(noun);
        if (photos == null) return;
        Integer prev = last.get(noun);
        int i;
        do { i = RND.nextInt(photos.length); } while (prev != null && i == prev && photos.length > 1);
        last.put(noun, i);
        final String[] pick = photos[i];
        final String shout = noun.toUpperCase(java.util.Locale.US) + "!";
        final String url = "https://commons.wikimedia.org/wiki/Special:FilePath/" + Uri.encode(pick[0]) + "?width=1920";
        io.execute(() -> {
            try {
                byte[] data = MainActivity.fetch(url);
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inPreferredConfig = Bitmap.Config.RGB_565;
                final Bitmap bmp = BitmapFactory.decodeByteArray(data, 0, data.length, o);
                if (bmp != null) ui.post(() -> present(bmp, shout, pick[1], display, caption));
            } catch (Exception e) {
                android.util.Log.w("Frame", "picture fetch failed: " + pick[0], e);
            }
        });
    }

    private void present(Bitmap bmp, String shout, String species, Typeface display, Typeface caption) {
        Context c = root.getContext();
        // Crossfade: the old picture stays underneath until the new one is fully in, so home never peeks through.
        final FrameLayout previous = overlay;
        if (previous != null) { previous.animate().cancel(); previous.setAlpha(1f); }
        removeStale(previous);   // anything older than the picture on screen (rapid-fire words)
        overlay = new FrameLayout(c);
        overlay.setTag(TAG);
        overlay.setBackgroundColor(0xFF000000);

        ImageView img = new ImageView(c);
        // Wide shots fill the screen; tall ones (penguins, owls) fit so nobody loses their head.
        boolean wide = bmp.getWidth() >= bmp.getHeight() * 1.3f;
        img.setScaleType(wide ? ImageView.ScaleType.CENTER_CROP : ImageView.ScaleType.FIT_CENTER);
        img.setImageBitmap(bmp);
        overlay.addView(img, new FrameLayout.LayoutParams(-1, -1));

        View scrim = new View(c);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0xB3000000, 0x00000000}));
        overlay.addView(scrim, new FrameLayout.LayoutParams(-1, dp(c, 260), Gravity.BOTTOM));

        TextView word = new TextView(c);
        word.setText(shout);
        word.setTypeface(display);
        word.setTextColor(0xFFFFD866);
        word.setTextSize(TypedValue.COMPLEX_UNIT_SP, shout.length() <= 8 ? 120 : 92);
        word.setShadowLayer(dp(c, 14), 0, dp(c, 4), 0xCC000000);
        word.setIncludeFontPadding(false);
        FrameLayout.LayoutParams wl = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        wl.bottomMargin = dp(c, 52);
        overlay.addView(word, wl);

        TextView sub = new TextView(c);
        sub.setText(species + "  ·  Wikimedia Commons");
        sub.setTypeface(caption);
        sub.setTextColor(0xB3FFFFFF);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        FrameLayout.LayoutParams sl = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        sl.bottomMargin = dp(c, 24);
        overlay.addView(sub, sl);

        // Any remote key closes it.
        overlay.setFocusable(true);
        overlay.setFocusableInTouchMode(true);
        overlay.setOnKeyListener((v, code, ev) -> {
            if (ev.getAction() == android.view.KeyEvent.ACTION_UP) dismiss();
            return true;
        });

        overlay.setAlpha(0f);
        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        overlay.requestFocus();
        overlay.animate().alpha(1f).setDuration(350).withEndAction(() -> {
            if (previous != null) root.removeView(previous);
        }).start();
        img.setScaleX(1.08f);
        img.setScaleY(1.08f);
        img.animate().scaleX(1f).scaleY(1f).setDuration(6000).start();
        word.setScaleX(0.2f);
        word.setScaleY(0.2f);
        word.animate().scaleX(1f).scaleY(1f).setStartDelay(250).setDuration(600)
                .setInterpolator(new OvershootInterpolator(2.2f)).start();

        ui.removeCallbacks(autoClose);
        ui.postDelayed(autoClose, 30_000);
    }

    void dismiss() {
        ui.removeCallbacks(autoClose);
        final FrameLayout o = overlay;
        overlay = null;
        if (o == null) return;
        removeStale(o);
        o.animate().alpha(0f).setDuration(300).withEndAction(() -> {
            root.removeView(o);
            if (onDismiss != null) onDismiss.run();
        }).start();
    }

    private static final String TAG = "picture-overlay";

    /** Removes every picture overlay except {@code keep}. */
    private void removeStale(View keep) {
        for (int i = root.getChildCount() - 1; i >= 0; i--) {
            View v = root.getChildAt(i);
            if (v != keep && TAG.equals(v.getTag())) root.removeViewAt(i);
        }
    }

    private static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }
}
