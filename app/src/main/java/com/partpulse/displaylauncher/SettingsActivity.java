package com.partpulse.displaylauncher;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
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

    private EditText     etUrl;
    private Spinner      spRefresh;
    private Switch       swPinEnabled;
    private LinearLayout layoutNewPin;
    private EditText     etPin1, etPin2;
    private TextView     tvPinMismatch;
    private Switch       swToolbar;
    private Button       btnSave;

    private static final String[] REFRESH_LABELS = { "Off", "30 s", "1 min", "2 min", "5 min", "10 min" };
    private static final int[]    REFRESH_VALUES  = {    0,     30,      60,     120,     300,      600  };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        prefs = new AppPrefs(this);

        androidx.appcompat.widget.Toolbar toolbar = findViewById(R.id.settingsToolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Settings");
        }

        initViews();
        initDisplaySection();
        initSecuritySection();
        initInterfaceSection();
        wireDpadNavigation();

        btnSave.setOnClickListener(v -> saveAndClose());

        // Initial focus goes to the URL field
        etUrl.requestFocus();
    }

    // ── View binding ─────────────────────────────────────────────────────────

    private void initViews() {
        etUrl         = findViewById(R.id.etUrl);
        spRefresh     = findViewById(R.id.spRefresh);
        swPinEnabled  = findViewById(R.id.swPinEnabled);
        layoutNewPin  = findViewById(R.id.layoutNewPin);
        etPin1        = findViewById(R.id.etPin1);
        etPin2        = findViewById(R.id.etPin2);
        tvPinMismatch = findViewById(R.id.tvPinMismatch);
        swToolbar     = findViewById(R.id.swToolbar);
        btnSave       = findViewById(R.id.btnSave);
    }

    // ── Display section ──────────────────────────────────────────────────────

    private void initDisplaySection() {
        etUrl.setText(prefs.getUrl());

        ArrayAdapter<String> adapter = new ArrayAdapter<>(
            this, android.R.layout.simple_spinner_item, REFRESH_LABELS);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spRefresh.setAdapter(adapter);

        int cur = prefs.getRefreshSeconds();
        for (int i = 0; i < REFRESH_VALUES.length; i++) {
            if (REFRESH_VALUES[i] == cur) { spRefresh.setSelection(i); break; }
        }
    }

    // ── Security section ─────────────────────────────────────────────────────

    private void initSecuritySection() {
        swPinEnabled.setChecked(prefs.isPinEnabled());
        layoutNewPin.setVisibility(prefs.isPinEnabled() ? View.VISIBLE : View.GONE);

        swPinEnabled.setOnCheckedChangeListener((btn, checked) -> {
            layoutNewPin.setVisibility(checked ? View.VISIBLE : View.GONE);
            wireDpadNavigation(); // rebuild traversal when layout changes
        });

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

        etPin1.setHint("New 4-digit PIN  (current: " + prefs.getPin() + ")");
    }

    // ── Interface section ────────────────────────────────────────────────────

    private void initInterfaceSection() {
        swToolbar.setChecked(prefs.isToolbarVisible());
    }

    // ── D-pad traversal ──────────────────────────────────────────────────────

    /**
     * Builds the D-pad focus chain for the settings form.
     * Called once on create and again if PIN section visibility changes.
     */
    private void wireDpadNavigation() {
        boolean pinOn = swPinEnabled.isChecked();

        // URL → Refresh spinner
        etUrl.setNextFocusDownId(R.id.spRefresh);
        etUrl.setImeActionLabel("Next", android.view.inputmethod.EditorInfo.IME_ACTION_NEXT);

        // Spinner → PIN switch
        spRefresh.setNextFocusDownId(R.id.swPinEnabled);
        spRefresh.setNextFocusUpId(R.id.etUrl);

        // PIN switch
        swPinEnabled.setNextFocusUpId(R.id.spRefresh);

        if (pinOn) {
            // PIN switch → PIN1 field
            swPinEnabled.setNextFocusDownId(R.id.etPin1);
            etPin1.setNextFocusUpId(R.id.swPinEnabled);
            etPin1.setNextFocusDownId(R.id.etPin2);
            etPin2.setNextFocusUpId(R.id.etPin1);
            etPin2.setNextFocusDownId(R.id.swToolbar);
            swToolbar.setNextFocusUpId(R.id.etPin2);
        } else {
            // PIN switch → Toolbar switch
            swPinEnabled.setNextFocusDownId(R.id.swToolbar);
            swToolbar.setNextFocusUpId(R.id.swPinEnabled);
        }

        // Toolbar switch → Save button
        swToolbar.setNextFocusDownId(R.id.btnSave);
        btnSave.setNextFocusUpId(R.id.swToolbar);

        // Allow ENTER/DPAD_CENTER to toggle switches
        setDpadToggle(swPinEnabled);
        setDpadToggle(swToolbar);

        // Allow DPAD_CENTER / ENTER on Spinner to open dropdown
        spRefresh.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN &&
                    (keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                            || keyCode == KeyEvent.KEYCODE_ENTER)) {
                spRefresh.performClick();
                return true;
            }
            return false;
        });
    }

    /** Makes a Switch respond to DPAD_CENTER / ENTER to toggle it. */
    private void setDpadToggle(Switch sw) {
        sw.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN &&
                    (keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                            || keyCode == KeyEvent.KEYCODE_ENTER)) {
                sw.toggle();
                return true;
            }
            return false;
        });
    }

    // ── Save ─────────────────────────────────────────────────────────────────

    private void saveAndClose() {
        String url = etUrl.getText().toString().trim();
        if (url.isEmpty()) {
            Toast.makeText(this, "URL cannot be empty", Toast.LENGTH_SHORT).show();
            return;
        }
        prefs.setUrl(url);

        int idx = spRefresh.getSelectedItemPosition();
        prefs.setRefreshSeconds(REFRESH_VALUES[idx]);

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
