package com.partpulse.displaylauncher;

import android.content.Intent;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

/**
 * PIN entry screen — navigated entirely via dispatchKeyEvent.
 * No View focus system is used. The "cursor" is tracked as (row, col)
 * in a 4×3 logical grid:
 *
 *   [1][2][3]
 *   [4][5][6]
 *   [7][8][9]
 *   [CLR][0][⌫]
 */
public class PinActivity extends AppCompatActivity {

    public static final String EXTRA_TARGET    = "target";
    public static final String TARGET_SETTINGS = "settings";

    private static final int PIN_LENGTH = 4;
    private static final int ROWS = 4;
    private static final int COLS = 3;

    // Grid content — matches layout IDs below
    private static final String[][] LABELS = {
        { "1", "2", "3" },
        { "4", "5", "6" },
        { "7", "8", "9" },
        { "CLR", "0", "⌫" }
    };

    private TextView[][] buttons;
    private View[]       dots;
    private TextView     tvError;
    private AppPrefs     prefs;

    private final StringBuilder input = new StringBuilder();

    // Current cursor position
    private int curRow = 1; // start on row with 4-5-6
    private int curCol = 1; // start on 5

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_pin);

        prefs = new AppPrefs(this);

        tvError = findViewById(R.id.tvError);
        dots = new View[]{ findViewById(R.id.dot1), findViewById(R.id.dot2),
                           findViewById(R.id.dot3), findViewById(R.id.dot4) };

        // Map grid to view IDs
        buttons = new TextView[ROWS][COLS];
        buttons[0][0] = findViewById(R.id.btn1);
        buttons[0][1] = findViewById(R.id.btn2);
        buttons[0][2] = findViewById(R.id.btn3);
        buttons[1][0] = findViewById(R.id.btn4);
        buttons[1][1] = findViewById(R.id.btn5);
        buttons[1][2] = findViewById(R.id.btn6);
        buttons[2][0] = findViewById(R.id.btn7);
        buttons[2][1] = findViewById(R.id.btn8);
        buttons[2][2] = findViewById(R.id.btn9);
        buttons[3][0] = findViewById(R.id.btnClr);
        buttons[3][1] = findViewById(R.id.btn0);
        buttons[3][2] = findViewById(R.id.btnBack);

        // Make all buttons non-focusable — we draw focus manually
        for (TextView[] row : buttons) {
            for (TextView btn : row) {
                btn.setFocusable(false);
                btn.setFocusableInTouchMode(false);
                // Touch still works
                btn.setOnClickListener(v -> {
                    String label = ((TextView) v).getText().toString();
                    pressButton(label);
                });
            }
        }

        View cancel = findViewById(R.id.btnPinCancel);
        if (cancel != null) cancel.setOnClickListener(v -> finish());

        updateDots();
        highlightCursor();
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  D-PAD intercepted at Activity level
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) return true; // consume all

        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_UP:
                moveCursor(-1, 0);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                moveCursor(1, 0);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                moveCursor(0, -1);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                moveCursor(0, 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
                pressButton(LABELS[curRow][curCol]);
                return true;
            case KeyEvent.KEYCODE_BACK:
                // Cancel — returns to the display (Settings stay locked)
                finish();
                return true;
            case KeyEvent.KEYCODE_DEL:
                pressButton("⌫");
                return true;
            default:
                // Number keys on a remote / hardware keyboard
                if (event.getKeyCode() >= KeyEvent.KEYCODE_0
                        && event.getKeyCode() <= KeyEvent.KEYCODE_9) {
                    pressButton(String.valueOf(event.getKeyCode() - KeyEvent.KEYCODE_0));
                } else if (event.getKeyCode() >= KeyEvent.KEYCODE_NUMPAD_0
                        && event.getKeyCode() <= KeyEvent.KEYCODE_NUMPAD_9) {
                    pressButton(String.valueOf(event.getKeyCode() - KeyEvent.KEYCODE_NUMPAD_0));
                }
                return true;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Cursor movement
    // ─────────────────────────────────────────────────────────────────────────

    private void moveCursor(int dr, int dc) {
        clearCursor();
        curRow = (curRow + dr + ROWS) % ROWS;
        curCol = (curCol + dc + COLS) % COLS;
        highlightCursor();
    }

    private void highlightCursor() {
        TextView btn = buttons[curRow][curCol];
        btn.setBackgroundResource(R.drawable.btn_pin_digit_focused);
        btn.setTextColor(0xFFFFFFFF);
    }

    private void clearCursor() {
        TextView btn = buttons[curRow][curCol];
        // Restore original drawable based on whether it's an alt button
        boolean isAlt = curRow == 3 && curCol != 1;
        btn.setBackgroundResource(isAlt ? R.drawable.btn_pin_alt : R.drawable.btn_pin_digit);
        btn.setTextColor(isAlt ? 0xFF00B4D8 : 0xFFEEEEFF);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Button action
    // ─────────────────────────────────────────────────────────────────────────

    private void pressButton(String label) {
        tvError.setVisibility(View.INVISIBLE);
        switch (label) {
            case "CLR":
                input.setLength(0);
                break;
            case "⌫":
                if (input.length() > 0) input.deleteCharAt(input.length() - 1);
                break;
            default:
                if (input.length() < PIN_LENGTH) input.append(label);
                break;
        }
        updateDots();
        if (input.length() == PIN_LENGTH) verifyPin();
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

    // ─────────────────────────────────────────────────────────────────────────
    //  Dot indicators
    // ─────────────────────────────────────────────────────────────────────────

    private void updateDots() {
        for (int i = 0; i < dots.length; i++) {
            dots[i].setBackgroundResource(
                i < input.length() ? R.drawable.dot_filled : R.drawable.dot_empty);
        }
    }

    @Override
    public void onBackPressed() { finish(); }
}
