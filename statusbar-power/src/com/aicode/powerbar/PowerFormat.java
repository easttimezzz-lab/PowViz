package com.aicode.powerbar;

import java.util.Locale;

final class PowerFormat {
    private PowerFormat() {
    }

    static String fromWatts(double watts) {
        if (Double.isNaN(watts) || watts < 0 || watts >= 1000) {
            return "";
        }
        return watts >= 10
                ? String.format(Locale.US, "%.1fW", watts)
                : String.format(Locale.US, "%.2fW", watts);
    }
}
