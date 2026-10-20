package com.zipe.aiteam.runtime;

import com.zipe.aiteam.sandbox.SandboxUnitCleanup;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Model execution timeout; does not imply that an independent operation failed. */
public final class RuntimeTimedOut extends AgentRuntime.RuntimeExecutionException {
    public enum Phase { MODEL, PREFLIGHT }
    private final Phase phase;
    private final String runtimeId;
    private final Duration timeout;
    private final Path stdoutLog;
    private final Path stderrLog;
    private final SandboxUnitCleanup.Result cleanup;

    public RuntimeTimedOut(String runtimeId, Duration timeout, Path stdoutLog, Path stderrLog,
                           SandboxUnitCleanup.Result cleanup) {
        this(runtimeId, Phase.MODEL, timeout, stdoutLog, stderrLog, cleanup);
    }
    public RuntimeTimedOut(String runtimeId, Phase phase, Duration timeout, Path stdoutLog, Path stderrLog,
                           SandboxUnitCleanup.Result cleanup) {
        super(runtimeId + " 超過設定逾時 " + timeout + "；sandbox 清理=" + cleanup.status()
                + "；unit=" + cleanup.unit() + "；stdout=" + stdoutLog + "；stderr=" + stderrLog
                + "；耗時根因與外部作業結果尚未確認，須依原作業收據對帳。");
        this.phase = phase;
        this.runtimeId = runtimeId; this.timeout = timeout; this.stdoutLog = stdoutLog;
        this.stderrLog = stderrLog; this.cleanup = cleanup;
    }
    public Phase phase() { return phase; }
    public String runtimeId() { return runtimeId; }
    public Duration timeout() { return timeout; }
    public Path stdoutLog() { return stdoutLog; }
    public Path stderrLog() { return stderrLog; }
    public String unit() { return cleanup.unit(); }
    public SandboxUnitCleanup.Result cleanup() { return cleanup; }
    /** Returns null when no typed timeout exists; safe for cyclic cause chains. */
    public static RuntimeTimedOut find(Throwable error) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable current = error; current != null && seen.add(current); current = current.getCause())
            if (current instanceof RuntimeTimedOut timeout) return timeout;
        return null;
    }
}
