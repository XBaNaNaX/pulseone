package com.unclebanana.pulseone.ble;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import com.unclebanana.pulseone.core.BleParsers;
import com.unclebanana.pulseone.core.JStyleFrame;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Locale;
import java.util.Queue;
import java.util.UUID;

/**
 * BLE transport for Pulse One WS01A. Vendor writes are restricted to the
 * allowlisted SpO2 start/stop and read-only diagnostic frames created by JStyleFrame.
 */
public final class PulseBleManager {
    private static final UUID HEART_RATE_SERVICE = uuid16(0x180D);
    private static final UUID HEART_RATE_MEASUREMENT = uuid16(0x2A37);
    private static final UUID PLX_SERVICE = uuid16(0x1822);
    private static final UUID PLX_CONTINUOUS = uuid16(0x2A5F);
    private static final UUID BATTERY_SERVICE = uuid16(0x180F);
    private static final UUID BATTERY_LEVEL = uuid16(0x2A19);
    private static final UUID RSC_SERVICE = uuid16(0x1814);
    private static final UUID RSC_MEASUREMENT = uuid16(0x2A53);
    private static final UUID JSTYLE_SERVICE = uuid16(0xFFF0);
    private static final UUID JSTYLE_WRITE = uuid16(0xFFF6);
    private static final UUID JSTYLE_NOTIFY = uuid16(0xFFF7);
    private static final UUID CCCD = uuid16(0x2902);

    private static final long SCAN_TIMEOUT_MS = 15_000;
    private static final int SPO2_MEASUREMENT_SECONDS = 30;
    private static final long SPO2_STOP_GRACE_MS = 2_000;
    private static final long EXT_DIAGNOSTIC_TIMEOUT_MS = 4_000;
    private static final int MAX_AUTO_HISTORY_CONTINUATIONS = 10;

    private enum DiagnosticState {
        IDLE, READ_VERSION, READ_AUTO_CONFIG, READ_AUTO_HISTORY,
        WAIT_HISTORY, COMPLETED, FAILED
    }

    public interface Listener {
        void onStatus(String status);
        void onConnected(String name, String address);
        void onDisconnected();
        void onHeartRate(int bpm);
        void onSpO2(double percent, double pulseRate);
        void onBattery(int percent);
        void onCadence(int stepsPerMinute, double metersPerSecond);
        void onProprietaryFrame(int command, String hex, boolean valid);
        void onDiagnostic(String event);
        void onSpO2MeasurementState(boolean available, boolean active, String message);
        void onError(String message);
    }

    private final Context context;
    private Listener listener = null;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final BluetoothAdapter adapter;
    private final Queue<BluetoothGattDescriptor> descriptorWrites = new ArrayDeque<>();

    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private boolean scanning;
    private boolean descriptorWriteInProgress;
    private boolean initialBatteryRead;
    private boolean subscriptionsReady;
    private boolean spO2MeasurementAvailable;
    private boolean spO2MeasurementActive;
    private boolean spO2ResultSeen;
    private boolean spO2HistoryReadActive;
    private boolean vendorWritePending;
    private String pendingVendorAction = "";
    private boolean readManualHistoryAfterStop;
    private DiagnosticState diagnosticState = DiagnosticState.IDLE;
    private int autoHistoryRecords;
    private int autoHistoryContinuations;
    private byte[] pendingDiagnosticResponse;

    private final Runnable automaticSpO2Stop = () ->
            stopSpO2Measurement("ครบเวลาวัด 30 วินาที", true);
    private final Runnable spO2HistoryTimeout = () -> {
        if (!spO2HistoryReadActive) return;
        spO2HistoryReadActive = false;
        if (!spO2ResultSeen) {
            listener.onDiagnostic("HISTORY 60 timeout/no SpO2 result");
            listener.onSpO2MeasurementState(spO2MeasurementAvailable, false,
                    "ไม่พบผล SpO₂ ที่อุปกรณ์บันทึกไว้");
            startExtendedDiagnostic("manual history timeout");
        }
    };
    private final Runnable extendedDiagnosticTimeout = () -> {
        if (isExtendedDiagnosticActive()) {
            failExtendedDiagnostic("timeout state=" + diagnosticState);
        }
    };

    public PulseBleManager(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        BluetoothManager manager = context.getSystemService(BluetoothManager.class);
        this.adapter = manager == null ? null : manager.getAdapter();
    }

    public boolean isBluetoothEnabled() {
        try {
            return adapter != null && adapter.isEnabled();
        } catch (SecurityException ignored) {
            return false;
        }
    }

    @SuppressLint("MissingPermission")
    public void start() {
        if (!hasPermissions()) {
            listener.onError("ยังไม่ได้รับสิทธิ์อุปกรณ์ใกล้เคียง");
            return;
        }
        if (!isBluetoothEnabled()) {
            listener.onError("กรุณาเปิด Bluetooth");
            return;
        }
        stopScan();
        closeGatt();
        descriptorWrites.clear();
        descriptorWriteInProgress = false;
        initialBatteryRead = false;
        subscriptionsReady = false;
        spO2MeasurementAvailable = false;
        spO2MeasurementActive = false;
        spO2ResultSeen = false;
        spO2HistoryReadActive = false;
        vendorWritePending = false;
        pendingVendorAction = "";
        readManualHistoryAfterStop = false;
        resetExtendedDiagnostic();
        mainHandler.removeCallbacks(automaticSpO2Stop);
        mainHandler.removeCallbacks(spO2HistoryTimeout);
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            listener.onError("ไม่พบ Bluetooth LE scanner");
            return;
        }
        scanning = true;
        listener.onStatus("กำลังค้นหา PULSE ONE…");
        scanner.startScan(scanCallback);
        mainHandler.postDelayed(this::onScanTimeout, SCAN_TIMEOUT_MS);
    }

    @SuppressLint("MissingPermission")
    public void stop() {
        stopScan();
        closeGatt();
        descriptorWrites.clear();
        descriptorWriteInProgress = false;
        initialBatteryRead = false;
        subscriptionsReady = false;
        spO2MeasurementAvailable = false;
        spO2MeasurementActive = false;
        readManualHistoryAfterStop = false;
        resetExtendedDiagnostic();
        mainHandler.removeCallbacks(automaticSpO2Stop);
        listener.onSpO2MeasurementState(false, false, "ยังไม่พร้อมวัด SpO2");
    }

    public void destroy() {
        stop();
        mainHandler.removeCallbacksAndMessages(null);
    }

    private boolean hasPermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)
                == PackageManager.PERMISSION_GRANTED
                && context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED;
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            String name = result.getScanRecord() == null ? null : result.getScanRecord().getDeviceName();
            if (name == null && hasPermissions()) {
                try { name = device.getName(); } catch (SecurityException ignored) { }
            }
            if (name != null && name.toUpperCase(Locale.US).startsWith("PULSE ONE")) {
                stopScan();
                connect(device);
            }
        }

        @Override public void onScanFailed(int errorCode) {
            scanning = false;
            listener.onError("ค้นหา Bluetooth ไม่สำเร็จ (รหัส " + errorCode + ")");
        }
    };

    private void onScanTimeout() {
        if (!scanning) return;
        stopScan();
        listener.onError("ไม่พบ PULSE ONE ภายใน 15 วินาที");
    }

    @SuppressLint("MissingPermission")
    private void stopScan() {
        if (scanning && scanner != null && hasPermissions()) {
            try { scanner.stopScan(scanCallback); } catch (RuntimeException ignored) { }
        }
        scanning = false;
    }

    @SuppressLint("MissingPermission")
    private void connect(BluetoothDevice device) {
        listener.onStatus("พบอุปกรณ์ กำลังเชื่อมต่อ…");
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt activeGatt, int status, int newState) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                listener.onError("การเชื่อมต่อขัดข้อง (GATT " + status + ")");
                closeGatt();
                return;
            }
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                String name = "PULSE ONE";
                String address = "";
                if (hasPermissions()) {
                    try {
                        if (activeGatt.getDevice().getName() != null) name = activeGatt.getDevice().getName();
                        address = activeGatt.getDevice().getAddress();
                    } catch (SecurityException ignored) { }
                }
                listener.onConnected(name, address);
                discover(activeGatt);
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                listener.onDisconnected();
                closeGatt();
            }
        }

        @Override public void onServicesDiscovered(BluetoothGatt activeGatt, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                listener.onError("อ่านบริการของอุปกรณ์ไม่สำเร็จ");
                return;
            }
            listener.onDiagnostic("GATT services discovered");
            subscribe(activeGatt, HEART_RATE_SERVICE, HEART_RATE_MEASUREMENT, "2A37 Heart Rate");
            subscribe(activeGatt, PLX_SERVICE, PLX_CONTINUOUS, "2A5F PLX Continuous");
            subscribe(activeGatt, RSC_SERVICE, RSC_MEASUREMENT, "2A53 RSC");
            subscribe(activeGatt, BATTERY_SERVICE, BATTERY_LEVEL, "2A19 Battery");
            subscribe(activeGatt, JSTYLE_SERVICE, JSTYLE_NOTIFY, "FFF7 Vendor notify");
            spO2MeasurementAvailable = find(activeGatt, JSTYLE_SERVICE, JSTYLE_WRITE) != null;
            listener.onDiagnostic(spO2MeasurementAvailable
                    ? "FOUND FFF6 Vendor write"
                    : "MISSING FFF6 Vendor write");
            drainDescriptorQueue(activeGatt);
            listener.onStatus("เชื่อมต่อแล้ว · กำลังรับข้อมูล");
        }

        @Override public void onDescriptorWrite(BluetoothGatt activeGatt, BluetoothGattDescriptor descriptor, int status) {
            descriptorWriteInProgress = false;
            listener.onDiagnostic(String.format(Locale.US, "CCCD %s status=%d",
                    shortUuid(descriptor.getCharacteristic().getUuid()), status));
            if (status != BluetoothGatt.GATT_SUCCESS) {
                listener.onError("เปิดการรับข้อมูลบางรายการไม่สำเร็จ");
            }
            drainDescriptorQueue(activeGatt);
        }

        @Override public void onCharacteristicRead(BluetoothGatt activeGatt,
                                                   BluetoothGattCharacteristic characteristic,
                                                   byte[] value, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) handle(characteristic.getUuid(), value);
        }

        @SuppressWarnings("deprecation")
        @Override public void onCharacteristicRead(BluetoothGatt activeGatt,
                                                   BluetoothGattCharacteristic characteristic,
                                                   int status) {
            if (Build.VERSION.SDK_INT < 33 && status == BluetoothGatt.GATT_SUCCESS) {
                handle(characteristic.getUuid(), characteristic.getValue());
            }
        }

        @Override public void onCharacteristicChanged(BluetoothGatt activeGatt,
                                                      BluetoothGattCharacteristic characteristic,
                                                      byte[] value) {
            handle(characteristic.getUuid(), value);
        }

        @SuppressWarnings("deprecation")
        @Override public void onCharacteristicChanged(BluetoothGatt activeGatt,
                                                      BluetoothGattCharacteristic characteristic) {
            if (Build.VERSION.SDK_INT < 33) handle(characteristic.getUuid(), characteristic.getValue());
        }

        @Override public void onCharacteristicWrite(BluetoothGatt activeGatt,
                                                    BluetoothGattCharacteristic characteristic,
                                                    int status) {
            if (JSTYLE_WRITE.equals(characteristic.getUuid())) {
                String action = pendingVendorAction;
                vendorWritePending = false;
                pendingVendorAction = "";
                listener.onDiagnostic("WRITE FFF6 status=" + status + " action=" + action);
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    mainHandler.removeCallbacks(automaticSpO2Stop);
                    spO2MeasurementActive = false;
                    spO2HistoryReadActive = false;
                    readManualHistoryAfterStop = false;
                    if (isExtendedDiagnosticActive()) {
                        failExtendedDiagnostic("GATT write status=" + status + " action=" + action);
                    }
                    listener.onSpO2MeasurementState(spO2MeasurementAvailable, false,
                            "อุปกรณ์ปฏิเสธคำสั่งวัด SpO2");
                    listener.onError("อุปกรณ์ไม่รับคำสั่งวัด SpO2 (GATT " + status + ")");
                } else {
                    onVendorWriteComplete(action);
                }
            }
        }
    };

    @SuppressLint("MissingPermission")
    private void discover(BluetoothGatt activeGatt) {
        if (hasPermissions()) activeGatt.discoverServices();
    }

    @SuppressLint("MissingPermission")
    private void subscribe(BluetoothGatt activeGatt, UUID serviceId, UUID characteristicId, String label) {
        BluetoothGattCharacteristic characteristic = find(activeGatt, serviceId, characteristicId);
        if (characteristic == null) {
            listener.onDiagnostic("MISSING " + label);
            return;
        }
        if (!activeGatt.setCharacteristicNotification(characteristic, true)) {
            listener.onDiagnostic("SUBSCRIBE FAILED " + label);
            return;
        }
        BluetoothGattDescriptor cccd = characteristic.getDescriptor(CCCD);
        if (cccd != null) {
            descriptorWrites.add(cccd);
            listener.onDiagnostic("SUBSCRIBE QUEUED " + label);
        } else {
            listener.onDiagnostic("NO CCCD " + label);
        }
    }

    @SuppressLint("MissingPermission")
    private void drainDescriptorQueue(BluetoothGatt activeGatt) {
        if (descriptorWriteInProgress) return;
        BluetoothGattDescriptor descriptor = descriptorWrites.poll();
        if (descriptor == null) {
            if (!initialBatteryRead) {
                initialBatteryRead = true;
                read(activeGatt, BATTERY_SERVICE, BATTERY_LEVEL);
            }
            if (!subscriptionsReady) {
                subscriptionsReady = true;
                boolean available = spO2MeasurementAvailable;
                mainHandler.postDelayed(() -> listener.onSpO2MeasurementState(
                        available, false, available ? "พร้อมวัด SpO2" : "อุปกรณ์ไม่มี FFF6"), 700);
            }
            return;
        }
        descriptorWriteInProgress = true;
        int result;
        if (Build.VERSION.SDK_INT >= 33) {
            result = activeGatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
        } else {
            //noinspection deprecation
            descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            //noinspection deprecation
            result = activeGatt.writeDescriptor(descriptor) ? 0 : -1;
        }
        if (result != 0) {
            descriptorWriteInProgress = false;
            drainDescriptorQueue(activeGatt);
        }
    }

    @SuppressLint("MissingPermission")
    private void read(BluetoothGatt activeGatt, UUID serviceId, UUID characteristicId) {
        BluetoothGattCharacteristic characteristic = find(activeGatt, serviceId, characteristicId);
        if (characteristic != null) activeGatt.readCharacteristic(characteristic);
    }

    private BluetoothGattCharacteristic find(BluetoothGatt activeGatt, UUID serviceId, UUID characteristicId) {
        BluetoothGattService service = activeGatt.getService(serviceId);
        return service == null ? null : service.getCharacteristic(characteristicId);
    }

    private void handle(UUID id, byte[] source) {
        byte[] value = source == null ? new byte[0] : Arrays.copyOf(source, source.length);
        if (PLX_CONTINUOUS.equals(id) || JSTYLE_NOTIFY.equals(id)) {
            listener.onDiagnostic(String.format(Locale.US, "RX %s %dB %s",
                    shortUuid(id), value.length, BleParsers.hex(value)));
        }
        try {
            if (HEART_RATE_MEASUREMENT.equals(id)) {
                listener.onHeartRate(BleParsers.heartRate(value));
            } else if (PLX_CONTINUOUS.equals(id)) {
                BleParsers.PlxMeasurement plx = BleParsers.plxContinuous(value);
                listener.onDiagnostic(String.format(Locale.US,
                        "PLX parsed flags=0x%02X SpO2=%.2f PR=%.2f",
                        plx.flags, plx.spo2Percent, plx.pulseRateBpm));
                listener.onSpO2(plx.spo2Percent, plx.pulseRateBpm);
            } else if (BATTERY_LEVEL.equals(id)) {
                listener.onBattery(BleParsers.batteryPercent(value));
            } else if (RSC_MEASUREMENT.equals(id)) {
                BleParsers.RscMeasurement rsc = BleParsers.runningSpeedCadence(value);
                listener.onCadence(rsc.cadencePerMinute, rsc.speedMetersPerSecond);
            } else if (JSTYLE_NOTIFY.equals(id)) {
                boolean fixedFrame = JStyleFrame.isValid(value);
                boolean historyPacket = JStyleFrame.isManualSpO2HistoryRecord(value)
                        || JStyleFrame.isManualSpO2HistoryEnd(value)
                        || JStyleFrame.isAutoSpO2HistoryRecord(value)
                        || JStyleFrame.isAutoSpO2HistoryEnd(value);
                boolean valid = fixedFrame || historyPacket;
                int command = valid && value.length > 0 ? value[0] & 0xFF : -1;
                listener.onProprietaryFrame(command, BleParsers.hex(value), valid);
                if (handleExtendedDiagnosticPacket(value)) {
                    return;
                } else if (spO2MeasurementActive && JStyleFrame.isSpO2MeasurementResponse(value)) {
                    int percent = JStyleFrame.spO2Percent(value);
                    int pulse = value[2] & 0xFF;
                    listener.onDiagnostic("VENDOR HR=" + pulse + " bpm SpO2=" + percent + "%");
                    if (percent > 0 && percent <= 100) {
                        spO2ResultSeen = true;
                        listener.onSpO2(percent, pulse);
                    }
                } else if (spO2MeasurementActive && JStyleFrame.isMeasurementFinished(value)) {
                    mainHandler.removeCallbacks(automaticSpO2Stop);
                    spO2MeasurementActive = false;
                    listener.onDiagnostic("MEASUREMENT FINISHED 28-FF; request manual SpO2 history");
                    listener.onSpO2MeasurementState(spO2MeasurementAvailable, false,
                            "อุปกรณ์จบการวัด · กำลังอ่านผลที่บันทึกไว้");
                    mainHandler.postDelayed(this::requestManualSpO2History, 300);
                } else if (JStyleFrame.isManualSpO2HistoryRecord(value)) {
                    int percent = JStyleFrame.manualSpO2Percent(value);
                    listener.onDiagnostic("HISTORY 60 SpO2=" + percent + "%");
                    if (percent > 0) {
                        spO2ResultSeen = true;
                        spO2HistoryReadActive = false;
                        mainHandler.removeCallbacks(spO2HistoryTimeout);
                        listener.onSpO2(percent, Double.NaN);
                        listener.onSpO2MeasurementState(spO2MeasurementAvailable, false,
                                "อ่านผล SpO₂ ที่อุปกรณ์บันทึกไว้แล้ว");
                    }
                } else if (JStyleFrame.isManualSpO2HistoryEnd(value)) {
                    spO2HistoryReadActive = false;
                    mainHandler.removeCallbacks(spO2HistoryTimeout);
                    listener.onDiagnostic("HISTORY 60 END resultSeen=" + spO2ResultSeen);
                    if (!spO2ResultSeen) {
                        listener.onSpO2MeasurementState(spO2MeasurementAvailable, false,
                                "รอบนี้อุปกรณ์ไม่ได้บันทึกค่า SpO₂");
                        startExtendedDiagnostic("manual history ended without result");
                    }
                }
            }
        } catch (IllegalArgumentException error) {
            listener.onDiagnostic("PARSE ERROR " + shortUuid(id) + ": " + error.getMessage());
            listener.onError("ได้รับแพ็กเก็ตที่อ่านไม่ได้: " + error.getMessage());
        }
    }

    @SuppressLint("MissingPermission")
    public void startSpO2Measurement() {
        BluetoothGatt activeGatt = gatt;
        if (!hasPermissions() || activeGatt == null || !subscriptionsReady || !spO2MeasurementAvailable) {
            listener.onError("ยังไม่พร้อมเริ่มวัด SpO2");
            return;
        }
        if (spO2MeasurementActive) return;
        if (isExtendedDiagnosticActive()) failExtendedDiagnostic("new measurement session");
        spO2ResultSeen = false;
        spO2HistoryReadActive = false;
        mainHandler.removeCallbacks(spO2HistoryTimeout);
        byte[] frame = JStyleFrame.spO2Measurement(true, SPO2_MEASUREMENT_SECONDS);
        if (!writeAllowlistedVendor(activeGatt, frame, "START")) return;
        spO2MeasurementActive = true;
        mainHandler.removeCallbacks(automaticSpO2Stop);
        mainHandler.postDelayed(automaticSpO2Stop,
                SPO2_MEASUREMENT_SECONDS * 1_000L + SPO2_STOP_GRACE_MS);
        listener.onSpO2MeasurementState(true, true, "กำลังวัด SpO2 · อยู่นิ่ง 30 วินาที");
    }

    public void stopSpO2Measurement() {
        stopSpO2Measurement("หยุดการวัดแล้ว", false);
    }

    @SuppressLint("MissingPermission")
    private void stopSpO2Measurement(String message, boolean readResultAfterStop) {
        mainHandler.removeCallbacks(automaticSpO2Stop);
        BluetoothGatt activeGatt = gatt;
        if (!spO2MeasurementActive) return;
        byte[] frame = JStyleFrame.spO2Measurement(false, 0);
        readManualHistoryAfterStop = readResultAfterStop;
        boolean accepted = hasPermissions() && activeGatt != null
                && writeAllowlistedVendor(activeGatt, frame, "STOP");
        if (!accepted) readManualHistoryAfterStop = false;
        spO2MeasurementActive = false;
        listener.onSpO2MeasurementState(spO2MeasurementAvailable, false,
                accepted ? message : "ส่งคำสั่งหยุด SpO2 ไม่สำเร็จ");
    }

    @SuppressLint("MissingPermission")
    private void requestManualSpO2History() {
        BluetoothGatt activeGatt = gatt;
        if (!hasPermissions() || activeGatt == null || !spO2MeasurementAvailable
                || spO2HistoryReadActive) return;
        byte[] frame = JStyleFrame.manualSpO2HistoryRequest();
        if (!writeAllowlistedVendor(activeGatt, frame, "READ-HISTORY-60")) return;
        spO2HistoryReadActive = true;
        mainHandler.removeCallbacks(spO2HistoryTimeout);
    }

    @SuppressLint("MissingPermission")
    private boolean writeAllowlistedVendor(BluetoothGatt activeGatt, byte[] frame, String action) {
        boolean validStart = Arrays.equals(frame,
                JStyleFrame.spO2Measurement(true, SPO2_MEASUREMENT_SECONDS));
        boolean validStop = Arrays.equals(frame, JStyleFrame.spO2Measurement(false, 0));
        boolean validHistoryRead = Arrays.equals(frame, JStyleFrame.manualSpO2HistoryRequest());
        boolean validReadOnlyDiagnostic = JStyleFrame.isReadOnlyDiagnosticRequest(frame);
        if (!validStart && !validStop && !validHistoryRead && !validReadOnlyDiagnostic) {
            listener.onError("ปฏิเสธคำสั่ง vendor ที่ไม่อยู่ใน allowlist");
            return false;
        }
        if (vendorWritePending) {
            listener.onDiagnostic("TX FFF6 BLOCKED pending=" + pendingVendorAction + " next=" + action);
            return false;
        }
        BluetoothGattCharacteristic characteristic = find(activeGatt, JSTYLE_SERVICE, JSTYLE_WRITE);
        if (characteristic == null) {
            listener.onError("ไม่พบ FFF6 สำหรับสั่งวัด SpO2");
            return false;
        }
        int properties = characteristic.getProperties();
        boolean withResponse = (properties & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0;
        int writeType = withResponse ? BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                : BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE;
        int result;
        if (Build.VERSION.SDK_INT >= 33) {
            result = activeGatt.writeCharacteristic(characteristic, frame, writeType);
        } else {
            //noinspection deprecation
            characteristic.setWriteType(writeType);
            //noinspection deprecation
            characteristic.setValue(frame);
            //noinspection deprecation
            result = activeGatt.writeCharacteristic(characteristic) ? 0 : -1;
        }
        if (action.startsWith("EXT-DIAG")) {
            listener.onDiagnostic(String.format(Locale.US, "%s result=%d %s",
                    action, result, BleParsers.hex(frame)));
        } else {
            listener.onDiagnostic(String.format(Locale.US, "TX FFF6 %s result=%d %s",
                    action, result, BleParsers.hex(frame)));
        }
        if (result != 0) listener.onError("ส่งคำสั่ง " + action + " SpO2 ไม่สำเร็จ (" + result + ")");
        if (result == 0) {
            vendorWritePending = true;
            pendingVendorAction = action;
        }
        return result == 0;
    }

    private void onVendorWriteComplete(String action) {
        if ("STOP".equals(action) && readManualHistoryAfterStop) {
            readManualHistoryAfterStop = false;
            mainHandler.postDelayed(this::requestManualSpO2History, 300);
            return;
        }
        if ("READ-HISTORY-60".equals(action)) {
            mainHandler.removeCallbacks(spO2HistoryTimeout);
            mainHandler.postDelayed(spO2HistoryTimeout, EXT_DIAGNOSTIC_TIMEOUT_MS);
            return;
        }
        if (!isExtendedDiagnosticActive()) return;
        mainHandler.removeCallbacks(extendedDiagnosticTimeout);
        if (diagnosticState == DiagnosticState.READ_AUTO_HISTORY) {
            diagnosticState = DiagnosticState.WAIT_HISTORY;
        }
        mainHandler.postDelayed(extendedDiagnosticTimeout, EXT_DIAGNOSTIC_TIMEOUT_MS);
        if (pendingDiagnosticResponse != null) {
            byte[] buffered = pendingDiagnosticResponse;
            pendingDiagnosticResponse = null;
            mainHandler.post(() -> handleExtendedDiagnosticPacket(buffered));
        }
    }

    private void startExtendedDiagnostic(String reason) {
        BluetoothGatt activeGatt = gatt;
        if (!hasPermissions() || activeGatt == null || !spO2MeasurementAvailable
                || vendorWritePending) {
            listener.onDiagnostic("EXT-DIAG FAILED cannot start: transport busy or unavailable");
            diagnosticState = DiagnosticState.FAILED;
            return;
        }
        resetExtendedDiagnostic();
        diagnosticState = DiagnosticState.READ_VERSION;
        listener.onDiagnostic("EXT-DIAG START reason=" + reason);
        sendExtendedDiagnostic(activeGatt, JStyleFrame.versionReadRequest(),
                "EXT-DIAG VERSION TX", DiagnosticState.READ_VERSION);
    }

    private void sendExtendedDiagnostic(BluetoothGatt activeGatt, byte[] frame,
                                        String action, DiagnosticState state) {
        mainHandler.removeCallbacks(extendedDiagnosticTimeout);
        diagnosticState = state;
        if (!writeAllowlistedVendor(activeGatt, frame, action)) {
            failExtendedDiagnostic("write rejected action=" + action);
        }
    }

    private boolean handleExtendedDiagnosticPacket(byte[] value) {
        if (!isExtendedDiagnosticActive() || value.length == 0) return false;
        int command = value[0] & 0xFF;
        boolean expectedBeforeWriteCallback = vendorWritePending
                && ((diagnosticState == DiagnosticState.READ_VERSION
                        && command == JStyleFrame.VERSION_READ_COMMAND)
                || (diagnosticState == DiagnosticState.READ_AUTO_CONFIG
                        && command == JStyleFrame.AUTO_CONFIG_READ_COMMAND)
                || (diagnosticState == DiagnosticState.READ_AUTO_HISTORY
                        && command == JStyleFrame.AUTO_SPO2_HISTORY_COMMAND));
        if (expectedBeforeWriteCallback) {
            pendingDiagnosticResponse = Arrays.copyOf(value, value.length);
            listener.onDiagnostic("EXT-DIAG RX buffered until write callback");
            return true;
        }

        if (diagnosticState == DiagnosticState.READ_VERSION
                && command == JStyleFrame.VERSION_READ_COMMAND) {
            if (value.length < 2) {
                failExtendedDiagnostic("malformed version response");
                return true;
            }
            mainHandler.removeCallbacks(extendedDiagnosticTimeout);
            listener.onDiagnostic("EXT-DIAG VERSION RX " + BleParsers.hex(value));
            BluetoothGatt activeGatt = gatt;
            if (activeGatt == null) {
                failExtendedDiagnostic("disconnect");
            } else {
                sendExtendedDiagnostic(activeGatt, JStyleFrame.autoSpO2ConfigReadRequest(),
                        "EXT-DIAG AUTO-CONFIG TX", DiagnosticState.READ_AUTO_CONFIG);
            }
            return true;
        }

        if (diagnosticState == DiagnosticState.READ_AUTO_CONFIG
                && command == JStyleFrame.AUTO_CONFIG_READ_COMMAND) {
            if (value.length < 2) {
                failExtendedDiagnostic("malformed auto-config response");
                return true;
            }
            mainHandler.removeCallbacks(extendedDiagnosticTimeout);
            listener.onDiagnostic("EXT-DIAG AUTO-CONFIG RX " + BleParsers.hex(value));
            BluetoothGatt activeGatt = gatt;
            if (activeGatt == null) {
                failExtendedDiagnostic("disconnect");
            } else {
                sendExtendedDiagnostic(activeGatt,
                        JStyleFrame.autoSpO2HistoryRequest(JStyleFrame.HISTORY_READ_START),
                        "EXT-DIAG AUTO-HISTORY TX mode=00", DiagnosticState.READ_AUTO_HISTORY);
            }
            return true;
        }

        if (diagnosticState == DiagnosticState.WAIT_HISTORY
                && command == JStyleFrame.AUTO_SPO2_HISTORY_COMMAND) {
            mainHandler.removeCallbacks(extendedDiagnosticTimeout);
            if (JStyleFrame.isAutoSpO2HistoryEnd(value)) {
                listener.onDiagnostic("AUTO-HISTORY END records=" + autoHistoryRecords);
                completeExtendedDiagnostic();
                return true;
            }
            if (!JStyleFrame.isAutoSpO2HistoryRecord(value)) {
                failExtendedDiagnostic("malformed auto-history response");
                return true;
            }
            try {
                JStyleFrame.AutoSpO2Record record = JStyleFrame.parseAutoSpO2Record(value);
                autoHistoryRecords++;
                listener.onDiagnostic(String.format(Locale.US,
                        "AUTO-HISTORY SpO2=%d%% time=%s id=%d",
                        record.percent, record.timestampText(), record.id));
            } catch (IllegalArgumentException error) {
                failExtendedDiagnostic("invalid auto-history record: " + error.getMessage());
                return true;
            }
            if (autoHistoryContinuations >= MAX_AUTO_HISTORY_CONTINUATIONS) {
                listener.onDiagnostic("AUTO-HISTORY STOP continuation-limit records="
                        + autoHistoryRecords);
                completeExtendedDiagnostic();
                return true;
            }
            BluetoothGatt activeGatt = gatt;
            if (activeGatt == null) {
                failExtendedDiagnostic("disconnect");
            } else {
                autoHistoryContinuations++;
                sendExtendedDiagnostic(activeGatt,
                        JStyleFrame.autoSpO2HistoryRequest(JStyleFrame.HISTORY_READ_CONTINUATION),
                        "EXT-DIAG AUTO-HISTORY TX mode=02 request=" + autoHistoryContinuations,
                        DiagnosticState.READ_AUTO_HISTORY);
            }
            return true;
        }
        return false;
    }

    private boolean isExtendedDiagnosticActive() {
        return diagnosticState != DiagnosticState.IDLE
                && diagnosticState != DiagnosticState.COMPLETED
                && diagnosticState != DiagnosticState.FAILED;
    }

    private void completeExtendedDiagnostic() {
        mainHandler.removeCallbacks(extendedDiagnosticTimeout);
        diagnosticState = DiagnosticState.COMPLETED;
        listener.onDiagnostic("EXT-DIAG COMPLETED");
    }

    private void failExtendedDiagnostic(String reason) {
        mainHandler.removeCallbacks(extendedDiagnosticTimeout);
        diagnosticState = DiagnosticState.FAILED;
        listener.onDiagnostic("EXT-DIAG FAILED reason=" + reason);
    }

    private void resetExtendedDiagnostic() {
        mainHandler.removeCallbacks(extendedDiagnosticTimeout);
        diagnosticState = DiagnosticState.IDLE;
        autoHistoryRecords = 0;
        autoHistoryContinuations = 0;
        pendingDiagnosticResponse = null;
    }

    @SuppressLint("MissingPermission")
    private void closeGatt() {
        BluetoothGatt activeGatt = gatt;
        if (isExtendedDiagnosticActive()) failExtendedDiagnostic("disconnect");
        gatt = null;
        mainHandler.removeCallbacks(automaticSpO2Stop);
        mainHandler.removeCallbacks(spO2HistoryTimeout);
        subscriptionsReady = false;
        spO2MeasurementAvailable = false;
        spO2MeasurementActive = false;
        spO2HistoryReadActive = false;
        vendorWritePending = false;
        pendingVendorAction = "";
        readManualHistoryAfterStop = false;
        if (activeGatt != null && hasPermissions()) {
            try {
                activeGatt.disconnect();
                activeGatt.close();
            } catch (RuntimeException ignored) { }
        }
    }

    private static UUID uuid16(int value) {
        return UUID.fromString(String.format(Locale.US, "0000%04x-0000-1000-8000-00805f9b34fb", value));
    }

    private static String shortUuid(UUID value) {
        String text = value.toString();
        return text.substring(4, 8).toUpperCase(Locale.US);
    }
}
