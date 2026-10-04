package org.loader.runtime.minecraft;

import org.junit.jupiter.api.*;
import org.loader.runtime.tick.TickContract;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for TickContract.
 */
class TickContractTest {

    @Test
    void tickBudget_defaultValues() {
        TickContract.TickBudget budget = TickContract.TickBudget.standard();
        assertEquals(50, budget.tickPeriodMs());
        assertEquals(25, budget.schedulerBudgetMs());
        assertEquals(12, budget.ioBudgetMs());
        assertEquals(12, budget.modBudgetMs());
    }

    @Test
    void tickBudget_remaining() {
        TickContract.TickBudget budget = TickContract.TickBudget.standard();
        assertEquals(30, budget.remainingBudget(20));
        assertEquals(0, budget.remainingBudget(60)); // exceeded
        assertEquals(50, budget.remainingBudget(0));
    }

    @Test
    void tickMetrics_calculations() {
        TickContract.TickMetrics metrics = new TickContract.TickMetrics(
                1L, System.nanoTime(), 35_000_000L,
                TickContract.TickPhase.TICK_END, 10, 10, false, null);

        assertEquals(35, metrics.durationMs());
        assertTrue(metrics.metDeadline(50));
        assertFalse(metrics.metDeadline(30));
        assertFalse(metrics.missedDeadline());
        assertNull(metrics.error());
    }

    @Test
    void tickPhases_exist() {
        assertEquals(7, TickContract.TickPhase.values().length);
        assertEquals("TICK_START", TickContract.TickPhase.TICK_START.name());
        assertEquals("TICK_END", TickContract.TickPhase.TICK_END.name());
    }

    @Test
    void constants() {
        assertEquals(20, TickContract.DEFAULT_TPS);
        assertEquals(50, TickContract.DEFAULT_TICK_PERIOD_MS);
    }
}
