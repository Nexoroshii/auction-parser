package com.example.auctionparser.service;

import com.example.auctionparser.config.AppProperties;
import com.example.auctionparser.model.AppSettings;
import com.example.auctionparser.model.CopartCredentials;
import com.example.auctionparser.model.TelegramSettings;
import com.example.auctionparser.repository.SettingsRepository;
import org.springframework.stereotype.Service;

/**
 * Typed access to the key/value {@code settings} table for Telegram credentials
 * and application preferences.
 */
@Service
public class SettingsService {

    private static final String TELEGRAM_TOKEN = "telegram.botToken";
    private static final String TELEGRAM_CHAT = "telegram.chatId";
    private static final String INTERVAL = "app.intervalMinutes";
    private static final String LAUNCH_ON_STARTUP = "app.launchOnStartup";
    private static final String MINIMIZE_TO_TRAY = "app.minimizeToTray";
    private static final String SEND_PHOTOS = "app.sendPhotos";
    private static final String SEND_VIDEO = "app.sendVideo";
    private static final String TOPICS_ENABLED = "app.topicsEnabled";
    private static final String COPART_USERNAME = "copart.username";
    private static final String COPART_PASSWORD = "copart.password";

    private final SettingsRepository repo;
    private final AppProperties properties;

    public SettingsService(SettingsRepository repo, AppProperties properties) {
        this.repo = repo;
        this.properties = properties;
    }

    public TelegramSettings getTelegramSettings() {
        return TelegramSettings.builder()
                .botToken(repo.get(TELEGRAM_TOKEN, ""))
                .chatId(repo.get(TELEGRAM_CHAT, ""))
                .build();
    }

    public void saveTelegramSettings(TelegramSettings settings) {
        repo.put(TELEGRAM_TOKEN, nullToEmpty(settings.getBotToken()));
        repo.put(TELEGRAM_CHAT, nullToEmpty(settings.getChatId()));
    }

    public AppSettings getAppSettings() {
        int defaultInterval = properties.getMonitoring().getDefaultIntervalMinutes();
        return AppSettings.builder()
                .intervalMinutes(parseInt(repo.get(INTERVAL, String.valueOf(defaultInterval)), defaultInterval))
                .launchOnStartup(parseBool(repo.get(LAUNCH_ON_STARTUP, "false")))
                .minimizeToTray(parseBool(repo.get(MINIMIZE_TO_TRAY, "true")))
                .sendPhotos(parseBool(repo.get(SEND_PHOTOS, "true")))
                .sendVideo(parseBool(repo.get(SEND_VIDEO, "true")))
                .auctionDayRange(clampDayRange(properties.getMonitoring().getDefaultDayRange()))
                .topicsEnabled(parseBool(repo.get(TOPICS_ENABLED, "false")))
                .build();
    }

    public CopartCredentials getCopartCredentials() {
        return CopartCredentials.builder()
                .username(repo.get(COPART_USERNAME, ""))
                .password(repo.get(COPART_PASSWORD, ""))
                .build();
    }

    public void saveCopartCredentials(CopartCredentials credentials) {
        repo.put(COPART_USERNAME, nullToEmpty(credentials.getUsername()));
        repo.put(COPART_PASSWORD, nullToEmpty(credentials.getPassword()));
    }

    public void saveAppSettings(AppSettings settings) {
        repo.put(INTERVAL, String.valueOf(Math.max(1, settings.getIntervalMinutes())));
        repo.put(LAUNCH_ON_STARTUP, String.valueOf(settings.isLaunchOnStartup()));
        repo.put(MINIMIZE_TO_TRAY, String.valueOf(settings.isMinimizeToTray()));
        repo.put(SEND_PHOTOS, String.valueOf(settings.isSendPhotos()));
        repo.put(SEND_VIDEO, String.valueOf(settings.isSendVideo()));
        repo.put(TOPICS_ENABLED, String.valueOf(settings.isTopicsEnabled()));
    }

    /** Only "today" (1) and "today+tomorrow" (2) are meaningful values. */
    private static int clampDayRange(int days) {
        return days <= 1 ? 1 : 2;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static int parseInt(String s, int fallback) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static boolean parseBool(String s) {
        return Boolean.parseBoolean(s);
    }
}
