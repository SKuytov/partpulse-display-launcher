package com.partpulse.displaylauncher;

import android.annotation.SuppressLint;
import android.content.Intent;
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
 * Improvements in this version:
 *  1. FLAG_KEEP_SCREEN_ON  — prevents screensaver / display sleep
 *  2. Smooth page load     — page is hidden until fully rendered, then
 *                            revealed with a quick fade (no more flash of
 *                            background → colours → text)
 *  3. Sharp text           — WebView forced to 100% initial scale with a
 *                            correct meta-viewport so TV pixels map 1:1
 *  4. D-pad navigation     — dispatchKeyEvent approach (unchanged from v4)
 */
public class DisplayActivity extends AppCompatActivity {

    private static final int  SCROLL_STEP  = 300;
    private static final long HIDE_DELAY_MS = 5_000;

    // Toolbar cursor: 0 = Refresh, 1 = Settings
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

    // JS injected on every page to hide content until load is complete
    private static final String JS_HIDE_UNTIL_READY =
        // 1. Immediately hide the body so nothing flashes in
        "document.documentElement.style.visibility='hidden';" +
        "document.documentElement.style.transition='';";

    // JS injected in onPageFinished to reveal with a smooth fade
    private static final String JS_REVEAL =
        "document.documentElement.style.transition='opacity 0.3s ease';" +
        "document.documentElement.style.opacity='0';" +
        "document.documentElement.style.visibility='visible';" +
        "setTimeout(function(){ document.documentElement.style.opacity='1'; }, 30);";

    // JS injected to fix viewport for sharp rendering on TV
    // Forces the page to fill the screen at 1:1 pixel ratio
    private static final String JS_FIX_VIEWPORT =
        "(function(){" +
        "  var m = document.querySelector('meta[name=viewport]');" +
        "  var c = 'width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no';" +
        "  if(m){ m.setAttribute('content', c); }" +
        "  else {" +
        "    var n = document.createElement('meta');" +
        "    n.name='viewport'; n.content=c;" +
        "    document.head.appendChild(n);" +
        "  }" +
        "})();";

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // ── 1. KEEP SCREEN ON — prevents screensaver ────────────────────────
        getWindow().addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        );

        setContentView(R.layout.activity_display);

        prefs          = new AppPrefs(this);
        refreshHandler = new Handler(Looper.getMainLooper());
        hideHandler    = new Handler(Looper.getMainLooper());

        webView     = findViewById(R.id.webView);
        toolbar     = findViewById(R.id.toolbar);
        tvUrl       = findViewById(R.id.tvUrl);
        btnRefresh  = findViewById(R.id.btnRefresh);
        btnSettings = findViewById(R.id.btnSettings);

        // ── 2. WebView config ────────────────────────────────────────────────
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setLoadWithOverviewMode(true);
        ws.setUseWideViewPort(true);
        ws.setCacheMode(WebSettings.LOAD_DEFAULT);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        // ── 3. SHARP TEXT — force 100% initial scale ─────────────────────────
        // setInitialScale(100) = 100% → 1 CSS px = 1 physical px at the
        // WebView's logical density. Combined with the meta-viewport override
        // injected in JS, this gives crisp rendering on large TV screens.
        webView.setInitialScale(0);   // 0 = let the viewport meta decide
        ws.setTextZoom(100);          // never scale text independently

        // ── 4. Disable WebView's internal focus / cursor ─────────────────────
        webView.setFocusable(false);
        webView.setFocusableInTouchMode(false);

        // ── 5. WebViewClient — hide-until-ready + reveal on finish ───────────
        webView.setWebViewClient(new WebViewClient() {

            @Override
            public void onPageStarted(WebView view, String url,
                                      android.graphics.Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                // Immediately hide to prevent flash of unstyled content
                view.evaluateJavascript(JS_HIDE_UNTIL_READY, null);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                // Fix viewport first, then reveal with fade
                view.evaluateJavascript(JS_FIX_VIEWPORT, null);
                view.evaluateJavascript(JS_REVEAL, null);
            }
        });
        webView.setWebChromeClient(new WebChromeClient());

        // ── Touch shows toolbar ──────────────────────────────────────────────
        webView.setOnTouchListener((v, ev) -> {
            showToolbar();
            return false;
        });

        // ── Toolbar buttons ──────────────────────────────────────────────────
        btnRefresh.setOnClickListener(v -> { webView.reload(); scheduleAutoHide(); });
        btnSettings.setOnClickListener(v -> openSettings());
        btnRefresh.setFocusable(false);
        btnSettings.setFocusable(false);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  D-PAD — intercepted at Activity level (before WebView touches anything)
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event);

        int kc = event.getKeyCode();

        if (kc == KeyEvent.KEYCODE_MENU) {
            showToolbar();
            return true;
        }

        return toolbarVisible ? handleKeyWithToolbar(kc) : handleKeyInWebView(kc);
    }

    private boolean handleKeyWithToolbar(int kc) {
        switch (kc) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                toolbarFocus = 0; highlightToolbar(); scheduleAutoHide(); return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                toolbarFocus = 1; highlightToolbar(); scheduleAutoHide(); return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                hideToolbar(); return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                scheduleAutoHide(); return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
                if (toolbarFocus == 0) { webView.reload(); scheduleAutoHide(); }
                else openSettings();
                return true;
            case KeyEvent.KEYCODE_BACK:
                hideToolbar(); return true;
            default:
                return true;
        }
    }

    private boolean handleKeyInWebView(int kc) {
        switch (kc) {
            case KeyEvent.KEYCODE_DPAD_UP:    jsScroll(0, -SCROLL_STEP);  return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:  jsScroll(0, SCROLL_STEP);   return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:  jsScroll(-SCROLL_STEP, 0);  return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT: jsScroll(SCROLL_STEP, 0);   return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:   jsClick();                   return true;
            case KeyEvent.KEYCODE_BACK:
                if (webView.canGoBack()) webView.goBack();
                return true;
            case KeyEvent.KEYCODE_MENU:
                showToolbar(); return true;
            default:
                return super.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, kc));
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
    //  Toolbar helpers
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
        btnRefresh.setAlpha(1f);
        btnSettings.setAlpha(1f);
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
    //  Settings / lifecycle
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

    @Override
    protected void onResume() {
        super.onResume();
        // Re-apply keep-screen-on in case it was cleared
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        String url = prefs.getUrl();
        tvUrl.setText(url);
        webView.loadUrl(url);
        toolbar.setVisibility(View.GONE);
        toolbarVisible = false;
        startRefreshTimer();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopRefreshTimer();
        hideHandler.removeCallbacksAndMessages(null);
    }

    @Override
    public void onBackPressed() { /* handled in dispatchKeyEvent */ }

    private void startRefreshTimer() {
        stopRefreshTimer();
        int sec = prefs.getRefreshSeconds();
        if (sec <= 0) return;
        refreshRunnable = new Runnable() {
            @Override public void run() {
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
