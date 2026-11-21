package com.aicode.powerbar;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.util.ArrayList;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class XposedInit implements IXposedHookLoadPackage {

    private static final String TAG = "PowerBar";

    private static final String SYSTEMUI = "com.android.systemui";
    private static final String BATTERY_VIEW = "com.flyme.statusbar.battery.FlymeBatteryMeterView";

    private static final String ACTION_CONFIG = "com.aicode.powerbar.CONFIG";
    private static final Uri CONFIG_URI = Uri.parse("content://com.aicode.powerbar.config");

    private static final String POWER_TAG = "aicode_power_text";

    private static final ArrayList<WeakReference<TextView>> views = new ArrayList<>();
    private static volatile Handler main;

    private static volatile BatteryManager battery;
    private static Context appContext;

    private static volatile int voltageMv = -1;
    private static volatile int batteryStatus = -1;
    private static volatile boolean enabled;
    private static volatile boolean chargingOnly;
    private static volatile boolean configLoaded;
    private static volatile long intervalMs = 2000;
    private static final Object tickLock = new Object();

    private static boolean receiversRegistered;
    private static boolean workerStarted;
    private static int clockId = -1;
    private static int batteryPercentId = -1;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!SYSTEMUI.equals(lpparam.packageName) || !SYSTEMUI.equals(lpparam.processName)) {
            return;
        }
        try {
            Class<?> batteryView = XposedHelpers.findClass(BATTERY_VIEW, lpparam.classLoader);
            XposedBridge.hookAllConstructors(batteryView, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.thisObject instanceof View) {
                        watch((View) param.thisObject);
                    }
                }
            });
            XposedBridge.log(TAG + ": hooked " + BATTERY_VIEW);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook failed: " + t);
        }
        try {
            XposedBridge.hookAllMethods(TextView.class, "setTextColor", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!(param.thisObject instanceof TextView)) {
                        return;
                    }
                    TextView view = (TextView) param.thisObject;
                    int id = view.getId();
                    if (id != View.NO_ID && (id == batteryPercentId || id == clockId)) {
                        applyColor(view.getCurrentTextColor());
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": color hook failed: " + t);
        }
    }

    private static void applyColor(int color) {
        synchronized (views) {
            for (int i = views.size() - 1; i >= 0; i--) {
                TextView v = views.get(i).get();
                if (v == null || !v.isAttachedToWindow()) {
                    continue;
                }
                if (v.getCurrentTextColor() != color) {
                    v.setTextColor(color);
                }
            }
        }
    }

    private static void watch(final View batteryView) {
        batteryView.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
                v.post(() -> insert(v));
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
            }
        });
    }

    private static void insert(View batteryView) {
        ViewGroup parent = batteryView.getParent() instanceof ViewGroup
                ? (ViewGroup) batteryView.getParent() : null;
        if (parent == null || !"system_icons".equals(entryName(parent))) {
            return;
        }
        if (parent.findViewWithTag(POWER_TAG) != null) {
            return;
        }
        if (!(parent instanceof LinearLayout)) {
            return;
        }
        Context context = batteryView.getContext();
        int index = parent.indexOfChild(batteryView);
        int marginStart = 0;
        int marginEnd = 0;
        ViewGroup.LayoutParams oldParams = batteryView.getLayoutParams();
        if (oldParams instanceof ViewGroup.MarginLayoutParams) {
            marginStart = ((ViewGroup.MarginLayoutParams) oldParams).getMarginStart();
            marginEnd = ((ViewGroup.MarginLayoutParams) oldParams).getMarginEnd();
        }
        TextView percent = null;
        if (batteryPercentId == -1) {
            batteryPercentId = context.getResources().getIdentifier("battery_percent", "id", SYSTEMUI);
        }
        if (clockId == -1) {
            clockId = context.getResources().getIdentifier("clock", "id", SYSTEMUI);
        }
        if (batteryPercentId != 0) {
            View found = parent.findViewById(batteryPercentId);
            if (found instanceof TextView && found != batteryView && found.getParent() == parent) {
                percent = (TextView) found;
            }
        }

        TextView power = createView(context);
        try {
            parent.removeView(batteryView);
            if (percent != null) {
                parent.removeView(percent);
            }
            FrameLayout slot = new FrameLayout(context);
            LinearLayout.LayoutParams slotParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT);
            slotParams.setMarginStart(marginStart);
            slotParams.setMarginEnd(marginEnd);

            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.addView(batteryView, new LinearLayout.LayoutParams(
                    oldParams.width, oldParams.height));
            if (percent != null) {
                LinearLayout.LayoutParams percentParams = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                percentParams.setMarginStart(dp(context, 1));
                row.addView(percent, percentParams);
            }
            slot.addView(row, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER));

            FrameLayout.LayoutParams powerParams = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            powerParams.topMargin = dp(context, 2);
            slot.addView(power, powerParams);
            parent.addView(slot, index, slotParams);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": insert failed: " + t);
            return;
        }
        synchronized (views) {
            views.add(new WeakReference<>(power));
        }
        ensureRunning(context);
        syncAppearance(power);
    }

    private static TextView createView(Context context) {
        TextView v = new TextView(context);
        v.setTag(POWER_TAG);
        v.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.TOP);
        v.setSingleLine(true);
        v.setIncludeFontPadding(false);
        v.setFocusable(false);
        v.setClickable(false);
        v.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 9);
        v.setTextColor(Color.WHITE);
        v.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        v.setFontFeatureSettings("tnum");
        v.setText("");
        v.setVisibility(View.GONE);
        return v;
    }

    private static void ensureRunning(Context context) {
        if (!receiversRegistered) {
            receiversRegistered = true;
            appContext = context.getApplicationContext();
            battery = (BatteryManager) appContext.getSystemService(Context.BATTERY_SERVICE);
            try {
                Intent sticky;
                if (Build.VERSION.SDK_INT >= 33) {
                    sticky = appContext.registerReceiver(batteryReceiver,
                            new IntentFilter(Intent.ACTION_BATTERY_CHANGED), Context.RECEIVER_EXPORTED);
                    appContext.registerReceiver(configReceiver,
                            new IntentFilter(ACTION_CONFIG), Context.RECEIVER_EXPORTED);
                } else {
                    sticky = appContext.registerReceiver(batteryReceiver,
                            new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                    appContext.registerReceiver(configReceiver, new IntentFilter(ACTION_CONFIG));
                }
                if (sticky != null) {
                    readBattery(sticky);
                }
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": register receiver failed: " + t);
            }
        }
        if (!workerStarted) {
            workerStarted = true;
            Thread worker = new Thread(XposedInit::loop, "powerbar-meter");
            worker.setDaemon(true);
            worker.start();
        }
    }

    private static final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            readBattery(intent);
        }
    };

    private static final BroadcastReceiver configReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            enabled = intent.getBooleanExtra("enabled", enabled);
            chargingOnly = intent.getBooleanExtra("charging_only", chargingOnly);
            int interval = intent.getIntExtra("interval_ms", (int) intervalMs);
            if (interval >= 500 && interval <= 15000) {
                intervalMs = interval;
            }
            configLoaded = true;
            synchronized (tickLock) {
                tickLock.notifyAll();
            }
        }
    };

    private static void readBattery(Intent intent) {
        int voltage = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1);
        if (voltage > 0) {
            voltageMv = voltage;
        }
        int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        if (status >= 0) {
            batteryStatus = status;
        }
    }

    private static void loop() {
        long lastConfigTry = 0;
        while (true) {
            try {
                long now = SystemClock.elapsedRealtime();
                if (!configLoaded && now - lastConfigTry > 5000) {
                    lastConfigTry = now;
                    loadConfig();
                }
                int current = battery == null ? Integer.MIN_VALUE
                        : battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
                String text = text(current);
                handler().post(() -> apply(text));
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": loop failed: " + t);
            }
            try {
                synchronized (tickLock) {
                    tickLock.wait(Math.max(500, Math.min(15000, intervalMs)));
                }
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private static void loadConfig() {
        Context context = appContext;
        if (context == null) {
            return;
        }
        try (Cursor cursor = context.getContentResolver()
                .query(CONFIG_URI, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                enabled = cursor.getInt(0) == 1;
                chargingOnly = cursor.getInt(1) == 1;
                int interval = cursor.getInt(2);
                if (interval >= 500 && interval <= 15000) {
                    intervalMs = interval;
                }
                configLoaded = true;
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": load config failed: " + t);
        }
    }

    private static String text(int current) {
        if (!enabled || voltageMv <= 0 || current == Integer.MIN_VALUE) {
            return "";
        }
        boolean charging = batteryStatus == BatteryManager.BATTERY_STATUS_CHARGING
                || batteryStatus == BatteryManager.BATTERY_STATUS_FULL;
        if (chargingOnly && !charging) {
            return "";
        }
        double watts = Math.abs(voltageMv / 1000d * current / 1_000_000d);
        return PowerFormat.fromWatts(watts);
    }

    private static void apply(String text) {
        boolean show = !text.isEmpty();
        synchronized (views) {
            for (int i = views.size() - 1; i >= 0; i--) {
                TextView v = views.get(i).get();
                if (v == null || !v.isAttachedToWindow()) {
                    views.remove(i);
                    continue;
                }
                int visibility = show ? View.VISIBLE : View.GONE;
                if (v.getVisibility() != visibility) {
                    v.setVisibility(visibility);
                }
                if (show) {
                    if (!text.equals(v.getText().toString())) {
                        v.setText(text);
                    }
                    syncAppearance(v);
                }
            }
        }
    }

    private static void syncAppearance(TextView power) {
        View root = power.getRootView();
        Context context = power.getContext();
        TextView reference = null;
        if (batteryPercentId == -1) {
            batteryPercentId = context.getResources().getIdentifier("battery_percent", "id", SYSTEMUI);
        }
        if (batteryPercentId != 0) {
            View found = root.findViewById(batteryPercentId);
            if (found instanceof TextView && found != power) {
                reference = (TextView) found;
            }
        }
        if (reference == null) {
            if (clockId == -1) {
                clockId = context.getResources().getIdentifier("clock", "id", SYSTEMUI);
            }
            if (clockId != 0) {
                View found = root.findViewById(clockId);
                if (found instanceof TextView && found != power) {
                    reference = (TextView) found;
                }
            }
        }
        if (reference == null) {
            return;
        }
        int color = reference.getCurrentTextColor();
        if (power.getCurrentTextColor() != color) {
            power.setTextColor(color);
        }
        Typeface face = reference.getTypeface();
        if (power.getTypeface() != face) {
            power.setTypeface(face);
        }
    }

    private static String entryName(View view) {
        int id = view.getId();
        if (id == View.NO_ID) {
            return null;
        }
        try {
            return view.getResources().getResourceEntryName(id);
        } catch (Throwable t) {
            return null;
        }
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static Handler handler() {
        Handler h = main;
        if (h == null) {
            h = new Handler(Looper.getMainLooper());
            main = h;
        }
        return h;
    }
}
