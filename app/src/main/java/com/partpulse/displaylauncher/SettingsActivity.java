package com.partpulse.displaylauncher;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Settings screen — D-pad navigable via dispatchKeyEvent.
 *
 * Cursor rows:
 *   0  URL field
 *   1  Refresh spinner
 *   2  PIN enabled switch
 *   3  New PIN field          (hidden when PIN disabled)
 *   4  Confirm PIN field      (hidden when PIN disabled)
 *   5  Toolbar switch
 *   6  Weather city EditText
 *   7  Refresh Weather button
 *   8  Slide duration spinner
 *   9  Save button
 */
public class SettingsActivity extends AppCompatActivity {

    private static final String[] REFRESH_LABELS =
        { "Off", "30 s", "1 min", "2 min", "5 min", "10 min" };
    private static final int[] REFRESH_VALUES =
        {    0,     30,      60,     120,     300,      600   };

    private static final String[] SLIDE_DURATION_LABELS =
        { "5 s", "10 s", "15 s", "20 s", "30 s", "45 s", "60 s" };
    private static final int[] SLIDE_DURATION_VALUES =
        {    5,    10,     15,     20,     30,     45,     60   };

    private static final int ROW_URL           = 0;
    private static final int ROW_REFRESH       = 1;
    private static final int ROW_PIN_SW        = 2;
    private static final int ROW_PIN1          = 3;
    private static final int ROW_PIN2          = 4;
    private static final int ROW_TOOLBAR       = 5;
    private static final int ROW_WEATHER_CITY  = 6;
    private static final int ROW_WEATHER_BTN   = 7;
    private static final int ROW_SLIDE_DUR     = 8;
    private static final int ROW_SAVE          = 9;
    private static final int ROW_MAX           = 9;

    private AppPrefs   prefs;
    private int        cursor     = 0;
    private boolean    editingText = false;

    private ScrollView   scrollView;
    private EditText     etUrl;
    private Spinner      spRefresh;
    private Switch       swPinEnabled;
    private LinearLayout layoutNewPin;
    private EditText     etPin1, etPin2;
    private TextView     tvPinMismatch;
    private Switch       swToolbar;
    private TextView     tvWeatherCity;
    private EditText     etWeatherCity;
    private Button       btnRefreshWeather;
    private TextView     tvWeatherResult;
    private TextView     tvHolidays;
    private Spinner      spSlideDuration;
    private Button       btnSave;

    // All row anchor views — used for scrolling to the active row
    private View[] rowViews;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService weatherExec = Executors.newSingleThreadExecutor();
    private final OkHttpClient http = new OkHttpClient();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        prefs = new AppPrefs(this);

        androidx.appcompat.widget.Toolbar tb = findViewById(R.id.settingsToolbar);
        setSupportActionBar(tb);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Settings");
        }

        scrollView        = findViewById(R.id.settingsScroll);
        etUrl             = findViewById(R.id.etUrl);
        spRefresh         = findViewById(R.id.spRefresh);
        swPinEnabled      = findViewById(R.id.swPinEnabled);
        layoutNewPin      = findViewById(R.id.layoutNewPin);
        etPin1            = findViewById(R.id.etPin1);
        etPin2            = findViewById(R.id.etPin2);
        tvPinMismatch     = findViewById(R.id.tvPinMismatch);
        swToolbar         = findViewById(R.id.swToolbar);
        tvWeatherCity     = findViewById(R.id.tvWeatherCity);
        etWeatherCity     = findViewById(R.id.etWeatherCity);
        btnRefreshWeather = findViewById(R.id.btnRefreshWeather);
        tvWeatherResult   = findViewById(R.id.tvWeatherResult);
        tvHolidays        = findViewById(R.id.tvHolidays);
        spSlideDuration   = findViewById(R.id.spSlideDuration);
        btnSave           = findViewById(R.id.btnSave);

        rowViews = new View[]{ etUrl, spRefresh, swPinEnabled,
                               etPin1, etPin2, swToolbar,
                               etWeatherCity, btnRefreshWeather, spSlideDuration,
                               btnSave };

        // Disable View focus system — we manage focus manually
        for (View v : rowViews) {
            v.setFocusable(false);
            v.setFocusableInTouchMode(false);
        }

        // ── Populate fields ──────────────────────────────────────────────────
        etUrl.setText(prefs.getUrl());

        ArrayAdapter<String> adapter = new ArrayAdapter<>(
            this, android.R.layout.simple_spinner_item, REFRESH_LABELS);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spRefresh.setAdapter(adapter);
        int cur = prefs.getRefreshSeconds();
        for (int i = 0; i < REFRESH_VALUES.length; i++) {
            if (REFRESH_VALUES[i] == cur) { spRefresh.setSelection(i); break; }
        }

        swPinEnabled.setChecked(prefs.isPinEnabled());
        layoutNewPin.setVisibility(prefs.isPinEnabled() ? View.VISIBLE : View.GONE);
        etPin1.setHint("New PIN (current: " + prefs.getPin() + ")");
        swToolbar.setChecked(prefs.isToolbarVisible());

        // Weather
        String city = prefs.getWeatherCity();
        tvWeatherCity.setText("City: " + city);
        etWeatherCity.setText(city);
        btnRefreshWeather.setOnClickListener(v -> fetchWeather());

        // Holidays
        tvHolidays.setText(buildUpcomingHolidays(5));

        // Slide duration spinner
        ArrayAdapter<String> slideAdapter = new ArrayAdapter<>(
            this, android.R.layout.simple_spinner_item, SLIDE_DURATION_LABELS);
        slideAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spSlideDuration.setAdapter(slideAdapter);
        int curDur = prefs.getDefaultSlideDuration();
        for (int i = 0; i < SLIDE_DURATION_VALUES.length; i++) {
            if (SLIDE_DURATION_VALUES[i] == curDur) { spSlideDuration.setSelection(i); break; }
        }

        etPin2.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(Editable s) {
                boolean mismatch = s.length() == 4
                    && !s.toString().equals(etPin1.getText().toString());
                tvPinMismatch.setVisibility(mismatch ? View.VISIBLE : View.GONE);
            }
        });

        // Touch listeners (touch still works alongside D-pad)
        btnSave.setOnClickListener(v -> saveAndClose());
        swPinEnabled.setOnClickListener(v -> togglePinSwitch());
        swToolbar.setOnClickListener(v -> swToolbar.toggle());

        highlightCursor();
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  D-PAD
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int kc = event.getKeyCode();

        // While keyboard is open let text flow through; only intercept navigation keys
        if (editingText) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (kc == KeyEvent.KEYCODE_BACK || kc == KeyEvent.KEYCODE_DPAD_DOWN) {
                    closeKeyboard();
                    return true;
                }
            }
            return super.dispatchKeyEvent(event);
        }

        if (event.getAction() != KeyEvent.ACTION_DOWN) return true;

        switch (kc) {
            case KeyEvent.KEYCODE_DPAD_UP:    moveCursor(-1); return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:  moveCursor(+1); return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:  handleLR(-1);   return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT: handleLR(+1);   return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:   activate();     return true;
            case KeyEvent.KEYCODE_BACK:        finish();       return true;
            default:                           return true;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Cursor movement
    // ─────────────────────────────────────────────────────────────────────────

    private void moveCursor(int delta) {
        clearHighlight(cursor);
        int next = cursor + delta;

        // Skip PIN input rows if PIN switch is off
        if (!swPinEnabled.isChecked()) {
            while (next == ROW_PIN1 || next == ROW_PIN2) next += delta;
        }

        cursor = Math.max(0, Math.min(ROW_MAX, next));
        highlightCursor();
        scrollToCursor();
    }

    private void handleLR(int delta) {
        switch (cursor) {
            case ROW_REFRESH:
                int pos = spRefresh.getSelectedItemPosition() + delta;
                spRefresh.setSelection(Math.max(0, Math.min(REFRESH_LABELS.length - 1, pos)));
                break;
            case ROW_PIN_SW: togglePinSwitch();       break;
            case ROW_TOOLBAR: swToolbar.toggle();     break;
            case ROW_SLIDE_DUR:
                int p = spSlideDuration.getSelectedItemPosition() + delta;
                spSlideDuration.setSelection(
                    Math.max(0, Math.min(SLIDE_DURATION_LABELS.length - 1, p)));
                break;
        }
    }

    private void activate() {
        switch (cursor) {
            case ROW_URL:
            case ROW_PIN1:
            case ROW_PIN2:
            case ROW_WEATHER_CITY:
                openKeyboard(rowViews[cursor]);
                break;
            case ROW_REFRESH:      spRefresh.performClick();       break;
            case ROW_PIN_SW:       togglePinSwitch();              break;
            case ROW_TOOLBAR:      swToolbar.toggle();             break;
            case ROW_WEATHER_BTN:  fetchWeather();                 break;
            case ROW_SLIDE_DUR:    spSlideDuration.performClick(); break;
            case ROW_SAVE:         saveAndClose();                 break;
        }
    }

    private void togglePinSwitch() {
        swPinEnabled.setChecked(!swPinEnabled.isChecked());
        layoutNewPin.setVisibility(swPinEnabled.isChecked() ? View.VISIBLE : View.GONE);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Scroll active row into view
    // ─────────────────────────────────────────────────────────────────────────

    private void scrollToCursor() {
        final View target = rowViews[cursor];
        scrollView.post(() -> {
            int[] loc = new int[2];
            target.getLocationOnScreen(loc);
            int[] scrollLoc = new int[2];
            scrollView.getLocationOnScreen(scrollLoc);
            int relativeTop = loc[1] - scrollLoc[1] + scrollView.getScrollY();
            int targetScroll = relativeTop - 80;
            scrollView.smoothScrollTo(0, Math.max(0, targetScroll));
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Highlight
    // ─────────────────────────────────────────────────────────────────────────

    private void highlightCursor() {
        View v = rowViews[cursor];
        v.setBackgroundResource(R.drawable.row_focused);
        v.setPadding(12, 8, 12, 8);
    }

    private void clearHighlight(int idx) {
        View v = rowViews[idx];
        // Restore the default spinner background for spinner rows
        if (idx == ROW_REFRESH || idx == ROW_SLIDE_DUR) {
            v.setBackgroundResource(R.drawable.input_bg);
        } else if (idx == ROW_URL || idx == ROW_PIN1 || idx == ROW_PIN2
                   || idx == ROW_WEATHER_CITY) {
            v.setBackgroundResource(R.drawable.input_bg);
        } else if (idx == ROW_WEATHER_BTN || idx == ROW_SAVE) {
            v.setBackgroundResource(R.drawable.btn_primary);
        } else {
            v.setBackground(null);
        }
        v.setPadding(0, 0, 0, 0);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Keyboard
    // ─────────────────────────────────────────────────────────────────────────

    private void openKeyboard(View v) {
        v.setFocusable(true);
        v.setFocusableInTouchMode(true);
        v.requestFocus();
        android.view.inputmethod.InputMethodManager imm =
            (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null)
            imm.showSoftInput(v, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
        editingText = true;
    }

    private void closeKeyboard() {
        View focused = getCurrentFocus();
        if (focused != null) {
            android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null)
                imm.hideSoftInputFromWindow(focused.getWindowToken(), 0);
            focused.setFocusable(false);
            focused.setFocusableInTouchMode(false);
            focused.clearFocus();
        }
        editingText = false;
        highlightCursor();
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Weather
    // ─────────────────────────────────────────────────────────────────────────

    private void fetchWeather() {
        final String city = etWeatherCity.getText().toString().trim().isEmpty()
            ? prefs.getWeatherCity()
            : etWeatherCity.getText().toString().trim();
        prefs.setWeatherCity(city);
        tvWeatherCity.setText("City: " + city);
        tvWeatherResult.setText("Loading…");

        weatherExec.execute(() -> {
            String url = "https://wttr.in/" + city.replace(" ", "+") + "?format=3";
            Request req = new Request.Builder().url(url).build();
            try (Response resp = http.newCall(req).execute()) {
                ResponseBody body = resp.body();
                String result = (body != null) ? body.string().trim() : "";
                final String text = result.isEmpty() ? "No data" : result;
                mainHandler.post(() -> tvWeatherResult.setText(text));
            } catch (IOException e) {
                mainHandler.post(() -> tvWeatherResult.setText("Error: " + e.getMessage()));
            }
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Holidays
    // ─────────────────────────────────────────────────────────────────────────

    private static class Holiday {
        final int month; // 1-12
        final int day;
        final String name;
        Holiday(int m, int d, String n) { month = m; day = d; name = n; }
    }

    private static final Holiday[] BG_HOLIDAYS = new Holiday[]{
        new Holiday(1,  1,  "New Year"),
        new Holiday(3,  3,  "Liberation Day"),
        new Holiday(5,  1,  "Labour Day"),
        new Holiday(5,  6,  "St. George's Day / Army Day"),
        new Holiday(5,  24, "Education and Culture Day"),
        new Holiday(9,  6,  "Unification Day"),
        new Holiday(9,  22, "Independence Day"),
        new Holiday(11, 1,  "National Revival Day"),
        new Holiday(12, 24, "Christmas Eve"),
        new Holiday(12, 25, "Christmas Day"),
        new Holiday(12, 26, "Christmas Day 2"),
    };

    private String buildUpcomingHolidays(int count) {
        Calendar today = Calendar.getInstance();
        int todayYear  = today.get(Calendar.YEAR);
        // Collect upcoming (this year from today, then next year) until count reached
        List<Calendar> upcoming = new ArrayList<>();
        List<String>   names    = new ArrayList<>();

        int[] years = { todayYear, todayYear + 1 };
        for (int y : years) {
            for (Holiday h : BG_HOLIDAYS) {
                Calendar c = Calendar.getInstance();
                c.clear();
                c.set(y, h.month - 1, h.day);
                if (!c.before(stripTime(today))) {
                    upcoming.add(c);
                    names.add(h.name);
                    if (upcoming.size() >= count) break;
                }
            }
            if (upcoming.size() >= count) break;
        }

        SimpleDateFormat fmt = new SimpleDateFormat("d MMM", Locale.ENGLISH);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < upcoming.size(); i++) {
            if (i > 0) sb.append("\n");
            sb.append(fmt.format(upcoming.get(i).getTime()))
              .append(" — ")
              .append(names.get(i));
        }
        return sb.toString();
    }

    private static Calendar stripTime(Calendar c) {
        Calendar out = (Calendar) c.clone();
        out.set(Calendar.HOUR_OF_DAY, 0);
        out.set(Calendar.MINUTE, 0);
        out.set(Calendar.SECOND, 0);
        out.set(Calendar.MILLISECOND, 0);
        return out;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Save
    // ─────────────────────────────────────────────────────────────────────────

    private void saveAndClose() {
        String url = etUrl.getText().toString().trim();
        if (url.isEmpty()) {
            Toast.makeText(this, "URL cannot be empty", Toast.LENGTH_SHORT).show();
            return;
        }
        prefs.setUrl(url);
        prefs.setRefreshSeconds(REFRESH_VALUES[spRefresh.getSelectedItemPosition()]);
        prefs.setPinEnabled(swPinEnabled.isChecked());

        if (swPinEnabled.isChecked()) {
            String p1 = etPin1.getText().toString();
            String p2 = etPin2.getText().toString();
            if (!p1.isEmpty()) {
                if (!p1.matches("\\d{4}")) {
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

        String city = etWeatherCity.getText().toString().trim();
        if (!city.isEmpty()) prefs.setWeatherCity(city);

        prefs.setDefaultSlideDuration(
            SLIDE_DURATION_VALUES[spSlideDuration.getSelectedItemPosition()]);

        Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show();
        finish();
    }

    @Override
    public boolean onSupportNavigateUp() { finish(); return true; }
}
