package com.unclebanana.pulseone.core;

/** Lifecycle and nullable result model for one on-demand SpO2 attempt. */
public final class ManualSpO2Session {
    public enum State {
        IDLE, STARTING, MEASURING, WAITING_MANUAL_HISTORY, SUCCEEDED, NO_RESULT,
        TIMED_OUT, CANCELLED, DISCONNECTED, FAILED
    }

    public enum ResultStatus {
        IN_PROGRESS, SUCCEEDED, PARTIAL, NO_RESULT, TIMED_OUT, CANCELLED,
        DISCONNECTED, FAILED
    }

    public enum Reason {
        NONE, SPO2_PROVIDED, SPO2_NOT_PROVIDED, TIMEOUT, USER_CANCELLED,
        DISCONNECTED, TRANSPORT_ERROR
    }

    public static final class Snapshot {
        public final State state;
        public final ResultStatus status;
        public final Reason reason;
        public final Integer heartRate;
        public final Integer spO2Percent;

        private Snapshot(State state, ResultStatus status, Reason reason,
                         Integer heartRate, Integer spO2Percent) {
            this.state = state;
            this.status = status;
            this.reason = reason;
            this.heartRate = heartRate;
            this.spO2Percent = spO2Percent;
        }

        public boolean isMeasuring() {
            return state == State.STARTING || state == State.MEASURING;
        }

        public boolean isBusy() {
            return isMeasuring() || state == State.WAITING_MANUAL_HISTORY;
        }
    }

    private State state = State.IDLE;
    private ResultStatus status = ResultStatus.NO_RESULT;
    private Reason reason = Reason.NONE;
    private Integer heartRate;
    private Integer spO2Percent;
    private boolean historyRequested;

    public boolean start() {
        if (snapshot().isBusy()) return false;
        state = State.STARTING;
        status = ResultStatus.IN_PROGRESS;
        reason = Reason.NONE;
        heartRate = null;
        spO2Percent = null;
        historyRequested = false;
        return true;
    }

    public void onStartWritten() {
        if (state == State.STARTING) state = State.MEASURING;
    }

    public void acceptLive(JStyleFrame.ManualSpO2Reading reading) {
        if (reading == null || !snapshot().isMeasuring()) return;
        state = State.MEASURING;
        if (reading.heartRate != null) heartRate = reading.heartRate;
        if (reading.spO2Percent != null) spO2Percent = reading.spO2Percent;
    }

    public boolean onMeasurementFinished() {
        if (!snapshot().isMeasuring()) return false;
        if (spO2Percent != null) {
            succeed();
            return false;
        }
        state = State.WAITING_MANUAL_HISTORY;
        return true;
    }

    public boolean markHistoryRequested() {
        if (state != State.WAITING_MANUAL_HISTORY || historyRequested) return false;
        historyRequested = true;
        return true;
    }

    public void acceptManualHistory(Integer percent) {
        if (state == State.WAITING_MANUAL_HISTORY && percent != null) {
            spO2Percent = percent;
        }
    }

    public Snapshot onManualHistoryEnd() {
        if (state != State.WAITING_MANUAL_HISTORY) return snapshot();
        if (spO2Percent != null) succeed();
        else {
            state = State.NO_RESULT;
            status = heartRate == null ? ResultStatus.NO_RESULT : ResultStatus.PARTIAL;
            reason = Reason.SPO2_NOT_PROVIDED;
        }
        return snapshot();
    }

    public Snapshot timeout() {
        if (snapshot().isBusy()) {
            state = State.TIMED_OUT;
            status = ResultStatus.TIMED_OUT;
            reason = Reason.TIMEOUT;
        }
        return snapshot();
    }

    public Snapshot cancel() {
        if (snapshot().isBusy()) {
            state = State.CANCELLED;
            status = ResultStatus.CANCELLED;
            reason = Reason.USER_CANCELLED;
        }
        return snapshot();
    }

    public Snapshot disconnect() {
        if (snapshot().isBusy()) {
            state = State.DISCONNECTED;
            status = ResultStatus.DISCONNECTED;
            reason = Reason.DISCONNECTED;
        }
        return snapshot();
    }

    public Snapshot fail() {
        if (snapshot().isBusy()) {
            state = State.FAILED;
            status = ResultStatus.FAILED;
            reason = Reason.TRANSPORT_ERROR;
        }
        return snapshot();
    }

    public void reset() {
        state = State.IDLE;
        status = ResultStatus.NO_RESULT;
        reason = Reason.NONE;
        heartRate = null;
        spO2Percent = null;
        historyRequested = false;
    }

    public Snapshot snapshot() {
        return new Snapshot(state, status, reason, heartRate, spO2Percent);
    }

    private void succeed() {
        state = State.SUCCEEDED;
        status = ResultStatus.SUCCEEDED;
        reason = Reason.SPO2_PROVIDED;
    }
}
