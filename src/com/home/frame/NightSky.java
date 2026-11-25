package com.home.frame;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;

import java.util.Random;

/**
 * Bedtime sky: a deep blue gradient, softly twinkling stars, the odd shooting star,
 * and tonight's real moon phase. Calm on purpose: nothing moves fast or flashes.
 */
final class NightSky extends View {

    private static final int STARS = 220;
    private final float[] sx = new float[STARS], sy = new float[STARS], sr = new float[STARS];
    private final float[] base = new float[STARS], speed = new float[STARS], phase = new float[STARS];
    private final int[] tint = new int[STARS];
    private final Random rnd = new Random();
    private final Paint star = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sky = new Paint();
    private final Paint moonLit = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint moonDark = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint streak = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path lit = new Path();
    private final RectF circle = new RectF(), ellipse = new RectF();
    private final float density;
    private long nextShootAt, shootStart;
    private float shootX, shootY, shootDx, shootDy;
    private boolean running;

    NightSky(Context c) {
        super(c);
        density = c.getResources().getDisplayMetrics().density;
        for (int i = 0; i < STARS; i++) {
            sx[i] = rnd.nextFloat();
            sy[i] = (float) Math.pow(rnd.nextFloat(), 1.4);   // a little denser toward the top
            boolean bright = rnd.nextFloat() < 0.08f;
            sr[i] = (bright ? 1.6f + rnd.nextFloat() : 0.5f + rnd.nextFloat() * 0.9f) * density;
            base[i] = bright ? 0.9f : 0.35f + rnd.nextFloat() * 0.45f;
            speed[i] = 0.25f + rnd.nextFloat() * 0.9f;          // slow twinkle
            phase[i] = rnd.nextFloat() * 6.283f;
            float k = rnd.nextFloat();
            tint[i] = k < 0.15f ? 0xFFCFE0FF : k < 0.25f ? 0xFFFFF1D6 : 0xFFFFFFFF;
        }
        moonLit.setColor(0xFFF4EEDC);
        moonDark.setColor(0x1AF4EEDC);   // faint earthshine on the dark side
        streak.setStrokeCap(Paint.Cap.ROUND);
        streak.setStrokeWidth(2f * density);
        nextShootAt = SystemClock.uptimeMillis() + 20_000;
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        sky.setShader(new LinearGradient(0, 0, 0, h,
                new int[]{0xFF02040B, 0xFF060E24, 0xFF101A38}, new float[]{0f, 0.6f, 1f}, Shader.TileMode.CLAMP));
    }

    void start() { running = true; invalidate(); }   // also re-kicks the draw loop after the app comes back

    void stop() { running = false; }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        long now = SystemClock.uptimeMillis();
        float t = now / 1000f;
        c.drawRect(0, 0, w, h, sky);

        for (int i = 0; i < STARS; i++) {
            float a = base[i] * (0.65f + 0.35f * (float) Math.sin(t * speed[i] + phase[i]));
            star.setColor(tint[i]);
            star.setAlpha((int) (255 * Math.max(0, Math.min(1, a))));
            c.drawCircle(sx[i] * w, sy[i] * h * 0.85f, sr[i], star);
        }

        drawMoon(c, w * 0.5f, 92 * density, 30 * density, Night.moonPhase(System.currentTimeMillis()));

        // A shooting star every 25-60 s, gliding down-right over ~1.2 s.
        if (now >= nextShootAt && shootStart == 0) {
            shootStart = now;
            shootX = (0.15f + rnd.nextFloat() * 0.6f) * w;
            shootY = rnd.nextFloat() * h * 0.35f;
            shootDx = (0.18f + rnd.nextFloat() * 0.1f) * w;
            shootDy = shootDx * 0.35f;
        }
        if (shootStart != 0) {
            float p = (now - shootStart) / 1200f;
            if (p >= 1) {
                shootStart = 0;
                nextShootAt = now + 25_000 + rnd.nextInt(35_000);
            } else {
                float hx = shootX + shootDx * p, hy = shootY + shootDy * p;
                float tx = hx - shootDx * 0.25f, ty = hy - shootDy * 0.25f;
                int alpha = (int) (220 * Math.sin(Math.PI * p));
                streak.setShader(new LinearGradient(tx, ty, hx, hy, 0x00FFFFFF, (alpha << 24) | 0xFFFFFF, Shader.TileMode.CLAMP));
                c.drawLine(tx, ty, hx, hy, streak);
            }
        }
        if (running && getVisibility() == VISIBLE) postInvalidateDelayed(40);
    }

    /** phase 0 = new, 0.5 = full; northern-hemisphere view (waxing lit on the right). */
    private void drawMoon(Canvas c, float cx, float cy, float r, double phase) {
        glow.setShader(new RadialGradient(cx, cy, r * 3.2f, 0x33FFF4D6, 0x00FFF4D6, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, r * 3.2f, glow);
        c.drawCircle(cx, cy, r, moonDark);

        double k = (1 - Math.cos(2 * Math.PI * phase)) / 2;   // illuminated fraction
        if (k < 0.02) return;                                   // new moon: just the earthshine
        float rx = (float) (r * Math.abs(Math.cos(2 * Math.PI * phase)));
        circle.set(cx - r, cy - r, cx + r, cy + r);
        ellipse.set(cx - rx, cy - r, cx + rx, cy + r);
        lit.reset();
        lit.moveTo(cx, cy - r);
        lit.arcTo(circle, -90, 180);                           // lit limb: top, right side, bottom
        lit.arcTo(ellipse, 90, k > 0.5 ? 180 : -180);          // terminator back to the top
        lit.close();
        c.save();
        if (phase > 0.5) c.scale(-1, 1, cx, cy);               // waning: lit on the left
        c.drawPath(lit, moonLit);
        c.restore();
    }
}
