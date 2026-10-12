package tz.utamu.whitelist;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final int ACCENT = Color.rgb(31, 95, 191);

    private SharedPreferences prefs;
    private final List<Device> devices = new ArrayList<>();
    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private RouterRunner runner;
    private boolean busy = false;
    private String routerPassword = "";       // memory only, never saved

    // views
    private EditText urlIn, userIn, passIn, nameIn, macIn, keyIn, stepsIn;
    private LinearLayout listBox, settingsBox;
    private TextView summary, logView;
    private Button runBtn, syncBtn;
    private WebView web;
    private CheckBox showRouter;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("whitelist", MODE_PRIVATE);
        loadDevices();
        setContentView(buildUi());
        runner = new RouterRunner(web);
        render();
        if (!key().isEmpty()) sync(false);
    }

    @Override
    protected void onDestroy() {
        if (runner != null) runner.stop();
        bg.shutdownNow();
        super.onDestroy();
    }

    // =============================================================== UI

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout root = column();
        int p = dp(16);
        root.setPadding(p, p, p, dp(40));
        scroll.addView(root);

        TextView eyebrow = text("VIETTEL ROUTER · WI-FI MAC WHITELIST", 12, false);
        eyebrow.setTextColor(Color.GRAY);
        eyebrow.setLetterSpacing(0.06f);
        root.addView(eyebrow);
        root.addView(text("UTAMU Whitelist", 26, true));
        summary = text("", 14, false);
        summary.setTextColor(Color.GRAY);
        root.addView(summary);

        // ---- add device
        LinearLayout add = card(root, "Add a device");
        nameIn = input(add, "Name, e.g. Juma's phone", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        macIn = input(add, "Wi-Fi MAC, e.g. A4:C3:F0:12:34:56", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        macIn.setTypeface(Typeface.MONOSPACE);
        macIn.addTextChangedListener(new MacFormatter(macIn));
        TextView hint = text("Android: Settings → About phone → Status. iPhone: Settings → General → About → Wi-Fi Address. "
                + "Turn off Private / Random MAC for UTAMU on that phone first.", 13, false);
        hint.setTextColor(Color.GRAY);
        add.addView(hint);
        add.addView(button("Add device", true, v -> onAddClicked()));

        // ---- devices
        LinearLayout list = card(root, "Devices");
        listBox = column();
        list.addView(listBox);
        TextView note = text("Remove only takes a device off this list. To block it, delete it on the router's Access Control page too.", 13, false);
        note.setTextColor(Color.GRAY);
        list.addView(note);

        // ---- router
        LinearLayout router = card(root, "Router");
        urlIn = input(router, "Router address", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        urlIn.setText(prefs.getString("router_url", "http://192.168.1.1"));
        userIn = input(router, "Admin username", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        userIn.setText(prefs.getString("username", "admin"));
        passIn = input(router, "Admin password (not saved)", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        TextView wifi = text("This phone must be connected to the UTAMU Wi-Fi to reach the router.", 13, false);
        wifi.setTextColor(Color.GRAY);
        router.addView(wifi);
        runBtn = button("Add waiting devices to router", true, v -> onRunClicked());
        router.addView(runBtn);
        syncBtn = button("Get new devices from the page", false, v -> sync(true));
        router.addView(syncBtn);
        showRouter = new CheckBox(this);
        showRouter.setText("Show the router page while working");
        showRouter.setChecked(prefs.getBoolean("show_router", true));
        showRouter.setOnCheckedChangeListener((b, on) -> {
            prefs.edit().putBoolean("show_router", on).apply();
            sizeWeb();
        });
        router.addView(showRouter);
        web = new WebView(this);
        router.addView(web, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        sizeWeb();

        // ---- activity log
        LinearLayout log = card(root, "Activity");
        logView = text("", 12, false);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextIsSelectable(true);
        log.addView(logView);

        // ---- settings (collapsed)
        Button settingsToggle = button("Settings ▾", false, null);
        root.addView(settingsToggle, margins(0, dp(16), 0, 0));
        settingsBox = card(root, null);
        settingsBox.setVisibility(View.GONE);
        settingsToggle.setOnClickListener(v -> {
            boolean open = settingsBox.getVisibility() == View.VISIBLE;
            settingsBox.setVisibility(open ? View.GONE : View.VISIBLE);
            settingsToggle.setText(open ? "Settings ▾" : "Settings ▴");
        });

        settingsBox.addView(text("Whitelist page key (optional)", 16, true));
        TextView keyHelp = text("Links this app to the UTAMU Wi-Fi Whitelist page in claude.ai, so devices added there come here. "
                + "Create it on the page: Connect your PC → Create PC key.", 13, false);
        keyHelp.setTextColor(Color.GRAY);
        settingsBox.addView(keyHelp);
        keyIn = input(settingsBox, "Paste key (rw_…)", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        keyIn.setText(key());
        settingsBox.addView(button("Save key", false, v -> {
            prefs.edit().putString("page_key", keyIn.getText().toString().trim()).apply();
            log(key().isEmpty() ? "Page link removed. The app works on its own." : "Page key saved.");
            render();
            if (!key().isEmpty()) sync(true);
        }));

        settingsBox.addView(text("Router steps", 16, true), margins(0, dp(16), 0, 0));
        TextView stepsHelp = text("The clicks the app makes in the router page. If a step fails, the log names it; "
                + "fix its selector here. CSS selectors, or text=Words, alternatives separated by ||.", 13, false);
        stepsHelp.setTextColor(Color.GRAY);
        settingsBox.addView(stepsHelp);
        stepsIn = input(settingsBox, "", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        stepsIn.setTypeface(Typeface.MONOSPACE);
        stepsIn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        stepsIn.setGravity(Gravity.TOP | Gravity.START);
        stepsIn.setMinLines(8);
        stepsIn.setHorizontallyScrolling(false);
        stepsIn.setText(steps());
        LinearLayout stepBtns = new LinearLayout(this);
        stepBtns.addView(button("Save steps", false, v -> {
            String s = stepsIn.getText().toString();
            try {
                JSONObject o = new JSONObject(s);
                if (o.optJSONArray("login") == null || o.optJSONArray("add_device") == null)
                    throw new Exception("needs \"login\" and \"add_device\" lists");
                prefs.edit().putString("steps", s).apply();
                toast("Steps saved");
            } catch (Exception e) {
                alert("Steps not saved", "That isn't valid: " + e.getMessage());
            }
        }), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        stepBtns.addView(button("Reset", false, v -> {
            prefs.edit().remove("steps").apply();
            stepsIn.setText(steps());
            toast("Steps reset");
        }), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        settingsBox.addView(stepBtns);

        return scroll;
    }

    private void render() {
        int pending = 0, applied = 0, failed = 0;
        for (Device d : devices) {
            if ("applied".equals(d.status)) applied++;
            else if ("failed".equals(d.status)) failed++;
            else pending++;
        }
        summary.setText(applied + " on router  ·  " + pending + " waiting  ·  " + failed + " failed");
        syncBtn.setVisibility(key().isEmpty() ? View.GONE : View.VISIBLE);
        runBtn.setEnabled(!busy);
        runBtn.setText(busy ? "Working…" : "Add waiting devices to router");

        listBox.removeAllViews();
        if (devices.isEmpty()) {
            TextView t = text("No devices yet. Add your own phone first, so you don't lock yourself out.", 14, false);
            t.setTextColor(Color.GRAY);
            listBox.addView(t);
            return;
        }
        for (Device d : devices) listBox.addView(row(d));
    }

    private View row(Device d) {
        LinearLayout r = column();
        r.setPadding(0, dp(10), 0, dp(10));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout left = column();
        left.addView(text(d.name, 16, true));
        TextView mac = text(d.mac + (d.cloud ? "  · from page" : ""), 13, false);
        mac.setTypeface(Typeface.MONOSPACE);
        mac.setTextColor(Color.GRAY);
        left.addView(mac);
        top.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        top.addView(chip(d.status));
        r.addView(top);

        if ("failed".equals(d.status) && d.error != null) {
            TextView e = text(d.error, 13, false);
            e.setTextColor(Color.rgb(180, 35, 24));
            r.addView(e);
        }

        LinearLayout acts = new LinearLayout(this);
        if ("failed".equals(d.status)) {
            acts.addView(smallButton("Try again", v -> {
                d.status = "pending";
                d.error = null;
                saveDevices();
                render();
            }));
        }
        Button del = smallButton("Remove", null);
        del.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Remove " + d.name + "?")
                .setMessage("This removes it from this list only."
                        + (d.cloud ? " Remove it on the whitelist page too." : ""))
                .setPositiveButton("Remove", (dlg, w) -> {
                    devices.remove(d);
                    saveDevices();
                    render();
                })
                .setNegativeButton("Cancel", null)
                .show());
        acts.addView(del);
        r.addView(acts);

        View line = new View(this);
        line.setBackgroundColor(Color.argb(40, 0, 0, 0));
        LinearLayout wrap = column();
        wrap.addView(r);
        wrap.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        return wrap;
    }

    private TextView chip(String status) {
        String label = "applied".equals(status) ? "on router" : "failed".equals(status) ? "failed" : "waiting";
        int fg = "applied".equals(status) ? Color.rgb(23, 128, 61) : "failed".equals(status) ? Color.rgb(180, 35, 24) : Color.rgb(154, 91, 0);
        int bgc = "applied".equals(status) ? Color.rgb(228, 244, 234) : "failed".equals(status) ? Color.rgb(253, 232, 230) : Color.rgb(253, 240, 217);
        TextView t = text(label, 12, false);
        t.setTextColor(fg);
        t.setTypeface(Typeface.MONOSPACE);
        GradientDrawable g = new GradientDrawable();
        g.setColor(bgc);
        g.setCornerRadius(dp(12));
        t.setBackground(g);
        t.setPadding(dp(10), dp(3), dp(10), dp(3));
        return t;
    }

    private void sizeWeb() {
        if (web == null) return;
        // Keep the WebView attached even when hidden, so pages still load.
        web.getLayoutParams().height = showRouter.isChecked() ? dp(420) : dp(1);
        web.requestLayout();
    }

    // =========================================================== actions

    private void onAddClicked() {
        String name = nameIn.getText().toString().replaceAll("\\p{Cntrl}", "").trim();
        String mac = Device.normalizeMac(macIn.getText().toString());
        if (name.isEmpty()) { alert("Missing name", "Give the device a name, like \"Juma's phone\"."); return; }
        if (name.length() > 31) name = name.substring(0, 31);
        if (mac == null) { alert("Check the MAC", "A MAC address has 12 characters (0–9, A–F), like A4:C3:F0:12:34:56."); return; }
        for (Device d : devices) {
            if (d.mac.equals(mac)) { alert("Already listed", mac + " is already on the list."); return; }
        }
        final String finalName = name;
        if (Device.looksRandom(mac)) {
            new AlertDialog.Builder(this)
                    .setTitle("This MAC looks random")
                    .setMessage("Phones use a private MAC per network that can change, and then the phone gets blocked.\n\n"
                            + "On that phone, open the UTAMU Wi-Fi settings and turn off Private / Random MAC, then use its real MAC.")
                    .setPositiveButton("Add anyway", (dlg, w) -> addDevice(finalName, mac))
                    .setNegativeButton("Cancel", null)
                    .show();
        } else {
            addDevice(finalName, mac);
        }
    }

    private void addDevice(String name, String mac) {
        String k = key();
        if (k.isEmpty()) {
            devices.add(Device.local(name, mac));
            saveDevices();
            clearAddForm();
            render();
            log("Added " + name + " (" + mac + "). It's waiting to go to the router.");
            return;
        }
        // Linked to the page: add it there too, so both lists agree.
        bg.execute(() -> {
            try {
                String id = Cloud.add(k, name, mac);
                runOnUiThread(() -> {
                    Device d = Device.local(name, mac);
                    d.id = id;
                    d.cloud = true;
                    devices.add(d);
                    saveDevices();
                    clearAddForm();
                    render();
                    log("Added " + name + " (" + mac + ") here and on the page.");
                });
            } catch (Exception e) {
                runOnUiThread(() -> alert("Couldn't add", e.getMessage()));
            }
        });
    }

    private void clearAddForm() {
        nameIn.setText("");
        macIn.setText("");
    }

    /** Pull devices added on the page that are waiting. */
    private void sync(boolean thenTellUser) {
        String k = key();
        if (k.isEmpty()) return;
        bg.execute(() -> {
            try {
                JSONArray rows = Cloud.pull(k);
                runOnUiThread(() -> {
                    int added = mergeCloud(rows);
                    if (thenTellUser || added > 0)
                        log(added > 0 ? added + " new device(s) from the page." : "No new devices on the page.");
                    render();
                });
            } catch (Exception e) {
                runOnUiThread(() -> log(e.getMessage()));
            }
        });
    }

    private int mergeCloud(JSONArray rows) {
        int added = 0;
        for (int i = 0; i < rows.length(); i++) {
            JSONObject o = rows.optJSONObject(i);
            if (o == null) continue;
            String id = o.optString("id");
            String mac = Device.normalizeMac(o.optString("mac"));
            if (mac == null) continue;
            Device found = null;
            for (Device d : devices) {
                if (d.id.equals(id) || d.mac.equals(mac)) { found = d; break; }
            }
            if (found == null) {
                Device d = Device.local(o.optString("name", "Device"), mac);
                d.id = id;
                d.cloud = true;
                devices.add(d);
                added++;
            } else {
                found.id = id;
                found.cloud = true;
                found.status = "pending";
                found.error = null;
            }
        }
        saveDevices();
        return added;
    }

    private void onRunClicked() {
        if (busy) return;
        String pw = passIn.getText().toString();
        if (pw.isEmpty()) { alert("Router password", "Type the router admin password first. It isn't saved."); return; }
        routerPassword = pw;
        prefs.edit()
                .putString("router_url", urlIn.getText().toString().trim())
                .putString("username", userIn.getText().toString().trim())
                .apply();
        if (!prefs.getBoolean("lockout_warned", false)) {
            new AlertDialog.Builder(this)
                    .setTitle("Before you start")
                    .setMessage("Once whitelist mode is turned ON in the router, every device not on the list is cut off from Wi-Fi.\n\n"
                            + "Add this phone first. Turn whitelist mode on yourself, last, on the router's Access Control page.")
                    .setPositiveButton("Continue", (dlg, w) -> {
                        prefs.edit().putBoolean("lockout_warned", true).apply();
                        startRun();
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        } else {
            startRun();
        }
    }

    private void startRun() {
        String k = key();
        if (k.isEmpty()) { applyWaiting(); return; }
        busy = true;
        render();
        // Pick up anything added on the page first.
        bg.execute(() -> {
            JSONArray rows = null;
            String err = null;
            try { rows = Cloud.pull(k); } catch (Exception e) { err = e.getMessage(); }
            final JSONArray fRows = rows;
            final String fErr = err;
            runOnUiThread(() -> {
                if (fRows != null) {
                    int n = mergeCloud(fRows);
                    if (n > 0) log(n + " new device(s) from the page.");
                } else {
                    log("Couldn't check the page (" + fErr + "). Continuing with this phone's list.");
                }
                busy = false;
                applyWaiting();
            });
        });
    }

    private void applyWaiting() {
        List<Device> waiting = new ArrayList<>();
        for (Device d : devices) if ("pending".equals(d.status)) waiting.add(d);
        if (waiting.isEmpty()) { toast("No waiting devices"); render(); return; }

        JSONObject cfg;
        try { cfg = new JSONObject(steps()); }
        catch (Exception e) { alert("Router steps", "The router steps in Settings aren't valid. Tap Reset."); return; }

        Map<String, String> base = new HashMap<>();
        String url = urlIn.getText().toString().trim();
        if (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        if (!url.startsWith("http")) url = "http://" + url;
        base.put("router_url", url);
        base.put("username", userIn.getText().toString().trim());
        base.put("password", routerPassword);

        busy = true;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        render();
        log("Sending " + waiting.size() + " device(s) to " + url);

        String k = key();
        runner.run(cfg, base, waiting, new RouterRunner.Listener() {
            @Override public void log(String line) { MainActivity.this.log(line); }

            @Override public void deviceResult(Device d, boolean ok, String error) {
                d.status = ok ? "applied" : "failed";
                d.error = ok ? null : error;
                saveDevices();
                render();
                if (d.cloud && !k.isEmpty()) {
                    bg.execute(() -> {
                        try { Cloud.mark(k, d.id, d.status, d.error); }
                        catch (Exception e) { runOnUiThread(() -> MainActivity.this.log("  (couldn't update the page: " + e.getMessage() + ")")); }
                    });
                }
            }

            @Override public void finished(boolean loginOk) {
                busy = false;
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                render();
                MainActivity.this.log(loginOk ? "Done." : "Nothing was changed on the router.");
            }
        });
    }

    // ======================================================== storage

    private String key() { return prefs.getString("page_key", ""); }

    private String steps() {
        String s = prefs.getString("steps", null);
        return s != null ? s : readAsset("router_steps.json");
    }

    private void loadDevices() {
        devices.clear();
        try {
            JSONArray a = new JSONArray(prefs.getString("devices", "[]"));
            for (int i = 0; i < a.length(); i++) devices.add(Device.fromJson(a.getJSONObject(i)));
        } catch (Exception ignored) { }
    }

    private void saveDevices() {
        JSONArray a = new JSONArray();
        try { for (Device d : devices) a.put(d.toJson()); } catch (Exception ignored) { }
        prefs.edit().putString("devices", a.toString()).apply();
    }

    private String readAsset(String name) {
        try (InputStream is = getAssets().open(name)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
            return new String(bo.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "{}";
        }
    }

    // ======================================================== helpers

    private void log(String line) {
        String t = new SimpleDateFormat("HH:mm", Locale.ROOT).format(new Date());
        CharSequence old = logView.getText();
        String all = t + " " + line + "\n" + old;      // newest first
        if (all.length() > 8000) all = all.substring(0, 8000);
        logView.setText(all);
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    private void alert(String title, String msg) {
        new AlertDialog.Builder(this).setTitle(title).setMessage(msg).setPositiveButton("OK", null).show();
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private LinearLayout column() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private LinearLayout.LayoutParams margins(int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.setMargins(l, t, r, b);
        return p;
    }

    private LinearLayout card(LinearLayout parent, String title) {
        LinearLayout c = column();
        int p = dp(14);
        c.setPadding(p, p, p, p);
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.WHITE);
        g.setStroke(dp(1), Color.rgb(217, 222, 228));
        g.setCornerRadius(dp(10));
        c.setBackground(g);
        if (title != null) c.addView(text(title, 18, true));
        parent.addView(c, margins(0, dp(16), 0, 0));
        return c;
    }

    private TextView text(String s, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(Color.rgb(22, 32, 43));
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private EditText input(LinearLayout parent, String hint, int type) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setInputType(type);
        e.setTextColor(Color.rgb(22, 32, 43));
        e.setHintTextColor(Color.GRAY);
        parent.addView(e, margins(0, dp(6), 0, 0));
        return e;
    }

    private Button button(String label, boolean primary, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        if (primary) {
            GradientDrawable g = new GradientDrawable();
            g.setColor(ACCENT);
            g.setCornerRadius(dp(8));
            b.setBackground(g);
            b.setTextColor(Color.WHITE);
        }
        if (l != null) b.setOnClickListener(l);
        b.setLayoutParams(margins(0, dp(8), 0, 0));
        return b;
    }

    private Button smallButton(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        if (l != null) b.setOnClickListener(l);
        return b;
    }

    /** Adds colons while typing a MAC: a4c3 → A4:C3 */
    private static final class MacFormatter implements TextWatcher {
        private final EditText field;
        private boolean editing = false;
        MacFormatter(EditText f) { field = f; }
        @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
        @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
        @Override public void afterTextChanged(Editable s) {
            if (editing) return;
            String hex = s.toString().replaceAll("[^0-9A-Fa-f]", "").toUpperCase(Locale.ROOT);
            if (hex.length() > 12) hex = hex.substring(0, 12);
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < hex.length(); i++) {
                if (i > 0 && i % 2 == 0) out.append(':');
                out.append(hex.charAt(i));
            }
            if (!out.toString().equals(s.toString())) {
                editing = true;
                field.setText(out);
                field.setSelection(out.length());
                editing = false;
            }
        }
    }
}
