package com.unclebanana.pulseone.core;

import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Narrowly allowlisted JStyle frames used by Pulse One diagnostics. */
public final class JStyleFrame {
    public static final int FRAME_SIZE = 16;
    public static final int HEALTH_MEASUREMENT_COMMAND = 0x28;
    public static final int MEASUREMENT_SPO2 = 0x03;
    public static final int MANUAL_SPO2_HISTORY_COMMAND = 0x60;
    public static final int VERSION_READ_COMMAND = 0x27;
    public static final int AUTO_CONFIG_READ_COMMAND = 0x2B;
    public static final int AUTO_SPO2_HISTORY_COMMAND = 0x66;
    public static final int HISTORY_READ_START = 0x00;
    public static final int AUTO_SPO2_RECORD_SIZE = 10;

    public static final class ManualSpO2Reading {
        public final Integer heartRate;
        public final Integer spO2Percent;

        private ManualSpO2Reading(Integer heartRate, Integer spO2Percent) {
            this.heartRate = heartRate;
            this.spO2Percent = spO2Percent;
        }
    }

    public static final class AutoSpO2Record {
        public final int id;
        public final int percent;
        public final LocalDateTime timestamp;

        private AutoSpO2Record(int id, int percent, LocalDateTime timestamp) {
            this.id = id;
            this.percent = percent;
            this.timestamp = timestamp;
        }

        public String timestampText() {
            return timestamp.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        }
    }

    private JStyleFrame() {}

    public static boolean isValid(byte[] frame) {
        if (frame == null || frame.length != FRAME_SIZE) return false;
        int sum = 0;
        for (int i = 0; i < FRAME_SIZE - 1; i++) sum = (sum + (frame[i] & 0xFF)) & 0xFF;
        return sum == (frame[FRAME_SIZE - 1] & 0xFF);
    }

    public static int command(byte[] frame) {
        if (!isValid(frame)) throw new IllegalArgumentException("Invalid JStyle frame");
        return frame[0] & 0xFF;
    }

    /**
     * Builds the only vendor command this app is allowed to transmit.
     * Layout follows BleSDK.healthMeasurementWithDataType(type, open, seconds).
     */
    public static byte[] spO2Measurement(boolean open, int seconds) {
        if (open && (seconds < 10 || seconds > 60)) {
            throw new IllegalArgumentException("SpO2 duration must be 10..60 seconds");
        }
        byte[] frame = new byte[FRAME_SIZE];
        frame[0] = (byte) HEALTH_MEASUREMENT_COMMAND;
        frame[1] = (byte) MEASUREMENT_SPO2;
        frame[2] = (byte) (open ? 1 : 0);
        if (open) {
            frame[4] = (byte) (seconds & 0xFF);
            frame[5] = (byte) ((seconds >>> 8) & 0xFF);
        } else {
            frame[4] = (byte) 0xFF;
            frame[5] = (byte) 0xFF;
        }
        frame[FRAME_SIZE - 1] = checksum(frame);
        return frame;
    }

    /** Read-only request for manually measured SpO2 history. Mode 0 starts a read. */
    public static byte[] manualSpO2HistoryRequest() {
        byte[] frame = new byte[FRAME_SIZE];
        frame[0] = (byte) MANUAL_SPO2_HISTORY_COMMAND;
        frame[1] = 0x00;
        frame[FRAME_SIZE - 1] = checksum(frame);
        return frame;
    }

    /** Read-only vendor firmware version request. Response fields remain uninterpreted. */
    public static byte[] versionReadRequest() {
        return readRequest(VERSION_READ_COMMAND, 0x00);
    }

    /** Read-only automatic-monitoring configuration request for measurement type SpO2. */
    public static byte[] autoSpO2ConfigReadRequest() {
        return readRequest(AUTO_CONFIG_READ_COMMAND, MEASUREMENT_SPO2);
    }

    /** Starts one bounded read-only history session; the device streams subsequent packets. */
    public static byte[] autoSpO2HistoryStartRequest() {
        return readRequest(AUTO_SPO2_HISTORY_COMMAND, HISTORY_READ_START);
    }

    public static boolean isReadOnlyDiagnosticRequest(byte[] frame) {
        return Arrays.equals(frame, versionReadRequest())
                || Arrays.equals(frame, autoSpO2ConfigReadRequest())
                || Arrays.equals(frame, autoSpO2HistoryStartRequest());
    }

    public static boolean isSpO2MeasurementResponse(byte[] frame) {
        return isValid(frame)
                && (frame[0] & 0xFF) == HEALTH_MEASUREMENT_COMMAND
                && (frame[1] & 0xFF) == MEASUREMENT_SPO2;
    }

    /** Parses only the confirmed HR byte and nullable SpO2 byte from 0x28/0x03. */
    public static ManualSpO2Reading manualSpO2Reading(byte[] frame) {
        if (!isSpO2MeasurementResponse(frame)) {
            throw new IllegalArgumentException("Not a valid SpO2 measurement response");
        }
        int rawHeartRate = frame[2] & 0xFF;
        int rawSpO2 = frame[3] & 0xFF;
        if (rawSpO2 > 100) {
            throw new IllegalArgumentException("Manual SpO2 percent must be 0..100");
        }
        return new ManualSpO2Reading(rawHeartRate == 0 ? null : rawHeartRate,
                rawSpO2 == 0 ? null : rawSpO2);
    }

    /** 0x28/0xFF marks the end of an on-demand measurement. */
    public static boolean isMeasurementFinished(byte[] frame) {
        return isValid(frame)
                && (frame[0] & 0xFF) == HEALTH_MEASUREMENT_COMMAND
                && (frame[1] & 0xFF) == 0xFF;
    }

    /**
     * Manual SpO2 history is a dynamic response, not a checksummed 16-byte frame.
     * Each record is 10 bytes: 60, id, reserved, YY, MM, DD, hh, mm, ss, SpO2.
     */
    public static boolean isManualSpO2HistoryRecord(byte[] packet) {
        if (packet == null || packet.length == 0 || packet.length % 10 != 0) return false;
        for (int offset = 0; offset < packet.length; offset += 10) {
            if ((packet[offset] & 0xFF) != MANUAL_SPO2_HISTORY_COMMAND) return false;
        }
        return true;
    }

    public static boolean isManualSpO2HistoryEnd(byte[] packet) {
        return packet != null && packet.length == 2
                && (packet[0] & 0xFF) == MANUAL_SPO2_HISTORY_COMMAND
                && (packet[1] & 0xFF) == 0xFF;
    }

    /** Returns the newest valid value in a history packet, or null when unavailable. */
    public static Integer manualSpO2PercentOrNull(byte[] packet) {
        if (!isManualSpO2HistoryRecord(packet)) {
            throw new IllegalArgumentException("Not a manual SpO2 history record");
        }
        Integer newest = null;
        for (int offset = 0; offset < packet.length; offset += 10) {
            int value = packet[offset + 9] & 0xFF;
            if (value >= 1 && value <= 100) newest = value;
        }
        return newest;
    }

    public static boolean isAutoSpO2HistoryPayload(byte[] payload) {
        if (payload == null || payload.length == 0
                || payload.length % AUTO_SPO2_RECORD_SIZE != 0) return false;
        for (int offset = 0; offset < payload.length; offset += AUTO_SPO2_RECORD_SIZE) {
            if ((payload[offset] & 0xFF) != AUTO_SPO2_HISTORY_COMMAND) return false;
        }
        return true;
    }

    public static boolean isAutoSpO2HistoryEnd(byte[] packet) {
        return packet != null && packet.length == 2
                && (packet[0] & 0xFF) == AUTO_SPO2_HISTORY_COMMAND
                && (packet[1] & 0xFF) == 0xFF;
    }

    /** Parses one 10-byte historical record without treating it as a current measurement. */
    public static AutoSpO2Record parseAutoSpO2Record(byte[] packet) {
        if (packet == null || packet.length != AUTO_SPO2_RECORD_SIZE) {
            throw new IllegalArgumentException("Auto SpO2 history record must be exactly 10 bytes");
        }
        return parseAutoSpO2Record(packet, 0);
    }

    /** Parses every aligned record in a payload; malformed boundaries are never scanned past. */
    public static List<AutoSpO2Record> parseAutoSpO2Records(byte[] payload) {
        if (!isAutoSpO2HistoryPayload(payload)) {
            throw new IllegalArgumentException("Auto SpO2 payload must contain aligned 10-byte records");
        }
        List<AutoSpO2Record> records = new ArrayList<>(payload.length / AUTO_SPO2_RECORD_SIZE);
        for (int offset = 0; offset < payload.length; offset += AUTO_SPO2_RECORD_SIZE) {
            records.add(parseAutoSpO2Record(payload, offset));
        }
        return records;
    }

    static AutoSpO2Record parseAutoSpO2Record(byte[] payload, int offset) {
        if (payload == null || offset < 0 || offset + AUTO_SPO2_RECORD_SIZE > payload.length
                || (payload[offset] & 0xFF) != AUTO_SPO2_HISTORY_COMMAND) {
            throw new IllegalArgumentException("Invalid Auto SpO2 record boundary");
        }
        int percent = payload[offset + 9] & 0xFF;
        if (percent < 1 || percent > 100) {
            throw new IllegalArgumentException("Auto SpO2 percent must be 1..100");
        }
        int year = 2000 + bcd(payload[offset + 3], "year");
        int month = bcd(payload[offset + 4], "month");
        int day = bcd(payload[offset + 5], "day");
        int hour = bcd(payload[offset + 6], "hour");
        int minute = bcd(payload[offset + 7], "minute");
        int second = bcd(payload[offset + 8], "second");
        try {
            int recordId = (payload[offset + 1] & 0xFF)
                    | ((payload[offset + 2] & 0xFF) << 8);
            return new AutoSpO2Record(recordId, percent,
                    LocalDateTime.of(year, month, day, hour, minute, second));
        } catch (DateTimeException error) {
            throw new IllegalArgumentException("Invalid Auto SpO2 BCD timestamp", error);
        }
    }

    private static int bcd(byte value, String field) {
        int raw = value & 0xFF;
        int high = (raw >>> 4) & 0x0F;
        int low = raw & 0x0F;
        if (high > 9 || low > 9) throw new IllegalArgumentException("Invalid BCD " + field);
        return high * 10 + low;
    }

    private static byte[] readRequest(int command, int argument) {
        byte[] frame = new byte[FRAME_SIZE];
        frame[0] = (byte) command;
        frame[1] = (byte) argument;
        frame[FRAME_SIZE - 1] = checksum(frame);
        return frame;
    }

    private static byte checksum(byte[] frame) {
        int sum = 0;
        for (int i = 0; i < FRAME_SIZE - 1; i++) sum = (sum + (frame[i] & 0xFF)) & 0xFF;
        return (byte) sum;
    }
}
