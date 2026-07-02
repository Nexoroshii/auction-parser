package com.example.auctionparser.service;

import com.example.auctionparser.model.AuctionType;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Thread-safe, observable snapshot of monitoring state for the status panel:
 * last/next check time, which auctions were checked, and today's new-lot count.
 * Producers are scheduler threads; the UI subscribes and marshals to the FX
 * thread itself.
 */
@Component
public class MonitoringStatus {

    private volatile boolean running;
    private volatile Instant lastCheck;
    private volatile Instant nextCheck;
    private volatile int foundToday;
    private final Set<AuctionType> checkedAuctions =
            ConcurrentHashMap.newKeySet();

    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();

    public synchronized Snapshot snapshot() {
        return new Snapshot(running, lastCheck, nextCheck, foundToday, Set.copyOf(checkedAuctions));
    }

    public void setRunning(boolean running) {
        this.running = running;
        notifyListeners();
    }

    public void recordCheck(Instant lastCheck, Instant nextCheck) {
        this.lastCheck = lastCheck;
        this.nextCheck = nextCheck;
        notifyListeners();
    }

    public void markAuctionChecked(AuctionType type) {
        checkedAuctions.add(type);
        notifyListeners();
    }

    public void setFoundToday(int foundToday) {
        this.foundToday = foundToday;
        notifyListeners();
    }

    public void addListener(Runnable listener) {
        listeners.add(listener);
    }

    public void removeListener(Runnable listener) {
        listeners.remove(listener);
    }

    private void notifyListeners() {
        for (Runnable r : listeners) {
            try {
                r.run();
            } catch (Exception ignored) {
                // UI listener failures must not break monitoring
            }
        }
    }

    /** Immutable point-in-time view for rendering. */
    public record Snapshot(boolean running, Instant lastCheck, Instant nextCheck,
                           int foundToday, Set<AuctionType> checkedAuctions) {
    }
}
