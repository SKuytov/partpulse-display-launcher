package com.partpulse.displaylauncher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class SseUpdateService extends Service {

    private static final String TAG = "SseUpdateService";

    public static final String ACTION_SLIDES_UPDATED =
        "com.partpulse.displaylauncher.SLIDES_UPDATED";
    public static final String ACTION_CONTENT_UPDATED =
        "com.partpulse.displaylauncher.CONTENT_UPDATED";

    private static final String CHANNEL_ID = "pp_sse_channel";
    private static final int    NOTIF_ID   = 1001;

    private static final long BACKOFF_MIN       = 5_000L;
    private static final long BACKOFF_MAX       = 60_000L;
    private static final long WATCHDOG_INTERVAL = 90_000L;   // check every 90s
    private static final long WATCHDOG_TIMEOUT  = 5 * 60_000L; // reconnect if silent 5min
    private static final long POLL_INTERVAL     = 3 * 60_000L; // poll every 3min

    private static final Pattern HASH_PATTERN =
        Pattern.compile("STATE_HASH\\s*=\\s*['\"]([0-9a-fA-F]{32})['\"]");
    private static final Pattern HASH_FALLBACK =
        Pattern.compile("['\"]([0-9a-fA-F]{32})['\"]");

    private OkHttpClient httpClient;
    private volatile boolean running = true;
    private volatile Call activeCall;
    private volatile Thread sseThread;
    private volatile Thread watchdogThread;
    private volatile Thread pollThread;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private PowerManager.WakeLock wakeLock;

    private final AtomicLong lastEventTime = new AtomicLong(System.currentTimeMillis());

    @Override
    public void onCreate() {
        super.onCreate();
        httpClient = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .build();
        createNotificationChannel();
        startForeground(NOTIF_ID, buildNotification());

        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PartPulse:SseService");
                wakeLock.setReferenceCounted(false);
                if (!wakeLock.isHeld()) wakeLock.acquire();
            }
        } catch (Exception e) {
            Log.w(TAG, "WakeLock init failed: " + e.getMessage());
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        running = true;
        // IMPORTANT: NativeDisplayActivity calls startForegroundService() every 5 minutes as a
        // health check, and every call lands here. Earlier builds started a NEW watchdog and a
        // NEW poller thread on every call and never stopped them — ~2000 pollers per week, each
        // hitting index.php every 3 minutes. That slowly choked the device/server until the app
        // was restarted (the "every Monday" symptom). Each thread now exists at most once.
        synchronized (this) {
            if (sseThread == null || !sseThread.isAlive()) {
                sseThread = new Thread(this::sseLoop, "SSE-Listener");
                sseThread.setDaemon(true);
                sseThread.start();
            }
            if (watchdogThread == null || !watchdogThread.isAlive()) {
                watchdogThread = new Thread(this::watchdogLoop, "SSE-Watchdog");
                watchdogThread.setDaemon(true);
                watchdogThread.start();
            }
            if (pollThread == null || !pollThread.isAlive()) {
                pollThread = new Thread(this::pollLoop, "Hash-Poller");
                pollThread.setDaemon(true);
                pollThread.start();
            }
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (activeCall != null) activeCall.cancel();
        Thread w = watchdogThread, p = pollThread, t = sseThread;
        if (w != null) w.interrupt();
        if (p != null) p.interrupt();
        if (t != null) t.interrupt();
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Exception ignored) {}
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }

    // ─── SSE loop ───────────────────────────────────────────────────────────

    private void sseLoop() {
        long backoff = BACKOFF_MIN;
        AppPrefs prefs = new AppPrefs(this);

        while (running) {
            String currentHash = prefs.getLastHash();
            String sseUrl = buildSseUrl(currentHash);
            Log.d(TAG, "Connecting to SSE: " + sseUrl);

            try {
                Request request = new Request.Builder()
                    .url(sseUrl)
                    .addHeader("Accept", "text/event-stream")
                    .addHeader("Cache-Control", "no-cache")
                    .build();

                activeCall = httpClient.newCall(request);
                try (Response response = activeCall.execute()) {
                    if (!response.isSuccessful()) {
                        Log.w(TAG, "SSE HTTP error: " + response.code());
                        sleep(backoff);
                        backoff = Math.min(backoff * 2, BACKOFF_MAX);
                        continue;
                    }
                    backoff = BACKOFF_MIN;
                    lastEventTime.set(System.currentTimeMillis());
                    Log.d(TAG, "SSE connected");

                    ResponseBody body = response.body();
                    if (body == null) continue;

                    BufferedReader reader =
                        new BufferedReader(new InputStreamReader(body.byteStream()));
                    String line;
                    String currentEvent = null;
                    StringBuilder dataBuf = new StringBuilder();

                    while (running && (line = reader.readLine()) != null) {
                        lastEventTime.set(System.currentTimeMillis());
                        if (line.isEmpty()) {
                            if (dataBuf.length() > 0) {
                                handleEvent(currentEvent, dataBuf.toString(), prefs);
                            }
                            currentEvent = null;
                            dataBuf.setLength(0);
                        } else if (line.startsWith("event:")) {
                            currentEvent = line.substring(6).trim();
                        } else if (line.startsWith("data:")) {
                            if (dataBuf.length() > 0) dataBuf.append('\n');
                            dataBuf.append(line.substring(5).trim());
                        }
                    }
                }
            } catch (IOException e) {
                if (running) {
                    Log.w(TAG, "SSE connection lost: " + e.getMessage());
                    sleep(backoff);
                    backoff = Math.min(backoff * 2, BACKOFF_MAX);
                }
            }
        }
        Log.d(TAG, "SSE loop ended");
    }

    // ─── Watchdog loop ──────────────────────────────────────────────────────
    // If no SSE data received within WATCHDOG_TIMEOUT, cancels the active call
    // so sseLoop catches IOException and reconnects.

    private void watchdogLoop() {
        while (running) {
            sleep(WATCHDOG_INTERVAL);
            if (!running) break;
            long silence = System.currentTimeMillis() - lastEventTime.get();
            if (silence > WATCHDOG_TIMEOUT) {
                Log.w(TAG, "SSE watchdog: " + (silence / 1000) + "s silence — forcing reconnect");
                lastEventTime.set(System.currentTimeMillis());
                Call call = activeCall;
                if (call != null) call.cancel();
            }
        }
    }

    // ─── Polling fallback ───────────────────────────────────────────────────
    // Runs independently of SSE. Fetches index.php, compares STATE_HASH, and
    // broadcasts an update when it changes.

    private void pollLoop() {
        AppPrefs prefs = new AppPrefs(this);
        OkHttpClient pollClient = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build();

        while (running) {
            sleep(POLL_INTERVAL);
            if (!running) break;
            try {
                String indexUrl = prefs.getUrl();
                Request req = new Request.Builder()
                    .url(indexUrl)
                    .addHeader("Cache-Control", "no-cache")
                    .build();
                try (Response resp = pollClient.newCall(req).execute()) {
                    if (!resp.isSuccessful()) continue;
                    ResponseBody body = resp.body();
                    if (body == null) continue;
                    String html = body.string();
                    String newHash = parseHash(html);
                    if (newHash == null || newHash.isEmpty()) continue;
                    String knownHash = prefs.getLastHash();
                    if (!newHash.equals(knownHash)) {
                        Log.i(TAG, "Poll detected hash change: " + knownHash + " → " + newHash);
                        prefs.setLastHash(newHash);
                        broadcastUpdate();
                    } else {
                        Log.v(TAG, "Poll: hash unchanged (" + newHash + ")");
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Poll failed: " + e.getMessage());
            }
        }
    }

    private String parseHash(String html) {
        Matcher m = HASH_PATTERN.matcher(html);
        if (m.find()) return m.group(1);
        Matcher f = HASH_FALLBACK.matcher(html);
        if (f.find()) return f.group(1);
        return null;
    }

    // ─── event handling ─────────────────────────────────────────────────────

    private void handleEvent(String event, String data, AppPrefs prefs) {
        String eventName = event == null ? "message" : event;
        Log.v(TAG, "SSE event=" + eventName + " data=" + data);

        if (!"update".equals(eventName) && !"message".equals(eventName)) return;

        String newHash = null;
        try {
            JSONObject j = new JSONObject(data);
            newHash = j.optString("hash", null);
        } catch (Exception ignore) {}

        String currentHash = prefs.getLastHash();
        if (newHash != null && !newHash.isEmpty()) {
            if (!newHash.equals(currentHash)) {
                prefs.setLastHash(newHash);
                broadcastUpdate();
            } else {
                Log.v(TAG, "Hash unchanged — skipping broadcast");
            }
        } else {
            broadcastUpdate();
        }
    }

    private String buildSseUrl(String currentHash) {
        String displayUrl = new AppPrefs(this).getUrl();
        try {
            java.net.URL url = new java.net.URL(displayUrl);
            String path = url.getPath();
            String base = url.getProtocol() + "://" + url.getHost()
                + (url.getPort() != -1 ? ":" + url.getPort() : "");
            if (path.endsWith(".php")) {
                int slash = path.lastIndexOf('/');
                path = path.substring(0, slash + 1) + "events.php";
            } else {
                if (!path.endsWith("/")) path += "/";
                path += "events.php";
            }
            String hashParam = currentHash == null ? "" : currentHash;
            return base + path + "?hash=" + hashParam;
        } catch (Exception e) {
            return "https://skuytov.eu/grafik/events.php?hash=" +
                (currentHash == null ? "" : currentHash);
        }
    }

    private void broadcastUpdate() {
        mainHandler.post(() -> {
            Log.i(TAG, "Broadcasting SLIDES_UPDATED");
            Intent intent = new Intent(ACTION_SLIDES_UPDATED);
            intent.setPackage(getPackageName());
            sendBroadcast(intent);
            Intent legacy = new Intent(ACTION_CONTENT_UPDATED);
            legacy.setPackage(getPackageName());
            sendBroadcast(legacy);
        });
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "PartPulse Live Sync", NotificationManager.IMPORTANCE_MIN);
            channel.setDescription("Monitors schedule updates in the background");
            channel.setShowBadge(false);
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        return new androidx.core.app.NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("PartPulse Display")
            .setContentText("Monitoring for schedule updates…")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setSilent(true)
            .build();
    }
}
