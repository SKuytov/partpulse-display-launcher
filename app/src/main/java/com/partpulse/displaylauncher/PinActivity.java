package com.partpulse.displaylauncher;

import android.content.Intent;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

/**
 * Full-screen dark PIN entry screen.
 * Fully navigable with a TV remote D-pad:
 *   - Arrow keys move between numpad buttons
 *   - ENTER/DPAD_CENTER selects the focused button
 */
public class PinActivity extends AppCompatActivity {

    public static final String EXTRA_TARGET    = "target";
    public static final String TARGET_SETTINGS = "settings";

    private static final int PIN_LENGTH = 4;

    private final StringBuilder input = new StringBuilder();
    private AppPrefs prefs;

    private View dot1, dot2, dot3, dot4;
    private TextView tvError;

    // Numpad button grid — row-major order (3 columns)
    private TextView[][] grid;

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

        // Reference all buttons in grid order so we can wire D-pad traversal
        grid = new TextView[][] {
            { findViewById(R.id.btn1), findViewById(R.id.btn2), findViewById(R.id.btn3) },
            { findViewById(R.id.btn4), findViewById(R.id.btn5), findViewById(R.id.btn6) },
            { findViewById(R.id.btn7), findViewById(R.id.btn8), findViewById(R.id.btn9) },
            { findViewById(R.id.btnClr), findViewById(R.id.btn0), findViewById(R.id.btnBack) },
        };

        // Wire click listeners
        for (TextView[] row : grid) {
            for (TextView btn : row) {
                btn.setOnClickListener(v -> handleButton(((TextView) v).getText().toString()));
                // Also respond to DPAD_CENTER / ENTER key while focused
                btn.setOnKeyListener((v, keyCode, event) -> {
                    if (event.getAction() == KeyEvent.ACTION_DOWN &&
                            (keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                                    || keyCode == KeyEvent.KEYCODE_ENTER)) {
                        handleButton(((TextView) v).getText().toString());
                        return true;
                    }
                    return false;
                });
            }
        }

        // Wire D-pad focus traversal manually for the grid
        wireFocusTraversal();

        updateDots();

        // Start focus on button "5" (centre of numpad — ergonomic default)
        grid[1][1].requestFocus();
    }

    // ── Button action ─────────────────────────────────────────────────────────

    private void handleButton(String label) {
        switch (label) {
            case "CLR":
                input.setLength(0);
                updateDots();
                tvError.setVisibility(View.INVISIBLE);
                break;
            case "⌫":
                if (input.length() > 0) {
                    input.deleteCharAt(input.length() - 1);
                    updateDots();
                    tvError.setVisibility(View.INVISIBLE);
                }
                break;
            default:
                // Digit
                if (input.length() < PIN_LENGTH) {
                    input.append(label);
                    updateDots();
                    if (input.length() == PIN_LENGTH) verifyPin();
                }
                break;
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

    // ── D-pad focus traversal wiring ─────────────────────────────────────────

    private void wireFocusTraversal() {
        int rows = grid.length;
        int cols = grid[0].length;

        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                TextView btn = grid[r][c];

                // UP
                if (r > 0) {
                    btn.setNextFocusUpId(grid[r - 1][c].getId());
                } else {
                    btn.setNextFocusUpId(btn.getId()); // stay on top row
                }

                // DOWN
                if (r < rows - 1) {
                    btn.setNextFocusDownId(grid[r + 1][c].getId());
                } else {
                    btn.setNextFocusDownId(btn.getId()); // stay on bottom row
                }

                // LEFT
                if (c > 0) {
                    btn.setNextFocusLeftId(grid[r][c - 1].getId());
                } else {
                    btn.setNextFocusLeftId(grid[r][cols - 1].getId()); // wrap to end of row
                }

                // RIGHT
                if (c < cols - 1) {
                    btn.setNextFocusRightId(grid[r][c + 1].getId());
                } else {
                    btn.setNextFocusRightId(grid[r][0].getId()); // wrap to start of row
                }
            }
        }
    }

    // ── Dot indicators ────────────────────────────────────────────────────────

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

    // ── Block back ────────────────────────────────────────────────────────────

    @Override
    public void onBackPressed() {
        // Block — user must enter the correct PIN
    }
}
