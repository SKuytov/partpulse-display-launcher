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
import android.util.Log;

import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Background service that holds an open SSE connection to the schedule server.
 *
 * Endpoint: events.php?hash=<lastKnownHash>
 * Event:    "update" with JSON data {"hash": "<newHash>"}
 * Behavior: broadcasts ACTION_SLIDES_UPDATED only when the hash changes.
 */
public class SseUpdateService extends Service {

    private static final String TAG = "SseUpdateService";

    // New action (hash-driven slide refresh)
    public static final String ACTION_SLIDES_UPDATED =
        "com.partpulse.displaylauncher.SLIDES_UPDATED";
    // Kept for compatibility with older code paths
    public static final String ACTION_CONTENT_UPDATED =
        "com.partpulse.displaylauncher.CONTENT_UPDATED";

    private static final String CHANNEL_ID = "pp_sse_channel";
    private static final int    NOTIF_ID   = 1001;

    private static final long BACKOFF_MIN = 5_000L;
    private static final long BACKOFF_MAX = 60_000L;

    private OkHttpClient httpClient;
    private volatile boolean running = true;
    private Call activeCall;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

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
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        running = true;
        Thread thread = new Thread(this::sseLoop, "SSE-Listener");
        thread.setDaemon(true);
        thread.start();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (activeCall != null) activeCall.cancel();
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
                    Log.d(TAG, "SSE connected");

                    ResponseBody body = response.body();
                    if (body == null) continue;

                    BufferedReader reader =
                        new BufferedReader(new InputStreamReader(body.byteStream()));
                    String line;
                    String currentEvent = null;
                    StringBuilder dataBuf = new StringBuilder();

                    while (running && (line = reader.readLine()) != null) {
                        if (line.isEmpty()) {
                            // dispatch buffered event
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
                        // ignore id:, retry:, comments (":")
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

    private void handleEvent(String event, String data, AppPrefs prefs) {
        String eventName = event == null ? "message" : event;
        Log.v(TAG, "SSE event=" + eventName + " data=" + data);

        if (!"update".equals(eventName) && !"message".equals(eventName)) return;

        String newHash = null;
        try {
            JSONObject j = new JSONObject(data);
            newHash = j.optString("hash", null);
        } catch (Exception ignore) {
            // not JSON — fall through to broadcast as a legacy trigger
        }

        String currentHash = prefs.getLastHash();
        if (newHash != null && !newHash.isEmpty()) {
            if (!newHash.equals(currentHash)) {
                prefs.setLastHash(newHash);
                broadcastUpdate();
            } else {
                Log.v(TAG, "Hash unchanged — skipping broadcast");
            }
        } else {
            // Legacy: no hash payload, just trigger a refresh
            broadcastUpdate();
        }
    }

    /**
     * Builds: https://host/grafik/events.php?hash=<currentHash>
     */
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
            // Back-compat: also send the old action for any listeners that still use it
            Intent legacy = new Intent(ACTION_CONTENT_UPDATED);
            legacy.setPackage(getPackageName());
            sendBroadcast(legacy);
        });
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }

    // ─── foreground notification ───────────────────────────────────────────

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "PartPulse Live Sync",
                NotificationManager.IMPORTANCE_MIN
            );
            channel.setDescription("Monitors schedule updates in the background");
            channel.setShowBadge(false);
            NotificationManager nm =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        androidx.core.app.NotificationCompat.Builder builder =
            new androidx.core.app.NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("PartPulse Display")
                .setContentText("Monitoring for schedule updates…")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_MIN)
                .setOngoing(true)
                .setSilent(true);
        return builder.build();
    }
}
