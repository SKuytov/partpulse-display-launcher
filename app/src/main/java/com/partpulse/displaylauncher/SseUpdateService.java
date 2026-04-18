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
 * Background service that holds an open SSE (Server-Sent Events) connection
 * to the display page's server.
 *
 * When the server pushes any SSE event (the admin saves changes), this service
 * broadcasts ACTION_CONTENT_UPDATED locally. DisplayActivity listens for that
 * broadcast and clears the WebView cache + reloads the page.
 *
 * Architecture:
 *   - Runs as a foreground service (required on Android 8+ for long-running tasks)
 *   - Uses OkHttp with infinite read timeout to keep the SSE stream open
 *   - Auto-reconnects after any error with exponential back-off (5s → 60s max)
 *   - Stops cleanly when the app process dies
 *
 * The SSE endpoint is derived from the display URL stored in AppPrefs:
 *   https://skuytov.eu/grafik/index.php  →  https://skuytov.eu/grafik/sse.php
 * The server emits "data: update\n\n" (or any event) whenever content changes.
 * We treat ANY received SSE line starting with "data:" as a trigger.
 */
public class SseUpdateService extends Service {

    private static final String TAG          = "SseUpdateService";
    public  static final String ACTION_CONTENT_UPDATED =
        "com.partpulse.displaylauncher.CONTENT_UPDATED";

    private static final String CHANNEL_ID   = "pp_sse_channel";
    private static final int    NOTIF_ID     = 1001;

    // Back-off: start at 5 s, double on each failure, cap at 60 s
    private static final long   BACKOFF_MIN  = 5_000L;
    private static final long   BACKOFF_MAX  = 60_000L;

    private OkHttpClient   httpClient;
    private volatile boolean running = true;
    private Call           activeCall;
    private final Handler  mainHandler = new Handler(Looper.getMainLooper());

    // ─────────────────────────────────────────────────────────────────────────
    //  Service lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void onCreate() {
        super.onCreate();

        // OkHttp client — no read timeout so the SSE stream stays open
        httpClient = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS) // infinite — required for SSE
            .retryOnConnectionFailure(true)
            .build();

        createNotificationChannel();
        startForeground(NOTIF_ID, buildNotification());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        running = true;
        // Run the SSE loop on a dedicated background thread
        Thread thread = new Thread(this::sseLoop, "SSE-Listener");
        thread.setDaemon(true);
        thread.start();
        // STICKY: if killed by OS, restart automatically
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

    // ─────────────────────────────────────────────────────────────────────────
    //  SSE connection loop
    // ─────────────────────────────────────────────────────────────────────────

    private void sseLoop() {
        long backoff = BACKOFF_MIN;

        while (running) {
            String sseUrl = buildSseUrl();
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

                    // Connected — reset back-off
                    backoff = BACKOFF_MIN;
                    Log.d(TAG, "SSE connected");

                    ResponseBody body = response.body();
                    if (body == null) continue;

                    BufferedReader reader =
                        new BufferedReader(new InputStreamReader(body.byteStream()));
                    String line;
                    while (running && (line = reader.readLine()) != null) {
                        Log.v(TAG, "SSE line: " + line);
                        // Any "data:" line from the server means content changed
                        if (line.startsWith("data:")) {
                            broadcastUpdate();
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

    // ─────────────────────────────────────────────────────────────────────────
    //  Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Derives the SSE endpoint from the stored display URL.
     *
     * Strategy:
     *   1. Take the display URL (e.g. https://skuytov.eu/grafik/index.php)
     *   2. Replace the filename with sse.php
     *      → https://skuytov.eu/grafik/sse.php
     *
     * If the URL doesn't end in a .php file, append /sse.php to the base.
     */
    private String buildSseUrl() {
        String displayUrl = new AppPrefs(this).getUrl();
        try {
            java.net.URL url = new java.net.URL(displayUrl);
            String path = url.getPath(); // e.g. /grafik/index.php
            String base = url.getProtocol() + "://" + url.getHost()
                + (url.getPort() != -1 ? ":" + url.getPort() : "");

            if (path.endsWith(".php")) {
                // Replace filename
                int slash = path.lastIndexOf('/');
                path = path.substring(0, slash + 1) + "sse.php";
            } else {
                // Append
                if (!path.endsWith("/")) path += "/";
                path += "sse.php";
            }
            return base + path;
        } catch (Exception e) {
            // Fallback: hard-coded known endpoint
            return "https://skuytov.eu/grafik/sse.php";
        }
    }

    /** Broadcasts the update intent on the main thread. */
    private void broadcastUpdate() {
        mainHandler.post(() -> {
            Log.i(TAG, "SSE update received — broadcasting to DisplayActivity");
            Intent intent = new Intent(ACTION_CONTENT_UPDATED);
            intent.setPackage(getPackageName()); // explicit broadcast (Android 8+)
            sendBroadcast(intent);
        });
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Foreground notification (required on Android 8+)
    // ─────────────────────────────────────────────────────────────────────────

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "PartPulse Live Sync",
                NotificationManager.IMPORTANCE_MIN // silent, no sound
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
