package tz.utamu.whitelist;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;
import java.util.UUID;

/** One device on the whitelist. status: pending | applied | failed. */
final class Device {
    String id;
    String name;
    String mac;          // always AA:BB:CC:DD:EE:FF
    String status = "pending";
    String error;
    boolean cloud;       // also lives in Supabase

    static Device local(String name, String mac) {
        Device d = new Device();
        d.id = UUID.randomUUID().toString();
        d.name = name;
        d.mac = mac;
        return d;
    }

    JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("name", name);
        o.put("mac", mac);
        o.put("status", status);
        o.put("error", error == null ? JSONObject.NULL : error);
        o.put("cloud", cloud);
        return o;
    }

    static Device fromJson(JSONObject o) {
        Device d = new Device();
        d.id = o.optString("id");
        d.name = o.optString("name");
        d.mac = o.optString("mac");
        d.status = o.optString("status", "pending");
        d.error = o.isNull("error") ? null : o.optString("error", null);
        d.cloud = o.optBoolean("cloud", false);
        return d;
    }

    // ---- MAC helpers ----

    /** Returns AA:BB:CC:DD:EE:FF or null when it isn't 12 hex digits. */
    static String normalizeMac(String raw) {
        if (raw == null) return null;
        String hex = raw.replaceAll("[^0-9A-Fa-f]", "");
        if (hex.length() != 12) return null;
        hex = hex.toUpperCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 12; i += 2) {
            if (i > 0) sb.append(':');
            sb.append(hex, i, i + 2);
        }
        return sb.toString();
    }

    /** Private / randomised MACs have the "locally administered" bit set. */
    static boolean looksRandom(String mac) {
        return (Integer.parseInt(mac.substring(0, 2), 16) & 0x02) != 0;
    }

    /** style: colon-upper | colon-lower | dash-upper | dash-lower | plain-upper | plain-lower */
    static String formatMac(String mac, String style) {
        String sep = ":";
        if (style.startsWith("dash")) sep = "-";
        else if (style.startsWith("plain")) sep = "";
        String out = mac.replace(":", sep);
        return style.endsWith("lower") ? out.toLowerCase(Locale.ROOT) : out.toUpperCase(Locale.ROOT);
    }
}
