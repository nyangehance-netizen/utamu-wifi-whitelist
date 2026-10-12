package tz.utamu.whitelist;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.webkit.HttpAuthHandler;
import android.webkit.JsResult;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Drives the router's own web page inside a WebView, one step at a time,
 * the same way a person would: fill fields, click menus, press Apply.
 * Everything runs on the main thread through a Handler.
 */
final class RouterRunner {

    interface Listener {
        void log(String line);
        void deviceResult(Device d, boolean ok, String error);
        void finished(boolean loginOk);
    }

    private interface Done { void run(boolean ok, String err); }

    private final WebView web;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Listener listener;
    private volatile boolean loading = false;
    private boolean stopped = false;
    private String authUser = "", authPass = "";

    RouterRunner(WebView web) {
        this.web = web;
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageStarted(WebView v, String url, Bitmap favicon) { loading = true; }
            @Override public void onPageFinished(WebView v, String url) { loading = false; }
            @Override public void onReceivedHttpAuthRequest(WebView v, HttpAuthHandler h, String host, String realm) {
                h.proceed(authUser, authPass);   // routers that use a browser login box
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onJsAlert(WebView v, String url, String msg, JsResult r) {
                if (listener != null) listener.log("    router says: " + msg);
                r.confirm(); return true;
            }
            @Override public boolean onJsConfirm(WebView v, String url, String msg, JsResult r) {
                r.confirm(); return true;     // "Are you sure?" → yes
            }
        });
    }

    void stop() { stopped = true; }

    /** Log in once, then add each device in turn. Stops at the first failure. */
    void run(JSONObject cfg, Map<String, String> base, List<Device> devices, Listener l) {
        listener = l;
        stopped = false;
        authUser = base.get("username");
        authPass = base.get("password");
        JSONArray login = cfg.optJSONArray("login");
        JSONArray add = cfg.optJSONArray("add_device");
        String macFmt = cfg.optString("mac_format", "colon-upper");
        if (login == null || add == null) {
            l.log("Router steps are missing 'login' or 'add_device'. Reset them in Settings.");
            l.finished(false);
            return;
        }
        l.log("Logging in to the router…");
        runSteps(login, base, 0, (ok, err) -> {
            if (!ok) {
                l.log("LOGIN FAILED: " + err);
                l.log("Check the router password, and that this phone is on the UTAMU Wi-Fi.");
                l.finished(false);
                return;
            }
            l.log("Logged in.");
            addNext(add, base, devices, 0, macFmt);
        });
    }

    private void addNext(JSONArray steps, Map<String, String> base, List<Device> devices, int idx, String macFmt) {
        if (idx >= devices.size()) { listener.finished(true); return; }
        Device d = devices.get(idx);
        Map<String, String> v = new HashMap<>(base);
        v.put("name", d.name.length() > 31 ? d.name.substring(0, 31) : d.name);
        v.put("mac", Device.formatMac(d.mac, macFmt));
        listener.log("Adding " + d.name + " (" + v.get("mac") + ")…");
        runSteps(steps, v, 0, (ok, err) -> {
            listener.deviceResult(d, ok, err);
            if (!ok) {
                listener.log("  ✗ " + err);
                listener.log("Stopped here. Fix that step in Settings → Router steps, then tap Try again.");
                listener.finished(true);
                return;
            }
            listener.log("  ✓ on router");
            addNext(steps, base, devices, idx + 1, macFmt);
        });
    }

    private void runSteps(JSONArray steps, Map<String, String> vals, int i, Done done) {
        if (stopped) { done.run(false, "Stopped."); return; }
        if (i >= steps.length()) { done.run(true, null); return; }
        JSONObject st = steps.optJSONObject(i);
        if (st == null) { runSteps(steps, vals, i + 1, done); return; }
        runStep(st, vals, (ok, err) -> {
            if (!ok && st.optBoolean("optional", false)) {
                listener.log("    (optional step skipped)");
                ok = true;
            }
            if (!ok) {
                String what = st.optString("selector", st.optString("url", ""));
                done.run(false, "Step " + (i + 1) + " (" + st.optString("action") + " " + what + "): " + err);
                return;
            }
            runSteps(steps, vals, i + 1, done);
        });
    }

    private void runStep(JSONObject st, Map<String, String> vals, Done done) {
        String action = st.optString("action");
        long timeout = st.optLong("timeout", 8000);
        listener.log("  " + action + " " + st.optString("selector", st.optString("url", "")));
        switch (action) {
            case "goto": {
                loading = true;
                web.loadUrl(fill(st.optString("url"), vals));
                waitLoaded(System.currentTimeMillis() + Math.max(timeout, 15000), done);
                return;
            }
            case "wait":
                handler.postDelayed(() -> done.run(true, null), st.optLong("ms", 1000));
                return;
            case "frame":           // frames are searched automatically
                done.run(true, null);
                return;
            case "fill": case "click": case "check": case "select": case "wait_for": case "press":
                break;
            default:
                done.run(false, "unknown action '" + action + "'");
                return;
        }
        String json;
        try {
            JSONObject js = new JSONObject();
            js.put("action", action);
            js.put("selector", st.optString("selector"));
            js.put("value", fill(st.optString("value", ""), vals));
            json = js.toString();
        } catch (Exception e) {
            done.run(false, e.toString());
            return;
        }
        poll(action, json, System.currentTimeMillis() + timeout, timeout, done);
    }

    private void poll(String action, String json, long deadline, long timeout, Done done) {
        if (stopped) { done.run(false, "Stopped."); return; }
        if (loading) {
            if (System.currentTimeMillis() > deadline + 15000) { done.run(false, "page never finished loading"); return; }
            handler.postDelayed(() -> poll(action, json, deadline, timeout, done), 300);
            return;
        }
        web.evaluateJavascript(JS + "(" + json + ")", raw -> {
            String res = raw == null ? "null" : raw.replace("\"", "");
            if (res.equals("ok")) {
                if (action.equals("click") || action.equals("press")) {
                    // give the page time to react or navigate, then wait for it
                    handler.postDelayed(() -> waitLoaded(System.currentTimeMillis() + 15000, done), 800);
                } else {
                    done.run(true, null);
                }
            } else if (res.equals("notfound") || res.equals("null")) {
                if (System.currentTimeMillis() > deadline) {
                    done.run(false, "not found on the page within " + (timeout / 1000) + "s");
                } else {
                    handler.postDelayed(() -> poll(action, json, deadline, timeout, done), 300);
                }
            } else {
                done.run(false, res);
            }
        });
    }

    private void waitLoaded(long deadline, Done done) {
        if (stopped) { done.run(false, "Stopped."); return; }
        if (!loading) { handler.postDelayed(() -> done.run(true, null), 400); return; }
        if (System.currentTimeMillis() > deadline) { done.run(false, "page didn't load. Is this phone on the router's Wi-Fi?"); return; }
        handler.postDelayed(() -> waitLoaded(deadline, done), 250);
    }

    private static String fill(String s, Map<String, String> vals) {
        for (Map.Entry<String, String> e : vals.entrySet()) {
            s = s.replace("{" + e.getKey() + "}", e.getValue() == null ? "" : e.getValue());
        }
        return s;
    }

    /**
     * Runs one step in the page. Returns "ok", "notfound", or an error message.
     * Selectors: CSS, or text=Words (visible text), alternatives split by ||.
     * Only visible elements count; same-site frames are searched too.
     */
    static final String JS =
        "(function(st){" +
        "function docs(d,acc){acc.push(d);var fs=d.querySelectorAll('iframe,frame');" +
          "for(var i=0;i<fs.length;i++){try{var cd=fs[i].contentDocument;if(cd)docs(cd,acc);}catch(e){}}return acc;}" +
        "function vis(el){try{var r=el.getBoundingClientRect();if(r.width<=0||r.height<=0)return false;" +
          "var cs=el.ownerDocument.defaultView.getComputedStyle(el);return cs.visibility!=='hidden'&&cs.display!=='none';}catch(e){return false;}}" +
        "function byText(d,t){var best=null,bl=1e9,exact=null;" +
          "var els=d.querySelectorAll('a,button,span,div,li,td,th,label,p,font,b,h1,h2,h3,h4,input[type=button],input[type=submit]');" +
          "for(var i=0;i<els.length;i++){var el=els[i];var s=((el.tagName==='INPUT'?el.value:el.innerText)||'').trim();" +
            "if(!s)continue;var l=s.toLowerCase();if(l.indexOf(t)<0)continue;if(!vis(el))continue;" +
            // later in document order = deeper, so the link wins over its wrapper
            "if(l===t)exact=el;if(s.length<=bl){best=el;bl=s.length;}}" +
          "return exact||best;}" +
        "function find(sel){var alts=sel.split('||');var ds=docs(document,[]);" +
          "for(var a=0;a<alts.length;a++){var s=alts[a].trim();if(!s)continue;" +
            "for(var k=0;k<ds.length;k++){var d=ds[k];" +
              "if(s.indexOf('text=')===0){var el=byText(d,s.slice(5).trim().toLowerCase());if(el)return el;}" +
              "else{var list;try{list=d.querySelectorAll(s);}catch(e){return 'bad';}" +
                "for(var i=0;i<list.length;i++){if(vis(list[i]))return list[i];}}}}" +
          "return null;}" +
        "var el=find(st.selector||'');if(el==='bad')return 'bad selector: '+st.selector;if(!el)return 'notfound';" +
        "var a=st.action;var w=el.ownerDocument.defaultView;" +
        "function fire(t){el.dispatchEvent(new w.Event(t,{bubbles:true}));}" +
        "if(a==='wait_for')return 'ok';" +
        "if(a==='click'){try{el.scrollIntoView({block:'center'});}catch(e){}el.click();return 'ok';}" +
        "if(a==='check'){if(!el.checked)el.click();return 'ok';}" +
        "if(a==='fill'){el.focus();var proto=el.tagName==='TEXTAREA'?w.HTMLTextAreaElement.prototype:w.HTMLInputElement.prototype;" +
          "var setter=Object.getOwnPropertyDescriptor(proto,'value').set;setter.call(el,st.value);" +
          "fire('input');fire('change');fire('keyup');fire('blur');return 'ok';}" +
        "if(a==='select'){var v=String(st.value).toLowerCase();for(var i=0;i<el.options.length;i++){var o=el.options[i];" +
          "if(o.value.toLowerCase()===v||o.text.trim().toLowerCase()===v){el.selectedIndex=i;fire('change');return 'ok';}}" +
          "return 'option not found: '+st.value;}" +
        "if(a==='press'){['keydown','keypress','keyup'].forEach(function(t){el.dispatchEvent(new w.KeyboardEvent(t,{key:'Enter',keyCode:13,which:13,bubbles:true}));});return 'ok';}" +
        "return 'unknown action';})";
}
