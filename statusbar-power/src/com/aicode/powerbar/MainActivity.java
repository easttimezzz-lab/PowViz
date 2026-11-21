package com.aicode.powerbar;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import java.util.Locale;

public class MainActivity extends Activity {

    private static final String ACTION_CONFIG = "com.aicode.powerbar.CONFIG";

    private TextView statusText;
    private Switch enableSwitch;
    private CheckBox chargingOnly;
    private SeekBar intervalBar;
    private TextView intervalValue;
    private boolean syncing;
    private boolean resumed;
    private boolean receiverRegistered;

    private BatteryManager battery;
    private int voltageMv = -1;
    private int batteryStatus = -1;
    private int capacity = -1;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            updateStatus();
            handler.postDelayed(this, Prefs.intervalMs(MainActivity.this));
        }
    };

    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            voltageMv = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1);
            batteryStatus = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            capacity = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        battery = (BatteryManager) getSystemService(BATTERY_SERVICE);
        buildUi();
        syncUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        register(Intent.ACTION_BATTERY_CHANGED, batteryReceiver);
        receiverRegistered = true;
        resumed = true;
        sendConfig();
        syncUi();
        handler.post(poll);
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        handler.removeCallbacks(poll);
        if (receiverRegistered) {
            unregisterReceiver(batteryReceiver);
            receiverRegistered = false;
        }
    }

    private void register(String action, BroadcastReceiver receiver) {
        IntentFilter filter = new IntentFilter(action);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(receiver, filter);
        }
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFFFFFFFF);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(this);
        title.setText("PowViz");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        title.setTextColor(0xFF111111);
        title.setTypeface(null, Typeface.BOLD);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("状态栏功率");
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        subtitle.setTextColor(0xFF999999);
        subtitle.setPadding(0, dp(4), 0, dp(18));
        root.addView(subtitle);

        statusText = new TextView(this);
        statusText.setText("—");
        statusText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        statusText.setTypeface(Typeface.MONOSPACE);
        statusText.setTextColor(0xFF222222);
        statusText.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable card = new GradientDrawable();
        card.setColor(0xFFFFFFFF);
        card.setCornerRadius(dp(12));
        card.setStroke(dp(1), 0xFFE8E8E8);
        statusText.setBackground(card);
        root.addView(statusText);

        addDivider(root, 20);

        enableSwitch = new Switch(this);
        enableSwitch.setText("在状态栏显示");
        enableSwitch.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        enableSwitch.setTextColor(0xFF222222);
        enableSwitch.setPadding(0, dp(16), 0, dp(12));
        root.addView(enableSwitch);

        chargingOnly = new CheckBox(this);
        chargingOnly.setText("仅在充电时显示");
        chargingOnly.setTextColor(0xFF222222);
        root.addView(chargingOnly);

        LinearLayout intervalRow = new LinearLayout(this);
        intervalRow.setOrientation(LinearLayout.HORIZONTAL);
        intervalRow.setGravity(Gravity.CENTER_VERTICAL);
        intervalRow.setPadding(0, dp(18), 0, dp(2));

        TextView intervalLabel = new TextView(this);
        intervalLabel.setText("刷新间隔");
        intervalLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        intervalLabel.setTextColor(0xFF222222);
        intervalRow.addView(intervalLabel, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        intervalValue = new TextView(this);
        intervalValue.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        intervalValue.setTextColor(0xFF999999);
        intervalRow.addView(intervalValue);

        root.addView(intervalRow);

        intervalBar = new SeekBar(this);
        intervalBar.setMax(9);
        root.addView(intervalBar);

        TextView footer = new TextView(this);
        footer.setText("首次使用：在 LSPosed 管理器中启用「PowViz」模块，作用域选择「系统界面」，"
                + "然后重启状态栏（或重启手机）生效。");
        footer.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        footer.setTextColor(0xFF999999);
        footer.setLineSpacing(0, 1.3f);
        footer.setPadding(0, dp(22), 0, 0);
        root.addView(footer);

        enableSwitch.setOnCheckedChangeListener((b, checked) -> {
            if (syncing) {
                return;
            }
            Prefs.setEnabled(this, checked);
            sendConfig();
        });

        chargingOnly.setOnCheckedChangeListener((b, checked) -> {
            if (!syncing) {
                Prefs.setChargingOnly(this, checked);
                sendConfig();
            }
        });

        intervalBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) {
                    return;
                }
                Prefs.setIntervalMs(MainActivity.this, (progress + 1) * 1000);
                updateIntervalLabel();
                sendConfig();
                restartPoll();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        setContentView(scroll);
    }

    private void addDivider(LinearLayout root, int topMarginDp) {
        View divider = new View(this);
        divider.setBackgroundColor(0xFFF0F0F0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        lp.topMargin = dp(topMarginDp);
        root.addView(divider, lp);
    }

    private void syncUi() {
        syncing = true;
        enableSwitch.setChecked(Prefs.enabled(this));
        chargingOnly.setChecked(Prefs.chargingOnly(this));
        intervalBar.setProgress(Math.max(0, Math.min(9, Prefs.intervalMs(this) / 1000 - 1)));
        syncing = false;
        updateIntervalLabel();
        updateStatus();
    }

    private void updateIntervalLabel() {
        intervalValue.setText("每 " + (Prefs.intervalMs(this) / 1000) + " 秒");
    }

    private void restartPoll() {
        handler.removeCallbacks(poll);
        if (resumed) {
            handler.post(poll);
        }
    }

    private void sendConfig() {
        Intent intent = new Intent(ACTION_CONFIG).setPackage("com.android.systemui");
        intent.putExtra("enabled", Prefs.enabled(this));
        intent.putExtra("charging_only", Prefs.chargingOnly(this));
        intent.putExtra("interval_ms", Prefs.intervalMs(this));
        sendBroadcast(intent);
    }

    private void updateStatus() {
        int current = battery == null ? Integer.MIN_VALUE
                : battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
        if (voltageMv <= 0 || current == Integer.MIN_VALUE) {
            statusText.setText("—");
        } else {
            double volts = voltageMv / 1000d;
            double amps = current / 1_000_000d;
            String watts = PowerFormat.fromWatts(Math.abs(volts * amps));
            boolean charging = batteryStatus == BatteryManager.BATTERY_STATUS_CHARGING
                    || batteryStatus == BatteryManager.BATTERY_STATUS_FULL;
            statusText.setText(String.format(Locale.US,
                    "电压  %.2f V\n电流  %.2f A\n功率  %s\n状态  %s%s",
                    volts, amps, watts.isEmpty() ? "—" : watts,
                    charging ? "充电中" : "放电",
                    capacity >= 0 ? " · 电量 " + capacity + "%" : ""));
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
