package com.partpulse.displaylauncher;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Centralised SharedPreferences wrapper.
 * All settings are stored in a single file "pp_launcher_prefs".
 */
public class AppPrefs {

    private static final String PREFS_NAME      = "pp_launcher_prefs";
    private static final String KEY_URL          = "display_url";
    private static final String KEY_REFRESH      = "refresh_seconds"; // 0 = off
    private static final String KEY_PIN          = "pin_code";
    private static final String KEY_PIN_ENABLED  = "pin_enabled";
    private static final String KEY_TOOLBAR      = "toolbar_visible";
    private static final String KEY_LAST_HASH    = "last_state_hash";

    // Defaults
    public static final String  DEFAULT_URL      = "https://skuytov.eu/grafik/index.php";
    public static final int     DEFAULT_REFRESH  = 60;   // seconds
    public static final String  DEFAULT_PIN      = "1234";
    public static final boolean DEFAULT_PIN_EN   = true;
    public static final boolean DEFAULT_TOOLBAR  = true;

    private final SharedPreferences prefs;

    public AppPrefs(Context context) {
        prefs = context.getApplicationContext()
                       .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    // --- URL ---
    public String getUrl() {
        return prefs.getString(KEY_URL, DEFAULT_URL);
    }
    public void setUrl(String url) {
        prefs.edit().putString(KEY_URL, url.trim()).apply();
    }

    // --- Refresh interval (seconds, 0 = disabled) ---
    public int getRefreshSeconds() {
        return prefs.getInt(KEY_REFRESH, DEFAULT_REFRESH);
    }
    public void setRefreshSeconds(int seconds) {
        prefs.edit().putInt(KEY_REFRESH, seconds).apply();
    }

    // --- PIN ---
    public String getPin() {
        return prefs.getString(KEY_PIN, DEFAULT_PIN);
    }
    public void setPin(String pin) {
        prefs.edit().putString(KEY_PIN, pin).apply();
    }
    public boolean isPinEnabled() {
        return prefs.getBoolean(KEY_PIN_ENABLED, DEFAULT_PIN_EN);
    }
    public void setPinEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_PIN_ENABLED, enabled).apply();
    }

    // --- Toolbar visibility ---
    public boolean isToolbarVisible() {
        return prefs.getBoolean(KEY_TOOLBAR, DEFAULT_TOOLBAR);
    }
    public void setToolbarVisible(boolean visible) {
        prefs.edit().putBoolean(KEY_TOOLBAR, visible).apply();
    }

    // --- Last known SSE state hash ---
    public String getLastHash() {
        return prefs.getString(KEY_LAST_HASH, "");
    }
    public void setLastHash(String hash) {
        prefs.edit().putString(KEY_LAST_HASH, hash == null ? "" : hash).apply();
    }

    // --- Weather city ---
    public String getWeatherCity() { return prefs.getString("weather_city", "Ruse"); }
    public void setWeatherCity(String city) { prefs.edit().putString("weather_city", city).apply(); }

    // --- Default slide duration (seconds) ---
    public int getDefaultSlideDuration() { return prefs.getInt("default_slide_duration", 10); }
    public void setDefaultSlideDuration(int secs) { prefs.edit().putInt("default_slide_duration", secs).apply(); }

    // --- Daily self-restart hour (0-23, -1 = off). Default 04:00 ---
    public static final int DEFAULT_RESTART_HOUR = 4;
    public int getDailyRestartHour() { return prefs.getInt("daily_restart_hour", DEFAULT_RESTART_HOUR); }
    public void setDailyRestartHour(int hour) { prefs.edit().putInt("daily_restart_hour", hour).apply(); }

    // --- Day key (yyyyMMdd) of the last daily self-restart ---
    public String getLastRestartDay() { return prefs.getString("last_restart_day", ""); }
    public void setLastRestartDay(String day) { prefs.edit().putString("last_restart_day", day).commit(); }
}
