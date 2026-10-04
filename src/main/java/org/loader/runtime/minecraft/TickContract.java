package org.loader.runtime.minecraft;

import org.loader.runtime.scheduler.Scheduler;

/**
 * Formal tick contract per spec section 16.
 * <p>
 * Every tick MUST record:
 * <pre>
 * tickId, startTime, deadline, phase, tasks, completion, duration, error
 * </pre>
 * <p>
 * Recommended phases:
 * <pre>
 * TICK_START → PRE_TICK → SCHEDULE → MINECRAFT_CORE_TICK → ASYNC_COMPLETION → POST_TICK → TICK_END
 * </pre>
 * Actual phases are derived from Minecraft 26.2 real bytecode.
 */
public final class TickContract {

    /** Default target ticks per second. */
    public static final int DEFAULT_TPS = 20;

    /** Default tick period in milliseconds (1000 / 20). */
    public static final long DEFAULT_TICK_PERIOD_MS = 50L;

    private TickContract() {
        // utility
    }

    /**
     * Tick lifecycle phases.
     */
    public enum TickPhase {
        /** Before any tick work begins. */
        TICK_START,
        /** Pre-tick hooks (mod pre-tick handlers). */
        PRE_TICK,
        /** Schedule async-safe work. */
        SCHEDULE,
        /** Synchronous Minecraft core tick. */
        MINECRAFT_CORE_TICK,
        /** Wait for async completion. */
        ASYNC_COMPLETION,
        /** Post-tick hooks. */
        POST_TICK,
        /** Tick end, metrics tally. */
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

        /**
         * Returns the remaining budget after overhead.
         */
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

        /**
         * Whether this tick met deadline.
         */
        public boolean metDeadline(long deadlineMs) {
            return durationMs() <= deadlineMs;
        }
    }
}
