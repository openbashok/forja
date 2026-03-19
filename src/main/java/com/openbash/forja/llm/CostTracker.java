package com.openbash.forja.llm;

import com.openbash.forja.util.TokenEstimator;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Global cost tracker for all LLM API calls in the session.
 * Thread-safe. Singleton via static instance.
 */
public final class CostTracker {

    private static final CostTracker INSTANCE = new CostTracker();

    private final AtomicLong totalInputTokens = new AtomicLong(0);
    private final AtomicLong totalOutputTokens = new AtomicLong(0);
    private final AtomicInteger totalCalls = new AtomicInteger(0);
    private volatile double totalCostUsd = 0.0;
    private final Object costLock = new Object();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    private CostTracker() {}

    public static CostTracker getInstance() {
        return INSTANCE;
    }

    /**
     * Record a completed LLM call.
     */
    public void record(LLMResponse response) {
        totalInputTokens.addAndGet(response.getInputTokens());
        totalOutputTokens.addAndGet(response.getOutputTokens());
        totalCalls.incrementAndGet();
        synchronized (costLock) {
            totalCostUsd += response.estimatedCostUsd();
        }
        notifyListeners();
    }

    public long getTotalInputTokens() { return totalInputTokens.get(); }
    public long getTotalOutputTokens() { return totalOutputTokens.get(); }
    public int getTotalCalls() { return totalCalls.get(); }

    public double getTotalCostUsd() {
        synchronized (costLock) {
            return totalCostUsd;
        }
    }

    public String getFormattedCost() {
        return TokenEstimator.formatCost(getTotalCostUsd());
    }

    public String getSummary() {
        long in = totalInputTokens.get();
        long out = totalOutputTokens.get();
        return String.format("%s (%d calls, %dk in, %dk out)",
                getFormattedCost(), totalCalls.get(),
                in / 1000, out / 1000);
    }

    public void reset() {
        totalInputTokens.set(0);
        totalOutputTokens.set(0);
        totalCalls.set(0);
        synchronized (costLock) {
            totalCostUsd = 0.0;
        }
        notifyListeners();
    }

    /**
     * Add a listener that gets called whenever cost changes.
     * Typically used by UI components to update a status label.
     */
    public void addListener(Runnable listener) {
        listeners.add(listener);
    }

    private void notifyListeners() {
        for (Runnable r : listeners) {
            try { r.run(); } catch (Exception ignored) {}
        }
    }
}
