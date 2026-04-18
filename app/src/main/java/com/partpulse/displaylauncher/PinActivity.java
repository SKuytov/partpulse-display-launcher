package com.partpulse.displaylauncher;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

/**
 * Full-screen dark PIN entry screen.
 * Shows 4 dot indicators and a custom numpad.
 * On success navigates to the target activity specified by EXTRA_TARGET.
 */
public class PinActivity extends AppCompatActivity {

    public static final String EXTRA_TARGET    = "target";
    public static final String TARGET_SETTINGS = "settings";

    private static final int PIN_LENGTH = 4;

    private final StringBuilder input = new StringBuilder();
    private AppPrefs prefs;

    // Dot views
    private View dot1, dot2, dot3, dot4;
    private TextView tvError;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_pin);

        prefs   = new AppPrefs(this);
        dot1    = findViewById(R.id.dot1);
        dot2    = findViewById(R.id.dot2);
        dot3    = findViewById(R.id.dot3);
        dot4    = findViewById(R.id.dot4);
        tvError = findViewById(R.id.tvError);

        // Digit buttons
        int[] digitIds = {
            R.id.btn0, R.id.btn1, R.id.btn2, R.id.btn3,
            R.id.btn4, R.id.btn5, R.id.btn6, R.id.btn7,
            R.id.btn8, R.id.btn9
        };
        for (int id : digitIds) {
            TextView btn = findViewById(id);
            btn.setOnClickListener(v -> appendDigit(((TextView) v).getText().toString()));
        }

        // CLR button — clear all
        findViewById(R.id.btnClr).setOnClickListener(v -> {
            input.setLength(0);
            updateDots();
            tvError.setVisibility(View.INVISIBLE);
        });

        // Backspace button
        findViewById(R.id.btnBack).setOnClickListener(v -> {
            if (input.length() > 0) {
                input.deleteCharAt(input.length() - 1);
                updateDots();
                tvError.setVisibility(View.INVISIBLE);
            }
        });

        updateDots();
    }

    private void appendDigit(String digit) {
        if (input.length() >= PIN_LENGTH) return;
        input.append(digit);
        updateDots();
        if (input.length() == PIN_LENGTH) {
            verifyPin();
        }
    }

    private void verifyPin() {
        if (input.toString().equals(prefs.getPin())) {
            String target = getIntent().getStringExtra(EXTRA_TARGET);
            if (TARGET_SETTINGS.equals(target)) {
                startActivity(new Intent(this, SettingsActivity.class));
            }
            finish();
        } else {
            tvError.setVisibility(View.VISIBLE);
            input.setLength(0);
            updateDots();
        }
    }

    private void updateDots() {
        int len = input.length();
        setDotFilled(dot1, len >= 1);
        setDotFilled(dot2, len >= 2);
        setDotFilled(dot3, len >= 3);
        setDotFilled(dot4, len >= 4);
    }

    private void setDotFilled(View dot, boolean filled) {
        dot.setBackgroundResource(filled ? R.drawable.dot_filled : R.drawable.dot_empty);
    }

    @Override
    public void onBackPressed() {
        // Prevent navigating back — user must enter the correct PIN or power-off
    }
}
