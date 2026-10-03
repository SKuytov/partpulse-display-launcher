package com.partpulse.displaylauncher;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
 * and downloads referenced PDF/image assets into persistent app storage.
 * Webpage slides (type=webpage) are NOT downloaded — the URL is passed directly.
 *
 * Reliability rules (fix for "blank screen on Monday until app restart"):
 *  1. Assets live in getFilesDir()/slides — Android never purges it (getCacheDir() can be
 *     emptied by the system at any time, which left PDF slides black until a restart).
 *  2. Downloads go to a .part file, are validated (complete PDF / decodable image, matching
 *     Content-Length) and only then atomically renamed over the old file. A broken or
 *     half-finished download can never replace a good file.
 *  3. Every refresh re-validates the local files, even when STATE_HASH is unchanged, and
 *     re-downloads anything missing or corrupt.
 *  4. If a download fails, the refresh is reported as incomplete and the hash is NOT
 *     committed, so the next refresh tries again.
 *  5. The last fully working slide list is persisted and used as an offline fallback.
 *  6. Requests bypass HTTP/CDN caches (no-cache header + ?v=hash) so an overwritten
 *     week.pdf with the same file name is always fetched fresh.
 */
public class SlideManager {

    private static final String TAG = "SlideManager";
    private static final String PREFS = "slide_manager_cache";
    private static final String KEY_SLIDES_JSON = "slides_json";
    private static final String KEY_HASH = "state_hash";
    private static final String KEY_LKG = "last_known_good";
    private static final String ASSET_DIR = "slides";

    private static final Pattern SLIDES_PATTERN =
        Pattern.compile("const\\s+SLIDES\\s*=\\s*(\\[.*?\\])\\s*;", Pattern.DOTALL);
    private static final Pattern HASH_PATTERN =
        Pattern.compile("STATE_HASH\\s*=\\s*['\"]([0-9a-fA-F]{32})['\"]");
    private static final Pattern HASH_FALLBACK =
        Pattern.compile("['\"]([0-9a-fA-F]{32})['\"]");

    public interface Callback {
        /**
         * @param slides   fresh copy of the slide list (safe to keep)
         * @param hash     server STATE_HASH
         * @param changed  true if anything visible changed (list, text, or an asset file)
         * @param complete false if one or more assets could not be downloaded — caller should retry
         */
        void onSlidesReady(List<Slide> slides, String hash, boolean changed, boolean complete);

        /**
         * @param lastKnownGood last fully working slide list from disk (may be empty)
         */
        void onError(Exception e, List<Slide> lastKnownGood);
    }

    private final OkHttpClient http;
    private final ExecutorService executor;
    private final Handler main;
    private String stateHash = "";
    private String lastSignature = "";

    public SlideManager() {
        this.http = new OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build();
        this.executor = Executors.newSingleThreadExecutor();
        this.main = new Handler(Looper.getMainLooper());
    }

    public String getStateHash() { return stateHash; }

    public void shutdown() { executor.shutdownNow(); }

    public void refresh(final Context context, final Callback cb) {
        refresh(context, false, cb);
    }

    /**
     * @param force re-download every asset even if the hash is unchanged
     */
    public void refresh(final Context context, final boolean force, final Callback cb) {
        final Context app = context.getApplicationContext();
        final AppPrefs prefs = new AppPrefs(app);
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

                File dir = assetDir(app);
                boolean hashChanged = force || !hash.equals(stateHash);
                boolean anyAssetChanged = false;
                boolean complete = true;
                Set<String> referenced = new HashSet<>();

                for (Slide s : parsed) {
                    // Webpage slides: URL is used directly by WebView — skip download
                    if (Slide.TYPE_WEBPAGE.equals(s.type)) continue;
                    if (s.url == null || s.url.isEmpty()) continue;

                    String fname = fileNameFromUrl(s.url);
                    referenced.add(fname);
                    File target = new File(dir, fname);
                    boolean isPdf = Slide.TYPE_PDF.equals(s.type);
                    boolean valid = isValidAsset(target, isPdf);

                    if (!valid || hashChanged) {
                        try {
                            if (download(s.url, hash, target, isPdf)) {
                                anyAssetChanged = true;
                                deleteRenderCaches(dir, fname);
                            }
                            valid = true;
                        } catch (Exception e) {
                            complete = false;
                            Log.w(TAG, "Asset download failed: " + s.url + " — " + e.getMessage()
                                + (valid ? " (keeping previous copy)" : " (no local copy)"));
                        }
                    }
                    if (valid) s.localPath = target.getAbsolutePath();
                }

                if (complete) cleanupOrphans(dir, referenced);

                String sig = signature(parsed);
                boolean changed = force || anyAssetChanged || !sig.equals(lastSignature);
                lastSignature = sig;

                // Only commit the hash when every asset is good — otherwise the next
                // refresh must try again instead of thinking it is up to date.
                if (complete) {
                    stateHash = hash;
                    SharedPreferences sp = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
                    sp.edit()
                      .putString(KEY_SLIDES_JSON, arr.toString())
                      .putString(KEY_HASH, hash)
                      .putString(KEY_LKG, toJson(parsed).toString())
                      .apply();
                    prefs.setLastHash(hash);
                }

                final List<Slide> out = new ArrayList<>(parsed);
                final boolean fChanged = changed, fComplete = complete;
                final String fHash = hash;
                main.post(() -> cb.onSlidesReady(out, fHash, fChanged, fComplete));
            } catch (Exception e) {
                Log.e(TAG, "refresh failed", e);
                final List<Slide> lkg = loadLastKnownGood(app);
                main.post(() -> cb.onError(e, lkg));
            }
        });
    }

    /** Counts slides in an index page — used by Settings "Test URL". Returns -1 if not found. */
    public static int countSlides(String html) {
        try {
            Matcher m = SLIDES_PATTERN.matcher(html);
            if (!m.find()) return -1;
            return new JSONArray(m.group(1)).length();
        } catch (Exception e) {
            return -1;
        }
    }

    // ─── storage / validation ───────────────────────────────────────────────

    public static File assetDir(Context context) {
        File dir = new File(context.getApplicationContext().getFilesDir(), ASSET_DIR);
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    /** True if the file looks like a complete PDF (or a decodable image for notifications). */
    public static boolean isValidAsset(File f, boolean isPdf) {
        if (f == null || !f.isFile() || f.length() < 16) return false;
        if (isPdf) return isCompletePdf(f);
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        return o.outWidth > 0 && o.outHeight > 0;
    }

    private static boolean isCompletePdf(File f) {
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            long len = raf.length();
            if (len < 64) return false;
            byte[] head = new byte[5];
            raf.readFully(head);
            if (!new String(head, "ISO-8859-1").equals("%PDF-")) return false;
            int tailLen = (int) Math.min(2048, len);
            byte[] tail = new byte[tailLen];
            raf.seek(len - tailLen);
            raf.readFully(tail);
            return new String(tail, "ISO-8859-1").contains("%%EOF");
        } catch (Exception e) {
            return false;
        }
    }

    /** Removes rendered bitmaps that belong to an asset file name. */
    private static void deleteRenderCaches(File dir, String fname) {
        File[] files = dir.listFiles((d, name) ->
            name.startsWith(fname + "_") && (name.endsWith("_rendered.jpg") || name.endsWith("_rendered.png")));
        if (files == null) return;
        for (File f : files) {
            if (f.delete()) Log.i(TAG, "Invalidated render cache: " + f.getName());
        }
    }

    /** Deletes assets and render caches no longer referenced by the server. */
    private static void cleanupOrphans(File dir, Set<String> referenced) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            String n = f.getName();
            boolean keep = false;
            for (String r : referenced) {
                if (n.equals(r) || n.startsWith(r + "_")) { keep = true; break; }
            }
            if (!keep && f.delete()) Log.i(TAG, "Removed orphan file: " + n);
        }
    }

    // ─── last known good ────────────────────────────────────────────────────

    private static JSONArray toJson(List<Slide> list) throws Exception {
        JSONArray a = new JSONArray();
        for (Slide s : list) {
            JSONObject o = new JSONObject();
            o.put("id", s.id);
            o.put("type", s.type);
            o.put("label", s.label);
            o.put("lblClass", s.lblClass);
            o.put("url", s.url == null ? "" : s.url);
            o.put("title", s.title);
            o.put("body", s.body);
            o.put("date", s.date);
            o.put("localPath", s.localPath == null ? "" : s.localPath);
            o.put("duration", s.duration);
            a.put(o);
        }
        return a;
    }

    private static List<Slide> loadLastKnownGood(Context context) {
        List<Slide> out = new ArrayList<>();
        try {
            String json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                                 .getString(KEY_LKG, "");
            if (json == null || json.isEmpty()) return out;
            JSONArray a = new JSONArray(json);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Slide s = new Slide();
                s.id = o.optInt("id", i);
                s.type = o.optString("type", Slide.TYPE_PDF);
                s.label = o.optString("label", "");
                s.lblClass = o.optString("lblClass", "");
                String u = o.optString("url", "");
                s.url = u.isEmpty() ? null : u;
                s.title = o.optString("title", "");
                s.body = o.optString("body", "");
                s.date = o.optString("date", "");
                s.duration = o.optInt("duration", 10);
                String lp = o.optString("localPath", "");
                if (!lp.isEmpty() && isValidAsset(new File(lp), Slide.TYPE_PDF.equals(s.type))) {
                    s.localPath = lp;
                }
                // Skip PDF slides whose file is gone — nothing to show for them.
                if (Slide.TYPE_PDF.equals(s.type) && s.localPath == null) continue;
                out.add(s);
            }
        } catch (Exception e) {
            Log.w(TAG, "Last-known-good load failed: " + e.getMessage());
        }
        return out;
    }

    private static String signature(List<Slide> list) {
        StringBuilder sb = new StringBuilder();
        for (Slide s : list) {
            sb.append(s.type).append('|').append(s.url).append('|').append(s.label).append('|')
              .append(s.lblClass).append('|').append(s.title).append('|').append(s.body).append('|')
              .append(s.date).append('|').append(s.duration).append('|')
              .append(s.localPath != null).append('\n');
        }
        return sb.toString();
    }

    // ─── helpers ────────────────────────────────────────────────────────────

    private static final Pattern NOTIF_TITLE =
        Pattern.compile("<h2[^>]*class=\"notif-title\"[^>]*>(.*?)</h2>", Pattern.DOTALL);
    private static final Pattern NOTIF_BODY =
        Pattern.compile("<p[^>]*class=\"notif-body\"[^>]*>(.*?)</p>", Pattern.DOTALL);
    private static final Pattern NOTIF_DATE =
        Pattern.compile("<div[^>]*class=\"notif-date\"[^>]*>(.*?)</div>", Pattern.DOTALL);
    private static final Pattern NOTIF_IMG =
        Pattern.compile("<img[^>]+src=\"([^\"]+)\"", Pattern.DOTALL);

    private void enrichNotificationFromHtml(Slide s, String html, String base) {
        Pattern slidePat = Pattern.compile(
            "<div[^>]+id=\"slide-" + s.id + "\"[^>]*>(.*?)</div>\\s*</div>\\s*</div>",
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
                   .replace("&#9679;", "\u25cf")
                   .replace("&nbsp;", " ")
                   .trim();
    }

    private String fetchString(String url) throws Exception {
        Request req = new Request.Builder()
            .url(url)
            .header("Cache-Control", "no-cache")
            .header("Pragma", "no-cache")
            .build();
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

    private static String cacheBusted(String url, String hash) {
        String v = (hash == null || hash.isEmpty()) ? String.valueOf(System.currentTimeMillis()) : hash;
        return url + (url.contains("?") ? "&" : "?") + "v=" + v;
    }

    /**
     * Downloads into a temp file, validates it, then atomically replaces the target.
     * @return true if the target content actually changed
     */
    private boolean download(String url, String hash, File target, boolean isPdf) throws Exception {
        File tmp = new File(target.getParentFile(), target.getName() + ".part");
        Request req = new Request.Builder()
            .url(cacheBusted(url, hash))
            .header("Cache-Control", "no-cache")
            .header("Pragma", "no-cache")
            .build();
        try (Response resp = http.newCall(req).execute()) {
            if (!resp.isSuccessful()) throw new RuntimeException("HTTP " + resp.code() + " for " + url);
            ResponseBody b = resp.body();
            if (b == null) throw new RuntimeException("Empty body for " + url);
            long expected = b.contentLength();
            long written = 0;
            try (InputStream in = b.byteStream();
                 FileOutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[16 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) { out.write(buf, 0, n); written += n; }
                out.getFD().sync();
            }
            if (expected > 0 && written != expected) {
                throw new RuntimeException("Incomplete download " + written + "/" + expected + " for " + url);
            }
        } catch (Exception e) {
            tmp.delete();
            throw e;
        }

        if (!isValidAsset(tmp, isPdf)) {
            tmp.delete();
            throw new RuntimeException("Downloaded file is not a valid " + (isPdf ? "PDF" : "image") + ": " + url);
        }

        if (target.exists() && sameContent(tmp, target)) {
            tmp.delete();
            return false;
        }
        if (!tmp.renameTo(target)) {
            target.delete();
            if (!tmp.renameTo(target)) {
                tmp.delete();
                throw new RuntimeException("Could not move download into place: " + target);
            }
        }
        Log.i(TAG, "Asset updated: " + target.getName() + " (" + target.length() + " bytes)");
        return true;
    }

    private static boolean sameContent(File a, File b) {
        if (a.length() != b.length()) return false;
        try (java.io.DataInputStream ia = new java.io.DataInputStream(new java.io.FileInputStream(a));
             java.io.DataInputStream ib = new java.io.DataInputStream(new java.io.FileInputStream(b))) {
            byte[] ba = new byte[16 * 1024], bb = new byte[16 * 1024];
            long left = a.length();
            while (left > 0) {
                int n = (int) Math.min(ba.length, left);
                ia.readFully(ba, 0, n);
                ib.readFully(bb, 0, n);
                for (int i = 0; i < n; i++) if (ba[i] != bb[i]) return false;
                left -= n;
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
