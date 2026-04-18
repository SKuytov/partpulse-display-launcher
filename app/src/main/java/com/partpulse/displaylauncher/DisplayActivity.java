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
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

public class DisplayActivity extends AppCompatActivity {

    private WebView         webView;
    private View            toolbar;
    private TextView        tvUrl;
    private ImageButton     btnRefresh;
    private ImageButton     btnSettings;

    private AppPrefs        prefs;
    private Handler         refreshHandler;
    private Runnable        refreshRunnable;

    // Toolbar auto-hide timer
    private Handler         hideHandler;
    private static final long HIDE_DELAY_MS = 5_000; // 5 s

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_display);

        prefs         = new AppPrefs(this);
        refreshHandler = new Handler(Looper.getMainLooper());
        hideHandler    = new Handler(Looper.getMainLooper());

        // Views
        webView     = findViewById(R.id.webView);
        toolbar     = findViewById(R.id.toolbar);
        tvUrl       = findViewById(R.id.tvUrl);
        btnRefresh  = findViewById(R.id.btnRefresh);
        btnSettings = findViewById(R.id.btnSettings);

        // WebView setup
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                // Hide scrollbars injected by some pages
                view.evaluateJavascript(
                    "document.documentElement.style.overflow='hidden';", null);
            }
        });
        webView.setWebChromeClient(new WebChromeClient());

        // Back button stays inside WebView history
        webView.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_BACK
                    && event.getAction() == KeyEvent.ACTION_UP
                    && webView.canGoBack()) {
                webView.goBack();
                return true;
            }
            return false;
        });

        // Toolbar buttons
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

        // Show/hide toolbar on touch
        webView.setOnTouchListener((v, motionEvent) -> {
            showToolbar();
            scheduleAutoHide();
            return false;
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Reload URL and settings every time we come back (e.g. after Settings)
        loadUrl();
        applyToolbarVisibility();
        startRefreshTimer();
        if (prefs.isToolbarVisible()) {
            showToolbar();
            scheduleAutoHide();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopRefreshTimer();
        hideHandler.removeCallbacksAndMessages(null);
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        }
        // Intentionally NOT calling super — prevents exiting the app
    }

    // -------------------------------------------------------------------------

    private void loadUrl() {
        String url = prefs.getUrl();
        tvUrl.setText(url);
        webView.loadUrl(url);
    }

    private void applyToolbarVisibility() {
        if (!prefs.isToolbarVisible()) {
            toolbar.setVisibility(View.GONE);
        } else {
            toolbar.setVisibility(View.VISIBLE);
        }
    }

    private void showToolbar() {
        toolbar.setVisibility(View.VISIBLE);
    }

    private void scheduleAutoHide() {
        hideHandler.removeCallbacksAndMessages(null);
        hideHandler.postDelayed(() -> {
            if (!prefs.isToolbarVisible()) return;
            toolbar.setVisibility(View.GONE);
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
