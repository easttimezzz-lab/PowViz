package com.aicode.powerbar;

import android.content.Context;
import android.content.SharedPreferences;

public final class Prefs {
    private static final String FILE = "powerbar";

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    static boolean enabled(Context c) {
        return sp(c).getBoolean("enabled", false);
    }

    static void setEnabled(Context c, boolean v) {
        sp(c).edit().putBoolean("enabled", v).apply();
    }

    static boolean chargingOnly(Context c) {
        return sp(c).getBoolean("charging_only", false);
    }

    static void setChargingOnly(Context c, boolean v) {
        sp(c).edit().putBoolean("charging_only", v).apply();
    }

    static int intervalMs(Context c) {
        return sp(c).getInt("interval_ms", 2000);
    }

    static void setIntervalMs(Context c, int v) {
        sp(c).edit().putInt("interval_ms", v).apply();
    }
}
