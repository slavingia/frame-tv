package com.home.frame;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CalendarContract;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Upcoming events from calendars synced on the device (the TV's Google account). Data never leaves the device. */
final class Agenda {

    static final class Event {
        String title, calendar;
        long begin, end;
        boolean allDay, past;
        int color;
    }

    /**
     * @param only comma-separated calendar display names to include; empty = every calendar marked visible.
     */
    static List<Event> upcoming(ContentResolver cr, String only, int days, int max) {
        Set<String> names = new HashSet<>();
        if (only != null) for (String n : only.split(",")) if (!n.trim().isEmpty()) names.add(n.trim().toLowerCase(Locale.US));

        long now = System.currentTimeMillis();
        java.util.Calendar midnight = java.util.Calendar.getInstance();
        midnight.set(java.util.Calendar.HOUR_OF_DAY, 0); midnight.set(java.util.Calendar.MINUTE, 0);
        midnight.set(java.util.Calendar.SECOND, 0); midnight.set(java.util.Calendar.MILLISECOND, 0);
        long dayStart = midnight.getTimeInMillis();
        Uri.Builder b = CalendarContract.Instances.CONTENT_URI.buildUpon();
        ContentUris.appendId(b, now - 86_400_000L);       // include all-day events that started "today" in UTC
        ContentUris.appendId(b, now + days * 86_400_000L);
        String[] proj = {
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END,
                CalendarContract.Instances.ALL_DAY,
                CalendarContract.Instances.DISPLAY_COLOR,
                CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
                CalendarContract.Instances.VISIBLE,
        };
        List<Event> out = new ArrayList<>();
        try (Cursor c = cr.query(b.build(), proj, null, null, CalendarContract.Instances.BEGIN + " ASC")) {
            if (c == null) return out;
            while (c.moveToNext() && out.size() < 40) {
                Event e = new Event();
                e.title = c.getString(0);
                e.begin = c.getLong(1);
                e.end = c.getLong(2);
                e.allDay = c.getInt(3) == 1;
                e.color = c.getInt(4) | 0xFF000000;
                e.calendar = c.getString(5);
                boolean visible = c.getInt(6) == 1;
                if (names.isEmpty() ? !visible : !names.contains(String.valueOf(e.calendar).toLowerCase(Locale.US))) continue;
                if (e.allDay) {
                    // All-day events are stored as UTC midnights; shift to local midnights.
                    java.util.TimeZone tz = java.util.TimeZone.getDefault();
                    e.begin -= tz.getOffset(e.begin);
                    e.end -= tz.getOffset(e.end);
                }
                // Keep everything from today, even what's already over (shown dimmed); drop earlier days.
                if (e.end <= dayStart) continue;
                e.past = e.end <= now;
                if (e.title == null || e.title.trim().isEmpty()) e.title = "(busy)";
                // The same event often lives on two calendars (sometimes with a shortened title): show it once.
                boolean dup = false;
                for (Event o : out) {
                    if (o.begin != e.begin || o.allDay != e.allDay) continue;
                    String a = norm(o.title), t = norm(e.title);
                    if (a.equals(t) || (t.length() >= 4 && a.contains(t))) { dup = true; break; }
                    if (a.length() >= 4 && t.contains(a)) { o.title = e.title; dup = true; break; }
                }
                if (dup) continue;
                out.add(e);
            }
        }
        // Too many to fit: give up finished events first (oldest first), then trim the far future.
        while (out.size() > max && out.get(0).past) out.remove(0);
        while (out.size() > max) out.remove(out.size() - 1);
        return out;
    }

    private static String norm(String s) {
        return s.toLowerCase(Locale.US).replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }
}
