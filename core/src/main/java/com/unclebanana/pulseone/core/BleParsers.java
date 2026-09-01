package com.unclebanana.pulseone.core;

import java.util.Locale;

/** Pure Java parsers for Bluetooth SIG characteristics exposed by Pulse One. */
public final class BleParsers {
    private BleParsers() {}

    public static int heartRate(byte[] value) {
        require(value, 2, "heart-rate");
        int flags = u8(value[0]);
        if ((flags & 0x01) == 0) return u8(value[1]);
        require(value, 3, "16-bit heart-rate");
        return u16(value, 1);
    }

    public static PlxMeasurement plxContinuous(byte[] value) {
        // Bluetooth SIG PLXS v1.0.1, Table 3.6: Flags is one octet,
        // followed by two mandatory SFLOAT values (SpO2 and pulse rate).
        require(value, 5, "PLX continuous measurement");
        int flags = u8(value[0]);
        double spo2 = sfloat(value, 1);
        double pulseRate = sfloat(value, 3);
        return new PlxMeasurement(flags, spo2, pulseRate);
    }

    public static RscMeasurement runningSpeedCadence(byte[] value) {
        require(value, 4, "RSC measurement");
        int flags = u8(value[0]);
        double speedMetersPerSecond = u16(value, 1) / 256.0;
        int cadencePerMinute = u8(value[3]);
        return new RscMeasurement(flags, speedMetersPerSecond, cadencePerMinute);
    }

    public static int batteryPercent(byte[] value) {
        require(value, 1, "battery");
        return Math.min(100, u8(value[0]));
    }

    /** IEEE-11073 16-bit SFLOAT: signed 4-bit exponent and signed 12-bit mantissa. */
    public static double sfloat(byte[] value, int offset) {
        require(value, offset + 2, "SFLOAT");
        int raw = u16(value, offset);
        if (raw == 0x07FF || raw == 0x0800 || raw == 0x0801 || raw == 0x0802) {
            return Double.NaN;
        }
        int mantissa = raw & 0x0FFF;
        if ((mantissa & 0x0800) != 0) mantissa -= 0x1000;
        int exponent = (raw >> 12) & 0x0F;
        if ((exponent & 0x08) != 0) exponent -= 0x10;
        return mantissa * Math.pow(10, exponent);
    }

    public static String hex(byte[] value) {
        if (value == null) return "";
        StringBuilder out = new StringBuilder(value.length * 3);
        for (int i = 0; i < value.length; i++) {
            if (i > 0) out.append('-');
            out.append(String.format(Locale.US, "%02X", u8(value[i])));
        }
        return out.toString();
    }

    private static int u8(byte value) { return value & 0xFF; }
    private static int u16(byte[] value, int offset) {
        return u8(value[offset]) | (u8(value[offset + 1]) << 8);
    }
    private static void require(byte[] value, int size, String label) {
        if (value == null || value.length < size) {
            throw new IllegalArgumentException(label + " packet is too short");
        }
    }

    public static final class PlxMeasurement {
        public final int flags;
        public final double spo2Percent;
        public final double pulseRateBpm;

        public PlxMeasurement(int flags, double spo2Percent, double pulseRateBpm) {
            this.flags = flags;
            this.spo2Percent = spo2Percent;
            this.pulseRateBpm = pulseRateBpm;
        }
    }

    public static final class RscMeasurement {
        public final int flags;
        public final double speedMetersPerSecond;
        public final int cadencePerMinute;

        public RscMeasurement(int flags, double speedMetersPerSecond, int cadencePerMinute) {
            this.flags = flags;
            this.speedMetersPerSecond = speedMetersPerSecond;
            this.cadencePerMinute = cadencePerMinute;
        }
    }
}
