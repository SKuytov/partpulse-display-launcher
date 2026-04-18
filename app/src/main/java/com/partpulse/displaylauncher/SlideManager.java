package com.partpulse.displaylauncher;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Fetches the index.php page, parses the SLIDES array and STATE_HASH,
 * and downloads referenced PDF/image assets into the internal cache.
 */
public class SlideManager {

    private static final String TAG = "SlideManager";
    private static final String PREFS = "slide_manager_cache";
    private static final String KEY_SLIDES_JSON = "slides_json";
    private static final String KEY_HASH = "state_hash";

    // Regex patterns
    private static final Pattern SLIDES_PATTERN =
        Pattern.compile("const\\s+SLIDES\\s*=\\s*(\\[.*?\\])\\s*;", Pattern.DOTALL);
    private static final Pattern HASH_PATTERN =
        Pattern.compile("STATE_HASH\\s*=\\s*['\"]([0-9a-fA-F]{32})['\"]");
    // Fallback: any 32-char hex in single quotes
    private static final Pattern HASH_FALLBACK =
        Pattern.compile("['\"]([0-9a-fA-F]{32})['\"]");

    public interface Callback {
        void onSlidesReady(List<Slide> slides, String hash);
        void onError(Exception e);
    }

    private final OkHttpClient http;
    private final ExecutorService executor;
    private final Handler main;
    private final List<Slide> slides = new ArrayList<>();
    private String stateHash = "";

    public SlideManager() {
        this.http = new OkHttpClient();
        this.executor = Executors.newSingleThreadExecutor();
        this.main = new Handler(Looper.getMainLooper());
    }

    public List<Slide> getSlides() { return slides; }
    public String getStateHash() { return stateHash; }

    /**
     * Fetches index.php, parses slides, downloads assets, then invokes callback.
     */
    public void refresh(final Context context, final Callback cb) {
        final AppPrefs prefs = new AppPrefs(context);
        final String indexUrl = prefs.getUrl();
        executor.execute(() -> {
            try {
                String html = fetchString(indexUrl);
                String hash = parseHash(html);
                JSONArray arr = parseSlidesArray(html);

                String base = indexUrlBase(indexUrl);
                List<Slide> parsed = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    Slide s = new Slide();
                    s.type = o.optString("type", Slide.TYPE_PDF);
                    s.label = o.optString("label", "");
                    String rawUrl = o.optString("url", "");
                    if (rawUrl != null && !rawUrl.isEmpty()) {
                        s.url = absolute(base, rawUrl);
                    }
                    s.title = o.optString("title", "");
                    s.body = o.optString("body", "");
                    s.date = o.optString("date", "");
                    parsed.add(s);
                }

                File cacheDir = context.getCacheDir();
                for (Slide s : parsed) {
                    if (s.url == null || s.url.isEmpty()) continue;
                    try {
                        String fname = fileNameFromUrl(s.url);
                        File target = new File(cacheDir, fname);
                        downloadIfMissingOrStale(s.url, target, hash.equals(stateHash));
                        s.localPath = target.getAbsolutePath();
                    } catch (Exception e) {
                        Log.w(TAG, "Asset download failed: " + s.url + " — " + e.getMessage());
                    }
                }

                slides.clear();
                slides.addAll(parsed);
                stateHash = hash;

                // Cache slide list + hash
                SharedPreferences sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
                sp.edit()
                  .putString(KEY_SLIDES_JSON, arr.toString())
                  .putString(KEY_HASH, hash)
                  .apply();
                new AppPrefs(context).setLastHash(hash);

                main.post(() -> cb.onSlidesReady(slides, stateHash));
            } catch (Exception e) {
                Log.e(TAG, "refresh failed", e);
                main.post(() -> cb.onError(e));
            }
        });
    }

    // ─── helpers ────────────────────────────────────────────────────────────

    private String fetchString(String url) throws Exception {
        Request req = new Request.Builder().url(url).build();
        try (Response resp = http.newCall(req).execute()) {
            if (!resp.isSuccessful()) throw new RuntimeException("HTTP " + resp.code());
            ResponseBody b = resp.body();
            return b == null ? "" : b.string();
        }
    }

    private String parseHash(String html) {
        Matcher m = HASH_PATTERN.matcher(html);
        if (m.find()) return m.group(1);
        Matcher f = HASH_FALLBACK.matcher(html);
        if (f.find()) return f.group(1);
        return "";
    }

    private JSONArray parseSlidesArray(String html) throws Exception {
        Matcher m = SLIDES_PATTERN.matcher(html);
        if (!m.find()) throw new RuntimeException("SLIDES array not found in HTML");
        return new JSONArray(m.group(1));
    }

    /** Gets base dir of the index URL (e.g. https://host/grafik/). */
    private String indexUrlBase(String indexUrl) {
        try {
            URL u = new URL(indexUrl);
            String path = u.getPath();
            int slash = path.lastIndexOf('/');
            String dir = slash >= 0 ? path.substring(0, slash + 1) : "/";
            String port = u.getPort() == -1 ? "" : (":" + u.getPort());
            return u.getProtocol() + "://" + u.getHost() + port + dir;
        } catch (Exception e) {
            return "https://skuytov.eu/grafik/";
        }
    }

    private String absolute(String base, String ref) {
        if (ref.startsWith("http://") || ref.startsWith("https://")) return ref;
        if (ref.startsWith("/")) {
            try {
                URL b = new URL(base);
                String port = b.getPort() == -1 ? "" : (":" + b.getPort());
                return b.getProtocol() + "://" + b.getHost() + port + ref;
            } catch (Exception e) {
                return base + ref;
            }
        }
        return base + ref;
    }

    private String fileNameFromUrl(String url) {
        int q = url.indexOf('?');
        String clean = q >= 0 ? url.substring(0, q) : url;
        int slash = clean.lastIndexOf('/');
        String name = slash >= 0 ? clean.substring(slash + 1) : clean;
        if (name.isEmpty()) name = "asset_" + Math.abs(url.hashCode());
        // sanitize
        return name.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private void downloadIfMissingOrStale(String url, File target, boolean hashUnchanged) throws Exception {
        if (target.exists() && hashUnchanged) return;
        Request req = new Request.Builder().url(url).build();
        try (Response resp = http.newCall(req).execute()) {
            if (!resp.isSuccessful()) throw new RuntimeException("HTTP " + resp.code() + " for " + url);
            ResponseBody b = resp.body();
            if (b == null) throw new RuntimeException("Empty body for " + url);
            try (InputStream in = b.byteStream();
                 FileOutputStream out = new FileOutputStream(target)) {
                byte[] buf = new byte[16 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
        }
    }
}
