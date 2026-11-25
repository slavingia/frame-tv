package com.home.frame;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** MTA realtime arrivals (GTFS-realtime protobuf) and service alerts (JSON). No API key needed. */
final class Subway {

    /** No stops by default (board hidden). Set with: --es subwayStops "A27|42 St|A,C,E;R20|14 St|N,Q,R" */
    static final String DEFAULT_STOPS = "";
    private static final String BASE = "https://api-endpoint.mta.info/Dataservice/mtagtfsfeeds/";

    static final class Station {
        String id, name;
        List<String> routes = new ArrayList<>();
    }

    static final class Arrival implements Comparable<Arrival> {
        final String route;
        final long time;
        Arrival(String r, long t) { route = r; time = t; }
        @Override public int compareTo(Arrival o) { return Long.compare(time, o.time); }
    }

    static final class Alert {
        final Set<String> routes = new LinkedHashSet<>();
        String text;
    }

    /** "STOP_ID|Name|ROUTES;..." e.g. "A27|42 St|A,C,E;R20|14 St|N,Q,R" (GTFS stop ids, without N/S) */
    static List<Station> parseStops(String cfg) {
        List<Station> out = new ArrayList<>();
        if (cfg == null) return out;
        for (String part : cfg.split(";")) {
            String[] f = part.trim().split("\\|");
            if (f.length < 3) continue;
            Station s = new Station();
            s.id = f[0].trim().toUpperCase(Locale.US);
            s.name = f[1].trim();
            for (String r : f[2].split("[,\\s]+")) if (!r.isEmpty()) s.routes.add(r.toUpperCase(Locale.US));
            out.add(s);
        }
        return out;
    }

    private static Set<String> feedsFor(Set<String> routes) {
        Set<String> feeds = new LinkedHashSet<>();
        for (String r : routes) {
            if ("ACEH".contains(r) || r.equals("FS")) feeds.add("nyct%2Fgtfs-ace");
            if ("BDFM".contains(r)) feeds.add("nyct%2Fgtfs-bdfm");
            if (r.equals("G")) feeds.add("nyct%2Fgtfs-g");
            if (r.equals("J") || r.equals("Z")) feeds.add("nyct%2Fgtfs-jz");
            if ("NQRW".contains(r)) feeds.add("nyct%2Fgtfs-nqrw");
            if (r.equals("L")) feeds.add("nyct%2Fgtfs-l");
            if (r.matches("[1-7]X?|GS|S")) feeds.add("nyct%2Fgtfs");
        }
        // A/C and F trains get rerouted onto each other's tracks; check both feeds.
        if (feeds.contains("nyct%2Fgtfs-ace") || feeds.contains("nyct%2Fgtfs-bdfm")) {
            feeds.add("nyct%2Fgtfs-ace");
            feeds.add("nyct%2Fgtfs-bdfm");
        }
        return feeds;
    }

    /** Returns stopId+direction ("A27N") -> upcoming arrivals, soonest first. */
    static Map<String, List<Arrival>> arrivals(List<Station> stations) throws Exception {
        Set<String> stops = new HashSet<>();
        Set<String> routes = new HashSet<>();
        for (Station s : stations) {
            stops.add(s.id + "N");
            stops.add(s.id + "S");
            routes.addAll(s.routes);
        }
        Map<String, List<Arrival>> out = new HashMap<>();
        Set<String> seen = new HashSet<>();
        Exception last = null;
        int ok = 0;
        for (String feed : feedsFor(routes)) {
            try {
                parseFeed(MainActivity.fetch(BASE + feed), stops, routes, out, seen);
                ok++;
            } catch (Exception e) {
                last = e;
            }
        }
        if (ok == 0 && last != null) throw last;
        long now = System.currentTimeMillis() / 1000;
        for (List<Arrival> l : out.values()) {
            for (int i = l.size() - 1; i >= 0; i--) if (l.get(i).time < now - 20) l.remove(i);
            Collections.sort(l);
        }
        return out;
    }

    // --- minimal protobuf reader for GTFS-realtime ---

    private static final class Pb {
        final byte[] b;
        int p;
        final int end;
        int tag, wire;

        Pb(byte[] b, int start, int end) { this.b = b; this.p = start; this.end = end; }

        boolean next() {
            if (p >= end) return false;
            long k = varint();
            tag = (int) (k >>> 3);
            wire = (int) (k & 7);
            return true;
        }

        long varint() {
            long r = 0;
            int shift = 0;
            while (true) {
                int x = b[p++] & 0xff;
                r |= (long) (x & 0x7f) << shift;
                if (x < 0x80) return r;
                shift += 7;
            }
        }

        Pb sub() {
            int len = (int) varint();
            Pb s = new Pb(b, p, p + len);
            p += len;
            return s;
        }

        String str() {
            int len = (int) varint();
            String s = new String(b, p, len, StandardCharsets.UTF_8);
            p += len;
            return s;
        }

        void skip() {
            switch (wire) {
                case 0: varint(); break;
                case 1: p += 8; break;
                case 2: { int len = (int) varint(); p += len; break; } // not "p += varint()": p would be read before varint() advances it
                case 5: p += 4; break;
                default: throw new IllegalStateException("bad wire type " + wire);
            }
        }
    }

    private static void parseFeed(byte[] data, Set<String> stops, Set<String> routes,
                                  Map<String, List<Arrival>> out, Set<String> seen) {
        Pb msg = new Pb(data, 0, data.length);
        while (msg.next()) {
            if (msg.tag != 2 || msg.wire != 2) { msg.skip(); continue; }        // FeedMessage.entity
            Pb ent = msg.sub();
            while (ent.next()) {
                if (ent.tag != 3 || ent.wire != 2) { ent.skip(); continue; }    // FeedEntity.trip_update
                Pb tu = ent.sub();
                String route = null, tripId = null;
                List<String> stopIds = new ArrayList<>();
                List<Long> times = new ArrayList<>();
                while (tu.next()) {
                    if (tu.tag == 1 && tu.wire == 2) {                           // TripUpdate.trip
                        Pb trip = tu.sub();
                        while (trip.next()) {
                            if (trip.tag == 1 && trip.wire == 2) tripId = trip.str();
                            else if (trip.tag == 5 && trip.wire == 2) route = trip.str();
                            else trip.skip();
                        }
                    } else if (tu.tag == 2 && tu.wire == 2) {                    // TripUpdate.stop_time_update
                        Pb stu = tu.sub();
                        String stop = null;
                        long arr = 0, dep = 0;
                        while (stu.next()) {
                            if (stu.tag == 4 && stu.wire == 2) stop = stu.str();
                            else if ((stu.tag == 2 || stu.tag == 3) && stu.wire == 2) {
                                boolean isArr = stu.tag == 2;
                                Pb ev = stu.sub();
                                while (ev.next()) {
                                    if (ev.tag == 2 && ev.wire == 0) {
                                        long t = ev.varint();
                                        if (isArr) arr = t; else dep = t;
                                    } else ev.skip();
                                }
                            } else stu.skip();
                        }
                        if (stop != null && stops.contains(stop)) {
                            stopIds.add(stop);
                            times.add(arr != 0 ? arr : dep);
                        }
                    } else tu.skip();
                }
                if (route == null) continue;
                route = route.toUpperCase(Locale.US);
                if (!routes.contains(route)) continue;
                for (int i = 0; i < stopIds.size(); i++) {
                    long t = times.get(i);
                    if (t == 0) continue;
                    String key = stopIds.get(i);
                    if (!seen.add(key + "|" + (tripId != null ? tripId : route + t))) continue;
                    List<Arrival> l = out.get(key);
                    if (l == null) out.put(key, l = new ArrayList<>());
                    l.add(new Arrival(route, t));
                }
            }
        }
    }

    // --- service alerts ---

    static Alert alert(Set<String> mine) throws Exception {
        JSONArray ents = MainActivity.json(BASE + "camsys%2Fsubway-alerts.json").getJSONArray("entity");
        long now = System.currentTimeMillis() / 1000;
        Alert out = new Alert();
        for (int i = 0; i < ents.length(); i++) {
            JSONObject a = ents.getJSONObject(i).optJSONObject("alert");
            if (a == null || !activeNow(a.optJSONArray("active_period"), now)) continue;
            JSONArray ie = a.optJSONArray("informed_entity");
            if (ie == null) continue;
            Set<String> hit = new LinkedHashSet<>();
            for (int j = 0; j < ie.length(); j++) {
                String r = ie.getJSONObject(j).optString("route_id", "").toUpperCase(Locale.US);
                if (mine.contains(r)) hit.add(r);
            }
            if (hit.isEmpty()) continue;
            out.routes.addAll(hit);
            if (out.text == null) {
                String h = firstText(a.optJSONObject("header_text"));
                if (h != null) {
                    // Alerts can bundle several lines' messages; keep the sentence about our lines.
                    for (String line : h.split("\\n")) {
                        boolean ours = false;
                        for (String m : hit) if (line.contains("[" + m + "]")) ours = true;
                        if (ours) { out.text = line.trim(); break; }
                    }
                }
            }
        }
        return out;
    }

    private static boolean activeNow(JSONArray periods, long now) {
        if (periods == null || periods.length() == 0) return true;
        for (int i = 0; i < periods.length(); i++) {
            JSONObject p = periods.optJSONObject(i);
            if (p == null) continue;
            long s = p.optLong("start", 0), e = p.optLong("end", 0);
            if (e == 0) e = Long.MAX_VALUE;
            if (now >= s && now <= e) return true;
        }
        return false;
    }

    private static String firstText(JSONObject ts) {
        if (ts == null) return null;
        JSONArray tr = ts.optJSONArray("translation");
        if (tr == null) return null;
        for (int i = 0; i < tr.length(); i++) {
            JSONObject t = tr.optJSONObject(i);
            if (t != null && "en".equals(t.optString("language", "en"))) return t.optString("text", null);
        }
        return tr.length() > 0 ? tr.optJSONObject(0).optString("text", null) : null;
    }

    // --- official MTA line colors ---

    static int color(String r) {
        switch (r) {
            case "A": case "C": case "E": return 0xFF0039A6;
            case "B": case "D": case "F": case "M": return 0xFFFF6319;
            case "G": return 0xFF6CBE45;
            case "J": case "Z": return 0xFF996633;
            case "L": return 0xFFA7A9AC;
            case "N": case "Q": case "R": case "W": return 0xFFFCCC0A;
            case "1": case "2": case "3": return 0xFFEE352E;
            case "4": case "5": case "6": return 0xFF00933C;
            case "7": return 0xFFB933AD;
            default: return 0xFF808183;
        }
    }

    static boolean darkText(String r) {
        return "NQRW".contains(r) && r.length() == 1;
    }
}
