package com.partpulse.displaylauncher;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.LruCache;
import android.view.KeyEvent;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.shockwave.pdfium.PdfiumCore;
import com.shockwave.pdfium.PdfDocument;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Main launcher activity — renders PDF slides natively via PdfRenderer
 * and notification overlays. Replaces WebView-based DisplayActivity.
 */
public class NativeDisplayActivity extends Activity {

    private static final String TAG = "NativeDisplay";
    public static final String ACTION_SLIDES_UPDATED =
        "com.partpulse.displaylauncher.SLIDES_UPDATED";

    private PdfPageView pdfCanvas;
    private FrameLayout notificationOverlay;
    private LinearLayout notifWithImageLayout;
    private LinearLayout notifTextPanel;
    private LinearLayout notifImagePanel;
    private TextView notifTag, notifTitle, notifBody, notifDate;
    private TextView notifTagNoImg, notifTitleNoImg, notifBodyNoImg, notifDateNoImg;
    private ImageView notifImage;
    private TextView hudLabel, hudClock;
    private LinearLayout dotRow;
    private View progressBar;
    private ProgressBar loadingSpinner;

    private SlideManager slideManager;
    private AppPrefs prefs;
    private final List<Slide> slides = new ArrayList<>();
    private int currentIndex = 0;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService renderExec = Executors.newSingleThreadExecutor();
    private final ExecutorService saveExec   = Executors.newSingleThreadExecutor(); // dedicated PNG save thread

    // In-memory bitmap cache: key = localPath, value = rendered Bitmap
    // Use 1/4 of available heap — keeps rendered bitmaps across slide cycles
    private final LruCache<String, Bitmap> bitmapCache = new LruCache<String, Bitmap>(
            (int)(Runtime.getRuntime().maxMemory() / 4)) {
        @Override protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };

    private PowerManager.WakeLock screenWakeLock;
    private static final long KEEPALIVE_INTERVAL_MS = 3 * 60 * 1000L;
    private static final long SERVICE_CHECK_INTERVAL_MS = 5 * 60_000L;

    private final Runnable serviceCheckRunnable = new Runnable() {
        @Override public void run() {
            startSseService();
            main.postDelayed(this, SERVICE_CHECK_INTERVAL_MS);
        }
    };

    private final Runnable keepAliveRunnable = new Runnable() {
        @Override public void run() {
            try {
                // userActivity() is @hide — use reflection so it compiles against public SDK
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                if (pm != null) {
                    try {
                        java.lang.reflect.Method ua = pm.getClass()
                            .getMethod("userActivity", long.class, boolean.class);
                        ua.invoke(pm, SystemClock.uptimeMillis(), false);
                    } catch (Exception ignored) { /* not available on this firmware */ }
                }
            } catch (Exception e) {
                Log.w(TAG, "userActivity failed: " + e.getMessage());
            }
            runOnUiThread(() ->
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
            main.postDelayed(keepAliveRunnable, KEEPALIVE_INTERVAL_MS);
        }
    };

    private final Runnable advanceRunnable = new Runnable() {
        @Override public void run() {
            if (!slides.isEmpty()) {
                currentIndex = (currentIndex + 1) % slides.size();
                showSlide(currentIndex);
            }
            scheduleAdvance();
        }
    };

    private final Runnable clockRunnable = new Runnable() {
        @Override public void run() {
            updateClock();
            main.postDelayed(this, 1_000L);
        }
    };

    private final BroadcastReceiver updateReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context ctx, Intent intent) {
            Log.i(TAG, "Slides update broadcast received, refreshing...");
            loadSlides();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                screenWakeLock = pm.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ON_AFTER_RELEASE,
                    "PartPulse:ScreenKeepOn");
                screenWakeLock.setReferenceCounted(false);
            }
        } catch (Exception e) {
            Log.w(TAG, "WakeLock init failed: " + e.getMessage());
        }

        setFullscreen();
        setContentView(R.layout.activity_native_display);

        pdfCanvas           = findViewById(R.id.pdfCanvas);
        notificationOverlay = findViewById(R.id.notificationOverlay);
        notifWithImageLayout = findViewById(R.id.notifWithImageLayout);
        notifTextPanel      = findViewById(R.id.notifTextPanel);
        notifImagePanel     = findViewById(R.id.notifImagePanel);
        notifTag            = findViewById(R.id.notifTag);
        notifTitle          = findViewById(R.id.notifTitle);
        notifBody           = findViewById(R.id.notifBody);
        notifDate           = findViewById(R.id.notifDate);
        notifImage          = findViewById(R.id.notifImage);
        notifTagNoImg       = findViewById(R.id.notifTagNoImg);
        notifTitleNoImg     = findViewById(R.id.notifTitleNoImg);
        notifBodyNoImg      = findViewById(R.id.notifBodyNoImg);
        notifDateNoImg      = findViewById(R.id.notifDateNoImg);
        hudLabel            = findViewById(R.id.hudLabel);
        hudClock            = findViewById(R.id.hudClock);
        dotRow              = findViewById(R.id.dotRow);
        progressBar         = findViewById(R.id.progressBar);
        loadingSpinner      = findViewById(R.id.loadingSpinner);

        prefs = new AppPrefs(this);
        slideManager = new SlideManager();

        main.post(clockRunnable);

        IntentFilter filter = new IntentFilter(ACTION_SLIDES_UPDATED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(updateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(updateReceiver, filter);
        }

        loadSlides();
        startSseService();
    }

    @Override
    protected void onResume() {
        super.onResume();
        try {
            if (screenWakeLock != null && !screenWakeLock.isHeld()) screenWakeLock.acquire();
        } catch (Exception e) {
            Log.w(TAG, "WakeLock acquire failed: " + e.getMessage());
        }
        main.postDelayed(keepAliveRunnable, KEEPALIVE_INTERVAL_MS);
        main.postDelayed(serviceCheckRunnable, SERVICE_CHECK_INTERVAL_MS);
    }

    @Override
    protected void onPause() {
        super.onPause();
        main.removeCallbacks(keepAliveRunnable);
        main.removeCallbacks(serviceCheckRunnable);
        try {
            if (screenWakeLock != null && screenWakeLock.isHeld()) screenWakeLock.release();
        } catch (Exception e) {
            Log.w(TAG, "WakeLock release failed: " + e.getMessage());
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        main.removeCallbacksAndMessages(null);
        try { unregisterReceiver(updateReceiver); } catch (Exception ignored) {}
        try {
            if (screenWakeLock != null && screenWakeLock.isHeld()) screenWakeLock.release();
        } catch (Exception ignored) {}
        renderExec.shutdownNow();
    }

    private void setFullscreen() {
        View decor = getWindow().getDecorView();
        int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                  | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                  | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                  | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                  | View.SYSTEM_UI_FLAG_FULLSCREEN
                  | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
        decor.setSystemUiVisibility(flags);
    }

    private void loadSlides() {
        loadingSpinner.setVisibility(View.VISIBLE);
        slideManager.refresh(this, new SlideManager.Callback() {
            @Override public void onSlidesReady(List<Slide> newSlides, String hash) {
                slides.clear();
                slides.addAll(newSlides);
                bitmapCache.evictAll(); // clear stale bitmaps from previous schedule
                loadingSpinner.setVisibility(View.GONE);
                if (slides.isEmpty()) {
                    Log.w(TAG, "No slides to display");
                    return;
                }
                buildDots();
                currentIndex = Math.min(currentIndex, slides.size() - 1);
                showSlide(currentIndex);
                scheduleAdvance();
            }
            @Override public void onError(Exception e) {
                Log.e(TAG, "Failed to load slides", e);
                loadingSpinner.setVisibility(View.GONE);
            }
        });
    }

    private void startSseService() {
        try {
            Intent svc = new Intent(this, SseUpdateService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(svc);
            } else {
                startService(svc);
            }
        } catch (Exception e) {
            Log.w(TAG, "SSE service start failed: " + e.getMessage());
        }
    }

    // ─── slide display ──────────────────────────────────────────────────────

    private void showSlide(int index) {
        if (index < 0 || index >= slides.size()) return;
        final Slide s = slides.get(index);

        applyLabelChip(s);
        updateDots(index);

        if (Slide.TYPE_NOTIFICATION.equals(s.type)) {
            pdfCanvas.setVisibility(View.GONE);
            pdfCanvas.clearBitmap();
            notificationOverlay.setVisibility(View.VISIBLE);

            String tagText;
            if (s.lblClass != null && !s.lblClass.isEmpty()) {
                switch (s.lblClass.toLowerCase().replace("-", "").replace("_", "")) {
                    case "lblnotif":
                    case "notif":
                    case "notification":
                        tagText = "СЪОБЩЕНИЕ"; break;
                    case "lblwarn":
                    case "warn":
                    case "warning":
                        tagText = "ВНИМАНИЕ"; break;
                    case "lblinfo":
                    case "info":
                        tagText = "ИНФОРМАЦИЯ"; break;
                    default:
                        tagText = s.lblClass.replace("lbl-", "").replace("lbl_", "")
                                            .replace("-", " ").replace("_", " ")
                                            .toUpperCase();
                }
            } else {
                tagText = "СЪОБЩЕНИЕ";
            }

            boolean hasImage = s.localPath != null && !s.localPath.isEmpty()
                    && new File(s.localPath).exists();

            if (hasImage) {
                notifWithImageLayout.setVisibility(View.VISIBLE);
                notifImagePanel.setVisibility(View.GONE);

                notifTag.setText(tagText);
                notifTitle.setText(s.title != null ? s.title : "");
                notifBody.setText(s.body != null ? s.body : "");
                notifDate.setText(s.date != null ? s.date : "");

                final String imgPath = s.localPath;
                renderExec.execute(() -> {
                    BitmapFactory.Options opts = new BitmapFactory.Options();
                    opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
                    final Bitmap bmp = BitmapFactory.decodeFile(imgPath, opts);
                    main.post(() -> {
                        if (bmp != null) {
                            notifImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
                            notifImage.setImageBitmap(bmp);
                        }
                    });
                });
            } else {
                showNotifNoImage(s, tagText);
            }
        } else {
            notificationOverlay.setVisibility(View.GONE);
            pdfCanvas.setVisibility(View.VISIBLE);
            renderPdf(s);
        }

        animateProgress(Math.max(1, s.duration) * 1000);

        // Prefetch the next slide in background so it's ready when auto-advance fires
        prefetchNextSlide(index);
    }

    private void showNotifNoImage(Slide s, String tagText) {
        notifWithImageLayout.setVisibility(View.GONE);
        notifImagePanel.setVisibility(View.VISIBLE);

        notifTagNoImg.setText(tagText);
        notifTitleNoImg.setText(s.title != null ? s.title : "");
        notifBodyNoImg.setText(s.body != null ? s.body : "");
        notifDateNoImg.setText(s.date != null ? s.date : "");
    }

    private void applyLabelChip(Slide s) {
        GradientDrawable chipBg = new GradientDrawable();
        chipBg.setCornerRadius(dp(20));
        chipBg.setColor(labelBgColor(s.lblClass));
        hudLabel.setBackground(chipBg);
        hudLabel.setTextColor(labelTextColor(s.lblClass));
        String text = s.label == null ? "" : s.label;
        hudLabel.setText(text.toUpperCase(Locale.getDefault()));
    }

    private int labelBgColor(String lblClass) {
        switch (lblClass == null ? "" : lblClass) {
            case "lbl-blue":   return 0x5A3B82F6;
            case "lbl-amber":  return 0x5AF59E0B;
            case "lbl-green":  return 0x4D22C55E;
            case "lbl-purple": return 0x4DA855F7;
            case "lbl-red":    return 0x4DEF4444;
            case "lbl-teal":   return 0x4D14B8A6;
            case "lbl-notif":  return 0x4022C55E;
            default:           return 0x663B82F6;
        }
    }

    private int labelTextColor(String lblClass) {
        switch (lblClass == null ? "" : lblClass) {
            case "lbl-blue":   return 0xFFBFDBFE;
            case "lbl-amber":  return 0xFFFDE68A;
            case "lbl-green":  return 0xFFBBF7D0;
            case "lbl-purple": return 0xFFE9D5FF;
            case "lbl-red":    return 0xFFFECACA;
            case "lbl-teal":   return 0xFF99F6E4;
            case "lbl-notif":  return 0xFF86EFAC;
            default:           return 0xFFFFFFFF;
        }
    }

    private void animateProgress(int durationMs) {
        if (progressBar == null) return;
        progressBar.animate().cancel();
        progressBar.setScaleX(0f);
        progressBar.setPivotX(0f);
        progressBar.animate()
            .scaleX(1f)
            .setDuration(durationMs)
            .setInterpolator(new LinearInterpolator())
            .start();
    }

    private void renderPdf(final Slide s) {
        if (s.localPath == null) return;
        final File pdfFile = new File(s.localPath);
        if (!pdfFile.exists()) {
            Log.w(TAG, "PDF missing: " + s.localPath);
            return;
        }

        final File pngCache = new File(
            pdfFile.getParent(),
            pdfFile.getName().replaceAll("\\.pdf$", "_rendered.jpg"));

        final DisplayMetrics realDm = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(realDm);
        final int screenW = realDm.widthPixels;
        final int screenH = realDm.heightPixels;
        final Context appContext = getApplicationContext();

        // Memory cache hit — instant, no disk I/O
        Bitmap memCached = bitmapCache.get(pdfFile.getAbsolutePath());
        if (memCached != null) {
            final Bitmap mc = memCached;
            main.post(() -> {
                pdfCanvas.setVisibility(View.VISIBLE);
                pdfCanvas.setBitmap(mc);
            });
            return;
        }

        renderExec.execute(() -> {
            if (pngCache.exists()) {
                try {
                    BitmapFactory.Options opts = new BitmapFactory.Options();
                    opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
                    final Bitmap cached = BitmapFactory.decodeFile(pngCache.getAbsolutePath(), opts);
                    if (cached != null) {
                        Log.i(TAG, "JPEG cache hit: " + pngCache.getName());
                        bitmapCache.put(pdfFile.getAbsolutePath(), cached); // store in memory too
                        main.post(() -> {
                            pdfCanvas.setVisibility(View.VISIBLE);
                            pdfCanvas.setBitmap(cached);
                        });
                        return;
                    }
                } catch (Exception e) {
                    Log.w(TAG, "JPEG cache decode failed, re-rendering: " + e.getMessage());
                    pngCache.delete();
                }
            }

            Log.i(TAG, "JPEG cache miss — rendering via Pdfium: " + pdfFile.getName());
            PdfiumCore pdfiumCore = null;
            PdfDocument pdfDocument = null;
            try {
                pdfiumCore = new PdfiumCore(appContext);
                ParcelFileDescriptor pfd = ParcelFileDescriptor.open(
                    pdfFile, ParcelFileDescriptor.MODE_READ_ONLY);
                pdfDocument = pdfiumCore.newDocument(pfd);
                pdfiumCore.openPage(pdfDocument, 0);

                int pdfW = pdfiumCore.getPageWidthPoint(pdfDocument, 0);
                int pdfH = pdfiumCore.getPageHeightPoint(pdfDocument, 0);

                Bitmap bmp = null;
                int[] scales = {4, 2, 1};
                for (int mult : scales) {
                    float scale = Math.min(screenW * mult / (float) pdfW, screenH * mult / (float) pdfH);
                    int bmpW = Math.max(1, Math.round(pdfW * scale));
                    int bmpH = Math.max(1, Math.round(pdfH * scale));
                    try {
                        bmp = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888);
                        pdfiumCore.renderPageBitmap(pdfDocument, bmp, 0, 0, 0, bmpW, bmpH, true);
                        Log.i(TAG, "PDF rendered at " + mult + "x (" + bmpW + "x" + bmpH + ")");
                        break;
                    } catch (OutOfMemoryError oom) {
                        Log.w(TAG, "OOM at " + mult + "x, trying lower scale");
                        if (bmp != null) { bmp.recycle(); bmp = null; }
                    }
                }
                if (bmp == null) return;
                final Bitmap finalBmp = bmp;

                // Store in memory cache for instant access on next slide cycle
                bitmapCache.put(pdfFile.getAbsolutePath(), finalBmp);

                // Show bitmap immediately — don't block on JPEG write
                main.post(() -> {
                    pdfCanvas.setVisibility(View.VISIBLE);
                    pdfCanvas.setBitmap(finalBmp);
                });

                // Save PNG on separate thread so display is never delayed
                saveExec.execute(() -> {
                    try (java.io.FileOutputStream fos = new java.io.FileOutputStream(pngCache)) {
                        finalBmp.compress(Bitmap.CompressFormat.JPEG, 95, fos); // JPEG 95 = visually lossless, 10x smaller
                        Log.i(TAG, "JPEG cache saved: " + pngCache.getName());
                    } catch (Exception e) {
                        Log.w(TAG, "JPEG cache save failed: " + e.getMessage());
                        pngCache.delete();
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Pdfium render failed: " + e.getMessage());
            } finally {
                try {
                    if (pdfDocument != null && pdfiumCore != null)
                        pdfiumCore.closeDocument(pdfDocument);
                } catch (Exception ignored) {}
            }
        });
    }

    // ─── pre-render ─────────────────────────────────────────────────────────

    /**
     * Immediately start rendering the NEXT slide in background.
     * Called after showSlide() so by the time auto-advance fires, it's already in cache.
     */
    private void prefetchNextSlide(int currentIdx) {
        if (slides.isEmpty()) return;
        int nextIdx = (currentIdx + 1) % slides.size();
        Slide next = slides.get(nextIdx);
        if (Slide.TYPE_PDF.equals(next.type) && next.localPath != null) {
            // Only prefetch if not already in memory cache
            if (bitmapCache.get(next.localPath) == null) {
                renderPdf(next); // queued on renderExec, runs in background
            }
        }
    }

    /** Render all PDF slides in background so every slide is instant on display. */
    private void preRenderAllSlides() {
        for (int i = 0; i < slides.size(); i++) {
            final Slide s = slides.get(i);
            if ("pdf".equals(s.type) && s.localPath != null) {
                final int idx = i;
                if (idx == currentIndex) continue; // already rendered by showSlide
                renderPdf(s); // queued on renderExec, runs sequentially in background
            }
        }
    }

    // ─── dots ───────────────────────────────────────────────────────────────

    private void buildDots() {
        dotRow.removeAllViews();
        int size = dp(10);
        int margin = dp(4);
        for (int i = 0; i < slides.size(); i++) {
            View dot = new View(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.setMargins(margin, 0, margin, 0);
            dot.setLayoutParams(lp);
            GradientDrawable d = new GradientDrawable();
            d.setShape(GradientDrawable.OVAL);
            d.setColor(0x66FFFFFF);
            dot.setBackground(d);
            dotRow.addView(dot);
        }
    }

    private void updateDots(int active) {
        for (int i = 0; i < dotRow.getChildCount(); i++) {
            View dot = dotRow.getChildAt(i);
            GradientDrawable d = new GradientDrawable();
            d.setShape(GradientDrawable.OVAL);
            d.setColor(i == active ? 0xFFFFFFFF : 0x66FFFFFF);
            dot.setBackground(d);
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    // ─── auto-advance ───────────────────────────────────────────────────────

    private void scheduleAdvance() {
        main.removeCallbacks(advanceRunnable);
        int secs = 0;
        if (!slides.isEmpty() && currentIndex >= 0 && currentIndex < slides.size()) {
            secs = slides.get(currentIndex).duration;
        }
        if (secs <= 0) secs = prefs.getRefreshSeconds();
        if (secs <= 0) secs = prefs.getDefaultSlideDuration();
        if (secs <= 0) secs = 10;
        main.postDelayed(advanceRunnable, secs * 1000L);
    }

    private void prevSlide() {
        if (slides.isEmpty()) return;
        currentIndex = (currentIndex - 1 + slides.size()) % slides.size();
        showSlide(currentIndex);
        scheduleAdvance();
    }

    private void nextSlide() {
        if (slides.isEmpty()) return;
        currentIndex = (currentIndex + 1) % slides.size();
        showSlide(currentIndex);
        scheduleAdvance();
    }

    // ─── clock ──────────────────────────────────────────────────────────────

    private void updateClock() {
        SimpleDateFormat fmt = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
        hudClock.setText(fmt.format(new Date()));
    }

    // ─── D-pad / remote navigation ──────────────────────────────────────────

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    prevSlide();
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    nextSlide();
                    return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                    return true;
                case KeyEvent.KEYCODE_MENU:
                case KeyEvent.KEYCODE_SETTINGS:
                case KeyEvent.KEYCODE_BACK:
                    openSettings();
                    return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private void openSettings() {
        if (prefs.isPinEnabled()) {
            Intent intent = new Intent(this, PinActivity.class);
            intent.putExtra(PinActivity.EXTRA_TARGET, PinActivity.TARGET_SETTINGS);
            startActivity(intent);
        } else {
            startActivity(new Intent(this, SettingsActivity.class));
        }
    }
}
