import com.unclebanana.pulseone.core.BleParsers;
import com.unclebanana.pulseone.core.JStyleFrame;

public final class ProtocolSelfTest {
    public static void main(String[] args) {
        equal(72, BleParsers.heartRate(new byte[]{0x00, 72}), "8-bit heart rate");
        equal(300, BleParsers.heartRate(new byte[]{0x01, 0x2C, 0x01}), "16-bit heart rate");
        equal(87, BleParsers.batteryPercent(new byte[]{87}), "battery");

        var plx = BleParsers.plxContinuous(new byte[]{0, 98, 0, 72, 0});
        close(98.0, plx.spo2Percent, "SpO2");
        close(72.0, plx.pulseRateBpm, "PLX pulse rate");
        equal(0, plx.flags, "PLX flags");

        var plxWithOptionalFields = BleParsers.plxContinuous(
                new byte[]{0x01, 97, 0, 70, 0, 96, 0, 71, 0});
        equal(1, plxWithOptionalFields.flags, "PLX optional-field flag");
        close(97.0, plxWithOptionalFields.spo2Percent, "PLX normal SpO2 with optional data");

        var rsc = BleParsers.runningSpeedCadence(new byte[]{0x00, 0x00, 0x02, 90});
        close(2.0, rsc.speedMetersPerSecond, "speed");
        equal(90, rsc.cadencePerMinute, "cadence");

        byte[] observed = new byte[]{0x16,0x09,0x01,0,0,0,0,0,0,0,0,0,0,0,0,0x20};
        truth(JStyleFrame.isValid(observed), "observed JStyle checksum");
        equal(0x16, JStyleFrame.command(observed), "JStyle command");
        observed[15] = 0x21;
        truth(!JStyleFrame.isValid(observed), "bad checksum rejected");

        byte[] startSpO2 = JStyleFrame.spO2Measurement(true, 30);
        equal(0x28, startSpO2[0] & 0xFF, "SpO2 start command");
        equal(0x03, startSpO2[1] & 0xFF, "SpO2 measurement type");
        equal(0x01, startSpO2[2] & 0xFF, "SpO2 start flag");
        equal(30, startSpO2[4] & 0xFF, "SpO2 duration low byte");
        equal(0x4A, startSpO2[15] & 0xFF, "SpO2 start checksum");
        truth(JStyleFrame.isValid(startSpO2), "SpO2 start frame valid");

        byte[] stopSpO2 = JStyleFrame.spO2Measurement(false, 0);
        equal(0x00, stopSpO2[2] & 0xFF, "SpO2 stop flag");
        equal(0xFF, stopSpO2[4] & 0xFF, "SpO2 stop duration marker");
        equal(0x29, stopSpO2[15] & 0xFF, "SpO2 stop checksum");
        truth(JStyleFrame.isValid(stopSpO2), "SpO2 stop frame valid");

        byte[] response = new byte[]{0x28, 0x03, 71, 98, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, (byte) 0xD4};
        truth(JStyleFrame.isSpO2MeasurementResponse(response), "SpO2 response recognized");
        equal(98, JStyleFrame.spO2Percent(response), "SpO2 vendor response value");
        truth(JStyleFrame.spO2Percent(response) != (response[2] & 0xFF),
                "28-03 byte index 2 is HR, not SpO2");

        byte[] finished = new byte[]{0x28, (byte) 0xFF, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x27};
        truth(JStyleFrame.isMeasurementFinished(finished), "measurement end recognized");

        byte[] historyRequest = JStyleFrame.manualSpO2HistoryRequest();
        equal(0x60, historyRequest[0] & 0xFF, "manual SpO2 history command");
        equal(0x00, historyRequest[1] & 0xFF, "history read mode");
        equal(0x60, historyRequest[15] & 0xFF, "history request checksum");
        truth(JStyleFrame.isValid(historyRequest), "history request frame valid");

        byte[] historyRecord = new byte[]{0x60, 0x01, 0x00, 0x26, 0x08, 0x31,
                0x10, 0x25, 0x05, 97};
        truth(JStyleFrame.isManualSpO2HistoryRecord(historyRecord), "history record recognized");
        equal(97, JStyleFrame.manualSpO2Percent(historyRecord), "history SpO2 parsed");
        truth(JStyleFrame.isManualSpO2HistoryEnd(new byte[]{0x60, (byte) 0xFF}),
                "history end recognized");

        byte[] versionRead = JStyleFrame.versionReadRequest();
        equal(0x27, versionRead[0] & 0xFF, "version read command");
        equal(0x27, versionRead[15] & 0xFF, "version read checksum");
        truth(JStyleFrame.isValid(versionRead), "version read frame valid");

        byte[] autoConfigRead = JStyleFrame.autoSpO2ConfigReadRequest();
        equal(0x2B, autoConfigRead[0] & 0xFF, "auto-config read command");
        equal(0x03, autoConfigRead[1] & 0xFF, "auto-config SpO2 type");
        equal(0x2E, autoConfigRead[15] & 0xFF, "auto-config read checksum");

        byte[] autoHistoryStart = JStyleFrame.autoSpO2HistoryRequest(
                JStyleFrame.HISTORY_READ_START);
        equal(0x66, autoHistoryStart[15] & 0xFF, "auto-history start checksum");
        byte[] autoHistoryContinue = JStyleFrame.autoSpO2HistoryRequest(
                JStyleFrame.HISTORY_READ_CONTINUATION);
        equal(0x68, autoHistoryContinue[15] & 0xFF, "auto-history continuation checksum");
        truth(JStyleFrame.isReadOnlyDiagnosticRequest(autoHistoryStart),
                "auto-history start allowlisted");
        truth(JStyleFrame.isReadOnlyDiagnosticRequest(autoHistoryContinue),
                "auto-history continuation allowlisted");

        byte[] autoRecord = new byte[]{0x66, 0x01, 0x00, 0x26, 0x08, 0x31,
                0x10, 0x25, 0x05, 97};
        JStyleFrame.AutoSpO2Record parsedAuto = JStyleFrame.parseAutoSpO2Record(autoRecord);
        equal(1, parsedAuto.id, "auto-history record id");
        equal(97, parsedAuto.percent, "auto-history SpO2");
        equal("2026-08-31 10:25:05", parsedAuto.timestampText(), "auto-history BCD timestamp");
        truth(JStyleFrame.isAutoSpO2HistoryEnd(new byte[]{0x66, (byte) 0xFF}),
                "auto-history end recognized");

        byte[] tooHigh = autoRecord.clone();
        tooHigh[9] = 101;
        rejects(() -> JStyleFrame.parseAutoSpO2Record(tooHigh),
                "auto-history SpO2 above 100 rejected");
        rejects(() -> JStyleFrame.parseAutoSpO2Record(new byte[]{0x66, 0x01}),
                "wrong auto-history packet size rejected");
        byte[] invalidBcd = autoRecord.clone();
        invalidBcd[4] = 0x1A;
        rejects(() -> JStyleFrame.parseAutoSpO2Record(invalidBcd),
                "invalid BCD timestamp rejected");

        byte[] deleteAutoHistory = new byte[16];
        deleteAutoHistory[0] = 0x66;
        deleteAutoHistory[1] = (byte) 0x99;
        deleteAutoHistory[15] = (byte) 0xFF;
        truth(!JStyleFrame.isReadOnlyDiagnosticRequest(deleteAutoHistory),
                "auto-history delete mode absent from allowlist");

        System.out.println("ProtocolSelfTest: all checks passed");
    }

    private static void equal(int expected, int actual, String label) {
        if (expected != actual) throw new AssertionError(label + ": " + actual);
    }
    private static void equal(String expected, String actual, String label) {
        if (!expected.equals(actual)) throw new AssertionError(label + ": " + actual);
    }
    private static void close(double expected, double actual, String label) {
        if (Math.abs(expected - actual) > 0.0001) throw new AssertionError(label + ": " + actual);
    }
    private static void truth(boolean value, String label) {
        if (!value) throw new AssertionError(label);
    }
    private static void rejects(Runnable operation, String label) {
        try {
            operation.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError(label);
    }
}
