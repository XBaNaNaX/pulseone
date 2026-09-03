import com.unclebanana.pulseone.core.AutoSpO2HistorySession;
import com.unclebanana.pulseone.core.BleParsers;
import com.unclebanana.pulseone.core.JStyleFrame;
import com.unclebanana.pulseone.core.ManualSpO2Session;

import java.util.Arrays;

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
        JStyleFrame.ManualSpO2Reading validReading = JStyleFrame.manualSpO2Reading(response);
        equal(71, validReading.heartRate, "manual response heart rate");
        equal(98, validReading.spO2Percent, "SpO2 vendor response value");
        truth(validReading.spO2Percent != (response[2] & 0xFF),
                "28-03 byte index 2 is HR, not SpO2");

        byte[] unavailableResponse = new byte[]{0x28, 0x03, 0x72, 0x00, 0, 0, 0, 0,
                0x6B, 0x01, 0, 0, 0, 0, 0, 0x09};
        JStyleFrame.ManualSpO2Reading unavailableReading =
                JStyleFrame.manualSpO2Reading(unavailableResponse);
        equal(114, unavailableReading.heartRate, "manual unavailable response heart rate");
        truth(unavailableReading.spO2Percent == null,
                "raw zero maps to unavailable SpO2");

        byte[] finished = new byte[]{0x28, (byte) 0xFF, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x27};
        truth(JStyleFrame.isMeasurementFinished(finished), "measurement end recognized");

        ManualSpO2Session manual = new ManualSpO2Session();
        truth(manual.start(), "manual session starts");
        truth(manual.snapshot().state == ManualSpO2Session.State.STARTING,
                "manual session starts in STARTING");
        manual.onStartWritten();
        manual.acceptLive(unavailableReading);
        equal(114, manual.snapshot().heartRate, "manual session retains valid HR");
        truth(manual.snapshot().spO2Percent == null,
                "manual session does not create zero SpO2");
        truth(manual.onMeasurementFinished(), "28-FF requests compatible manual history");
        truth(manual.snapshot().state == ManualSpO2Session.State.WAITING_MANUAL_HISTORY,
                "28-FF exits MEASURING");
        truth(!manual.snapshot().isMeasuring(), "28-FF stops measuring indicator");
        truth(manual.markHistoryRequested(), "manual history requested once");
        truth(!manual.markHistoryRequested(), "manual history request cannot repeat");
        ManualSpO2Session.Snapshot noResult = manual.onManualHistoryEnd();
        truth(noResult.state == ManualSpO2Session.State.NO_RESULT,
                "60-FF without SpO2 ends as NO_RESULT");
        truth(noResult.status == ManualSpO2Session.ResultStatus.PARTIAL,
                "valid HR with missing SpO2 is partial");
        truth(noResult.reason == ManualSpO2Session.Reason.SPO2_NOT_PROVIDED,
                "missing SpO2 terminal reason");
        truth(!noResult.isBusy(), "NO_RESULT stops spinner and enables retry");
        int existingCurrentSpO2 = 96;
        truth(noResult.spO2Percent == null && existingCurrentSpO2 == 96,
                "failed manual attempt does not replace confirmed current SpO2");

        ManualSpO2Session manualSuccess = new ManualSpO2Session();
        manualSuccess.start();
        manualSuccess.acceptLive(validReading);
        truth(!manualSuccess.onMeasurementFinished(),
                "valid live SpO2 does not need history fallback");
        truth(manualSuccess.snapshot().state == ManualSpO2Session.State.SUCCEEDED,
                "valid live SpO2 succeeds independently");

        ManualSpO2Session manualTimeout = new ManualSpO2Session();
        manualTimeout.start();
        truth(manualTimeout.timeout().state == ManualSpO2Session.State.TIMED_OUT,
                "manual timeout is terminal");
        truth(!manualTimeout.snapshot().isBusy(), "manual timeout clears busy state");

        ManualSpO2Session manualDisconnect = new ManualSpO2Session();
        manualDisconnect.start();
        truth(manualDisconnect.disconnect().state == ManualSpO2Session.State.DISCONNECTED,
                "manual disconnect is terminal");
        truth(!manualDisconnect.snapshot().isBusy(), "disconnect clears busy state");

        byte[] historyRequest = JStyleFrame.manualSpO2HistoryRequest();
        equal(0x60, historyRequest[0] & 0xFF, "manual SpO2 history command");
        equal(0x00, historyRequest[1] & 0xFF, "history read mode");
        equal(0x60, historyRequest[15] & 0xFF, "history request checksum");
        truth(JStyleFrame.isValid(historyRequest), "history request frame valid");

        byte[] historyRecord = new byte[]{0x60, 0x01, 0x00, 0x26, 0x08, 0x31,
                0x10, 0x25, 0x05, 97};
        truth(JStyleFrame.isManualSpO2HistoryRecord(historyRecord), "history record recognized");
        equal(97, JStyleFrame.manualSpO2PercentOrNull(historyRecord), "history SpO2 parsed");
        byte[] zeroHistoryRecord = historyRecord.clone();
        zeroHistoryRecord[9] = 0;
        truth(JStyleFrame.manualSpO2PercentOrNull(zeroHistoryRecord) == null,
                "manual history zero is unavailable");
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

        byte[] autoHistoryStart = JStyleFrame.autoSpO2HistoryStartRequest();
        equal(0x66, autoHistoryStart[15] & 0xFF, "auto-history start checksum");
        truth(JStyleFrame.isReadOnlyDiagnosticRequest(autoHistoryStart),
                "auto-history start allowlisted");

        byte[] autoRecord = new byte[]{0x66, 0x08, 0x01, 0x26, 0x04, 0x09,
                0x19, 0x12, 0x00, 94};
        JStyleFrame.AutoSpO2Record parsedAuto = JStyleFrame.parseAutoSpO2Record(autoRecord);
        equal(264, parsedAuto.id, "auto-history little-endian record id");
        equal(94, parsedAuto.percent, "auto-history SpO2");
        equal("2026-04-09 19:12:00", parsedAuto.timestampText(), "auto-history BCD timestamp");
        truth(JStyleFrame.isAutoSpO2HistoryEnd(new byte[]{0x66, (byte) 0xFF}),
                "auto-history end recognized");

        JStyleFrame.AutoSpO2Record id384 = JStyleFrame.parseAutoSpO2Record(
                new byte[]{0x66, (byte) 0x80, 0x01, 0x26, 0x04, 0x09,
                        0x10, 0x32, 0x00, 96});
        JStyleFrame.AutoSpO2Record id407 = JStyleFrame.parseAutoSpO2Record(
                new byte[]{0x66, (byte) 0x97, 0x01, 0x26, 0x04, 0x09,
                        0x06, 0x41, 0x00, 97});
        truth(id407.id > id384.id && id407.timestamp.isBefore(id384.timestamp),
                "record ID order does not define chronological order");

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

        for (int percent = 96; percent <= 99; percent++) {
            byte[] valueRecord = autoRecord(percent, 9, percent);
            equal(percent, JStyleFrame.parseAutoSpO2Record(valueRecord).percent,
                    "unsigned Auto SpO2 " + percent);
        }
        equal(0x01FF, JStyleFrame.parseAutoSpO2Record(autoRecord(0x01FF, 9, 96)).id,
                "record ID FF-01 little endian");
        equal(0x0200, JStyleFrame.parseAutoSpO2Record(autoRecord(0x0200, 9, 96)).id,
                "record ID 00-02 little endian");

        byte[] packet240 = new byte[240];
        for (int i = 0; i < 24; i++) {
            byte[] record = autoRecord(0x0100 + i, i + 1, 94 + i % 6);
            System.arraycopy(record, 0, packet240, i * 10, 10);
        }
        equal(24, JStyleFrame.parseAutoSpO2Records(packet240).size(),
                "240-byte packet contains 24 records");

        AutoSpO2HistorySession session = new AutoSpO2HistorySession();
        truth(session.start(), "history session starts once");
        truth(!session.start(), "overlapping history request blocked");
        session.onRequestWritten();
        AutoSpO2HistorySession.Batch firstBatch = session.accept(packet240);
        equal(24, firstBatch.records.size(), "session accepts all records in packet");
        equal(24, session.recordCount(), "session record count");
        AutoSpO2HistorySession.Batch duplicateBatch = session.accept(packet240);
        equal(0, duplicateBatch.records.size(), "duplicate packet adds no records");
        equal(24, duplicateBatch.duplicateCount, "duplicate packet counted");
        equal(24, session.recordCount(), "duplicate packet does not grow history");

        AutoSpO2HistorySession idleCompletionRepro = new AutoSpO2HistorySession();
        idleCompletionRepro.start();
        idleCompletionRepro.onRequestWritten();
        for (int packetIndex = 0; packetIndex < 50; packetIndex++) {
            idleCompletionRepro.accept(autoPacket(packetIndex * 24, 24));
        }
        equal(1200, idleCompletionRepro.recordCount(),
                "idle completion repro receives 1200 records");
        equal(0, idleCompletionRepro.duplicateCount(),
                "1200-record sync has no duplicates");
        equal(0, idleCompletionRepro.malformedCount(),
                "1200-record sync has no malformed records");
        AutoSpO2HistorySession.TerminalResult idleResult =
                idleCompletionRepro.onIdleTimeout();
        truth(idleCompletionRepro.state() == AutoSpO2HistorySession.State.COMPLETED,
                "clean inter-packet idle is completion, not timeout");
        truth(idleResult.outcome == AutoSpO2HistorySession.Outcome.COMPLETED,
                "idle result is completed");
        truth(idleResult.reason == AutoSpO2HistorySession.CompletionReason.IDLE_INFERRED,
                "idle completion reason");
        truth(idleResult.completeness == AutoSpO2HistorySession.Completeness.UNVERIFIED,
                "idle completion is unverified");
        truth(idleCompletionRepro.onIdleTimeout() == null,
                "terminal event is returned only once");

        AutoSpO2HistorySession emptyResponse = new AutoSpO2HistorySession();
        emptyResponse.start();
        emptyResponse.onRequestWritten();
        AutoSpO2HistorySession.TerminalResult emptyResult = emptyResponse.onIdleTimeout();
        truth(emptyResult.outcome == AutoSpO2HistorySession.Outcome.FAILED,
                "empty response timeout fails");
        truth(emptyResult.reason
                        == AutoSpO2HistorySession.CompletionReason.EMPTY_RESPONSE_TIMEOUT,
                "empty response timeout reason");

        AutoSpO2HistorySession stalledPartial = new AutoSpO2HistorySession();
        stalledPartial.start();
        stalledPartial.onRequestWritten();
        stalledPartial.accept(new byte[]{0x66, 0x01, 0x00});
        AutoSpO2HistorySession.TerminalResult partialResult =
                stalledPartial.onIdleTimeout();
        truth(partialResult.outcome == AutoSpO2HistorySession.Outcome.INCOMPLETE,
                "stalled partial stream is incomplete");
        truth(partialResult.reason
                        == AutoSpO2HistorySession.CompletionReason.STALLED_PARTIAL,
                "stalled partial reason");

        AutoSpO2HistorySession explicitMarker = new AutoSpO2HistorySession();
        explicitMarker.start();
        explicitMarker.accept(autoPacket(0, 24));
        explicitMarker.accept(new byte[]{0x66, (byte) 0xFF});
        AutoSpO2HistorySession.TerminalResult explicitResult = explicitMarker.terminalResult();
        truth(explicitResult.outcome == AutoSpO2HistorySession.Outcome.COMPLETED,
                "explicit marker completes history");
        truth(explicitResult.reason
                        == AutoSpO2HistorySession.CompletionReason.EXPLICIT_MARKER,
                "explicit marker reason");
        truth(explicitResult.completeness == AutoSpO2HistorySession.Completeness.VERIFIED,
                "explicit marker completion is verified");

        AutoSpO2HistorySession absoluteTimeout = new AutoSpO2HistorySession();
        absoluteTimeout.start();
        absoluteTimeout.accept(autoPacket(0, 24));
        AutoSpO2HistorySession.TerminalResult absoluteResult =
                absoluteTimeout.onAbsoluteTimeout();
        truth(absoluteResult.outcome == AutoSpO2HistorySession.Outcome.TRUNCATED,
                "absolute timeout truncates active stream");
        truth(absoluteResult.reason
                        == AutoSpO2HistorySession.CompletionReason.ABSOLUTE_TIMEOUT,
                "absolute timeout reason");
        truth(absoluteTimeout.onAbsoluteTimeout() == null,
                "absolute timeout cannot emit twice");

        AutoSpO2HistorySession.TerminalResult beforeUnrelated =
                idleCompletionRepro.terminalResult();
        idleCompletionRepro.accept(new byte[]{0x16, 0x09, 0x01});
        truth(idleCompletionRepro.terminalResult() == beforeUnrelated
                        && idleCompletionRepro.state() == AutoSpO2HistorySession.State.COMPLETED,
                "0x16 after terminal does not reopen or change history result");

        AutoSpO2HistorySession lowHistorical = new AutoSpO2HistorySession();
        lowHistorical.start();
        AutoSpO2HistorySession.Batch lowBatch = lowHistorical.accept(autoPacket(0, 1, 81));
        equal(81, lowBatch.records.get(0).percent,
                "low valid historical SpO2 is retained");

        AutoSpO2HistorySession capacityRepro = new AutoSpO2HistorySession(512);
        capacityRepro.start();
        for (int packetIndex = 0; packetIndex < 21; packetIndex++) {
            capacityRepro.accept(autoPacket(packetIndex * 24, 24));
        }
        equal(504, capacityRepro.recordCount(), "capacity repro starts at 504 records");
        AutoSpO2HistorySession.Batch crossingLimit =
                capacityRepro.accept(autoPacket(504, 24));
        equal(8, crossingLimit.records.size(), "capacity repro accepts remaining slots");
        equal(16, crossingLimit.droppedByLimit, "capacity repro drops only overflow");
        equal(0, crossingLimit.malformedCount, "capacity overflow is not malformed");
        truth(crossingLimit.capacityReached, "capacity repro reports capacity reached");
        equal(512, capacityRepro.recordCount(), "capacity repro stops at configured limit");
        truth(capacityRepro.state() == AutoSpO2HistorySession.State.DRAINING,
                "capacity overflow enters draining state");
        truth(capacityRepro.state() != AutoSpO2HistorySession.State.FAILED,
                "capacity overflow does not fail session");

        AutoSpO2HistorySession.Batch ignoredAfterLimit =
                capacityRepro.accept(autoPacket(600, 24));
        equal(0, ignoredAfterLimit.records.size(), "draining packet is not stored");
        equal(1, ignoredAfterLimit.ignoredAfterTerminal,
                "draining packet increments suppressed counter");
        equal(1, capacityRepro.ignoredPacketCount(), "ignored packet total");
        equal(512, capacityRepro.recordCount(), "draining packet does not grow history");
        AutoSpO2HistorySession.Batch drainedCompletion = capacityRepro.accept(
                new byte[]{0x66, (byte) 0xFF});
        truth(drainedCompletion.completed, "completion marker closes draining session");
        truth(capacityRepro.state() == AutoSpO2HistorySession.State.TRUNCATED,
                "completed draining session is terminal truncated");

        AutoSpO2HistorySession capacityTimeout = new AutoSpO2HistorySession(1);
        capacityTimeout.start();
        capacityTimeout.accept(autoPacket(700, 1));
        capacityTimeout.onIdleTimeout();
        truth(capacityTimeout.state() == AutoSpO2HistorySession.State.TRUNCATED,
                "draining session closes by bounded timeout");

        AutoSpO2HistorySession drainingDisconnect = new AutoSpO2HistorySession(1);
        drainingDisconnect.start();
        drainingDisconnect.accept(autoPacket(800, 1));
        AutoSpO2HistorySession.TerminalResult disconnectResult =
                drainingDisconnect.disconnect();
        truth(drainingDisconnect.state() == AutoSpO2HistorySession.State.IDLE,
                "disconnect resets draining session");
        truth(disconnectResult.outcome == AutoSpO2HistorySession.Outcome.CANCELLED
                        && disconnectResult.reason
                        == AutoSpO2HistorySession.CompletionReason.DISCONNECTED,
                "disconnect records cancelled terminal reason");
        truth(drainingDisconnect.start(), "new request starts after disconnect reset");

        equal(4096, new AutoSpO2HistorySession().maxHistoryRecords(),
                "default history capacity");
        rejects(() -> new AutoSpO2HistorySession(0), "zero history capacity rejected");
        rejects(() -> new AutoSpO2HistorySession(
                AutoSpO2HistorySession.MAX_HISTORY_RECORDS + 1),
                "history capacity above hard bound rejected");
        equal(4000, (int) AutoSpO2HistorySession.DEFAULT_TIMEOUTS.firstResponseMs,
                "default first-response timeout");
        equal(4000, (int) AutoSpO2HistorySession.DEFAULT_TIMEOUTS.interPacketIdleMs,
                "default inter-packet idle timeout");
        equal(20000, (int) AutoSpO2HistorySession.DEFAULT_TIMEOUTS.absoluteSessionMs,
                "default absolute session timeout");
        rejects(() -> new AutoSpO2HistorySession.Timeouts(999, 4000, 20000),
                "first-response timeout below bound rejected");
        rejects(() -> new AutoSpO2HistorySession.Timeouts(4000, 5000, 4500),
                "absolute timeout shorter than idle rejected");
        rejects(() -> new AutoSpO2HistorySession.Timeouts(4000, 4000, 300001),
                "absolute timeout above bound rejected");

        AutoSpO2HistorySession flooded = new AutoSpO2HistorySession();
        flooded.start();
        AutoSpO2HistorySession.Batch floodResult = null;
        for (int i = 0; i <= AutoSpO2HistorySession.MAX_NOTIFICATIONS; i++) {
            floodResult = flooded.accept(packet240);
        }
        truth(floodResult != null && floodResult.capacityReached,
                "duplicate notification flood is bounded");
        truth(flooded.state() == AutoSpO2HistorySession.State.DRAINING,
                "notification limit drains without malformed failure");
        equal(0, floodResult.malformedCount,
                "notification limit is not classified as malformed");

        AutoSpO2HistorySession malformed = new AutoSpO2HistorySession();
        malformed.start();
        byte[] invalidThenValid = new byte[20];
        byte[] invalidAligned = autoRecord(400, 9, 96);
        invalidAligned[4] = 0x1A;
        System.arraycopy(invalidAligned, 0, invalidThenValid, 0, 10);
        System.arraycopy(autoRecord(401, 10, 97), 0, invalidThenValid, 10, 10);
        AutoSpO2HistorySession.Batch malformedBatch = malformed.accept(invalidThenValid);
        equal(1, malformedBatch.malformedCount, "invalid aligned BCD record marked malformed");
        equal(1, malformedBatch.records.size(), "valid aligned record after malformed record retained");

        AutoSpO2HistorySession badBoundary = new AutoSpO2HistorySession();
        badBoundary.start();
        byte[] shifted = invalidThenValid.clone();
        shifted[10] = 0x65;
        AutoSpO2HistorySession.Batch boundaryBatch = badBoundary.accept(shifted);
        truth(boundaryBatch.failureReason != null, "bad boundary rejected without scanning forward");
        truth(badBoundary.state() == AutoSpO2HistorySession.State.FAILED,
                "bad boundary fails session safely");

        AutoSpO2HistorySession fragmented = new AutoSpO2HistorySession();
        truth(fragmented.start(), "fragmented session starts");
        fragmented.onRequestWritten();
        byte[] fragmentedRecord = autoRecord(300, 9, 97);
        AutoSpO2HistorySession.Batch fragmentOne = fragmented.accept(
                Arrays.copyOfRange(fragmentedRecord, 0, 7));
        equal(7, fragmentOne.bufferedBytes, "truncated fragment buffered safely");
        AutoSpO2HistorySession.Batch fragmentTwo = fragmented.accept(
                Arrays.copyOfRange(fragmentedRecord, 7, 10));
        equal(1, fragmentTwo.records.size(), "fragmented record reassembled");
        equal(300, fragmentTwo.records.get(0).id, "fragmented record ID");

        AutoSpO2HistorySession timedOut = new AutoSpO2HistorySession();
        timedOut.start();
        timedOut.accept(new byte[]{0x66, 0x01, 0x00});
        timedOut.onIdleTimeout();
        truth(timedOut.state() == AutoSpO2HistorySession.State.INCOMPLETE,
                "truncated payload becomes incomplete without crash");
        equal(0, timedOut.bufferedByteCount(), "timeout clears fragment buffer");

        AutoSpO2HistorySession disconnected = new AutoSpO2HistorySession();
        disconnected.start();
        disconnected.disconnect();
        truth(disconnected.state() == AutoSpO2HistorySession.State.IDLE,
                "disconnect resets active session");
        equal(0, disconnected.bufferedByteCount(), "disconnect clears session buffer");

        AutoSpO2HistorySession completed = new AutoSpO2HistorySession();
        completed.start();
        AutoSpO2HistorySession.Batch completion = completed.accept(
                new byte[]{0x66, (byte) 0xFF});
        truth(completion.completed, "66-FF completes active history session");
        AutoSpO2HistorySession.Batch repeatedCompletion = completed.accept(
                new byte[]{0x66, (byte) 0xFF});
        truth(!repeatedCompletion.completed, "66-FF completion occurs only once");

        byte[] deleteAutoHistory = new byte[16];
        deleteAutoHistory[0] = 0x66;
        deleteAutoHistory[1] = (byte) 0x99;
        deleteAutoHistory[15] = (byte) 0xFF;
        truth(!JStyleFrame.isReadOnlyDiagnosticRequest(deleteAutoHistory),
                "auto-history delete mode absent from allowlist");
        byte[] continuation = new byte[16];
        continuation[0] = 0x66;
        continuation[1] = 0x02;
        continuation[15] = 0x68;
        truth(!JStyleFrame.isReadOnlyDiagnosticRequest(continuation),
                "auto-history continuation absent from allowlist");

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

    private static byte[] autoRecord(int id, int day, int percent) {
        return new byte[]{0x66, (byte) id, (byte) (id >>> 8), 0x26, 0x04,
                bcd(day), 0x19, 0x12, 0x00, (byte) percent};
    }

    private static byte[] autoPacket(int firstId, int count) {
        return autoPacket(firstId, count, -1);
    }

    private static byte[] autoPacket(int firstId, int count, int fixedPercent) {
        byte[] packet = new byte[count * 10];
        for (int i = 0; i < count; i++) {
            int percent = fixedPercent > 0 ? fixedPercent : 94 + i % 6;
            byte[] record = autoRecord(firstId + i, i % 28 + 1, percent);
            System.arraycopy(record, 0, packet, i * 10, 10);
        }
        return packet;
    }

    private static byte bcd(int value) {
        return (byte) (((value / 10) << 4) | (value % 10));
    }
}
