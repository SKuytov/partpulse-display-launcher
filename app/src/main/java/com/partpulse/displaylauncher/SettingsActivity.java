package com.partpulse.displaylauncher;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
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
import java.util.concurrent.TimeUnit;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Settings screen — works with BOTH a TV remote (D-pad) and touch (tablet).
 *
 * Touch: tap any field and the on-screen keyboard opens; switches, spinners and
 * buttons work directly. The Save bar is fixed at the bottom and stays visible
 * above the keyboard.
 *
 * D-pad cursor rows:
 *   0  URL field            7  Toolbar switch
 *   1  Test URL button      8  Weather city
 *   2  Fallback spinner     9  Refresh weather
 *   3  Daily restart        10 Slide duration
 *   4  PIN switch           11 Re-download now
 *   5  New PIN              12 Save
 *   6  Confirm PIN
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

    private static final String[] RESTART_LABELS =
        { "Off", "02:00", "03:00", "04:00", "05:00", "06:00" };
    private static final int[] RESTART_VALUES =
        {   -1,      2,       3,       4,       5,       6   };

    private static final int ROW_URL           = 0;
    private static final int ROW_URL_TEST      = 1;
    private static final int ROW_REFRESH       = 2;
    private static final int ROW_RESTART       = 3;
    private static final int ROW_PIN_SW        = 4;
    private static final int ROW_PIN1          = 5;
    private static final int ROW_PIN2          = 6;
    private static final int ROW_TOOLBAR       = 7;
    private static final int ROW_WEATHER_CITY  = 8;
    private static final int ROW_WEATHER_BTN   = 9;
    private static final int ROW_SLIDE_DUR     = 10;
    private static final int ROW_RELOAD        = 11;
    private static final int ROW_SAVE          = 12;
    private static final int ROW_MAX           = 12;

    private AppPrefs   prefs;
    private int        cursor      = 0;
    private boolean    editingText = false;
    /** true = show the D-pad highlight; switched off as soon as the screen is touched. */
    private boolean    dpadMode    = true;

    private ScrollView   scrollView;
    private EditText     etUrl;
    private Button       btnUrlPaste, btnUrlClear, btnUrlDefault, btnUrlTest;
    private TextView     tvUrlStatus;
    private Spinner      spRefresh;
    private Spinner      spDailyRestart;
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
    private Button       btnReloadNow;
    private TextView     tvVersion;
    private Button       btnSave, btnCancel;

    // All row anchor views — used for highlighting and scrolling to the active row
    private View[] rowViews;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService netExec = Executors.newSingleThreadExecutor();
    private final OkHttpClient http = new OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build();

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
        btnUrlPaste       = findViewById(R.id.btnUrlPaste);
        btnUrlClear       = findViewById(R.id.btnUrlClear);
        btnUrlDefault     = findViewById(R.id.btnUrlDefault);
        btnUrlTest        = findViewById(R.id.btnUrlTest);
        tvUrlStatus       = findViewById(R.id.tvUrlStatus);
        spRefresh         = findViewById(R.id.spRefresh);
        spDailyRestart    = findViewById(R.id.spDailyRestart);
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
        btnReloadNow      = findViewById(R.id.btnReloadNow);
        tvVersion         = findViewById(R.id.tvVersion);
        btnSave           = findViewById(R.id.btnSave);
        btnCancel         = findViewById(R.id.btnCancel);

        rowViews = new View[]{ etUrl, btnUrlTest, spRefresh, spDailyRestart, swPinEnabled,
                               etPin1, etPin2, swToolbar,
                               etWeatherCity, btnRefreshWeather, spSlideDuration,
                               btnReloadNow, btnSave };

        // The View focus system is not used for D-pad navigation — we manage the
        // cursor ourselves. Text fields become focusable only while being edited.
        for (View v : rowViews) {
            v.setFocusable(false);
            v.setFocusableInTouchMode(false);
        }
        for (View v : new View[]{ btnUrlPaste, btnUrlClear, btnUrlDefault, btnCancel }) {
            v.setFocusable(false);
            v.setFocusableInTouchMode(false);
        }

        // Devices with a touchscreen start in touch mode (no D-pad highlight).
        dpadMode = !getPackageManager().hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN);

        // ── Populate fields ──────────────────────────────────────────────────
        etUrl.setText(prefs.getUrl());

        setupSpinner(spRefresh, REFRESH_LABELS, indexOf(REFRESH_VALUES, prefs.getRefreshSeconds()));
        setupSpinner(spDailyRestart, RESTART_LABELS, indexOf(RESTART_VALUES, prefs.getDailyRestartHour()));
        setupSpinner(spSlideDuration, SLIDE_DURATION_LABELS,
                     indexOf(SLIDE_DURATION_VALUES, prefs.getDefaultSlideDuration()));

        swPinEnabled.setChecked(prefs.isPinEnabled());
        layoutNewPin.setVisibility(prefs.isPinEnabled() ? View.VISIBLE : View.GONE);
        // Switch toggles itself on touch; we only react to the new state.
        swPinEnabled.setOnCheckedChangeListener((b, checked) ->
            layoutNewPin.setVisibility(checked ? View.VISIBLE : View.GONE));
        etPin1.setHint("Current PIN is set");
        swToolbar.setChecked(prefs.isToolbarVisible());

        // Weather
        String city = prefs.getWeatherCity();
        tvWeatherCity.setText("City: " + city);
        etWeatherCity.setText(city);
        btnRefreshWeather.setOnClickListener(v -> fetchWeather());

        // Holidays
        tvHolidays.setText(buildUpcomingHolidays(5));

        tvVersion.setText("PartPulse Display " + BuildConfig.VERSION_NAME
            + " (build " + BuildConfig.VERSION_CODE + ")");

        etPin2.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(Editable s) {
                boolean mismatch = s.length() == 4
                    && !s.toString().equals(etPin1.getText().toString());
                tvPinMismatch.setVisibility(mismatch ? View.VISIBLE : View.GONE);
            }
        });

        // ── Text fields: tap → keyboard pops up ──────────────────────────────
        attachTextField(etUrl, ROW_URL);
        attachTextField(etPin1, ROW_PIN1);
        attachTextField(etPin2, ROW_PIN2);
        attachTextField(etWeatherCity, ROW_WEATHER_CITY);

        // ── URL tools ────────────────────────────────────────────────────────
        btnUrlPaste.setOnClickListener(v -> pasteUrl());
        btnUrlClear.setOnClickListener(v -> {
            etUrl.setText("");
            openKeyboard(etUrl);
        });
        btnUrlDefault.setOnClickListener(v -> {
            etUrl.setText(AppPrefs.DEFAULT_URL);
            etUrl.setSelection(etUrl.getText().length());
            tvUrlStatus.setText("Default URL restored — press Save to apply.");
        });
        btnUrlTest.setOnClickListener(v -> testUrl());

        // ── Buttons ──────────────────────────────────────────────────────────
        btnReloadNow.setOnClickListener(v -> {
            sendReload(true);
            Toast.makeText(this, "Re-downloading all content…", Toast.LENGTH_SHORT).show();
        });
        btnSave.setOnClickListener(v -> saveAndClose());
        btnCancel.setOnClickListener(v -> finish());

        if (dpadMode) highlightCursor();
    }

    private void setupSpinner(Spinner sp, String[] labels, int selected) {
        ArrayAdapter<String> a = new ArrayAdapter<>(
            this, android.R.layout.simple_spinner_item, labels);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp.setAdapter(a);
        if (selected >= 0) sp.setSelection(selected);
    }

    private static int indexOf(int[] arr, int value) {
        for (int i = 0; i < arr.length; i++) if (arr[i] == value) return i;
        return -1;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Touch
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN && dpadMode) {
            dpadMode = false;
            clearHighlight(cursor);
        }
        return super.dispatchTouchEvent(ev);
    }

    private void attachTextField(final EditText et, final int row) {
        et.setOnTouchListener((v, ev) -> {
            if (ev.getActionMasked() == MotionEvent.ACTION_DOWN && !v.isFocused()) {
                cursor = row;
                // Make it focusable before the EditText handles the touch, so the
                // tap places the caret and the system keyboard opens.
                openKeyboard(v);
            }
            return false;
        });
        et.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE
                    || actionId == EditorInfo.IME_ACTION_GO
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                        && event.getAction() == KeyEvent.ACTION_DOWN)) {
                closeKeyboard();
                if (et == etUrl) testUrl();   // handy: pressing Done checks the URL
                return true;
            }
            if (actionId == EditorInfo.IME_ACTION_NEXT && et == etPin1) {
                cursor = ROW_PIN2;
                openKeyboard(etPin2);
                return true;
            }
            return false;
        });
    }

    private void pasteUrl() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null && cm.hasPrimaryClip()) {
            ClipData clip = cm.getPrimaryClip();
            if (clip != null && clip.getItemCount() > 0) {
                CharSequence t = clip.getItemAt(0).coerceToText(this);
                if (t != null && t.toString().trim().length() > 0) {
                    etUrl.setText(t.toString().trim());
                    etUrl.setSelection(etUrl.getText().length());
                    tvUrlStatus.setText("Pasted — press Test URL or Save.");
                    return;
                }
            }
        }
        Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show();
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  D-PAD
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int kc = event.getKeyCode();

        // While a text field is being edited let keys flow to it (soft or hardware
        // keyboard); only BACK and D-pad DOWN leave the field.
        View focused = getCurrentFocus();
        if (editingText || focused instanceof EditText) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (kc == KeyEvent.KEYCODE_BACK || kc == KeyEvent.KEYCODE_DPAD_DOWN) {
                    closeKeyboard();
                    if (kc == KeyEvent.KEYCODE_DPAD_DOWN) moveCursor(+1);
                    return true;
                }
            }
            return super.dispatchKeyEvent(event);
        }

        if (event.getAction() != KeyEvent.ACTION_DOWN) return true;

        boolean isNavKey = kc == KeyEvent.KEYCODE_DPAD_UP || kc == KeyEvent.KEYCODE_DPAD_DOWN
            || kc == KeyEvent.KEYCODE_DPAD_LEFT || kc == KeyEvent.KEYCODE_DPAD_RIGHT
            || kc == KeyEvent.KEYCODE_DPAD_CENTER || kc == KeyEvent.KEYCODE_ENTER
            || kc == KeyEvent.KEYCODE_BUTTON_A;
        if (isNavKey && !dpadMode) {
            // First remote key after touching: just show where the cursor is.
            dpadMode = true;
            highlightCursor();
            scrollToCursor();
            return true;
        }

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
            case ROW_REFRESH:   stepSpinner(spRefresh, REFRESH_LABELS.length, delta);        break;
            case ROW_RESTART:   stepSpinner(spDailyRestart, RESTART_LABELS.length, delta);   break;
            case ROW_SLIDE_DUR: stepSpinner(spSlideDuration, SLIDE_DURATION_LABELS.length, delta); break;
            case ROW_PIN_SW:    swPinEnabled.toggle(); break;
            case ROW_TOOLBAR:   swToolbar.toggle();    break;
        }
    }

    private void stepSpinner(Spinner sp, int count, int delta) {
        int pos = sp.getSelectedItemPosition() + delta;
        sp.setSelection(Math.max(0, Math.min(count - 1, pos)));
    }

    private void activate() {
        switch (cursor) {
            case ROW_URL:
            case ROW_PIN1:
            case ROW_PIN2:
            case ROW_WEATHER_CITY:
                openKeyboard(rowViews[cursor]);
                break;
            case ROW_URL_TEST:     testUrl();                      break;
            case ROW_REFRESH:      spRefresh.performClick();       break;
            case ROW_RESTART:      spDailyRestart.performClick();  break;
            case ROW_PIN_SW:       swPinEnabled.toggle();          break;
            case ROW_TOOLBAR:      swToolbar.toggle();             break;
            case ROW_WEATHER_BTN:  fetchWeather();                 break;
            case ROW_SLIDE_DUR:    spSlideDuration.performClick(); break;
            case ROW_RELOAD:       btnReloadNow.performClick();    break;
            case ROW_SAVE:         saveAndClose();                 break;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Scroll active row into view
    // ─────────────────────────────────────────────────────────────────────────

    private void scrollToCursor() {
        final View target = rowViews[cursor];
        if (target == btnSave) return; // lives in the fixed bottom bar
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
    //  Highlight (D-pad mode only)
    // ─────────────────────────────────────────────────────────────────────────

    private void highlightCursor() {
        if (!dpadMode) return;
        View v = rowViews[cursor];
        v.setBackgroundResource(R.drawable.row_focused);
        if (!(v instanceof EditText)) v.setPadding(12, 8, 12, 8);
    }

    private void clearHighlight(int idx) {
        View v = rowViews[idx];
        if (v instanceof EditText) {
            v.setBackgroundResource(R.drawable.input_bg);
            return; // keep the EditText's own padding
        }
        if (v instanceof Spinner) {
            v.setBackgroundResource(R.drawable.input_bg);
        } else if (v instanceof Button) {
            v.setBackgroundResource(R.drawable.btn_primary);
        } else {
            v.setBackground(null);
        }
        v.setPadding(0, 0, 0, 0);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Keyboard
    // ─────────────────────────────────────────────────────────────────────────

    private void openKeyboard(final View v) {
        v.setFocusable(true);
        v.setFocusableInTouchMode(true);
        v.requestFocus();
        if (v instanceof EditText) {
            EditText et = (EditText) v;
            et.setSelection(et.getText().length());
        }
        editingText = true;
        final InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm == null) return;
        // Ask immediately and once more after layout — some TV/tablet keyboards
        // ignore the first request if the view only just became focusable.
        imm.showSoftInput(v, InputMethodManager.SHOW_IMPLICIT);
        v.postDelayed(() -> {
            if (v.isFocused()) imm.showSoftInput(v, InputMethodManager.SHOW_IMPLICIT);
        }, 150);
        if (dpadMode) highlightCursor();
    }

    private void closeKeyboard() {
        View focused = getCurrentFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (focused != null) {
            if (imm != null) imm.hideSoftInputFromWindow(focused.getWindowToken(), 0);
            focused.clearFocus();
            focused.setFocusable(false);
            focused.setFocusableInTouchMode(false);
        } else if (imm != null) {
            imm.hideSoftInputFromWindow(getWindow().getDecorView().getWindowToken(), 0);
        }
        editingText = false;
        if (dpadMode) highlightCursor();
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  URL helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** Adds a scheme if missing (http:// for IPs/localhost, https:// otherwise). */
    private static String normalizeUrl(String raw) {
        String u = raw == null ? "" : raw.trim().replace(" ", "");
        if (u.isEmpty()) return u;
        if (!u.matches("(?i)^https?://.*")) {
            String host = u.split("[/:]")[0];
            boolean local = host.matches("^\\d{1,3}(\\.\\d{1,3}){3}$")
                || host.equalsIgnoreCase("localhost") || host.endsWith(".local");
            u = (local ? "http://" : "https://") + u;
        }
        return u;
    }

    private void testUrl() {
        final String url = normalizeUrl(etUrl.getText().toString());
        if (url.isEmpty() || HttpUrl.parse(url) == null) {
            tvUrlStatus.setTextColor(0xFFFF6B6B);
            tvUrlStatus.setText("Not a valid URL.");
            return;
        }
        if (!url.equals(etUrl.getText().toString())) {
            etUrl.setText(url);
            etUrl.setSelection(url.length());
        }
        tvUrlStatus.setTextColor(0xFF9CA3C8);
        tvUrlStatus.setText("Testing " + url + " …");
        netExec.execute(() -> {
            String msg;
            int color;
            Request req = new Request.Builder().url(url).header("Cache-Control", "no-cache").build();
            try (Response resp = http.newCall(req).execute()) {
                ResponseBody body = resp.body();
                String html = body != null ? body.string() : "";
                if (!resp.isSuccessful()) {
                    msg = "Server answered HTTP " + resp.code() + ".";
                    color = 0xFFFF6B6B;
                } else {
                    int n = SlideManager.countSlides(html);
                    if (n >= 0) {
                        msg = "OK — " + n + " slide(s) found.";
                        color = 0xFF4ADE80;
                    } else {
                        msg = "Page loads (HTTP " + resp.code() + ") but no slide list was found. "
                            + "Is this the index.php display page?";
                        color = 0xFFFBBF24;
                    }
                }
            } catch (IOException e) {
                msg = "Cannot connect: " + e.getMessage();
                color = 0xFFFF6B6B;
            }
            final String m = msg;
            final int c = color;
            mainHandler.post(() -> {
                tvUrlStatus.setTextColor(c);
                tvUrlStatus.setText(m);
            });
        });
    }

    private void sendReload(boolean force) {
        Intent i = new Intent(NativeDisplayActivity.ACTION_SLIDES_UPDATED);
        i.setPackage(getPackageName());
        i.putExtra(NativeDisplayActivity.EXTRA_FORCE, force);
        sendBroadcast(i);
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

        netExec.execute(() -> {
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
        String url = normalizeUrl(etUrl.getText().toString());
        if (url.isEmpty() || HttpUrl.parse(url) == null) {
            Toast.makeText(this, "Please enter a valid URL", Toast.LENGTH_SHORT).show();
            tvUrlStatus.setTextColor(0xFFFF6B6B);
            tvUrlStatus.setText("Not a valid URL.");
            scrollView.smoothScrollTo(0, 0);
            return;
        }

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

        boolean urlChanged = !url.equals(prefs.getUrl());
        prefs.setUrl(url);
        if (urlChanged) prefs.setLastHash("");
        prefs.setRefreshSeconds(REFRESH_VALUES[Math.max(0, spRefresh.getSelectedItemPosition())]);
        prefs.setDailyRestartHour(RESTART_VALUES[Math.max(0, spDailyRestart.getSelectedItemPosition())]);
        prefs.setPinEnabled(swPinEnabled.isChecked());
        prefs.setToolbarVisible(swToolbar.isChecked());

        String city = etWeatherCity.getText().toString().trim();
        if (!city.isEmpty()) prefs.setWeatherCity(city);

        prefs.setDefaultSlideDuration(
            SLIDE_DURATION_VALUES[Math.max(0, spSlideDuration.getSelectedItemPosition())]);

        closeKeyboard();
        // New URL → download everything from the new server; otherwise a normal check.
        sendReload(urlChanged);
        Toast.makeText(this, urlChanged ? "Saved — loading new URL…" : "Settings saved",
                       Toast.LENGTH_SHORT).show();
        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        netExec.shutdownNow();
    }

    @Override
    public boolean onSupportNavigateUp() { finish(); return true; }
}
