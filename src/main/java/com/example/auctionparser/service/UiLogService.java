package com.example.auctionparser.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * In-memory, timestamped log bus shared between the background monitoring
 * threads and the UI log window. Listeners (the UI) are notified on each entry;
 * a bounded backlog is retained so a newly opened window can render history.
 *
 * <p>Thread-safe: producers are scheduler threads, the consumer is the JavaFX
 * thread. Listeners are responsible for marshalling onto their own thread.
 */
@Service
public class UiLogService {

    private static final Logger log = LoggerFactory.getLogger(UiLogService.class);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final int MAX_BACKLOG = 1000;

    private final CopyOnWriteArrayList<String> backlog = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Consumer<String>> listeners = new CopyOnWriteArrayList<>();

    /** Adds a timestamped entry, mirrors it to SLF4J and notifies listeners. */
    public void info(String message) {
        String entry = LocalTime.now().format(TIME) + "  " + message;
        log.info(message);
        append(entry);
    }

    public void error(String message, Throwable t) {
        String entry = LocalTime.now().format(TIME) + "  [ERROR] " + message;
        log.error(message, t);
        append(entry);
    }

    private void append(String entry) {
        backlog.add(entry);
        if (backlog.size() > MAX_BACKLOG) {
            backlog.remove(0);
        }
        for (Consumer<String> listener : listeners) {
            try {
                listener.accept(entry);
            } catch (Exception e) {
                log.warn("Log listener failed", e);
            }
        }
    }

    public List<String> backlog() {
        return List.copyOf(backlog);
    }

    public void addListener(Consumer<String> listener) {
        listeners.add(listener);
    }

    public void removeListener(Consumer<String> listener) {
        listeners.remove(listener);
    }
}
