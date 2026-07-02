package com.example.auctionparser.scheduler;

import com.example.auctionparser.service.MonitoringService;
import com.example.auctionparser.service.MonitoringStatus;
import com.example.auctionparser.service.SettingsService;
import com.example.auctionparser.service.UiLogService;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Drives {@link MonitoringService#runCycle()} on a fixed interval using a
 * {@link ScheduledExecutorService}. All work runs off the UI thread. Supports
 * start/stop, live interval changes and manual "check now" triggers.
 *
 * <p>Uses fixed-delay scheduling so cycles never overlap even if one runs long.
 */
@Component
public class MonitoringScheduler {

    private final MonitoringService monitoringService;
    private final MonitoringStatus status;
    private final SettingsService settingsService;
    private final UiLogService uiLog;

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "monitoring-scheduler");
        t.setDaemon(true);
        return t;
    });

    private volatile ScheduledFuture<?> scheduledCycle;
    private volatile int intervalMinutes;
    private volatile boolean running;

    public MonitoringScheduler(MonitoringService monitoringService, MonitoringStatus status,
                               SettingsService settingsService, UiLogService uiLog) {
        this.monitoringService = monitoringService;
        this.status = status;
        this.settingsService = settingsService;
        this.uiLog = uiLog;
    }

    public synchronized void start() {
        if (running) {
            return;
        }
        this.intervalMinutes = Math.max(1, settingsService.getAppSettings().getIntervalMinutes());
        running = true;
        status.setRunning(true);
        uiLog.info("Мониторинг запущен (интервал " + intervalMinutes + " мин)");
        schedule(0);
    }

    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        cancel();
        status.setRunning(false);
        uiLog.info("Мониторинг остановлен");
    }

    public boolean isRunning() {
        return running;
    }

    /** Applies a new interval immediately if monitoring is active. */
    public synchronized void updateInterval(int minutes) {
        this.intervalMinutes = Math.max(1, minutes);
        if (running) {
            cancel();
            schedule(intervalMinutes);
            uiLog.info("Интервал изменён на " + intervalMinutes + " мин");
        }
    }

    /** Runs a cycle immediately without waiting for the next tick. */
    public void triggerNow() {
        uiLog.info("Ручная проверка запущена");
        executor.submit(this::runCycleGuarded);
    }

    private void schedule(long initialDelayMinutes) {
        scheduledCycle = executor.scheduleWithFixedDelay(
                this::runCycleGuarded, initialDelayMinutes, intervalMinutes, TimeUnit.MINUTES);
    }

    private void cancel() {
        if (scheduledCycle != null) {
            scheduledCycle.cancel(false);
            scheduledCycle = null;
        }
    }

    private void runCycleGuarded() {
        Instant now = Instant.now();
        status.recordCheck(now, now.plus(intervalMinutes, ChronoUnit.MINUTES));
        try {
            monitoringService.runCycle();
        } catch (Exception e) {
            uiLog.error("Ошибка цикла мониторинга", e);
        }
    }

    @PreDestroy
    public void shutdown() {
        cancel();
        executor.shutdownNow();
    }
}
