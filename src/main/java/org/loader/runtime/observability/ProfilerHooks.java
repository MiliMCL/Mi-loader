package org.loader.runtime.observability;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Lightweight profiler hooks for performance monitoring.
 * <p>
 * Provides tick timing, memory tracking, and custom metric counters.
 */
public class ProfilerHooks implements org.loader.runtime.kernel.Resource {

    private final String id;
    private final org.loader.runtime.kernel.Scope owner;
    private final Map<String, TimingEntry> timings = new LinkedHashMap<>();
    private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();
    private final List<String> frameLog = Collections.synchronizedList(new ArrayList<>());
    private volatile boolean closed = false;

    public ProfilerHooks(String id, org.loader.runtime.kernel.Scope owner) {
        this.id = id;
        this.owner = owner;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public org.loader.runtime.kernel.Scope owner() {
        return owner;
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    /**
     * Starts timing a section.
     */
    public void startTiming(String section) {
        if (closed) return;
        timings.put(section, new TimingEntry(System.nanoTime(), 0));
    }

    /**
     * Ends timing a section.
     */
    public long endTiming(String section) {
        if (closed) return -1;
        TimingEntry entry = timings.get(section);
        if (entry == null) return -1;
        long duration = System.nanoTime() - entry.startNanos;
        timings.put(section, new TimingEntry(entry.startNanos, duration));
        return duration;
    }

    /**
     * Gets the duration of a timing section in nanoseconds.
     */
    public long getTiming(String section) {
        TimingEntry entry = timings.get(section);
        return entry != null ? entry.durationNanos : -1;
    }

    /**
     * Increments a named counter.
     */
    public long incrementCounter(String name) {
        return counters.computeIfAbsent(name, k -> new AtomicLong(0)).incrementAndGet();
    }

    /**
     * Gets a counter value.
     */
    public long getCounter(String name) {
        AtomicLong counter = counters.get(name);
        return counter != null ? counter.get() : 0;
    }

    /**
     * Adds a frame timing log entry.
     */
    public void logFrame(String entry) {
        if (!closed) {
            frameLog.add(entry);
        }
    }

    /**
     * Returns all timing data.
     */
    public Map<String, Long> getAllTimings() {
        Map<String, Long> result = new LinkedHashMap<>();
        timings.forEach((k, v) -> result.put(k, v.durationNanos));
        return Collections.unmodifiableMap(result);
    }

    /**
     * Returns all counter values.
     */
    public Map<String, Long> getAllCounters() {
        Map<String, Long> result = new LinkedHashMap<>();
        counters.forEach((k, v) -> result.put(k, v.get()));
        return Collections.unmodifiableMap(result);
    }

    @Override
    public void close() {
        closed = true;
        timings.clear();
        counters.clear();
        frameLog.clear();
    }

    private record TimingEntry(long startNanos, long durationNanos) {
    }
}
