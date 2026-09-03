package com.unclebanana.pulseone.core;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Bounded, in-memory session for historical 0x66 notifications only. */
public final class AutoSpO2HistorySession {
    public static final int DEFAULT_MAX_HISTORY_RECORDS = 4096;
    public static final int MAX_HISTORY_RECORDS = 65_536;
    public static final int MAX_NOTIFICATION_BYTES = 4096;
    public static final int MAX_NOTIFICATIONS = 512;
    public static final long MIN_TIMEOUT_MS = 1_000;
    public static final long MAX_IDLE_TIMEOUT_MS = 60_000;
    public static final long MAX_ABSOLUTE_TIMEOUT_MS = 300_000;
    public static final Timeouts DEFAULT_TIMEOUTS = new Timeouts(4_000, 4_000, 20_000);

    public enum State {
        IDLE, REQUESTING, RECEIVING, DRAINING, COMPLETED, INCOMPLETE, TRUNCATED, FAILED
    }

    public enum Outcome {
        COMPLETED, INCOMPLETE, TRUNCATED, FAILED, CANCELLED
    }

    public enum CompletionReason {
        EXPLICIT_MARKER, IDLE_INFERRED, EMPTY_RESPONSE_TIMEOUT, STALLED_PARTIAL,
        RECORD_LIMIT, DISCONNECTED, PROTOCOL_ERROR, ABSOLUTE_TIMEOUT
    }

    public enum Completeness {
        VERIFIED, UNVERIFIED
    }

    public static final class Timeouts {
        public final long firstResponseMs;
        public final long interPacketIdleMs;
        public final long absoluteSessionMs;

        public Timeouts(long firstResponseMs, long interPacketIdleMs,
                        long absoluteSessionMs) {
            if (firstResponseMs < MIN_TIMEOUT_MS || firstResponseMs > MAX_IDLE_TIMEOUT_MS
                    || interPacketIdleMs < MIN_TIMEOUT_MS
                    || interPacketIdleMs > MAX_IDLE_TIMEOUT_MS
                    || absoluteSessionMs < Math.max(firstResponseMs, interPacketIdleMs)
                    || absoluteSessionMs > MAX_ABSOLUTE_TIMEOUT_MS) {
                throw new IllegalArgumentException("History timeouts are outside safe bounds");
            }
            this.firstResponseMs = firstResponseMs;
            this.interPacketIdleMs = interPacketIdleMs;
            this.absoluteSessionMs = absoluteSessionMs;
        }
    }

    public static final class TerminalResult {
        public final Outcome outcome;
        public final CompletionReason reason;
        public final Completeness completeness;
        public final int bufferedBytes;

        private TerminalResult(Outcome outcome, CompletionReason reason,
                               Completeness completeness, int bufferedBytes) {
            this.outcome = outcome;
            this.reason = reason;
            this.completeness = completeness;
            this.bufferedBytes = bufferedBytes;
        }
    }

    public static final class Batch {
        public final List<JStyleFrame.AutoSpO2Record> records;
        public final int duplicateCount;
        public final int malformedCount;
        public final int droppedByLimit;
        public final int ignoredAfterTerminal;
        public final int bufferedBytes;
        public final boolean completed;
        public final boolean capacityReached;
        public final String failureReason;

        private Batch(List<JStyleFrame.AutoSpO2Record> records, int duplicateCount,
                      int malformedCount, int droppedByLimit, int ignoredAfterTerminal,
                      int bufferedBytes, boolean completed, boolean capacityReached,
                      String failureReason) {
            this.records = List.copyOf(records);
            this.duplicateCount = duplicateCount;
            this.malformedCount = malformedCount;
            this.droppedByLimit = droppedByLimit;
            this.ignoredAfterTerminal = ignoredAfterTerminal;
            this.bufferedBytes = bufferedBytes;
            this.completed = completed;
            this.capacityReached = capacityReached;
            this.failureReason = failureReason;
        }
    }

    private final int maxHistoryRecords;
    private final Set<String> seen = new HashSet<>();
    private State state = State.IDLE;
    private byte[] fragment = new byte[0];
    private int recordCount;
    private int duplicateCount;
    private int malformedCount;
    private int notificationCount;
    private int droppedByLimitCount;
    private int ignoredPacketCount;
    private String truncationReason;
    private TerminalResult terminalResult;

    public AutoSpO2HistorySession() {
        this(DEFAULT_MAX_HISTORY_RECORDS);
    }

    public AutoSpO2HistorySession(int maxHistoryRecords) {
        if (maxHistoryRecords < 1 || maxHistoryRecords > MAX_HISTORY_RECORDS) {
            throw new IllegalArgumentException("maxHistoryRecords must be 1.."
                    + MAX_HISTORY_RECORDS);
        }
        this.maxHistoryRecords = maxHistoryRecords;
    }

    public boolean start() {
        if (isActive()) return false;
        clearBuffers();
        recordCount = 0;
        duplicateCount = 0;
        malformedCount = 0;
        notificationCount = 0;
        droppedByLimitCount = 0;
        ignoredPacketCount = 0;
        truncationReason = null;
        terminalResult = null;
        state = State.REQUESTING;
        return true;
    }

    public void onRequestWritten() {
        if (state == State.REQUESTING) state = State.RECEIVING;
    }

    public Batch accept(byte[] payload) {
        if (state == State.DRAINING) {
            notificationCount++;
            if (JStyleFrame.isAutoSpO2HistoryEnd(payload)) {
                finish(Outcome.TRUNCATED, CompletionReason.RECORD_LIMIT,
                        Completeness.UNVERIFIED);
                return batch(List.of(), 0, 0, 0, 0, true, false, null);
            }
            ignoredPacketCount++;
            return batch(List.of(), 0, 0, 0, 1, false, false, null);
        }
        if (!isActive()) {
            if (payload != null && payload.length > 0
                    && (payload[0] & 0xFF) == JStyleFrame.AUTO_SPO2_HISTORY_COMMAND) {
                ignoredPacketCount++;
                return batch(List.of(), 0, 0, 0, 1, false, false, null);
            }
            return batch(List.of(), 0, 0, 0, 0, false, false,
                    "session is not active");
        }
        if (payload == null || payload.length == 0) return failBatch("empty history payload");
        if (payload.length > MAX_NOTIFICATION_BYTES) return failBatch("history payload too large");
        notificationCount++;
        if (notificationCount > MAX_NOTIFICATIONS) {
            state = State.DRAINING;
            truncationReason = "notification-limit";
            ignoredPacketCount++;
            return batch(List.of(), 0, 0, 0, 1, false, true, null);
        }
        state = State.RECEIVING;

        if (fragment.length == 0 && JStyleFrame.isAutoSpO2HistoryEnd(payload)) {
            finish(Outcome.COMPLETED, CompletionReason.EXPLICIT_MARKER,
                    Completeness.VERIFIED);
            return batch(List.of(), 0, 0, 0, 0, true, false, null);
        }
        if (fragment.length != 0 && JStyleFrame.isAutoSpO2HistoryEnd(payload)) {
            finish(Outcome.INCOMPLETE, CompletionReason.STALLED_PARTIAL,
                    Completeness.UNVERIFIED);
            return batch(List.of(), 0, 0, 0, 0, false, false,
                    "completion received after partial record");
        }

        byte[] combined = new byte[fragment.length + payload.length];
        System.arraycopy(fragment, 0, combined, 0, fragment.length);
        System.arraycopy(payload, 0, combined, fragment.length, payload.length);
        int completeBytes = combined.length - combined.length % JStyleFrame.AUTO_SPO2_RECORD_SIZE;
        List<JStyleFrame.AutoSpO2Record> accepted = new ArrayList<>();
        int batchDuplicates = 0;
        int batchMalformed = 0;
        int batchDroppedByLimit = 0;

        for (int offset = 0; offset < completeBytes;
             offset += JStyleFrame.AUTO_SPO2_RECORD_SIZE) {
            if ((combined[offset] & 0xFF) != JStyleFrame.AUTO_SPO2_HISTORY_COMMAND) {
                return failBatch("invalid command at aligned record boundary " + offset);
            }
            try {
                JStyleFrame.AutoSpO2Record record =
                        JStyleFrame.parseAutoSpO2Record(combined, offset);
                String key = record.id + "|" + record.timestamp;
                if (seen.contains(key)) {
                    batchDuplicates++;
                    duplicateCount++;
                    continue;
                }
                if (recordCount >= maxHistoryRecords) {
                    batchDroppedByLimit++;
                    droppedByLimitCount++;
                    continue;
                }
                seen.add(key);
                recordCount++;
                accepted.add(record);
            } catch (IllegalArgumentException error) {
                batchMalformed++;
                malformedCount++;
            }
        }

        boolean capacityReached = recordCount >= maxHistoryRecords;
        if (capacityReached) {
            state = State.DRAINING;
            truncationReason = "record-limit";
            fragment = new byte[0];
        } else {
            int remainder = combined.length - completeBytes;
            fragment = new byte[remainder];
            if (remainder > 0) {
                System.arraycopy(combined, completeBytes, fragment, 0, remainder);
            }
        }
        return batch(accepted, batchDuplicates, batchMalformed, batchDroppedByLimit,
                0, false, capacityReached, null);
    }

    public TerminalResult onIdleTimeout() {
        if (!isActive() || terminalResult != null) return null;
        if (state == State.DRAINING) {
            return finish(Outcome.TRUNCATED, CompletionReason.RECORD_LIMIT,
                    Completeness.UNVERIFIED);
        }
        if (fragment.length > 0) {
            return finish(Outcome.INCOMPLETE, CompletionReason.STALLED_PARTIAL,
                    Completeness.UNVERIFIED);
        }
        if (recordCount == 0) {
            return finish(Outcome.FAILED, CompletionReason.EMPTY_RESPONSE_TIMEOUT,
                    Completeness.UNVERIFIED);
        }
        return finish(Outcome.COMPLETED, CompletionReason.IDLE_INFERRED,
                Completeness.UNVERIFIED);
    }

    public TerminalResult onAbsoluteTimeout() {
        if (!isActive() || terminalResult != null) return null;
        if (state == State.DRAINING) {
            return finish(Outcome.TRUNCATED, CompletionReason.RECORD_LIMIT,
                    Completeness.UNVERIFIED);
        }
        if (fragment.length > 0) {
            return finish(Outcome.INCOMPLETE, CompletionReason.STALLED_PARTIAL,
                    Completeness.UNVERIFIED);
        }
        if (recordCount == 0) {
            return finish(Outcome.FAILED, CompletionReason.EMPTY_RESPONSE_TIMEOUT,
                    Completeness.UNVERIFIED);
        }
        return finish(Outcome.TRUNCATED, CompletionReason.ABSOLUTE_TIMEOUT,
                Completeness.UNVERIFIED);
    }

    public TerminalResult disconnect() {
        if (!isActive() || terminalResult != null) return null;
        TerminalResult result = finish(Outcome.CANCELLED, CompletionReason.DISCONNECTED,
                Completeness.UNVERIFIED);
        state = State.IDLE;
        return result;
    }

    public void fail() {
        if (isActive() && terminalResult == null) {
            finish(Outcome.FAILED, CompletionReason.PROTOCOL_ERROR,
                    Completeness.UNVERIFIED);
        }
    }

    public void reset() {
        state = State.IDLE;
        recordCount = 0;
        duplicateCount = 0;
        malformedCount = 0;
        notificationCount = 0;
        droppedByLimitCount = 0;
        ignoredPacketCount = 0;
        truncationReason = null;
        terminalResult = null;
        clearBuffers();
    }

    public State state() { return state; }
    public boolean isActive() {
        return state == State.REQUESTING || state == State.RECEIVING || state == State.DRAINING;
    }
    public boolean isDraining() { return state == State.DRAINING; }
    public boolean isTruncated() { return state == State.DRAINING || state == State.TRUNCATED; }
    public int maxHistoryRecords() { return maxHistoryRecords; }
    public int recordCount() { return recordCount; }
    public int duplicateCount() { return duplicateCount; }
    public int malformedCount() { return malformedCount; }
    public int notificationCount() { return notificationCount; }
    public int droppedByLimitCount() { return droppedByLimitCount; }
    public int ignoredPacketCount() { return ignoredPacketCount; }
    public String truncationReason() { return truncationReason; }
    public int bufferedByteCount() { return fragment.length; }
    public TerminalResult terminalResult() { return terminalResult; }

    private Batch failBatch(String reason) {
        finish(Outcome.FAILED, CompletionReason.PROTOCOL_ERROR,
                Completeness.UNVERIFIED);
        return batch(List.of(), 0, 0, 0, 0, false, false, reason);
    }

    private TerminalResult finish(Outcome outcome, CompletionReason reason,
                                  Completeness completeness) {
        if (terminalResult != null) return null;
        terminalResult = new TerminalResult(outcome, reason, completeness, fragment.length);
        switch (outcome) {
            case COMPLETED:
                state = State.COMPLETED;
                break;
            case INCOMPLETE:
                state = State.INCOMPLETE;
                break;
            case TRUNCATED:
                state = State.TRUNCATED;
                break;
            case FAILED:
                state = State.FAILED;
                break;
            case CANCELLED:
                state = State.IDLE;
                break;
        }
        clearBuffers();
        return terminalResult;
    }

    private Batch batch(List<JStyleFrame.AutoSpO2Record> records, int duplicates,
                        int malformed, int droppedByLimit, int ignoredAfterTerminal,
                        boolean completed, boolean capacityReached, String failureReason) {
        return new Batch(records, duplicates, malformed, droppedByLimit, ignoredAfterTerminal,
                fragment.length, completed, capacityReached, failureReason);
    }

    private void clearBuffers() {
        fragment = new byte[0];
        seen.clear();
    }
}
