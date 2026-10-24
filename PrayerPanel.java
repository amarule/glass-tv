package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Glass TV: native prayer-time panel for Kulai, Johor (JAKIM JHR02).
 * Reads JAKIM e-Solat on a worker thread, caches ONLY the local day's times,
 * and periodically refreshes when Home is on screen. No location permission.
 */
public final class PrayerPanel extends LinearLayout {
    private static final String ZONE = "JHR02";
    private static final String JAKIM_URL =
            "https://www.e-solat.gov.my/index.php?r=esolatApi/takwimsolat&period=today&zone=JHR02";
    // Backup data provider states it mirrors JAKIM e-Solat.
    private static final String BACKUP_URL = "https://solat.my/api/daily/JHR02";
    private static final long REFRESH_MS = 6L * 60L * 60L * 1000L;
    private static final long RETRY_MS = 15L * 60L * 1000L;
    private static final TimeZone MY_TZ = TimeZone.getTimeZone("Asia/Kuala_Lumpur");
    private static final String[] LABELS = {"Subuh", "Syuruk", "Zohor", "Asar", "Maghrib", "Isyak"};
    private static final String[] KEYS = {"fajr", "syuruk", "dhuhr", "asr", "maghrib", "isha"};
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final TextView[] values = new TextView[6];
    private final TextView info;
    private final SharedPreferences cache;
    private String loadedDate = "";
    private String[] todayTimes;
    private boolean fetching;
    private boolean active;
    private long nextTryAt;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!active) return;
            String date = malaysiaDate();
            if (!date.equals(loadedDate)) {
                loadedDate = date;
                loadCache(date);
            }
            drawTimes();
            if (!fetching && System.currentTimeMillis() >= nextTryAt) fetch(date);
            ui.postDelayed(this, 60_000L);
        }
    };

    public PrayerPanel(Context context) { this(context, null); }
    public PrayerPanel(Context context, AttributeSet attrs) {
        super(context, attrs);
        cache = context.getSharedPreferences("kulai_prayer_cache", Context.MODE_PRIVATE);
        setOrientation(VERTICAL);
        setGravity(Gravity.END);
        setFocusable(false);
        setClickable(false);
        setPadding(dp(16), dp(12), dp(16), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x55000000);
        bg.setCornerRadius(dp(16));
        setBackground(bg);

        TextView heading = text("WAKTU SOLAT  ·  KULAI, JOHOR", 16, Color.WHITE, true);
        heading.setGravity(Gravity.RIGHT);
        addView(heading, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        info = text("JAKIM e-Solat  ·  JHR02", 11, 0xCCFFFFFF, false);
        info.setGravity(Gravity.RIGHT);
        LayoutParams meta = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        meta.topMargin = dp(3);
        meta.bottomMargin = dp(15);
        addView(info, meta);

        LinearLayout times = new LinearLayout(context);
        times.setOrientation(HORIZONTAL);
        times.setGravity(Gravity.CENTER_VERTICAL);
        addView(times, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        for (int i = 0; i < LABELS.length; i++) {
            LinearLayout group = new LinearLayout(context);
            group.setOrientation(VERTICAL);
            group.setGravity(Gravity.CENTER);
            TextView label = text(LABELS[i], 12, 0xDDFFFFFF, false);
            label.setGravity(Gravity.CENTER);
            group.addView(label, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
            TextView value = text("--:--", 19, Color.WHITE, true);
            value.setGravity(Gravity.CENTER);
            LayoutParams vlp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
            vlp.topMargin = dp(5);
            group.addView(value, vlp);
            values[i] = value;
            times.addView(group, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        }
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        active = true;
        ui.removeCallbacks(tick);
        ui.post(tick);
    }
    @Override protected void onDetachedFromWindow() {
        active = false;
        ui.removeCallbacks(tick);
        super.onDetachedFromWindow();
    }

    private TextView text(String str, int sp, int color, boolean bold) {
        TextView v = new TextView(getContext());
        v.setText(str);
        v.setTextColor(color);
        v.setTextSize(sp);
        v.setIncludeFontPadding(false);
        v.setSingleLine(true);
        if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        v.setShadowLayer(dp(1.5f), 0f, dp(1f), 0x99000000);
        return v;
    }
    private int dp(float n) { return Math.round(n * getResources().getDisplayMetrics().density); }

    private static String malaysiaDate() {
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        fmt.setTimeZone(MY_TZ);
        return fmt.format(new Date());
    }

    private void loadCache(String date) {
        todayTimes = null;
        if (!date.equals(cache.getString("date", ""))) {
            nextTryAt = 0L;
            info.setText("JAKIM e-Solat · JHR02 · Memuatkan...");
            return;
        }
        try {
            JSONArray array = new JSONArray(cache.getString("times", "[]"));
            if (array.length() != 6) return;
            String[] arr = new String[6];
            for (int i = 0; i < 6; i++) arr[i] = validTime(array.getString(i));
            todayTimes = arr;
            info.setText(cache.getString("source", "e-Solat JAKIM") + " · JHR02");
            long last = cache.getLong("fetchedAt", 0);
            nextTryAt = Math.max(0, last + REFRESH_MS);
        } catch (Exception ignored) { nextTryAt = 0; }
    }

    private void drawTimes() {
        int next = -1;
        if (todayTimes != null) {
            SimpleDateFormat now = new SimpleDateFormat("HH:mm", Locale.US);
            now.setTimeZone(MY_TZ);
            String current = now.format(new Date());
            for (int i : new int[] {0, 2, 3, 4, 5}) {
                if (todayTimes[i].compareTo(current) > 0) { next = i; break; }
            }
        }
        for (int i = 0; i < 6; i++) {
            values[i].setText(todayTimes == null ? "--:--" : todayTimes[i]);
            values[i].setTextColor(i == next ? 0xFF67E8A2 : Color.WHITE);
        }
    }

    private void fetch(String date) {
        fetching = true;
        nextTryAt = System.currentTimeMillis() + RETRY_MS;
        final String targetDate = date;
        new Thread(() -> {
            String[] fetched = null;
            String source = "e-Solat JAKIM";
            try { fetched = parseResponse(get(JAKIM_URL), targetDate); }
            catch (Exception ignored) { }
            if (fetched == null) {
                try {
                    fetched = parseResponse(get(BACKUP_URL), targetDate);
                    source = "Data JAKIM via Solat.my";
                } catch (Exception ignored) { }
            }
            final String[] result = fetched;
            final String provider = source;
            ui.post(() -> {
                fetching = false;
                if (!targetDate.equals(malaysiaDate())) { nextTryAt = 0; return; }
                if (result != null) {
                    todayTimes = result;
                    loadedDate = targetDate;
                    JSONArray saved = new JSONArray();
                    for (String s : result) saved.put(s);
                    long now = System.currentTimeMillis();
                    cache.edit().putString("date", targetDate)
                            .putString("times", saved.toString())
                            .putString("source", provider)
                            .putLong("fetchedAt", now).apply();
                    nextTryAt = now + REFRESH_MS;
                    info.setText(provider + " · JHR02");
                } else {
                    nextTryAt = System.currentTimeMillis() + RETRY_MS;
                    if (todayTimes == null) info.setText("JAKIM JHR02 · Tiada data, cuba semula");
                    else info.setText("JAKIM JHR02 · Data tersimpan hari ini");
                }
                drawTimes();
            });
        }, "KulaiPrayerFetch").start();
    }

    private static String get(String address) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
        c.setRequestMethod("GET");
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("User-Agent", "GlassTV-Kulai/1.0");
        try {
            if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream();
                 BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"))) {
                StringBuilder s = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) {
                    s.append(line);
                    if (s.length() > 262144) throw new Exception("Payload too large");
                }
                return s.toString();
            }
        } finally { c.disconnect(); }
    }

    private static String[] parseResponse(String json, String date) throws Exception {
        JSONObject root = new JSONObject(json);
        String zone = root.optString("zone", ZONE);
        if (!ZONE.equalsIgnoreCase(zone)) throw new Exception("Wrong zone");
        JSONObject day = null;
        JSONArray a = root.optJSONArray("prayerTime");
        if (a == null && root.optJSONObject("data") != null) {
            a = root.getJSONObject("data").optJSONArray("prayerTime");
        }
        if (a != null && a.length() > 0) {
            day = a.getJSONObject(0);
        } else if (root.has("fajr")) {
            day = root;
        } else if (root.optJSONObject("data") != null && root.getJSONObject("data").has("fajr")) {
            day = root.getJSONObject("data");
        }
        if (day == null) throw new Exception("No times");
        String remoteDate = day.optString("date", "");
        if (!matchesDate(remoteDate, date)) throw new Exception("Wrong day: " + remoteDate);
        String[] out = new String[6];
        for (int i = 0; i < KEYS.length; i++) out[i] = validTime(day.getString(KEYS[i]));
        return out;
    }

    private static boolean matchesDate(String value, String target) {
        String[] formats = {"yyyy-MM-dd", "dd-MMM-yyyy", "dd-MM-yyyy", "dd/MM/yyyy"};
        for (String pattern : formats) {
            try {
                SimpleDateFormat parser = new SimpleDateFormat(pattern, Locale.ENGLISH);
                parser.setLenient(false);
                parser.setTimeZone(MY_TZ);
                Date parsed = parser.parse(value);
                SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
                iso.setTimeZone(MY_TZ);
                if (parsed != null && target.equals(iso.format(parsed))) return true;
            } catch (ParseException ignored) { }
        }
        return false;
    }
    private static String validTime(String input) throws Exception {
        if (!input.matches("(?:[01]\\d|2[0-3]):[0-5]\\d(?::[0-5]\\d)?"))
            throw new Exception("Invalid time: " + input);
        return input.substring(0, 5);
    }
}
