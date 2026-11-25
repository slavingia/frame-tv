package com.home.frame;

import java.util.Calendar;

/** Sunrise/sunset and moon phase, computed on the device (no network). Accurate to a couple of minutes. */
final class Night {

    private Night() { }

    /** {sunrise, sunset} in epoch millis for the local calendar day containing {@code t}. */
    static long[] sun(long t, double lat, double lon) {
        Calendar noon = Calendar.getInstance();
        noon.setTimeInMillis(t);
        noon.set(Calendar.HOUR_OF_DAY, 12); noon.set(Calendar.MINUTE, 0);
        noon.set(Calendar.SECOND, 0); noon.set(Calendar.MILLISECOND, 0);
        double jdNoon = noon.getTimeInMillis() / 86_400_000.0 + 2440587.5;
        // Sunrise equation (en.wikipedia.org/wiki/Sunrise_equation), longitude east-positive.
        double n = Math.round(jdNoon - 2451545.0 + lon / 360.0);
        double jStar = n - lon / 360.0;
        double m = norm(357.5291 + 0.98560028 * jStar);
        double mr = Math.toRadians(m);
        double c = 1.9148 * Math.sin(mr) + 0.0200 * Math.sin(2 * mr) + 0.0003 * Math.sin(3 * mr);
        double lambda = Math.toRadians(norm(m + c + 180 + 102.9372));
        double jTransit = 2451545.0 + jStar + 0.0053 * Math.sin(mr) - 0.0069 * Math.sin(2 * lambda);
        double sinDec = Math.sin(lambda) * Math.sin(Math.toRadians(23.4397));
        double cosDec = Math.cos(Math.asin(sinDec));
        double phi = Math.toRadians(lat);
        double cosW = (Math.sin(Math.toRadians(-0.833)) - Math.sin(phi) * sinDec) / (Math.cos(phi) * cosDec);
        cosW = Math.max(-1, Math.min(1, cosW));   // polar day/night: clamp
        double w = Math.toDegrees(Math.acos(cosW)) / 360.0;
        return new long[]{toMillis(jTransit - w), toMillis(jTransit + w)};
    }

    /** True between today's sunset and tomorrow's sunrise (and before today's sunrise). */
    static boolean isNight(long t, double lat, double lon) {
        long[] s = sun(t, lat, lon);
        return t < s[0] || t >= s[1];
    }

    /** Moon phase 0..1: 0 = new, 0.25 = first quarter, 0.5 = full, 0.75 = last quarter. */
    static double moonPhase(long t) {
        final double synodic = 29.530588853;
        final long newMoon = 947182440000L;   // 2000-01-06 18:14 UTC
        double days = (t - newMoon) / 86_400_000.0;
        double p = (days % synodic) / synodic;
        return p < 0 ? p + 1 : p;
    }

    private static double norm(double deg) {
        deg %= 360;
        return deg < 0 ? deg + 360 : deg;
    }

    private static long toMillis(double jd) {
        return Math.round((jd - 2440587.5) * 86_400_000.0);
    }
}
