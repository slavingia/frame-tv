package com.home.frame;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** What the Sonos speakers on the LAN are playing (local UPnP on port 1400, no account needed). */
final class Sonos {

    static final class NowPlaying {
        boolean playing;
        String title = "", artist = "", album = "", art;
        List<String> rooms = new ArrayList<>();
        int position, duration;   // seconds
        String key() { return title + "\u0000" + artist; }
    }

    private static final class Player { String ip, room, uuid; }

    private final Map<String, Player> players = new LinkedHashMap<>();
    private long discoveredAt;
    private volatile String seeds = "";

    /** Known speaker IPs (comma-separated), checked even when SSDP discovery gets no answer. */
    void setSeeds(String ips) { seeds = ips == null ? "" : ips; }

    /** Blocking: call off the UI thread. */
    NowPlaying poll() {
        if (players.isEmpty() || System.currentTimeMillis() - discoveredAt > 10 * 60_000) discover();
        NowPlaying best = null;
        Map<String, String> follows = new LinkedHashMap<>();   // room -> coordinator uuid
        Player coord = null;
        for (Player p : new ArrayList<>(players.values())) {
            try {
                String pos = soap(p.ip, "GetPositionInfo");
                String uri = unescape(tag(pos, "TrackURI"));
                if (uri.startsWith("x-rincon:")) { follows.put(p.room, uri.substring(9)); continue; }
                String state = tag(soap(p.ip, "GetTransportInfo"), "CurrentTransportState");
                NowPlaying np = parse(p, pos);
                np.playing = "PLAYING".equals(state) || "TRANSITIONING".equals(state);
                if (best == null || (np.playing && !best.playing)) { best = np; coord = p; }
            } catch (Exception e) {
                android.util.Log.w("Frame", "sonos " + p.ip + ": " + e);
            }
        }
        if (best == null) return new NowPlaying();
        best.rooms.add(coord.room);
        for (Map.Entry<String, String> f : follows.entrySet()) if (f.getValue().equals(coord.uuid)) best.rooms.add(f.getKey());
        return best;
    }

    private static NowPlaying parse(Player p, String pos) {
        NowPlaying np = new NowPlaying();
        String didl = unescape(tag(pos, "TrackMetaData"));
        np.title = unescape(tag(didl, "dc:title"));
        np.artist = unescape(tag(didl, "dc:creator"));
        np.album = unescape(tag(didl, "upnp:album"));
        String stream = unescape(tag(didl, "r:streamContent"));
        if (!stream.isEmpty()) {   // radio: "Artist - Title"
            int dash = stream.indexOf(" - ");
            if (dash > 0) { np.artist = stream.substring(0, dash); np.title = stream.substring(dash + 3); }
            else np.title = stream;
        }
        String art = unescape(tag(didl, "upnp:albumArtURI"));
        if (!art.isEmpty()) np.art = art.startsWith("http") ? art : "http://" + p.ip + ":1400" + art;
        np.position = secs(tag(pos, "RelTime"));
        np.duration = secs(tag(pos, "TrackDuration"));
        return np;
    }

    private void discover() {
        discoveredAt = System.currentTimeMillis();
        try (DatagramSocket s = new DatagramSocket()) {
            s.setSoTimeout(1500);
            byte[] msg = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 1\r\n"
                    + "ST: urn:schemas-upnp-org:device:ZonePlayer:1\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
            InetAddress group = InetAddress.getByName("239.255.255.250");
            s.send(new DatagramPacket(msg, msg.length, group, 1900));
            s.send(new DatagramPacket(msg, msg.length, group, 1900));
            byte[] buf = new byte[2048];
            List<String> ips = new ArrayList<>();
            long end = System.currentTimeMillis() + 2500;
            while (System.currentTimeMillis() < end) {
                DatagramPacket r = new DatagramPacket(buf, buf.length);
                try { s.receive(r); } catch (SocketTimeoutException e) { break; }
                String ip = r.getAddress().getHostAddress();
                if (!ips.contains(ip)) ips.add(ip);
            }
            int heard = ips.size();
            for (String ip : seeds.split(",")) { ip = ip.trim(); if (!ip.isEmpty() && !ips.contains(ip)) ips.add(ip); }
            android.util.Log.i("Frame", "sonos discovery: " + heard + " answered, checking " + ips);
            for (String ip : ips) {
                if (players.containsKey(ip)) continue;
                try {
                    String d = new String(get("http://" + ip + ":1400/xml/device_description.xml"), StandardCharsets.UTF_8);
                    Player p = new Player();
                    p.ip = ip;
                    p.room = unescape(tag(d, "roomName"));
                    p.uuid = tag(d, "UDN").replace("uuid:", "");
                    if (!p.uuid.isEmpty()) players.put(ip, p);
                } catch (Exception e) {
                    android.util.Log.w("Frame", "sonos " + ip + " description: " + e);
                }
            }
        } catch (Exception e) {
            android.util.Log.w("Frame", "sonos discovery: " + e);
        }
    }

    private static String soap(String ip, String action) throws Exception {
        String body = "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" "
                + "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body><u:" + action
                + " xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\"><InstanceID>0</InstanceID></u:" + action
                + "></s:Body></s:Envelope>";
        HttpURLConnection c = (HttpURLConnection) new URL("http://" + ip + ":1400/MediaRenderer/AVTransport/Control").openConnection();
        c.setConnectTimeout(2000);
        c.setReadTimeout(3000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"");
        c.setRequestProperty("SOAPACTION", "\"urn:schemas-upnp-org:service:AVTransport:1#" + action + "\"");
        try (OutputStream o = c.getOutputStream()) { o.write(body.getBytes(StandardCharsets.UTF_8)); }
        try (InputStream in = c.getInputStream()) { return new String(readAll(in), StandardCharsets.UTF_8); }
        finally { c.disconnect(); }
    }

    static byte[] get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(3000);
        c.setReadTimeout(5000);
        try (InputStream in = c.getInputStream()) { return readAll(in); }
        finally { c.disconnect(); }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        for (int n; (n = in.read(buf)) > 0; ) b.write(buf, 0, n);
        return b.toByteArray();
    }

    private static String tag(String xml, String name) {
        Matcher m = Pattern.compile("<" + Pattern.quote(name) + "(?:\\s[^>]*)?>(.*?)</" + Pattern.quote(name) + ">", Pattern.DOTALL).matcher(xml);
        return m.find() ? m.group(1).trim() : "";
    }

    private static String unescape(String s) {
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&");
    }

    private static int secs(String t) {
        try {
            String[] p = t.split(":");
            if (p.length != 3) return 0;
            return Integer.parseInt(p[0]) * 3600 + Integer.parseInt(p[1]) * 60 + Integer.parseInt(p[2]);
        } catch (Exception e) { return 0; }
    }
}
