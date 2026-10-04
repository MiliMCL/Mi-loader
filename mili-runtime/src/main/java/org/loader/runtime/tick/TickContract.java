package org.loader.runtime.tick;

/**
 * Formal tick contract.
 * <p>
 * Every tick MUST record: tickId, startTime, deadline, phase, tasks, completion, duration, error.
 * <p>
 * Phases: TICK_START → PRE_TICK → SCHEDULE → CORE_TICK → ASYNC_COMPLETION → POST_TICK → TICK_END.
 * <p>
 * This is a runtime-level contract; the minecraft-integration module provides the
 * concrete poller that drives ticks derived from real Minecraft 26.2 bytecode.
 */
public final class TickContract {

    /** Default target ticks per second. */
    public static final int DEFAULT_TPS = 20;

    /** Default tick period in milliseconds (1000 / 20). */
    public static final long DEFAULT_TICK_PERIOD_MS = 50L;

    private TickContract() {
    }

    /**
     * Tick lifecycle phases.
     */
    public enum TickPhase {
        TICK_START,
        PRE_TICK,
        SCHEDULE,
        CORE_TICK,
        ASYNC_COMPLETION,
        POST_TICK,
        TICK_END
    }

    /**
     * Budget allocation per tick.
     */
    public record TickBudget(
            long tickPeriodMs,
            long schedulerBudgetMs,
            long ioBudgetMs,
            long modBudgetMs
    ) {
        public static TickBudget standard() {
            long tick = DEFAULT_TICK_PERIOD_MS;
            return new TickBudget(tick, tick / 2, tick / 4, tick / 4);
        }

        public long remainingBudget(long elapsedMs) {
            return Math.max(0, tickPeriodMs - elapsedMs);
        }
    }

    /**
     * Metrics recorded for one tick.
     */
    public record TickMetrics(
            long tickId,
            long startTimeNanos,
            long durationNanos,
            TickPhase finalPhase,
            int tasksScheduled,
            int tasksCompleted,
            boolean missedDeadline,
            Throwable error
    ) {
        public long durationMs() {
            return durationNanos / 1_000_000;
        }

        public boolean metDeadline(long deadlineMs) {
            return durationMs() <= deadlineMs;
        }
    }
}
