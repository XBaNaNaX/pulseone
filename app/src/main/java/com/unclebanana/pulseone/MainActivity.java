package com.unclebanana.pulseone;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.unclebanana.pulseone.ble.PulseBleManager;
import com.unclebanana.pulseone.data.MeasurementDb;
import com.unclebanana.pulseone.ui.VitalChartView;

import java.io.OutputStream;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity implements PulseBleManager.Listener {
    private static final int PERMISSION_REQUEST = 41;
    private static final int ENABLE_BLUETOOTH_REQUEST = 42;
    private static final int EXPORT_CSV_REQUEST = 43;
    private static final long SAVE_INTERVAL_MS = 5_000;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Map<String, Long> lastSavedAt = new ConcurrentHashMap<>();
    private final ArrayDeque<Float> heartTrend = new ArrayDeque<>();
    private final ArrayDeque<String> diagnosticLines = new ArrayDeque<>();
    private static final DateTimeFormatter DIAGNOSTIC_TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private PulseBleManager ble;
    private MeasurementDb db;
    private TextView status;
    private TextView heartValue;
    private TextView spo2Value;
    private TextView batteryValue;
    private TextView cadenceValue;
    private TextView savedValue;
    private TextView protocolValue;
    private TextView diagnosticValue;
    private VitalChartView chart;
    private Button connectButton;
    private Button disconnectButton;
    private Button measureSpO2Button;
    private boolean spO2Measuring;
    private volatile boolean destroyed;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        db = new MeasurementDb(this);
        ble = new PulseBleManager(this, this);
        setContentView(buildUi());
        loadHistory();
    }

    private View buildUi() {
        int bg = Color.rgb(11, 17, 23);
        int surface = Color.rgb(22, 33, 43);
        int primary = Color.rgb(56, 214, 199);
        int secondary = Color.rgb(174, 187, 197);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(bg);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(30));
        scroll.addView(root, new ScrollView.LayoutParams(-1, -2));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                view.setPadding(dp(18), dp(18) + bars.top, dp(18), dp(30) + bars.bottom);
            } else {
                view.setPadding(dp(18), dp(18) + insets.getSystemWindowInsetTop(),
                        dp(18), dp(30) + insets.getSystemWindowInsetBottom());
            }
            return insets;
        });

        TextView title = text("Pulse One Local", 28, Color.WHITE, true);
        root.addView(title);
        TextView subtitle = text("WS01A · Controlled Measurement v0.2.5", 14, secondary, false);
        root.addView(subtitle, margins(0, 4, 0, 18));

        status = text("ยังไม่เชื่อมต่อ", 15, secondary, false);
        status.setBackgroundColor(surface);
        status.setPadding(dp(14), dp(12), dp(14), dp(12));
        root.addView(status, margins(0, 0, 0, 12));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        connectButton = button("ค้นหาและเชื่อมต่อ", primary, Color.rgb(4, 33, 31));
        disconnectButton = button("ตัดการเชื่อมต่อ", surface, Color.WHITE);
        disconnectButton.setEnabled(false);
        actions.addView(connectButton, weighted(1, 0, 6));
        actions.addView(disconnectButton, weighted(1, 6, 0));
        root.addView(actions, margins(0, 0, 0, 18));
        connectButton.setOnClickListener(v -> ensurePermissionsAndConnect());
        disconnectButton.setOnClickListener(v -> {
            ble.stop();
            setConnected(false);
            status.setText("ตัดการเชื่อมต่อแล้ว");
        });

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        heartValue = addCard(row1, "หัวใจ", "— bpm", primary, 0, 6);
        spo2Value = addCard(row1, "ออกซิเจน", "— %", Color.rgb(116, 190, 255), 6, 0);
        root.addView(row1, margins(0, 0, 0, 12));

        measureSpO2Button = button("รอเชื่อมต่อเพื่อวัด SpO₂", surface, Color.WHITE);
        measureSpO2Button.setEnabled(false);
        measureSpO2Button.setOnClickListener(v -> {
            if (spO2Measuring) ble.stopSpO2Measurement();
            else {
                spo2Value.setText("กำลังวัด…");
                ble.startSpO2Measurement();
            }
        });
        root.addView(measureSpO2Button, margins(0, 0, 0, 12));

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        batteryValue = addCard(row2, "แบตเตอรี่", "— %", Color.rgb(130, 220, 145), 0, 6);
        cadenceValue = addCard(row2, "Cadence", "— spm", Color.rgb(255, 204, 102), 6, 0);
        root.addView(row2, margins(0, 0, 0, 20));

        root.addView(text("แนวโน้มหัวใจล่าสุด", 18, Color.WHITE, true));
        chart = new VitalChartView(this);
        chart.setBackgroundColor(surface);
        LinearLayout.LayoutParams chartParams = new LinearLayout.LayoutParams(-1, dp(220));
        chartParams.topMargin = dp(10);
        root.addView(chart, chartParams);

        LinearLayout dataActions = new LinearLayout(this);
        dataActions.setOrientation(LinearLayout.HORIZONTAL);
        Button export = button("ส่งออก CSV", surface, Color.WHITE);
        Button refresh = button("รีเฟรชประวัติ", surface, Color.WHITE);
        dataActions.addView(export, weighted(1, 0, 6));
        dataActions.addView(refresh, weighted(1, 6, 0));
        root.addView(dataActions, margins(0, 12, 0, 8));
        export.setOnClickListener(v -> chooseCsvDestination());
        refresh.setOnClickListener(v -> loadHistory());

        savedValue = text("ข้อมูลที่บันทึก: —", 13, secondary, false);
        root.addView(savedValue, margins(0, 4, 0, 4));
        protocolValue = text("โปรโตคอล FFF7: รอข้อมูล", 12, secondary, false);
        root.addView(protocolValue, margins(0, 0, 0, 18));

        root.addView(text("BLE Diagnostic", 18, Color.WHITE, true));
        diagnosticValue = text("รอเชื่อมต่อ…", 11, Color.rgb(174, 187, 197), false);
        diagnosticValue.setTypeface(Typeface.MONOSPACE);
        diagnosticValue.setTextIsSelectable(true);
        diagnosticValue.setBackgroundColor(surface);
        diagnosticValue.setPadding(dp(12), dp(10), dp(12), dp(10));
        root.addView(diagnosticValue, margins(0, 8, 0, 8));

        Button copyDiagnostic = button("คัดลอก Diagnostic Log", surface, Color.WHITE);
        root.addView(copyDiagnostic, margins(0, 0, 0, 18));
        copyDiagnostic.setOnClickListener(v -> copyDiagnosticLog());

        TextView warning = text(getString(R.string.medical_disclaimer), 13,
                Color.rgb(255, 204, 102), false);
        warning.setBackgroundColor(Color.rgb(48, 39, 19));
        warning.setPadding(dp(12), dp(10), dp(12), dp(10));
        root.addView(warning);
        return scroll;
    }

    @SuppressLint("MissingPermission")
    private void ensurePermissionsAndConnect() {
        String[] missing = missingPermissions();
        if (missing.length > 0) {
            requestPermissions(missing, PERMISSION_REQUEST);
            return;
        }
        if (!ble.isBluetoothEnabled()) {
            startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), ENABLE_BLUETOOTH_REQUEST);
            return;
        }
        ble.start();
    }

    private String[] missingPermissions() {
        ArrayList<String> missing = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)
                missing.add(Manifest.permission.BLUETOOTH_SCAN);
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                missing.add(Manifest.permission.BLUETOOTH_CONNECT);
        } else if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        return missing.toArray(new String[0]);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != PERMISSION_REQUEST) return;
        for (int result : results) {
            if (result != PackageManager.PERMISSION_GRANTED) {
                new AlertDialog.Builder(this)
                        .setTitle("ต้องใช้สิทธิ์ Bluetooth")
                        .setMessage("แอปใช้สิทธิ์นี้เพื่อค้นหาและเชื่อมต่อ PULSE ONE เท่านั้น")
                        .setPositiveButton("เปิดการตั้งค่า", (d, w) -> startActivity(new Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:" + getPackageName()))))
                        .setNegativeButton("ยกเลิก", null)
                        .show();
                return;
            }
        }
        ble.start();
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == ENABLE_BLUETOOTH_REQUEST && resultCode == RESULT_OK) {
            ensurePermissionsAndConnect();
        } else if (requestCode == EXPORT_CSV_REQUEST && resultCode == RESULT_OK && data != null) {
            exportCsv(data.getData());
        }
    }

    private void chooseCsvDestination() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/csv");
        intent.putExtra(Intent.EXTRA_TITLE, "pulse-one-" + LocalDate.now() + ".csv");
        startActivityForResult(intent, EXPORT_CSV_REQUEST);
    }

    private void exportCsv(Uri uri) {
        if (uri == null) return;
        io.execute(() -> {
            try (OutputStream output = getContentResolver().openOutputStream(uri, "wt")) {
                if (output == null) throw new IllegalStateException("เปิดไฟล์ไม่ได้");
                db.exportCsv(output);
                runOnUiThread(() -> Toast.makeText(this, "ส่งออก CSV แล้ว", Toast.LENGTH_LONG).show());
            } catch (Exception error) {
                runOnUiThread(() -> Toast.makeText(this,
                        "ส่งออกไม่สำเร็จ: " + error.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    private void loadHistory() {
        io.execute(() -> {
            List<Float> recent = db.recentValues("heart_rate", 60);
            long count = db.count();
            runOnUiThread(() -> {
                heartTrend.clear();
                heartTrend.addAll(recent);
                chart.setValues(recent);
                savedValue.setText(String.format(Locale.getDefault(), "ข้อมูลที่บันทึก: %,d รายการ", count));
            });
        });
    }

    private void save(String type, double value, String unit) {
        if (destroyed) return;
        long now = System.currentTimeMillis();
        Long previous = lastSavedAt.get(type);
        if (previous != null && now - previous < SAVE_INTERVAL_MS) return;
        lastSavedAt.put(type, now);
        io.execute(() -> {
            db.insert(now, type, value, unit);
            long count = db.count();
            runOnUiThread(() -> savedValue.setText(String.format(Locale.getDefault(),
                    "ข้อมูลที่บันทึก: %,d รายการ", count)));
        });
    }

    @Override public void onStatus(String message) { runOnUiThread(() -> status.setText(message)); }

    @Override public void onConnected(String name, String address) {
        runOnUiThread(() -> {
            setConnected(true);
            status.setText(name + " · เชื่อมต่อแล้ว");
        });
    }

    @Override public void onDisconnected() {
        runOnUiThread(() -> {
            setConnected(false);
            status.setText("การเชื่อมต่อสิ้นสุด");
        });
    }

    @Override public void onHeartRate(int bpm) {
        if (destroyed || bpm <= 0 || bpm > 300) return;
        save("heart_rate", bpm, "bpm");
        runOnUiThread(() -> {
            heartValue.setText(bpm + " bpm");
            heartTrend.addLast((float) bpm);
            while (heartTrend.size() > 60) heartTrend.removeFirst();
            chart.setValues(new ArrayList<>(heartTrend));
        });
    }

    @Override public void onSpO2(double percent, double pulseRate) {
        if (destroyed) return;
        boolean valid = !Double.isNaN(percent) && percent > 0 && percent <= 100;
        if (valid) save("spo2", percent, "percent");
        runOnUiThread(() -> spo2Value.setText(valid
                ? String.format(Locale.getDefault(), "%.0f %%", percent) : "— %"));
    }

    @Override public void onBattery(int percent) {
        save("battery", percent, "percent");
        runOnUiThread(() -> batteryValue.setText(percent + " %"));
    }

    @Override public void onCadence(int stepsPerMinute, double metersPerSecond) {
        save("cadence", stepsPerMinute, "spm");
        runOnUiThread(() -> cadenceValue.setText(stepsPerMinute + " spm"));
    }

    @Override public void onProprietaryFrame(int command, String hex, boolean valid) {
        runOnUiThread(() -> protocolValue.setText(valid
                ? String.format(Locale.US, "FFF7: คำสั่ง 0x%02X · checksum ถูกต้อง", command)
                : "FFF7: พบแพ็กเก็ตที่ checksum ไม่ถูกต้อง"));
    }

    @Override public void onDiagnostic(String event) {
        if (destroyed) return;
        runOnUiThread(() -> {
            diagnosticLines.addLast(LocalTime.now().format(DIAGNOSTIC_TIME) + "  " + event);
            while (diagnosticLines.size() > 40) diagnosticLines.removeFirst();
            diagnosticValue.setText(String.join("\n", diagnosticLines));
        });
    }

    @Override public void onSpO2MeasurementState(boolean available, boolean active, String message) {
        if (destroyed) return;
        runOnUiThread(() -> {
            spO2Measuring = active;
            measureSpO2Button.setEnabled(available);
            measureSpO2Button.setText(active ? "หยุดวัด SpO₂" : "เริ่มวัด SpO₂ (30 วินาที)");
            status.setText(message);
            if (!available && !active) measureSpO2Button.setText("อุปกรณ์ไม่พร้อมวัด SpO₂");
        });
    }

    private void copyDiagnosticLog() {
        ClipboardManager clipboard = getSystemService(ClipboardManager.class);
        if (clipboard == null) return;
        String log = String.join("\n", diagnosticLines);
        clipboard.setPrimaryClip(ClipData.newPlainText("Pulse One BLE Diagnostic", log));
        Toast.makeText(this, "คัดลอก Diagnostic Log แล้ว", Toast.LENGTH_SHORT).show();
    }

    @Override public void onError(String message) {
        runOnUiThread(() -> {
            status.setText(message);
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        });
    }

    private void setConnected(boolean connected) {
        connectButton.setEnabled(!connected);
        disconnectButton.setEnabled(connected);
        if (!connected) {
            spO2Measuring = false;
            measureSpO2Button.setEnabled(false);
            measureSpO2Button.setText("รอเชื่อมต่อเพื่อวัด SpO₂");
        }
    }

    @Override protected void onDestroy() {
        destroyed = true;
        ble.destroy();
        io.execute(db::close);
        io.shutdown();
        super.onDestroy();
    }

    private TextView addCard(LinearLayout row, String label, String initial, int accent, int start, int end) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(13), dp(14), dp(14));
        card.setBackgroundColor(Color.rgb(22, 33, 43));
        TextView caption = text(label, 13, Color.rgb(174, 187, 197), false);
        TextView value = text(initial, 25, accent, true);
        card.addView(caption);
        card.addView(value, margins(0, 5, 0, 0));
        row.addView(card, weighted(1, start, end));
        return value;
    }

    private TextView text(String value, float sp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private Button button(String label, int background, int foreground) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextColor(foreground);
        button.setBackgroundColor(background);
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER);
        return button;
    }

    private LinearLayout.LayoutParams margins(int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(dp(left), dp(top), dp(right), dp(bottom));
        return params;
    }

    private LinearLayout.LayoutParams weighted(int weight, int start, int end) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, weight);
        params.setMargins(dp(start), 0, dp(end), 0);
        return params;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
