package com.partpulse.displaylauncher;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

public class SettingsActivity extends AppCompatActivity {

    private AppPrefs prefs;

    // Display section
    private EditText    etUrl;
    private Spinner     spRefresh;

    // Security section
    private Switch      swPinEnabled;
    private LinearLayout layoutNewPin;
    private EditText    etPin1, etPin2;
    private TextView    tvPinMismatch;

    // Interface section
    private Switch      swToolbar;

    // Refresh options (labels and values in seconds; 0 = off)
    private static final String[] REFRESH_LABELS  = { "Off", "30 s", "1 min", "2 min", "5 min", "10 min" };
    private static final int[]    REFRESH_VALUES   = {    0,     30,      60,     120,     300,      600   };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        prefs = new AppPrefs(this);

        // Toolbar
        androidx.appcompat.widget.Toolbar toolbar = findViewById(R.id.settingsToolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Settings");
        }

        initDisplaySection();
        initSecuritySection();
        initInterfaceSection();

        // Save button
        findViewById(R.id.btnSave).setOnClickListener(v -> saveAndClose());
    }

    // ── Display ──────────────────────────────────────────────────────────────

    private void initDisplaySection() {
        etUrl = findViewById(R.id.etUrl);
        etUrl.setText(prefs.getUrl());

        spRefresh = findViewById(R.id.spRefresh);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
            this, android.R.layout.simple_spinner_item, REFRESH_LABELS);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spRefresh.setAdapter(adapter);

        int currentRefresh = prefs.getRefreshSeconds();
        for (int i = 0; i < REFRESH_VALUES.length; i++) {
            if (REFRESH_VALUES[i] == currentRefresh) {
                spRefresh.setSelection(i);
                break;
            }
        }
    }

    // ── Security ─────────────────────────────────────────────────────────────

    private void initSecuritySection() {
        swPinEnabled  = findViewById(R.id.swPinEnabled);
        layoutNewPin  = findViewById(R.id.layoutNewPin);
        etPin1        = findViewById(R.id.etPin1);
        etPin2        = findViewById(R.id.etPin2);
        tvPinMismatch = findViewById(R.id.tvPinMismatch);

        swPinEnabled.setChecked(prefs.isPinEnabled());
        layoutNewPin.setVisibility(prefs.isPinEnabled() ? View.VISIBLE : View.GONE);

        swPinEnabled.setOnCheckedChangeListener((btn, checked) ->
            layoutNewPin.setVisibility(checked ? View.VISIBLE : View.GONE));

        etPin2.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            public void onTextChanged(CharSequence s, int st, int b, int c) {}
            public void afterTextChanged(Editable s) {
                String p1 = etPin1.getText().toString();
                String p2 = s.toString();
                boolean mismatch = !p2.isEmpty() && p2.length() == 4 && !p1.equals(p2);
                tvPinMismatch.setVisibility(mismatch ? View.VISIBLE : View.GONE);
            }
        });

        // Pre-fill with existing PIN (masked)
        String currentPin = prefs.getPin();
        etPin1.setHint("New 4-digit PIN  (current: " + currentPin + ")");
    }

    // ── Interface ────────────────────────────────────────────────────────────

    private void initInterfaceSection() {
        swToolbar = findViewById(R.id.swToolbar);
        swToolbar.setChecked(prefs.isToolbarVisible());
    }

    // ── Save ─────────────────────────────────────────────────────────────────

    private void saveAndClose() {
        // URL
        String url = etUrl.getText().toString().trim();
        if (url.isEmpty()) {
            Toast.makeText(this, "URL cannot be empty", Toast.LENGTH_SHORT).show();
            return;
        }
        prefs.setUrl(url);

        // Refresh
        int selectedIdx = spRefresh.getSelectedItemPosition();
        prefs.setRefreshSeconds(REFRESH_VALUES[selectedIdx]);

        // PIN
        prefs.setPinEnabled(swPinEnabled.isChecked());
        if (swPinEnabled.isChecked()) {
            String p1 = etPin1.getText().toString();
            String p2 = etPin2.getText().toString();
            if (!p1.isEmpty()) {
                if (p1.length() != 4 || !p1.matches("\\d{4}")) {
                    Toast.makeText(this, "PIN must be exactly 4 digits", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (!p1.equals(p2)) {
                    Toast.makeText(this, "PINs do not match", Toast.LENGTH_SHORT).show();
                    return;
                }
                prefs.setPin(p1);
            }
        }

        // Toolbar
        prefs.setToolbarVisible(swToolbar.isChecked());

        Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show();
        finish();
    }

    @Override
    public boolean onSupportNavigateUp() {
        onBackPressed();
        return true;
    }
}
