package com.partpulse.displaylauncher;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

/**
 * Full-screen kiosk WebView for Android TV.
 *
 * Cache strategy:
 *   - On first load / app start: LOAD_CACHE_ELSE_NETWORK
 *     → page appears instantly from cache, no blank screen while network loads
 *   - On SSE update event: WebView.clearCache(true) + LOAD_NO_CACHE reload
 *     → ensures fresh content is fetched and stored as the new cache
 *   - After SSE reload completes: back to LOAD_CACHE_ELSE_NETWORK
 *     → subsequent auto-refreshes benefit from cache again
 *
 * SSE integration:
 *   SseUpdateService runs as a foreground service and broadcasts
 *   ACTION_CONTENT_UPDATED whenever the server pushes an SSE event.
 *   This activity registers a BroadcastReceiver for that intent.
 */
public class DisplayActivity extends AppCompatActivity {

    private static final int  SCROLL_STEP   = 300;
    private static final long HIDE_DELAY_MS = 5_000;

    private int     toolbarFocus   = 0;
    private boolean toolbarVisible = false;

    private WebView     webView;
    private View        toolbar;
    private TextView    tvUrl;
    private ImageButton btnRefresh;
    private ImageButton btnSettings;

    private AppPrefs  prefs;
    private Handler   refreshHandler;
    private Runnable  refreshRunnable;
    private Handler   hideHandler;

    // ── SSE broadcast receiver ───────────────────────────────────────────────
    private final BroadcastReceiver sseReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (SseUpdateService.ACTION_CONTENT_UPDATED.equals(intent.getAction())) {
                reloadFresh();
            }
        }
    };

    // ── Page load JS ─────────────────────────────────────────────────────────
    private static final String JS_HIDE =
        "document.documentElement.style.visibility='hidden';" +
        "document.documentElement.style.transition='';";

    private static final String JS_REVEAL =
        "document.documentElement.style.transition='opacity 0.3s ease';" +
        "document.documentElement.style.opacity='0';" +
        "document.documentElement.style.visibility='visible';" +
        "setTimeout(function(){ document.documentElement.style.opacity='1'; }, 30);";

    private static final String JS_FIX_VIEWPORT =
        "(function(){" +
        "  var m=document.querySelector('meta[name=viewport]');" +
        "  var c='width=device-width,initial-scale=1.0,maximum-scale=1.0,user-scalable=no';" +
        "  if(m){ m.setAttribute('content',c); } else {" +
        "    var n=document.createElement('meta');" +
        "    n.name='viewport'; n.content=c;" +
        "    document.head.appendChild(n); }" +
        "})();";

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_display);

        prefs          = new AppPrefs(this);
        refreshHandler = new Handler(Looper.getMainLooper());
        hideHandler    = new Handler(Looper.getMainLooper());

        webView     = findViewById(R.id.webView);
        toolbar     = findViewById(R.id.toolbar);
        tvUrl       = findViewById(R.id.tvUrl);
        btnRefresh  = findViewById(R.id.btnRefresh);
        btnSettings = findViewById(R.id.btnSettings);

        // ── WebView config ───────────────────────────────────────────────────
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setLoadWithOverviewMode(true);
        ws.setUseWideViewPort(true);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        ws.setTextZoom(100);
        // Enable app cache for offline / instant load
        ws.setDatabaseEnabled(true);
        // Start from cache — fastest first paint, no blank screen
        ws.setCacheMode(WebSettings.LOAD_CACHE_ELSE_NETWORK);

        webView.setFocusable(false);
        webView.setFocusableInTouchMode(false);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url,
                                      android.graphics.Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                view.evaluateJavascript(JS_HIDE, null);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                // After any load, restore normal cache mode
                view.getSettings().setCacheMode(WebSettings.LOAD_CACHE_ELSE_NETWORK);
                view.evaluateJavascript(JS_FIX_VIEWPORT, null);
                view.evaluateJavascript(JS_REVEAL, null);
            }
        });
        webView.setWebChromeClient(new WebChromeClient());

        webView.setOnTouchListener((v, ev) -> { showToolbar(); return false; });

        btnRefresh.setOnClickListener(v -> { reloadFresh(); scheduleAutoHide(); });
        btnSettings.setOnClickListener(v -> openSettings());
        btnRefresh.setFocusable(false);
        btnSettings.setFocusable(false);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Cache-aware reload helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Normal startup load — use cache first so the screen is never blank.
     * If the cache has the page, it shows instantly. Network fills in updates
     * in the background (or we wait for the SSE trigger).
     */
    private void loadFromCache(String url) {
        webView.getSettings().setCacheMode(WebSettings.LOAD_CACHE_ELSE_NETWORK);
        webView.loadUrl(url);
    }

    /**
     * Called when the SSE service signals that content has changed,
     * or when the user manually presses Refresh.
     *
     * Clears the WebView cache completely, then reloads bypassing cache
     * so the new content is fetched and becomes the new cached version.
     */
    private void reloadFresh() {
        webView.clearCache(true);
        webView.getSettings().setCacheMode(WebSettings.LOAD_NO_CACHE);
        webView.reload();
        // onPageFinished will reset cache mode back to LOAD_CACHE_ELSE_NETWORK
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Lifecycle — register/unregister SSE receiver, start/stop service
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    protected void onResume() {
        super.onResume();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        String url = prefs.getUrl();
        tvUrl.setText(url);
        loadFromCache(url);

        toolbar.setVisibility(View.GONE);
        toolbarVisible = false;

        startRefreshTimer();

        // Register receiver for SSE update broadcasts
        IntentFilter filter = new IntentFilter(SseUpdateService.ACTION_CONTENT_UPDATED);
        registerReceiver(sseReceiver, filter, RECEIVER_NOT_EXPORTED);

        // Start SSE background service
        Intent serviceIntent = new Intent(this, SseUpdateService.class);
        startService(serviceIntent);
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopRefreshTimer();
        hideHandler.removeCallbacksAndMessages(null);

        // Unregister receiver (re-registered in onResume)
        try { unregisterReceiver(sseReceiver); } catch (Exception ignored) {}

        // Keep service running so SSE stays alive even when screen is off
        // (the service is foreground so the OS won't kill it)
    }

    @Override
    public void onBackPressed() { /* handled in dispatchKeyEvent */ }

    // ─────────────────────────────────────────────────────────────────────────
    //  D-PAD — intercepted at Activity level
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event);
        int kc = event.getKeyCode();
        if (kc == KeyEvent.KEYCODE_MENU) { showToolbar(); return true; }
        return toolbarVisible ? handleKeyWithToolbar(kc) : handleKeyInWebView(kc);
    }

    private boolean handleKeyWithToolbar(int kc) {
        switch (kc) {
            case KeyEvent.KEYCODE_DPAD_LEFT:   toolbarFocus = 0; highlightToolbar(); scheduleAutoHide(); return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:  toolbarFocus = 1; highlightToolbar(); scheduleAutoHide(); return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:   hideToolbar(); return true;
            case KeyEvent.KEYCODE_DPAD_UP:     scheduleAutoHide(); return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
                if (toolbarFocus == 0) { reloadFresh(); scheduleAutoHide(); }
                else openSettings();
                return true;
            case KeyEvent.KEYCODE_BACK:        hideToolbar(); return true;
            default:                           return true;
        }
    }

    private boolean handleKeyInWebView(int kc) {
        switch (kc) {
            case KeyEvent.KEYCODE_DPAD_UP:    jsScroll(0, -SCROLL_STEP); return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:  jsScroll(0,  SCROLL_STEP); return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:  jsScroll(-SCROLL_STEP, 0); return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT: jsScroll( SCROLL_STEP, 0); return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:   jsClick(); return true;
            case KeyEvent.KEYCODE_BACK:
                if (webView.canGoBack()) webView.goBack();
                return true;
            case KeyEvent.KEYCODE_MENU: showToolbar(); return true;
            default: return super.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, kc));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  JS helpers
    // ─────────────────────────────────────────────────────────────────────────

    private void jsScroll(int dx, int dy) {
        webView.evaluateJavascript("window.scrollBy(" + dx + "," + dy + ");", null);
    }

    private void jsClick() {
        webView.evaluateJavascript(
            "(function(){ var el=document.activeElement;" +
            " if(el&&el!==document.body) el.click(); })()", null);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Toolbar
    // ─────────────────────────────────────────────────────────────────────────

    private void showToolbar() {
        toolbarVisible = true;
        toolbar.setVisibility(View.VISIBLE);
        toolbarFocus = 0;
        highlightToolbar();
        scheduleAutoHide();
    }

    private void hideToolbar() {
        toolbarVisible = false;
        toolbar.setVisibility(View.GONE);
        btnRefresh.setAlpha(1f); btnSettings.setAlpha(1f);
        btnRefresh.setBackgroundResource(R.drawable.btn_toolbar_selector);
        btnSettings.setBackgroundResource(R.drawable.btn_toolbar_selector);
        hideHandler.removeCallbacksAndMessages(null);
    }

    private void highlightToolbar() {
        btnRefresh.setAlpha(toolbarFocus == 0 ? 1.0f : 0.45f);
        btnSettings.setAlpha(toolbarFocus == 1 ? 1.0f : 0.45f);
        btnRefresh.setBackgroundResource(
            toolbarFocus == 0 ? R.drawable.btn_toolbar_focused : R.drawable.btn_toolbar_selector);
        btnSettings.setBackgroundResource(
            toolbarFocus == 1 ? R.drawable.btn_toolbar_focused : R.drawable.btn_toolbar_selector);
    }

    private void scheduleAutoHide() {
        hideHandler.removeCallbacksAndMessages(null);
        hideHandler.postDelayed(this::hideToolbar, HIDE_DELAY_MS);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Settings / refresh timer
    // ─────────────────────────────────────────────────────────────────────────

    private void openSettings() {
        stopRefreshTimer();
        hideToolbar();
        if (prefs.isPinEnabled()) {
            Intent i = new Intent(this, PinActivity.class);
            i.putExtra(PinActivity.EXTRA_TARGET, PinActivity.TARGET_SETTINGS);
            startActivity(i);
        } else {
            startActivity(new Intent(this, SettingsActivity.class));
        }
    }

    private void startRefreshTimer() {
        stopRefreshTimer();
        int sec = prefs.getRefreshSeconds();
        if (sec <= 0) return;
        refreshRunnable = new Runnable() {
            @Override public void run() {
                // Timer-based refresh uses cache — SSE handles forced refreshes
                webView.reload();
                refreshHandler.postDelayed(this, sec * 1000L);
            }
        };
        refreshHandler.postDelayed(refreshRunnable, sec * 1000L);
    }

    private void stopRefreshTimer() {
        if (refreshRunnable != null) {
            refreshHandler.removeCallbacks(refreshRunnable);
            refreshRunnable = null;
        }
    }
}
