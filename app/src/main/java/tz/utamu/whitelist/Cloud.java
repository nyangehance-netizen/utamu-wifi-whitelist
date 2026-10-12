package tz.utamu.whitelist;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Optional link to the "UTAMU Wi-Fi Whitelist" page in Supabase.
 * The project address and publishable key grant nothing on their own:
 * every call also needs the key created on the page (Connect your PC).
 * Call these from a background thread.
 */
final class Cloud {
    static final String URL_BASE = "https://imraessjxravlenvabwn.supabase.co";
    static final String PUBLISHABLE_KEY = "sb_publishable_sNoQpZAxcKhxebU-G2pvkw_k9UCWCA3";

    private Cloud() {}

    static JSONArray pull(String key) throws Exception {
        JSONObject a = new JSONObject();
        a.put("p_key", key);
        Object r = rpc("router_pull", a);
        return r instanceof JSONArray ? (JSONArray) r : new JSONArray();
    }

    static void mark(String key, String id, String status, String error) throws Exception {
        JSONObject a = new JSONObject();
        a.put("p_key", key);
        a.put("p_id", id);
        a.put("p_status", status);
        a.put("p_error", error == null ? JSONObject.NULL : error);
        rpc("router_mark", a);
    }

    static String add(String key, String name, String mac) throws Exception {
        JSONObject a = new JSONObject();
        a.put("p_key", key);
        a.put("p_name", name);
        a.put("p_mac", mac);
        Object r = rpc("router_add", a);
        if (!(r instanceof String)) throw new Exception("Supabase gave an unexpected answer.");
        return (String) r;
    }

    private static Object rpc(String fn, JSONObject args) throws Exception {
        HttpURLConnection c;
        try {
            c = (HttpURLConnection) new URL(URL_BASE + "/rest/v1/rpc/" + fn).openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(15000);
            c.setReadTimeout(20000);
            c.setDoOutput(true);
            c.setRequestProperty("apikey", PUBLISHABLE_KEY);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("Accept", "application/json");
            try (OutputStream os = c.getOutputStream()) {
                os.write(args.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (java.io.IOException e) {
            throw new Exception("Can't reach Supabase. Check mobile data or Wi-Fi internet.");
        }
        int code = c.getResponseCode();
        String body = read(code < 400 ? c.getInputStream() : c.getErrorStream());
        c.disconnect();
        if (code >= 400) {
            String msg = body;
            try { msg = new JSONObject(body).optString("message", body); } catch (Exception ignored) { }
            if (msg.contains("invalid PC key"))
                throw new Exception("The page key was rejected. Create a new one on the whitelist page (Connect your PC).");
            if (msg.contains("duplicate key") || msg.contains("router_whitelist_mac_key"))
                throw new Exception("That MAC address is already on the list.");
            throw new Exception("Supabase error " + code + ": " + (msg.length() > 200 ? msg.substring(0, 200) : msg));
        }
        if (body.trim().isEmpty()) return null;
        return new JSONTokener(body).nextValue();
    }

    private static String read(InputStream is) throws java.io.IOException {
        if (is == null) return "";
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
        is.close();
        return new String(bo.toByteArray(), StandardCharsets.UTF_8);
    }
}
