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

public class DisplayActivity extends AppCompatActivity {

    private WebView      webView;
    private View         toolbar;
    private TextView     tvUrl;
    private ImageButton  btnRefresh;
    private ImageButton  btnSettings;

    private AppPrefs     prefs;
    private Handler      refreshHandler;
    private Runnable     refreshRunnable;

    private Handler      hideHandler;
    private static final long HIDE_DELAY_MS = 5_000;

    // Track whether toolbar is currently shown so D-pad Up from WebView shows it
    private boolean toolbarShowing = false;

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

        // ── WebView settings ────────────────────────────────────────────────
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setLoadWithOverviewMode(true);
        ws.setUseWideViewPort(true);
        ws.setCacheMode(WebSettings.LOAD_DEFAULT);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                view.evaluateJavascript(
                    "document.documentElement.style.overflow='hidden';", null);
            }
        });
        webView.setWebChromeClient(new WebChromeClient());

        // ── D-pad inside WebView ─────────────────────────────────────────────
        // WebView handles LEFT/RIGHT/DOWN naturally for scrolling web content.
        // UP from WebView edge shows the toolbar; ENTER activates focused link.
        webView.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;

            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    // If the page can't scroll up further, show toolbar instead
                    if (!webView.canScrollVertically(-1)) {
                        showToolbar();
                        scheduleAutoHide();
                        btnRefresh.requestFocus();
                        return true;
                    }
                    return false; // let WebView scroll

                case KeyEvent.KEYCODE_BACK:
                    if (webView.canGoBack()) {
                        webView.goBack();
                        return true;
                    }
                    return true; // block exit

                default:
                    return false;
            }
        });

        // ── Toolbar buttons ──────────────────────────────────────────────────
        btnRefresh.setOnClickListener(v -> {
            webView.reload();
            scheduleAutoHide();
        });

        btnSettings.setOnClickListener(v -> {
            stopRefreshTimer();
            if (prefs.isPinEnabled()) {
                Intent intent = new Intent(this, PinActivity.class);
                intent.putExtra(PinActivity.EXTRA_TARGET, PinActivity.TARGET_SETTINGS);
                startActivity(intent);
            } else {
                startActivity(new Intent(this, SettingsActivity.class));
            }
        });

        // When focus leaves the last toolbar button downward → return focus to WebView
        btnSettings.setOnFocusChangeListener((v, hasFocus) -> {
            // nothing needed — handled by nextFocusDown in XML
        });

        // D-pad DOWN from toolbar → back to WebView
        btnRefresh.setNextFocusDownId(R.id.webView);
        btnSettings.setNextFocusDownId(R.id.webView);

        // D-pad RIGHT from Refresh → Settings
        btnRefresh.setNextFocusRightId(R.id.btnSettings);
        // D-pad LEFT from Settings → Refresh
        btnSettings.setNextFocusLeftId(R.id.btnRefresh);

        // ── Touch shows toolbar ──────────────────────────────────────────────
        webView.setOnTouchListener((v, ev) -> {
            showToolbar();
            scheduleAutoHide();
            return false;
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadUrl();
        applyToolbarVisibility();
        startRefreshTimer();
        if (prefs.isToolbarVisible()) {
            showToolbar();
            scheduleAutoHide();
        }
        // Give focus to WebView on resume so remote works immediately
        webView.requestFocus();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopRefreshTimer();
        hideHandler.removeCallbacksAndMessages(null);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // MENU key (some remotes) or ENTER while WebView not focused → show toolbar
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            showToolbar();
            scheduleAutoHide();
            btnRefresh.requestFocus();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        }
        // do NOT call super — prevents exiting kiosk
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void loadUrl() {
        String url = prefs.getUrl();
        tvUrl.setText(url);
        webView.loadUrl(url);
    }

    private void applyToolbarVisibility() {
        toolbar.setVisibility(prefs.isToolbarVisible() ? View.VISIBLE : View.GONE);
    }

    private void showToolbar() {
        toolbarShowing = true;
        toolbar.setVisibility(View.VISIBLE);
    }

    private void scheduleAutoHide() {
        hideHandler.removeCallbacksAndMessages(null);
        hideHandler.postDelayed(() -> {
            toolbarShowing = false;
            toolbar.setVisibility(View.GONE);
            // Return focus to WebView when toolbar hides
            webView.requestFocus();
        }, HIDE_DELAY_MS);
    }

    private void startRefreshTimer() {
        stopRefreshTimer();
        int seconds = prefs.getRefreshSeconds();
        if (seconds <= 0) return;
        refreshRunnable = new Runnable() {
            @Override
            public void run() {
                webView.reload();
                refreshHandler.postDelayed(this, seconds * 1000L);
            }
        };
        refreshHandler.postDelayed(refreshRunnable, seconds * 1000L);
    }

    private void stopRefreshTimer() {
        if (refreshRunnable != null) {
            refreshHandler.removeCallbacks(refreshRunnable);
            refreshRunnable = null;
        }
    }
}
