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
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Fetches the index.php page, parses the SLIDES array and STATE_HASH,
 * and downloads referenced PDF/image assets into PERSISTENT local storage
 * (getFilesDir(), not getCacheDir()) so cached content survives Android's
 * automatic cache-clearing under storage pressure — critical for 24/7
 * signage devices that must keep working through network outages.
 *
 * Robustness additions vs. the original version:
 *  1. Assets are stored under context.getFilesDir()/slides_cache/ instead of
 *     context.getCacheDir(). The cache dir can be purged by Android at any
 *     time; the files dir is not, so previously downloaded PDFs/images
 *     remain available indefinitely even with no network connection.
 *  2. On ANY failure to fetch/parse the remote index.php (network down,
 *     server error, malformed HTML, etc.), the manager now falls back to
 *     the LAST KNOWN GOOD slide list stored in SharedPreferences + disk,
 *     instead of surfacing an error and leaving the caller with nothing.
 *     The rotation keeps playing whatever last worked.
 *  3. New slide list is only committed/persisted if it passes a basic
 *     sanity check (non-empty, and PDF slides have a resolved local file).
 *     A malformed or partial remote payload can never wipe out a
 *     previously-working slide set.
 *
 * Webpage slides (type=webpage) are NOT downloaded — they are expected to
 * be bundled as local Android assets (see NativeDisplayActivity) and are
 * skipped entirely by the network/download logic below.
 */
public class SlideManager {

    private static final String TAG = "SlideManager";
    private static final String PREFS = "slide_manager_cache";
    private static final String KEY_SLIDES_JSON = "slides_json";
    private static final String KEY_HASH = "state_hash";
    private static final String ASSET_SUBDIR = "slides_cache";

    private static final Pattern SLIDES_PATTERN =
            Pattern.compile("const\\s+SLIDES\\s*=\\s*(\\[.*?\\])\\s*;", Pattern.DOTALL);
    private static final Pattern HASH_PATTERN =
            Pattern.compile("STATE_HASH\\s*=\\s*['\"]([0-9a-fA-F]{32})['\"]");
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
        this.http = new OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(20, TimeUnit.SECONDS)
                .build();
        this.executor = Executors.newSingleThreadExecutor();
        this.main = new Handler(Looper.getMainLooper());
    }

    public List<Slide> getSlides() { return slides; }
    public String getStateHash() { return stateHash; }

    /** Persistent storage directory — survives cache-clearing, unlike getCacheDir(). */
    private File persistentDir(Context context) {
        File dir = new File(context.getFilesDir(), ASSET_SUBDIR);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    public void refresh(final Context context, final Callback cb) {
        final AppPrefs prefs = new AppPrefs(context);
        final String indexUrl = prefs.getUrl();
        final File cacheDir = persistentDir(context);

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
                    s.id = o.optInt("id", i);
                    s.type = o.optString("type", Slide.TYPE_PDF);
                    s.label = o.optString("label", "");
                    s.lblClass = o.optString("lblClass", "");
                    String rawUrl = o.optString("src", o.optString("url", ""));
                    if (rawUrl != null && !rawUrl.isEmpty() && !"null".equals(rawUrl)) {
                        s.url = absolute(base, rawUrl);
                    }
                    s.title = o.optString("title", "");
                    s.body = o.optString("body", "");
                    s.date = o.optString("date", "");
                    s.duration = o.optInt("duration", 10);
                    parsed.add(s);
                }

                for (Slide s : parsed) {
                    if (!Slide.TYPE_NOTIFICATION.equals(s.type)) continue;
                    try {
                        enrichNotificationFromHtml(s, html, base);
                    } catch (Exception e) {
                        Log.w(TAG, "Failed to parse notification HTML for slide " + s.id + ": " + e.getMessage());
                    }
                }

                boolean hashChanged = !hash.equals(stateHash);
                if (hashChanged) {
                    File[] renderedFiles = cacheDir.listFiles(
                            (dir, name) -> name.endsWith("_rendered.jpg") || name.endsWith("_rendered.png"));
                    if (renderedFiles != null) {
                        for (File f : renderedFiles) {
                            f.delete();
                            Log.i(TAG, "Invalidated rendered cache: " + f.getName());
                        }
                    }
                }

                for (Slide s : parsed) {
                    // Webpage slides are bundled Android assets — never downloaded.
                    if (Slide.TYPE_WEBPAGE.equals(s.type)) {
                        Log.i(TAG, "Webpage slide — using bundled local asset, skipping download");
                        continue;
                    }

                    if (s.url == null || s.url.isEmpty()) continue;
                    try {
                        String fname = fileNameFromUrl(s.url);
                        File target = new File(cacheDir, fname);
                        downloadIfMissingOrStale(s.url, target, !hashChanged);
                        s.localPath = target.getAbsolutePath();
                    } catch (Exception e) {
                        Log.w(TAG, "Asset download failed: " + s.url + " — " + e.getMessage()
                                + " — will retry next refresh, keeping any existing local copy");
                        // Even on download failure, if a previously downloaded file exists
                        // on disk from an earlier successful refresh, still point to it so
                        // this slide keeps working offline.
                        File existing = new File(cacheDir, fileNameFromUrl(s.url));
                        if (existing.exists()) {
                            s.localPath = existing.getAbsolutePath();
                        }
                    }
                }

                if (!isSaneSlideList(parsed)) {
                    Log.w(TAG, "New slide list failed sanity check — keeping last known good slides");
                    loadLastKnownGood(context, cb);
                    return;
                }

                slides.clear();
                slides.addAll(parsed);
                stateHash = hash;

                persistLastKnownGood(context, arr.toString(), hash);

                main.post(() -> cb.onSlidesReady(slides, stateHash));
            } catch (Exception e) {
                Log.e(TAG, "refresh failed — falling back to last known good slides: " + e.getMessage(), e);
                loadLastKnownGood(context, cb);
            }
        });
    }

    /** Basic validation so a broken/partial remote payload never replaces a working slide set. */
    private boolean isSaneSlideList(List<Slide> parsed) {
        if (parsed == null || parsed.isEmpty()) return false;
        for (Slide s : parsed) {
            if (Slide.TYPE_PDF.equals(s.type)) {
                if (s.localPath == null || !new File(s.localPath).exists()) {
                    Log.w(TAG, "Sanity check: PDF slide " + s.id + " has no valid local file");
                    return false;
                }
            }
            if (s.duration <= 0 || s.duration > 3600) {
                Log.w(TAG, "Sanity check: slide " + s.id + " has suspicious duration " + s.duration);
            }
        }
        return true;
    }

    /** Loads the last successfully-parsed slide list from SharedPreferences + persistent disk cache. */
    private void loadLastKnownGood(final Context context, final Callback cb) {
        try {
            SharedPreferences sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String savedJson = sp.getString(KEY_SLIDES_JSON, null);
            String savedHash = sp.getString(KEY_HASH, "");

            if (savedJson == null) {
                Log.w(TAG, "No last known good slides available — this is a first-run offline failure");
                main.post(() -> cb.onError(new Exception("No cached slides available offline")));
                return;
            }

            File cacheDir = persistentDir(context);
            String base = indexUrlBase(new AppPrefs(context).getUrl());
            JSONArray arr = new JSONArray(savedJson);
            List<Slide> parsed = new ArrayList<>();

            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Slide s = new Slide();
                s.id = o.optInt("id", i);
                s.type = o.optString("type", Slide.TYPE_PDF);
                s.label = o.optString("label", "");
                s.lblClass = o.optString("lblClass", "");
                String rawUrl = o.optString("src", o.optString("url", ""));
                if (rawUrl != null && !rawUrl.isEmpty() && !"null".equals(rawUrl)) {
                    s.url = absolute(base, rawUrl);
                }
                s.title = o.optString("title", "");
                s.body = o.optString("body", "");
                s.date = o.optString("date", "");
                s.duration = o.optInt("duration", 10);

                if (!Slide.TYPE_WEBPAGE.equals(s.type) && s.url != null && !s.url.isEmpty()) {
                    File local = new File(cacheDir, fileNameFromUrl(s.url));
                    if (local.exists()) {
                        s.localPath = local.getAbsolutePath();
                    }
                }
                parsed.add(s);
            }

            Log.i(TAG, "Loaded " + parsed.size() + " slides from last-known-good offline cache (hash=" + savedHash + ")");

            slides.clear();
            slides.addAll(parsed);
            stateHash = savedHash;

            main.post(() -> cb.onSlidesReady(slides, stateHash));
        } catch (Exception e) {
            Log.e(TAG, "Failed to load last known good slides", e);
            main.post(() -> cb.onError(e));
        }
    }

    private void persistLastKnownGood(Context context, String slidesJson, String hash) {
        SharedPreferences sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        sp.edit()
                .putString(KEY_SLIDES_JSON, slidesJson)
                .putString(KEY_HASH, hash)
                .apply();
        new AppPrefs(context).setLastHash(hash);
    }

    // ─── helpers ────────────────────────────────────────────────────────────

    private static final Pattern NOTIF_TITLE =
            Pattern.compile("]*class=\"notif-title\"[^>]*>(.*?)", Pattern.DOTALL);
    private static final Pattern NOTIF_BODY =
            Pattern.compile("]*class=\"notif-body\"[^>]*>(.*?)", Pattern.DOTALL);
    private static final Pattern NOTIF_DATE =
            Pattern.compile("]*class=\"notif-date\"[^>]*>(.*?)", Pattern.DOTALL);
    private static final Pattern NOTIF_IMG =
            Pattern.compile("]+src=\"([^\"]+)\"", Pattern.DOTALL);

    private void enrichNotificationFromHtml(Slide s, String html, String base) {
        Pattern slidePat = Pattern.compile(
                "]+id=\"slide-" + s.id + "\"[^>]*>(.*?)\\s*\\s*",
                Pattern.DOTALL);
        Matcher m = slidePat.matcher(html);
        if (!m.find()) return;
        String block = m.group(1);

        Matcher t = NOTIF_TITLE.matcher(block);
        if (t.find()) s.title = stripHtml(t.group(1));
        Matcher b = NOTIF_BODY.matcher(block);
        if (b.find()) s.body = stripHtml(b.group(1));
        Matcher d = NOTIF_DATE.matcher(block);
        if (d.find()) s.date = stripHtml(d.group(1));
        Matcher i = NOTIF_IMG.matcher(block);
        if (i.find()) {
            String src = i.group(1);
            if (src != null && !src.isEmpty()) {
                s.url = absolute(base, src);
            }
        }
    }

    private String stripHtml(String html) {
        if (html == null) return "";
        return html.replaceAll("<[^>]+>", "")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&nbsp;", " ")
                .trim();
    }

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
        return name.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    /**
     * Downloads a remote asset into persistent storage (getFilesDir()-based
     * dir) unless it already exists and the hash is unchanged. On download
     * failure, any existing local copy is left untouched so the app can keep
     * using it offline.
     */
    private void downloadIfMissingOrStale(String url, File target, boolean hashUnchanged) throws Exception {
        if (target.exists() && hashUnchanged) return;

        File tmp = new File(target.getParent(), target.getName() + ".tmp");
        Request req = new Request.Builder().url(url).build();
        try (Response resp = http.newCall(req).execute()) {
            if (!resp.isSuccessful()) throw new RuntimeException("HTTP " + resp.code() + " for " + url);
            ResponseBody b = resp.body();
            if (b == null) throw new RuntimeException("Empty body for " + url);
            try (InputStream in = b.byteStream();
                 FileOutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[16 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
        }

        // Atomic-ish swap: only replace the real target once the download
        // fully succeeded, so a failed/partial download never corrupts a
        // previously-working cached asset.
        if (target.exists()) {
            target.delete();
        }
        tmp.renameTo(target);
    }
}
