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
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.view.WindowManager;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.webkit.WebViewClientCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewRenderProcess;
import androidx.webkit.WebViewRenderProcessClient;

import com.shockwave.pdfium.PdfDocument;
import com.shockwave.pdfium.PdfiumCore;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Main launcher activity — renders PDF slides natively via Pdfium,
 * notification overlays, and full-screen WebView slides.
 */
public class NativeDisplayActivity extends Activity {

    private static final String TAG = "NativeDisplay";
    public static final String ACTION_SLIDES_UPDATED =
            "com.partpulse.displaylauncher.SLIDES_UPDATED";

    private PdfPageView pdfCanvas;
    private WebView webSlideView;
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
    private final ExecutorService saveExec = Executors.newSingleThreadExecutor();

    private final LruCache<String, Bitmap> bitmapCache = new LruCache<String, Bitmap>(
            (int) (Runtime.getRuntime().maxMemory() / 4)
    ) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };

    private PowerManager.WakeLock screenWakeLock;
    private static final long KEEPALIVE_INTERVAL_MS = 3 * 60 * 1000L;
    private static final long SERVICE_CHECK_INTERVAL_MS = 5 * 60_000L;

    private final Runnable serviceCheckRunnable = new Runnable() {
        @Override
        public void run() {
            startSseService();
            main.postDelayed(this, SERVICE_CHECK_INTERVAL_MS);
        }
    };

    private final Runnable keepAliveRunnable = new Runnable() {
        @Override
        public void run() {
            try {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                if (pm != null) {
                    try {
                        java.lang.reflect.Method ua = pm.getClass()
                                .getMethod("userActivity", long.class, boolean.class);
                        ua.invoke(pm, SystemClock.uptimeMillis(), false);
                    } catch (Exception ignored) {
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "userActivity failed: " + e.getMessage());
            }

            runOnUiThread(() ->
                    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));

            main.postDelayed(this, KEEPALIVE_INTERVAL_MS);
        }
    };

    private final Runnable advanceRunnable = new Runnable() {
        @Override
        public void run() {
            if (!slides.isEmpty()) {
                currentIndex = (currentIndex + 1) % slides.size();
                showSlide(currentIndex);
            }
            scheduleAdvance();
        }
    };

    private final Runnable clockRunnable = new Runnable() {
        @Override
        public void run() {
            updateClock();
            main.postDelayed(this, 1_000L);
        }
    };

    private final BroadcastReceiver updateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
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
                        "PartPulse:ScreenKeepOn"
                );
                screenWakeLock.setReferenceCounted(false);
            }
        } catch (Exception e) {
            Log.w(TAG, "WakeLock init failed: " + e.getMessage());
        }

        setFullscreen();
        setContentView(R.layout.activity_native_display);

        pdfCanvas = findViewById(R.id.pdfCanvas);
        webSlideView = findViewById(R.id.webSlideView);
        notificationOverlay = findViewById(R.id.notificationOverlay);
        notifWithImageLayout = findViewById(R.id.notifWithImageLayout);
        notifTextPanel = findViewById(R.id.notifTextPanel);
        notifImagePanel = findViewById(R.id.notifImagePanel);
        notifTag = findViewById(R.id.notifTag);
        notifTitle = findViewById(R.id.notifTitle);
        notifBody = findViewById(R.id.notifBody);
        notifDate = findViewById(R.id.notifDate);
        notifImage = findViewById(R.id.notifImage);
        notifTagNoImg = findViewById(R.id.notifTagNoImg);
        notifTitleNoImg = findViewById(R.id.notifTitleNoImg);
        notifBodyNoImg = findViewById(R.id.notifBodyNoImg);
        notifDateNoImg = findViewById(R.id.notifDateNoImg);
        hudLabel = findViewById(R.id.hudLabel);
        hudClock = findViewById(R.id.hudClock);
        dotRow = findViewById(R.id.dotRow);
        progressBar = findViewById(R.id.progressBar);
        loadingSpinner = findViewById(R.id.loadingSpinner);

        configureWebSlideView();

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

    private void configureWebSlideView() {
        if (webSlideView == null) return;

        WebSettings ws = webSlideView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setLoadWithOverviewMode(true);
        ws.setUseWideViewPort(true);
        ws.setBuiltInZoomControls(false);
        ws.setDisplayZoomControls(false);
        ws.setCacheMode(WebSettings.LOAD_DEFAULT);
        ws.setSupportMultipleWindows(false);
        ws.setJavaScriptCanOpenWindowsAutomatically(false);

        webSlideView.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        webSlideView.setOnLongClickListener(v -> true);
        webSlideView.setLongClickable(false);
        webSlideView.setHapticFeedbackEnabled(false);

        webSlideView.setWebViewClient(new WebViewClientCompat() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return false;
            }

            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                Log.e(TAG, "WebView renderer gone. didCrash=" + detail.didCrash());
                recreateWebView();
                return true;
            }
        });

        try {
    webSlideView.setRendererPriorityPolicy(
            WebView.RENDERER_PRIORITY_IMPORTANT,
            false
    );
} catch (Throwable t) {
    Log.w(TAG, "Renderer priority policy not available: " + t.getMessage());
}
);
        } catch (Throwable t) {
            Log.w(TAG, "Renderer priority policy not available: " + t.getMessage());
        }

        try {
            WebViewCompat.setWebViewRenderProcessClient(
                    webSlideView,
                    new WebViewRenderProcessClient() {
                        @Override
                        public void onRenderProcessUnresponsive(
                                WebView view,
                                WebViewRenderProcess renderer
                        ) {
                            Log.e(TAG, "WebView renderer unresponsive. Recreating WebView.");
                            try {
                                if (renderer != null) {
                                    renderer.terminate();
                                }
                            } catch (Throwable ignored) {
                            }
                            recreateWebView();
                        }

                        @Override
                        public void onRenderProcessResponsive(
                                WebView view,
                                WebViewRenderProcess renderer
                        ) {
                            Log.i(TAG, "WebView renderer responsive again.");
                        }
                    }
            );
        } catch (Throwable t) {
            Log.w(TAG, "RenderProcessClient not available: " + t.getMessage());
        }
    }

    private void recreateWebView() {
        main.post(() -> {
            try {
                if (webSlideView != null) {
                    try {
                        webSlideView.stopLoading();
                        webSlideView.loadUrl("about:blank");
                        webSlideView.clearHistory();
                        webSlideView.clearCache(true);
                        webSlideView.onPause();
                    } catch (Throwable ignored) {
                    }

                    ViewGroup parent = (ViewGroup) webSlideView.getParent();
                    if (parent != null) {
                        parent.removeView(webSlideView);

                        WebView newWebView = new WebView(this);
                        newWebView.setId(R.id.webSlideView);
                        newWebView.setVisibility(View.GONE);

                        int index = parent.indexOfChild(pdfCanvas);
                        if (index < 0) {
                            index = 0;
                        }

                        parent.addView(
                                newWebView,
                                index + 1,
                                new FrameLayout.LayoutParams(
                                        FrameLayout.LayoutParams.MATCH_PARENT,
                                        FrameLayout.LayoutParams.MATCH_PARENT
                                )
                        );

                        webSlideView.destroy();
                        webSlideView = newWebView;
                        configureWebSlideView();
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to recreate WebView", e);
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        try {
            if (screenWakeLock != null && !screenWakeLock.isHeld()) {
                screenWakeLock.acquire();
            }
        } catch (Exception e) {
            Log.w(TAG, "WakeLock acquire failed: " + e.getMessage());
        }

        main.postDelayed(keepAliveRunnable, KEEPALIVE_INTERVAL_MS);
        main.postDelayed(serviceCheckRunnable, SERVICE_CHECK_INTERVAL_MS);

        if (webSlideView != null) {
            try {
               webSlideView.onResume();
               webSlideView.resumeTimers();
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        main.removeCallbacks(keepAliveRunnable);
        main.removeCallbacks(serviceCheckRunnable);

        try {
            if (screenWakeLock != null && screenWakeLock.isHeld()) {
                screenWakeLock.release();
            }
        } catch (Exception e) {
            Log.w(TAG, "WakeLock release failed: " + e.getMessage());
        }

        if (webSlideView != null) {
            try {
                webSlideView.onPause();
                webSlideView.pauseTimers();
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        main.removeCallbacksAndMessages(null);

        try {
            unregisterReceiver(updateReceiver);
        } catch (Exception ignored) {
        }

        try {
            if (screenWakeLock != null && screenWakeLock.isHeld()) {
                screenWakeLock.release();
            }
        } catch (Exception ignored) {
        }

        if (webSlideView != null) {
            try {
                webSlideView.stopLoading();
                webSlideView.loadUrl("about:blank");
                webSlideView.clearHistory();
                webSlideView.clearCache(true);
                webSlideView.onPause();
                webSlideView.destroy();
            } catch (Exception ignored) {
            }
        }

        renderExec.shutdownNow();
        saveExec.shutdownNow();
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
            @Override
            public void onSlidesReady(List<Slide> newSlides, String hash) {
                slides.clear();
                slides.addAll(newSlides);
                bitmapCache.evictAll();
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

            @Override
            public void onError(Exception e) {
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

    private void showSlide(int index) {
        if (index < 0 || index >= slides.size()) return;
        final Slide s = slides.get(index);

        applyLabelChip(s);
        updateDots(index);

        if (Slide.TYPE_WEBPAGE.equals(s.type)) {
            pdfCanvas.setVisibility(View.GONE);
            pdfCanvas.clearBitmap();
            notificationOverlay.setVisibility(View.GONE);
            webSlideView.setVisibility(View.VISIBLE);

            if (s.url != null && !s.url.isEmpty()) {
                try {
                    webSlideView.stopLoading();
                    webSlideView.loadUrl("about:blank");
                    webSlideView.clearHistory();
                    webSlideView.clearCache(false);
                    webSlideView.loadUrl(s.url);
                } catch (Exception e) {
                    Log.e(TAG, "Failed loading webpage slide: " + s.url, e);
                    recreateWebView();
                }
            }
        } else if (Slide.TYPE_NOTIFICATION.equals(s.type)) {
            pdfCanvas.setVisibility(View.GONE);
            pdfCanvas.clearBitmap();
            webSlideView.setVisibility(View.GONE);
            notificationOverlay.setVisibility(View.VISIBLE);

            String tagText;
            if (s.lblClass != null && !s.lblClass.isEmpty()) {
                switch (s.lblClass.toLowerCase().replace("-", "").replace("_", "")) {
                    case "lblnotif":
                    case "notif":
                    case "notification":
                        tagText = "СЪОБЩЕНИЕ";
                        break;
                    case "lblwarn":
                    case "warn":
                    case "warning":
                        tagText = "ВНИМАНИЕ";
                        break;
                    case "lblinfo":
                    case "info":
                        tagText = "ИНФОРМАЦИЯ";
                        break;
                    default:
                        tagText = s.lblClass.replace("lbl-", "").replace("lbl_", "")
                                .replace("-", " ").replace("_", " ")
                                .toUpperCase();
                        break;
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
                autoFitTitle(notifTitle, 48f, 22f);
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
            webSlideView.setVisibility(View.GONE);
            pdfCanvas.setVisibility(View.VISIBLE);
            renderPdf(s);
        }

        animateProgress(Math.max(1, s.duration) * 1000);
        prefetchNextSlide(index);
    }

    private void showNotifNoImage(Slide s, String tagText) {
        notifWithImageLayout.setVisibility(View.GONE);
        notifImagePanel.setVisibility(View.VISIBLE);

        notifTagNoImg.setText(tagText);
        notifTitleNoImg.setText(s.title != null ? s.title : "");
        autoFitTitle(notifTitleNoImg, 52f, 24f);
        notifBodyNoImg.setText(s.body != null ? s.body : "");
        notifDateNoImg.setText(s.date != null ? s.date : "");
    }

    private void autoFitTitle(final TextView tv, final float maxSp, final float minSp) {
        tv.post(() -> {
            int width = tv.getWidth() - tv.getPaddingLeft() - tv.getPaddingRight();
            if (width <= 0) return;

            float scaledDensity = getResources().getDisplayMetrics().scaledDensity;
            android.text.TextPaint paint = new android.text.TextPaint(tv.getPaint());

            float sp = maxSp;
            while (sp >= minSp) {
                paint.setTextSize(sp * scaledDensity);
                android.text.StaticLayout sl = android.text.StaticLayout.Builder
                        .obtain(tv.getText(), 0, tv.getText().length(), paint, width)
                        .setMaxLines(2)
                        .setEllipsize(android.text.TextUtils.TruncateAt.END)
                        .build();

                if (sl.getLineCount() <= 2 && !isTruncated(sl)) {
                    break;
                }
                sp -= 1f;
            }

            float finalSp = Math.max(sp, minSp);
            tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, finalSp);
        });
    }

    private boolean isTruncated(android.text.StaticLayout sl) {
        int lineCount = sl.getLineCount();
        if (lineCount == 0) return false;
        int lastLine = lineCount - 1;
        return sl.getEllipsisCount(lastLine) > 0;
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
            case "lbl-blue":
                return 0x5A3B82F6;
            case "lbl-amber":
                return 0x5AF59E0B;
            case "lbl-green":
                return 0x4D22C55E;
            case "lbl-purple":
                return 0x4DA855F7;
            case "lbl-red":
                return 0x4DEF4444;
            case "lbl-teal":
                return 0x4D14B8A6;
            case "lbl-notif":
                return 0x4022C55E;
            default:
                return 0x663B82F6;
        }
    }

    private int labelTextColor(String lblClass) {
        switch (lblClass == null ? "" : lblClass) {
            case "lbl-blue":
                return 0xFFBFDBFE;
            case "lbl-amber":
                return 0xFFFDE68A;
            case "lbl-green":
                return 0xFFBBF7D0;
            case "lbl-purple":
                return 0xFFE9D5FF;
            case "lbl-red":
                return 0xFFFECACA;
            case "lbl-teal":
                return 0xFF99F6E4;
            case "lbl-notif":
                return 0xFF86EFAC;
            default:
                return 0xFFFFFFFF;
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

        final File jpgCache = new File(
                pdfFile.getParent(),
                pdfFile.getName().replaceAll("\\.pdf$", "_rendered.jpg")
        );

        final DisplayMetrics realDm = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(realDm);
        final int screenW = realDm.widthPixels;
        final int screenH = realDm.heightPixels;
        final Context appContext = getApplicationContext();

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
            if (jpgCache.exists()) {
                try {
                    BitmapFactory.Options opts = new BitmapFactory.Options();
                    opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
                    final Bitmap cached = BitmapFactory.decodeFile(jpgCache.getAbsolutePath(), opts);
                    if (cached != null) {
                        Log.i(TAG, "JPEG cache hit: " + jpgCache.getName());
                        bitmapCache.put(pdfFile.getAbsolutePath(), cached);
                        main.post(() -> {
                            pdfCanvas.setVisibility(View.VISIBLE);
                            pdfCanvas.setBitmap(cached);
                        });
                        return;
                    }
                } catch (Exception e) {
                    Log.w(TAG, "JPEG cache decode failed, re-rendering: " + e.getMessage());
                    jpgCache.delete();
                }
            }

            Log.i(TAG, "JPEG cache miss — rendering via Pdfium: " + pdfFile.getName());

            PdfiumCore pdfiumCore = null;
            PdfDocument pdfDocument = null;

            try {
                pdfiumCore = new PdfiumCore(appContext);
                ParcelFileDescriptor pfd = ParcelFileDescriptor.open(
                        pdfFile,
                        ParcelFileDescriptor.MODE_READ_ONLY
                );
                pdfDocument = pdfiumCore.newDocument(pfd);
                pdfiumCore.openPage(pdfDocument, 0);

                int pdfW = pdfiumCore.getPageWidthPoint(pdfDocument, 0);
                int pdfH = pdfiumCore.getPageHeightPoint(pdfDocument, 0);

                Bitmap bmp = null;
                int[] scales = {4, 2, 1};

                for (int mult : scales) {
                    float scale = Math.min(
                            screenW * mult / (float) pdfW,
                            screenH * mult / (float) pdfH
                    );
                    int bmpW = Math.max(1, Math.round(pdfW * scale));
                    int bmpH = Math.max(1, Math.round(pdfH * scale));

                    try {
                        bmp = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888);
                        pdfiumCore.renderPageBitmap(pdfDocument, bmp, 0, 0, 0, bmpW, bmpH, true);
                        Log.i(TAG, "PDF rendered at " + mult + "x (" + bmpW + "x" + bmpH + ")");
                        break;
                    } catch (OutOfMemoryError oom) {
                        Log.w(TAG, "OOM at " + mult + "x, trying lower scale");
                        if (bmp != null) {
                            bmp.recycle();
                            bmp = null;
                        }
                    }
                }

                if (bmp == null) return;
                final Bitmap finalBmp = bmp;

                bitmapCache.put(pdfFile.getAbsolutePath(), finalBmp);

                main.post(() -> {
                    pdfCanvas.setVisibility(View.VISIBLE);
                    pdfCanvas.setBitmap(finalBmp);
                });

                saveExec.execute(() -> {
                    try (java.io.FileOutputStream fos = new java.io.FileOutputStream(jpgCache)) {
                        finalBmp.compress(Bitmap.CompressFormat.JPEG, 95, fos);
                        Log.i(TAG, "JPEG cache saved: " + jpgCache.getName());
                    } catch (Exception e) {
                        Log.w(TAG, "JPEG cache save failed: " + e.getMessage());
                        jpgCache.delete();
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Pdfium render failed: " + e.getMessage());
            } finally {
                try {
                    if (pdfDocument != null && pdfiumCore != null) {
                        pdfiumCore.closeDocument(pdfDocument);
                    }
                } catch (Exception ignored) {
                }
            }
        });
    }

    private void prefetchNextSlide(int currentIdx) {
        if (slides.isEmpty()) return;
        int nextIdx = (currentIdx + 1) % slides.size();
        Slide next = slides.get(nextIdx);
        if (Slide.TYPE_PDF.equals(next.type) && next.localPath != null) {
            if (bitmapCache.get(next.localPath) == null) {
                renderPdf(next);
            }
        }
    }

    private void preRenderAllSlides() {
        for (int i = 0; i < slides.size(); i++) {
            final Slide s = slides.get(i);
            if (Slide.TYPE_PDF.equals(s.type) && s.localPath != null) {
                if (i == currentIndex) continue;
                renderPdf(s);
            }
        }
    }

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

    private void updateClock() {
        SimpleDateFormat fmt = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
        hudClock.setText(fmt.format(new Date()));
    }

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
