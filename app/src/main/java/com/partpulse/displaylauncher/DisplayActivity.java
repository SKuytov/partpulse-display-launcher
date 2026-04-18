package com.partpulse.displaylauncher;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
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
 * KEY DESIGN DECISION — why we override dispatchKeyEvent instead of using
 * setOnKeyListener / nextFocusXxx:
 *
 *   Android TV remotes fire DPAD key events that flow through the Activity's
 *   dispatchKeyEvent() BEFORE they reach any View's focus system.
 *   WebView aggressively consumes key events internally (it tries to move its
 *   own internal focus/accessibility cursor), so by the time we set an
 *   OnKeyListener on the WebView, it's too late — the event is already eaten.
 *
 *   Solution: intercept at the Activity level, decide ourselves whether the
 *   key should scroll the WebView (via JS injection), show/hide the toolbar,
 *   or press a toolbar button — then return true to consume the event so
 *   nothing else touches it.
 */
public class DisplayActivity extends AppCompatActivity {

    // ── Scroll amount per D-pad press (px) ───────────────────────────────────
    private static final int SCROLL_STEP = 300;

    // ── Toolbar auto-hide delay ───────────────────────────────────────────────
    private static final long HIDE_DELAY_MS = 5_000;

    // ── Toolbar focus: 0 = Refresh, 1 = Settings ─────────────────────────────
    private int toolbarFocus = 0;
    private boolean toolbarVisible = false;

    private WebView      webView;
    private View         toolbar;
    private TextView     tvUrl;
    private ImageButton  btnRefresh;
    private ImageButton  btnSettings;

    private AppPrefs     prefs;
    private Handler      refreshHandler;
    private Runnable     refreshRunnable;
    private Handler      hideHandler;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
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
        ws.setCacheMode(WebSettings.LOAD_DEFAULT);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        // Disable WebView's own internal accessibility cursor — we handle navigation
        webView.setFocusable(false);
        webView.setFocusableInTouchMode(false);

        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient());

        // ── Touch on WebView shows toolbar ───────────────────────────────────
        webView.setOnTouchListener((v, ev) -> {
            showToolbar();
            return false;
        });

        // ── Toolbar button click listeners ───────────────────────────────────
        btnRefresh.setOnClickListener(v -> {
            webView.reload();
            scheduleAutoHide();
        });

        btnSettings.setOnClickListener(v -> openSettings());

        // Toolbar is NOT focusable via the View focus system —
        // we manage its "cursor" manually via toolbarFocus int
        btnRefresh.setFocusable(false);
        btnSettings.setFocusable(false);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  D-PAD KEY HANDLING  (intercepted before any View sees the event)
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) {
            // consume UP events for keys we already handled on DOWN, ignore the rest
            return super.dispatchKeyEvent(event);
        }

        int kc = event.getKeyCode();

        // ── MENU key or SELECT while toolbar hidden → show toolbar ────────────
        if (kc == KeyEvent.KEYCODE_MENU) {
            showToolbar();
            return true;
        }

        if (toolbarVisible) {
            return handleKeyWithToolbar(kc);
        } else {
            return handleKeyInWebView(kc);
        }
    }

    /** Handle key presses when toolbar is visible (toolbar is in "focus"). */
    private boolean handleKeyWithToolbar(int kc) {
        switch (kc) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                toolbarFocus = 0;
                highlightToolbar();
                scheduleAutoHide();
                return true;

            case KeyEvent.KEYCODE_DPAD_RIGHT:
                toolbarFocus = 1;
                highlightToolbar();
                scheduleAutoHide();
                return true;

            case KeyEvent.KEYCODE_DPAD_DOWN:
                // Move from toolbar back into WebView
                hideToolbar();
                return true;

            case KeyEvent.KEYCODE_DPAD_UP:
                // Already at top — just keep toolbar visible
                scheduleAutoHide();
                return true;

            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
                // Press whichever toolbar button is focused
                if (toolbarFocus == 0) {
                    webView.reload();
                    scheduleAutoHide();
                } else {
                    openSettings();
                }
                return true;

            case KeyEvent.KEYCODE_BACK:
                // Back hides toolbar first
                hideToolbar();
                return true;

            default:
                return true; // consume all keys while toolbar is open
        }
    }

    /** Handle key presses when toolbar is hidden (WebView is active). */
    private boolean handleKeyInWebView(int kc) {
        switch (kc) {
            case KeyEvent.KEYCODE_DPAD_UP:
                jsScroll(0, -SCROLL_STEP);
                return true;

            case KeyEvent.KEYCODE_DPAD_DOWN:
                jsScroll(0, SCROLL_STEP);
                return true;

            case KeyEvent.KEYCODE_DPAD_LEFT:
                jsScroll(-SCROLL_STEP, 0);
                return true;

            case KeyEvent.KEYCODE_DPAD_RIGHT:
                jsScroll(SCROLL_STEP, 0);
                return true;

            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
                // Click whatever element is under the JS cursor / simulate tap centre
                jsClick();
                return true;

            case KeyEvent.KEYCODE_BACK:
                if (webView.canGoBack()) {
                    webView.goBack();
                } // else: block exit from kiosk
                return true;

            case KeyEvent.KEYCODE_MENU:
                showToolbar();
                return true;

            default:
                return super.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, kc));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  JavaScript helpers
    // ─────────────────────────────────────────────────────────────────────────

    private void jsScroll(int dx, int dy) {
        webView.evaluateJavascript(
            "window.scrollBy(" + dx + "," + dy + ");", null);
    }

    private void jsClick() {
        // Simulate a click on the currently focused / active element
        webView.evaluateJavascript(
            "(function(){ var el = document.activeElement; " +
            "if(el && el !== document.body){ el.click(); } })()", null);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Toolbar helpers
    // ─────────────────────────────────────────────────────────────────────────

    private void showToolbar() {
        toolbarVisible = true;
        toolbar.setVisibility(View.VISIBLE);
        toolbarFocus = 0; // default focus on Refresh
        highlightToolbar();
        scheduleAutoHide();
    }

    private void hideToolbar() {
        toolbarVisible = false;
        toolbar.setVisibility(View.GONE);
        clearToolbarHighlight();
        hideHandler.removeCallbacksAndMessages(null);
    }

    private void highlightToolbar() {
        // Show which button is "focused" by changing alpha
        btnRefresh.setAlpha(toolbarFocus == 0 ? 1.0f : 0.5f);
        btnSettings.setAlpha(toolbarFocus == 1 ? 1.0f : 0.5f);

        // Also draw a highlight ring by changing background tint
        btnRefresh.setBackgroundResource(
            toolbarFocus == 0 ? R.drawable.btn_toolbar_focused : R.drawable.btn_toolbar_selector);
        btnSettings.setBackgroundResource(
            toolbarFocus == 1 ? R.drawable.btn_toolbar_focused : R.drawable.btn_toolbar_selector);
    }

    private void clearToolbarHighlight() {
        btnRefresh.setAlpha(1.0f);
        btnSettings.setAlpha(1.0f);
        btnRefresh.setBackgroundResource(R.drawable.btn_toolbar_selector);
        btnSettings.setBackgroundResource(R.drawable.btn_toolbar_selector);
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
    public void onBackPressed() {
        // Handled in dispatchKeyEvent — do nothing here
    }

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
