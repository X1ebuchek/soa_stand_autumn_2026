package orders;

import java.time.Duration;

final class Breaker {

    enum Kind { NORMAL, PROBE }

    record Permit(Kind kind, Duration timeout) {
    }

    private static final Duration NORMAL_TIMEOUT = Duration.ofSeconds(10);

    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(3);

    private static final long BASE_BACKOFF_MS = 500;
    private static final long MAX_BACKOFF_MS = 5_000;

    private boolean open;
    private boolean probing;
    private int failures;
    private long nextProbeAtMs;

    synchronized Permit acquire() {
        if (!open) {
            return new Permit(Kind.NORMAL, NORMAL_TIMEOUT);
        }
        if (probing || nowMs() < nextProbeAtMs) {
            return null;
        }
        probing = true;
        return new Permit(Kind.PROBE, PROBE_TIMEOUT);
    }

    synchronized void success(Permit permit) {
        if (permit.kind() == Kind.PROBE) {
            probing = false;
        }
        open = false;
        failures = 0;
    }

    synchronized void failure(Permit permit) {
        if (permit.kind() == Kind.PROBE) {
            probing = false;
        }
        failures = Math.min(failures + 1, 8);
        open = true;
        long backoff = Math.min(BASE_BACKOFF_MS << Math.min(failures - 1, 3), MAX_BACKOFF_MS);
        nextProbeAtMs = nowMs() + backoff;
    }

    synchronized void neutral(Permit permit) {
        if (permit.kind() == Kind.PROBE) {
            probing = false;
            nextProbeAtMs = nowMs();
        }
    }

    synchronized boolean isOpen() {
        return open;
    }

    private static long nowMs() {
        return System.nanoTime() / 1_000_000L;
    }
}
